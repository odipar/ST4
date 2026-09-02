package org.st4;

import java.util.ArrayDeque;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Writes an ST4 parse out as four streams.
 *
 * <p>Stream A carries nothing but bits - the block-type flags and the gamma
 * lengths - so it holds no byte-sized value that a word-wide refill could trip
 * over. Stream B carries nothing but literal units, so its first byte is as
 * aligned as the caller places it and every literal run is a whole number of
 * units. Streams B and C carry the offsets, split by width: bytes in B, words
 * in C, so each is uniform and C is word-aligned by construction.
 *
 * <p>A word offset is written as {@code -offset * unit}, which is exactly what
 * the 68000 decoders keep in a register, so they install it with one move and
 * no arithmetic. A byte offset is written as {@code bank * 256 + 256 - offset},
 * which those decoders read into a register whose high byte is already $FF, so
 * the value arrives pre-negated too.
 *
 * <p>Stream A is padded to an even length. The 68000 decoders refill their bit
 * queue with a {@code move.w}, so the last refill of a stream must find a whole
 * word even when the bits themselves stopped mid-byte.
 *
 * <p>Matches longer than {@code maxOpLength} units are split, as in jx1: the
 * 68000 decoders count an operation's remaining length in a word, so nothing
 * may exceed 65535 units. A literal run cannot be split - after a literal run
 * a 0 bit means a match, so the format has no way to say "more literals" - and
 * {@link Result#longestOp()} reports what actually came out.
 *
 * <p>A copy from the literal stream is written as a match whose offset lies
 * beyond the window: the window plus the number of literal units between the
 * copy's source and the copy, which is what the decoder walks back from its
 * literal read pointer. The parse names the source by its output position;
 * the count is taken here, from the literals actually written so far. A copy
 * must be strictly shorter than that count, because the decoder advances the
 * offset by what it copies and must never see it reach zero; the one copy
 * that would be exactly as long gives up its last unit to a literal.
 *
 * <p>A stream may be written from more than one parse, back to back: that is
 * how a loop longer than the window is packed, the intro and the loop each
 * parsed on their own so that nothing in the loop reaches before it. The seam
 * costs nothing the format cannot absorb. Two literal runs that meet there
 * become one, since the format cannot say "literals again"; and a one-unit
 * match that the loop's parse meant as a rep of the stream's initial offset -
 * the only way the format writes a one-unit match - goes out as a literal when
 * the intro left a different offset behind. Every flag is derived from the
 * stream as actually written, so the seam is otherwise just a block boundary.
 */
public final class St4Compressor {

    /**
     * The four streams, and what the caller needs to know about them.
     * {@code rewindIndex} is the loop point of a stream the caller loops by
     * rewind, in units, or -1 when there is nothing for the caller to do;
     * {@code window} is what the header records, and {@code copies} how many
     * blocks copied from the literal stream.
     */
    public record Result(byte[] control, byte[] literal, byte[] byteOffsets,
                         byte[] wordOffsets, int unit, int paddedSize, int longestOp,
                         int operations, int rewindIndex, int window, int copies,
                         int controlBits, boolean repeatWord) {

        /** Bytes all four streams take together, which is what a comparison wants. */
        public int packedSize() {
            return control.length + literal.length + byteOffsets.length
                    + wordOffsets.length;
        }

        /**
         * Bits the parse itself cost: everything written but the end code and
         * its repeat bit, and stream A's padding - what a parse's chain counts.
         */
        public int bits() {
            return controlBits - 4 + 8 * (literal.length + byteOffsets.length
                    + wordOffsets.length) - (repeatWord ? 16 : 0);
        }
    }

    private final int[] units;
    private final int unit;
    private final int window;
    private byte[] control = new byte[256];
    private int controlIndex;
    private byte[] literal;
    private int literalIndex;
    private byte[] byteOffsets = new byte[64];
    private int byteOffsetIndex;
    private byte[] wordOffsets = new byte[64];
    private int wordOffsetIndex;
    private int bitMask;
    private int bitIndex;
    private int bitsWritten;
    private int longestOp;
    private int operations;
    private int copies;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream currently holds, whether the
    // first block - which has no flag - is still to come, and how many
    // literal units precede each position written so far.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private boolean first = true;
    private final int[] literalsBefore;

    private St4Compressor(int[] units, int unit, int window) {
        this.units = units;
        this.unit = unit;
        this.window = window;
        this.literal = new byte[Math.max(unit, units.length * unit)];
        this.literalsBefore = new int[units.length + 1];
    }

    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength) {
        return compress(optimal, units, unit, maxOpLength, -1);
    }

    /**
     * As above, but the stream ends by repeating instead of stopping: the
     * container encodes the infinite input {@code units[0..R) units[R..O)}
     * repeated forever, so after its last unit the output continues from unit
     * {@code repeatIndex} and never stops. -1 means a plain end. What stream D
     * stores is the distance O-R back to the loop point, an offset like any
     * other, so the caller holds it to the window the stream was packed for.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex) {
        return compress(optimal, units, unit, maxOpLength, repeatIndex,
                St4Format.maxOffsetUnits(unit));
    }

    /**
     * As above, for a parse made at {@code window} units: an offset beyond it
     * is a copy from the literal stream, so the parse's matches must keep
     * within it and its copies are written past it.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex, int window) {
        assert -1 <= repeatIndex && repeatIndex < units.length
                : "the loop point must be a unit of the stream itself";
        return new St4Compressor(units, unit, window).run(new St4Block[] {optimal},
                maxOpLength, repeatIndex, -1);
    }

    /**
     * A stream that loops by rewind, for a loop longer than the window: the
     * intro {@code units[0..R)} and the loop {@code units[R..O)} come from
     * separate parses - {@code intro} is null when R is 0 - so no match in the
     * loop reaches before it, and the caller can replay the stream from the
     * state it saved at unit {@code rewindIndex} every time the output reaches
     * O. The stream ends plainly; the rewind point goes in the header.
     */
    public static Result compressRewinding(@Nullable St4Block intro, St4Block loop,
                                           int[] units, int unit, int maxOpLength,
                                           int rewindIndex) {
        return compressRewinding(intro, loop, units, unit, maxOpLength, rewindIndex,
                St4Format.maxOffsetUnits(unit));
    }

    /** As above, for parses made at {@code window} units. */
    public static Result compressRewinding(@Nullable St4Block intro, St4Block loop,
                                           int[] units, int unit, int maxOpLength,
                                           int rewindIndex, int window) {
        assert 0 <= rewindIndex && rewindIndex < units.length
                : "the rewind point must be a unit of the stream itself";
        assert (intro == null) == (rewindIndex == 0) : "an intro exactly when there is one";
        St4Block[] chains = intro == null ? new St4Block[] {loop} : new St4Block[] {intro, loop};
        return new St4Compressor(units, unit, window).run(chains, maxOpLength, -1,
                rewindIndex);
    }

    private Result run(St4Block[] chains, int maxOpLength, int repeatIndex, int rewindIndex) {
        for (St4Block chain : chains) {
            // Un-reverse the chain; its head is the parser's fake block.
            var blocks = new ArrayDeque<St4Block>();
            for (St4Block block = chain; block != null; block = block.chain()) {
                blocks.push(block);
            }
            St4Block previous = blocks.pop();

            for (St4Block block : blocks) {
                int length = block.index() - previous.index();
                previous = block;

                if (block.offset() == 0) {
                    pendingLiterals += length;      // runs merge across a seam
                    continue;
                }
                if (block.offset() < 0) {
                    copy(-block.offset(), length, maxOpLength);
                    continue;
                }
                int offset = block.offset();
                assert offset <= window : "a match reaches past the window";
                // Split evenly rather than greedily: every piece after the first
                // has to be a new-offset match, and those cannot be shorter than
                // two units, so a greedy remainder of one would be unwritable.
                int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
                int base = length / pieces;
                int wider = length % pieces;
                for (int piece = 0; piece < pieces; piece++) {
                    int size = base + (piece < wider ? 1 : 0);
                    boolean rep = pendingLiterals > 0 && offset == lastOffset;
                    if (size == 1 && !rep) {
                        pendingLiterals++;          // the seam's one-unit rep
                        continue;
                    }
                    flushLiterals();
                    emitMatch(offset, size, rep);
                }
            }
        }
        flushLiterals();
        assert readIndex == units.length : "the parses did not cover the input";

        // End marker, then the repeat bit: end for good, or install one last
        // word offset from stream D - the distance back to the loop point -
        // and match it forever.
        writeBit(true);
        writeBit(false);
        writeBit(true);
        writeBit(repeatIndex >= 0);
        if (repeatIndex >= 0) {
            int scaled = (units.length - repeatIndex) * unit;
            assert scaled <= 32768 : "the loop distance must fit -(O-R)*k in a signed word";
            assert units.length - repeatIndex <= window : "the loop must fit the window";
            if (wordOffsetIndex + 2 > wordOffsets.length) {
                wordOffsets = Arrays.copyOf(wordOffsets, wordOffsets.length * 2);
            }
            wordOffsets[wordOffsetIndex++] = (byte) (-scaled >> 8);
            wordOffsets[wordOffsetIndex++] = (byte) -scaled;
        }

        return new Result(Arrays.copyOf(control, controlIndex + (controlIndex & 1)),
                Arrays.copyOf(literal, literalIndex),
                Arrays.copyOf(byteOffsets, byteOffsetIndex),
                Arrays.copyOf(wordOffsets, wordOffsetIndex), unit,
                units.length * unit, longestOp, operations, rewindIndex, window, copies,
                bitsWritten, repeatIndex >= 0);
    }

    /**
     * A copy from the literal stream, {@code distance} units back in the
     * output for {@code length} units, in pieces the counters can hold. Each
     * piece is written as a match at the window plus the literals between its
     * source and itself; a piece that would be exactly as long as that count
     * gives up its last unit to a literal, so the decoder's offset - which it
     * advances by what it copies - never reaches zero.
     */
    private void copy(int distance, int length, int maxOpLength) {
        int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
        int base = length / pieces;
        int wider = length % pieces;
        for (int piece = 0; piece < pieces; piece++) {
            int size = base + (piece < wider ? 1 : 0);
            int start = readIndex + pendingLiterals;
            int source = start - distance;
            assert literalsAt(source + size) - literalsAt(source) == size
                    : "a copy's source must be literal";
            int back = literalsAt(start) - literalsAt(source);
            assert back >= size : "a copy's source lies behind its own literals";
            int given = 0;
            if (back == size) {
                if (size - 1 < 2) {
                    pendingLiterals += size;        // too short to write at all
                    continue;
                }
                given = 1;
                size--;
            }
            int wire = window + back;
            assert wire <= St4Format.maxOffsetUnits(unit) : "a copy reaches past the offsets";
            boolean rep = pendingLiterals > 0 && wire == lastOffset;
            flushLiterals();
            emitMatch(wire, size, rep);
            lastOffset = wire - size;               // where the decoder leaves it
            copies++;
            pendingLiterals += given;
        }
    }

    /**
     * Literal units before {@code position}: recorded for what is written,
     * counted for the run still pending.
     */
    private int literalsAt(int position) {
        return position <= readIndex ? literalsBefore[position]
                : literalsBefore[readIndex] + (position - readIndex);
    }

    private void emitMatch(int offset, int size, boolean rep) {
        if (rep) {
            writeBit(false);
            writeInterlacedEliasGamma(size);
        } else {
            writeBit(true);
            writeOffsetOf(offset);
            writeInterlacedEliasGamma(size - 1);
            lastOffset = offset;
        }
        for (int i = 0; i < size; i++) {
            literalsBefore[readIndex + i + 1] = literalsBefore[readIndex];
        }
        operations++;
        readIndex += size;
        longestOp = Math.max(longestOp, size);
    }

    /**
     * Writes the literal run gathered so far, if there is one: its flag -
     * unless it opens the stream - its length, and its units into stream B.
     */
    private void flushLiterals() {
        if (pendingLiterals == 0) {
            return;
        }
        if (first) {
            first = false;                          // a stream opens with literals
        } else {
            writeBit(false);
        }
        writeInterlacedEliasGamma(pendingLiterals);
        for (int i = 0; i < pendingLiterals; i++) {
            Units.write(literal, literalIndex, units[readIndex], unit);
            literalIndex += unit;
            literalsBefore[readIndex + 1] = literalsBefore[readIndex] + 1;
            readIndex++;
        }
        operations++;
        longestOp = Math.max(longestOp, pendingLiterals);
        pendingLiterals = 0;
    }

    /**
     * The two class bits, then the offset itself into whichever stream it
     * belongs to. The class bits are also what keeps the operation an even
     * number of bits long, which is what lets the decoder skip refill checks.
     */
    private void writeOffsetOf(int offset) {
        if (offset <= St4Format.BYTE_OFFSET_LIMIT) {
            int bank = (offset - 1) / 256;              // 0 for 1..256, 1 for 257..512
            writeBit(true);
            writeBit(bank != 0);
            if (byteOffsetIndex == byteOffsets.length) {
                byteOffsets = Arrays.copyOf(byteOffsets, byteOffsets.length * 2);
            }
            byteOffsets[byteOffsetIndex++] = (byte) (bank * 256 + 256 - offset);
        } else {
            int scaled = offset * unit;
            assert scaled <= 32768 : "a word offset must fit -offset*k in a signed word";
            writeBit(false);
            writeBit(false);
            if (wordOffsetIndex + 2 > wordOffsets.length) {
                wordOffsets = Arrays.copyOf(wordOffsets, wordOffsets.length * 2);
            }
            wordOffsets[wordOffsetIndex++] = (byte) (-scaled >> 8);
            wordOffsets[wordOffsetIndex++] = (byte) -scaled;
        }
    }

    private void writeControl(int value) {
        if (controlIndex == control.length) {
            control = Arrays.copyOf(control, control.length * 2);
        }
        control[controlIndex++] = (byte) value;
    }

    /**
     * Bits live in stream A, in the byte reserved when the reservoir ran dry -
     * so a set bit patches that byte where it already sits.
     */
    private void writeBit(boolean value) {
        bitsWritten++;
        if (bitMask == 0) {
            bitMask = 128;
            bitIndex = controlIndex;
            writeControl(0);
        }
        if (value) {
            control[bitIndex] |= (byte) bitMask;
        }
        bitMask >>= 1;
    }

    private void writeInterlacedEliasGamma(int value) {
        for (int bit = Integer.highestOneBit(value) >> 1; bit != 0; bit >>= 1) {
            writeBit(true);
            writeBit((value & bit) != 0);
        }
        writeBit(false);
    }
}
