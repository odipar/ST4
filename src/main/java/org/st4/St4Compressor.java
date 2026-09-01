package org.st4;

import java.util.ArrayDeque;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Writes an ST4 parse out as four streams.
 *
 * <p>Stream A carries nothing but bits - the block-type flags and the gamma
 * lengths - so it holds no byte-sized value that a word-wide refill could trip
 * over. Stream D carries nothing but literal units, so its first byte is as
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
     * rewind, in units, or -1 when there is nothing for the caller to do.
     */
    public record Result(byte[] control, byte[] literal, byte[] byteOffsets,
                         byte[] wordOffsets, int unit, int paddedSize, int longestOp,
                         int operations, int rewindIndex) {

        /** Bytes all four streams take together, which is what a comparison wants. */
        public int packedSize() {
            return control.length + literal.length + byteOffsets.length
                    + wordOffsets.length;
        }
    }

    private final int[] units;
    private final int unit;
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
    private int longestOp;
    private int operations;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream currently holds, and whether the
    // first block - which has no flag - is still to come.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private boolean first = true;

    private St4Compressor(int[] units, int unit) {
        this.units = units;
        this.unit = unit;
        this.literal = new byte[Math.max(unit, units.length * unit)];
    }

    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength) {
        return compress(optimal, units, unit, maxOpLength, -1);
    }

    /**
     * As above, but the stream ends by repeating instead of stopping: the
     * container encodes the infinite input {@code units[0..R) units[R..O)}
     * repeated forever, so after its last unit the output continues from unit
     * {@code repeatIndex} and never stops. -1 means a plain end. What stream C
     * stores is the distance O-R back to the loop point, an offset like any
     * other, so the caller holds it to the window the stream was packed for.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex) {
        assert -1 <= repeatIndex && repeatIndex < units.length
                : "the loop point must be a unit of the stream itself";
        return new St4Compressor(units, unit).run(new St4Block[] {optimal}, maxOpLength,
                repeatIndex, -1);
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
        assert 0 <= rewindIndex && rewindIndex < units.length
                : "the rewind point must be a unit of the stream itself";
        assert (intro == null) == (rewindIndex == 0) : "an intro exactly when there is one";
        St4Block[] chains = intro == null ? new St4Block[] {loop} : new St4Block[] {intro, loop};
        return new St4Compressor(units, unit).run(chains, maxOpLength, -1, rewindIndex);
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
                int offset = block.offset();
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
                    if (rep) {
                        writeBit(false);
                        writeInterlacedEliasGamma(size);
                    } else {
                        writeBit(true);
                        writeOffsetOf(offset);
                        writeInterlacedEliasGamma(size - 1);
                        lastOffset = offset;
                    }
                    operations++;
                    readIndex += size;
                    longestOp = Math.max(longestOp, size);
                }
            }
        }
        flushLiterals();
        assert readIndex == units.length : "the parses did not cover the input";

        // End marker, then the repeat bit: end for good, or install one last
        // word offset from stream C - the distance back to the loop point -
        // and match it forever.
        writeBit(true);
        writeBit(false);
        writeBit(true);
        writeBit(repeatIndex >= 0);
        if (repeatIndex >= 0) {
            int scaled = (units.length - repeatIndex) * unit;
            assert scaled <= 32768 : "the loop distance must fit -(O-R)*k in a signed word";
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
                units.length * unit, longestOp, operations, rewindIndex);
    }

    /**
     * Writes the literal run gathered so far, if there is one: its flag -
     * unless it opens the stream - its length, and its units into stream D.
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
            Units.write(literal, literalIndex, units[readIndex++], unit);
            literalIndex += unit;
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
