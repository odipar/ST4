package org.st4;

/**
 * The reference ST4 decoder: what the 68000 versions have to agree with.
 *
 * <p>It is the ZX1 state machine with four changes. Literals come from stream
 * D and offsets from stream B or C - by width - rather than from the stream the
 * bits live in; every length and offset is counted in units, so each step
 * moves k bytes; the end marker carries one more bit, which can turn the end
 * into an endless match - the repeat; and an offset beyond the window is a
 * copy from the literal stream rather than a match. The parse is still ZX1's;
 * only where the pieces are written differs, and the two class bits that say
 * which stream an offset came from.
 *
 * <p>The copy is what the 68000 decoders do, exactly: it reads {@code offset
 * - window} units behind the literal read pointer, leaves the pointer where
 * it was, and advances the offset by what it copied - so a rep after a copy
 * resumes just past it. A copy must stay behind the pointer, strictly, or
 * the offset would reach zero; the reference refuses one that does not.
 */
public final class St4Decompressor {

    private enum State { START, LITERALS, MATCH, DONE }

    private final int window;
    private final int rewindAt;
    private final byte[] control;
    private final byte[] literal;
    private final byte[] byteOffsets;
    private final byte[] wordOffsets;
    private final byte[] output;
    private final int unit;
    private int controlIndex;
    private int literalIndex;
    private int byteOffsetIndex;
    private int wordOffsetIndex;
    private int outputIndex;
    private int bitMask;
    private int bitValue;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private int repeatIndex = -1;
    private State state = State.START;

    private St4Decompressor(byte[] control, byte[] literal, byte[] byteOffsets,
                            byte[] wordOffsets, byte[] output, int unit,
                            int window, int rewindAt) {
        this.window = window;
        this.rewindAt = rewindAt;
        this.control = control;
        this.literal = literal;
        this.byteOffsets = byteOffsets;
        this.wordOffsets = wordOffsets;
        this.output = output;
        this.unit = unit;
    }

    /** Decodes the streams into {@code size} bytes, which must be a multiple of k. */
    public static byte[] decompress(byte[] control, byte[] literal, byte[] byteOffsets,
                                    byte[] wordOffsets, int unit, int size) {
        return decompress(control, literal, byteOffsets, wordOffsets, unit, size,
                St4Format.maxOffsetUnits(unit));
    }

    /**
     * What a decode produced: the output, and how the stream ended. A stream
     * that repeats reports its loop point - the unit the output continues
     * from after the last one, so the stream decodes as
     * {@code units[0..R) units[R..O)} forever; a stream that simply ends
     * reports -1. For a repeating stream any {@code size} from one whole pass
     * up is decodable - the repeat fills whatever the pass itself did not.
     */
    public record Decoded(byte[] output, int repeatIndex) {}

    /**
     * As above, at the window the stream was packed for: a match reaches at
     * most {@code window} units back, which is what makes a stream safe for a
     * ring of that many units, and an offset beyond it copies from the
     * literal stream. This is how tests hold a {@code -mN} stream to its ring
     * without a ring in sight.
     *
     * @throws IllegalStateException when a copy does not stay behind the
     *     literal read pointer, or a loop reaches past the window
     */
    public static byte[] decompress(byte[] control, byte[] literal, byte[] byteOffsets,
                                    byte[] wordOffsets, int unit, int size,
                                    int window) {
        return decode(control, literal, byteOffsets, wordOffsets, unit, size,
                window).output();
    }

    /** As {@link #decompress}, also reporting whether the stream repeats. */
    public static Decoded decode(byte[] control, byte[] literal, byte[] byteOffsets,
                                 byte[] wordOffsets, int unit, int size,
                                 int window) {
        return decode(control, literal, byteOffsets, wordOffsets, unit, size, window,
                St4Format.NO_REWIND);
    }

    /**
     * As above, holding a stream to its rewind point: from {@code rewindAt}
     * bytes on, no match may reach before it. That is what makes the loop
     * replayable from the state saved there - every pass then sees the same
     * history - and a stream that breaks it would loop wrongly on the 68000,
     * so the reference refuses it instead.
     *
     * @throws IllegalStateException when the loop reaches before its rewind
     *     point, a copy does not stay behind the literal read pointer, or a
     *     loop reaches past the window
     */
    public static Decoded decode(byte[] control, byte[] literal, byte[] byteOffsets,
                                 byte[] wordOffsets, int unit, int size,
                                 int window, int rewindAt) {
        assert St4Format.isUnitSize(unit) : "unit size must be 1, 2 or 4";
        assert size % unit == 0 : "output size must be a whole number of units";
        var decoder = new St4Decompressor(control, literal, byteOffsets, wordOffsets,
                new byte[size], unit, window, rewindAt);
        decoder.run();
        return new Decoded(decoder.output, decoder.repeatIndex);
    }

