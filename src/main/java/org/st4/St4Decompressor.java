package org.st4;

/**
 * The reference ST4 decoder: what the 68000 versions have to agree with.
 *
 * <p>It is the ZX1 state machine with three changes. Literals come from stream
 * B and offsets from stream C or D - by width - rather than from the stream the
 * bits live in; every length and offset is counted in units, so each step
 * moves k bytes; and the end marker carries one more bit, which can turn the
 * end into an endless match - the repeat. The parse is still ZX1's; only where
 * the pieces are written differs, and the two class bits that say which stream
 * an offset came from.
 */
public final class St4Decompressor {

    private enum State { START, LITERALS, MATCH, DONE }

    private final int offsetLimit;
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
                            int offsetLimit) {
        this.offsetLimit = offsetLimit;
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
     * As above, refusing any back-reference further than {@code offsetLimit}
     * units. An offset within the limit is exactly what makes a stream safe
     * for a ring of that many units, so this is how tests hold a {@code -mN}
     * stream to its ring without a ring in sight.
     *
     * @throws IllegalStateException when the stream reaches further back
     */
    public static byte[] decompress(byte[] control, byte[] literal, byte[] byteOffsets,
                                    byte[] wordOffsets, int unit, int size,
                                    int offsetLimit) {
        return decode(control, literal, byteOffsets, wordOffsets, unit, size,
                offsetLimit).output();
    }

    /** As {@link #decompress}, also reporting whether the stream repeats. */
    public static Decoded decode(byte[] control, byte[] literal, byte[] byteOffsets,
                                 byte[] wordOffsets, int unit, int size,
                                 int offsetLimit) {
        assert St4Format.isUnitSize(unit) : "unit size must be 1, 2 or 4";
        assert size % unit == 0 : "output size must be a whole number of units";
        var decoder = new St4Decompressor(control, literal, byteOffsets, wordOffsets,
                new byte[size], unit, offsetLimit);
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
        copy(readInterlacedEliasGamma());
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
        if (lastOffset > offsetLimit) {
            throw new IllegalStateException("offset " + lastOffset
                    + " units reaches past the " + offsetLimit + "-unit limit");
        }
        copy(readInterlacedEliasGamma() + 1);
        state = State.MATCH;
    }

    /**
     * The end code's extra bit: a plain end, or the repeat - one last word
     * offset from stream D, matched until the output the caller asked for is
     * full. The 68000 decoders run the same match 65535 units at a time,
     * re-armed forever.
     */
    private void endOrRepeat() {
        if (readBit()) {
            // Stream D holds the distance back to the loop point; the loop
            // point itself is where the pass so far ends, minus that.
            int distance = readWordOffset();
            assert distance > 0 : "a repeat must reach back at least one unit";
            if (distance > offsetLimit) {
                throw new IllegalStateException("the loop distance " + distance
                        + " units reaches past the " + offsetLimit + "-unit limit");
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