    private void run() {
        while (state != State.DONE) {
            switch (state) {
                case START -> beginLiterals();
                case LITERALS -> {
                    if (readBit()) {
                        beginMatchFromNewOffset();
                    } else {
                        beginMatchFromLastOffset();
                    }
                }
                case MATCH -> {
                    if (readBit()) {
                        beginMatchFromNewOffset();
                    } else {
                        beginLiterals();
                    }
                }
                case DONE -> throw new AssertionError("unreachable");
            }
        }
        assert outputIndex == output.length : "the streams did not fill the output";
    }

    private void beginLiterals() {
        int length = readInterlacedEliasGamma();
        assert length > 0 : "invalid literal length";
        for (int i = 0; i < length * unit; i++) {
            output[outputIndex++] = literal[literalIndex++];
        }
        state = State.LITERALS;
    }

    private void beginMatchFromLastOffset() {
        int length = readInterlacedEliasGamma();
        if (lastOffset > window) {
            copyFromLiterals(length);
        } else {
            copy(length);
        }
        state = State.MATCH;
    }

    private void beginMatchFromNewOffset() {
        // Two class bits: byte or word, then which bank - or, for a word, the
        // one code that means the stream is over.
        if (readBit()) {
            int bank = readBit() ? 1 : 0;
            assert byteOffsetIndex < byteOffsets.length : "truncated byte offsets";
            lastOffset = bank * 256 + 256 - (byteOffsets[byteOffsetIndex++] & 0xFF);
        } else {
            if (readBit()) {
                endOrRepeat();
                return;
            }
            lastOffset = readWordOffset();
        }
        assert lastOffset > 0 : "an offset must reach back at least one unit";
        int length = readInterlacedEliasGamma() + 1;
        if (lastOffset > window) {
            copyFromLiterals(length);
        } else {
            copy(length);
        }
        state = State.MATCH;
    }

    /**
     * Copies {@code length} units from the literal stream, {@code lastOffset -
     * window} units behind the read pointer, without moving the pointer, and
     * advances the offset by what it copied - as the 68000 decoders do.
     */
    private void copyFromLiterals(int length) {
        int back = lastOffset - window;
        if (back <= length) {
            throw new IllegalStateException("a copy of " + length + " units from " + back
                    + " units back does not stay behind the literal read pointer");
        }
        int source = literalIndex - back * unit;
        if (source < 0) {
            throw new IllegalStateException("a copy reaches before the literal stream");
        }
        for (int i = 0; i < length * unit; i++) {
            output[outputIndex++] = literal[source + i];
        }
        lastOffset -= length;
    }

    /**
     * The end code's extra bit: a plain end, or the repeat - one last word
     * offset from stream C, matched until the output the caller asked for is
     * full. The 68000 decoders run the same match 65535 units at a time,
     * re-armed forever.
     */
    private void endOrRepeat() {
        if (readBit()) {
            // Stream C holds the distance back to the loop point; the loop
            // point itself is where the pass so far ends, minus that.
            int distance = readWordOffset();
            assert distance > 0 : "a repeat must reach back at least one unit";
            if (distance > window) {
                throw new IllegalStateException("the loop distance " + distance
                        + " units reaches past the " + window + "-unit window");
            }
            repeatIndex = outputIndex / unit - distance;
            assert repeatIndex >= 0 : "the loop point must be a unit of the stream";
            lastOffset = distance;
            int remaining = (output.length - outputIndex) / unit;
            if (remaining > 0) {
                copy(remaining);
            }
        }
        state = State.DONE;
    }

    private int readWordOffset() {
        assert wordOffsetIndex + 2 <= wordOffsets.length : "truncated word offsets";
        int scaled = (wordOffsets[wordOffsetIndex] & 0xFF) << 8
                | (wordOffsets[wordOffsetIndex + 1] & 0xFF);
        wordOffsetIndex += 2;
        return ((1 << 16) - scaled) / unit;   // stored as -offset * unit
    }

    /** Copies {@code length} units from {@code lastOffset} units back. */
    private void copy(int length) {
        assert length > 0 : "invalid match length";
        int distance = lastOffset * unit;
        assert distance <= outputIndex : "match reaches before the output";
        for (int i = 0; i < length * unit; i++) {
            // With no rewind point this never fires: -1 is below every source.
            if (outputIndex >= rewindAt && outputIndex - distance < rewindAt) {
                throw new IllegalStateException("the loop reaches before the rewind point "
                        + rewindAt + " at byte " + outputIndex);
            }
            output[outputIndex] = output[outputIndex - distance];
            outputIndex++;
        }
    }

    private int readControl() {
        assert controlIndex < control.length : "truncated control stream";
        return control[controlIndex++] & 0xFF;
    }

    private boolean readBit() {
        bitMask >>= 1;
        if (bitMask == 0) {
            bitMask = 128;
            bitValue = readControl();
        }
        return (bitValue & bitMask) != 0;
    }

    private int readInterlacedEliasGamma() {
        int value = 1;
        while (readBit()) {
            value = value << 1 | (readBit() ? 1 : 0);
        }
        return value;
    }
}
