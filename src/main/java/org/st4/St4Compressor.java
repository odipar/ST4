package org.st4;

import java.util.ArrayDeque;
import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * Writes an ST4 parse out as four streams: bits and gamma lengths in A,
 * literal units in B, byte offsets in C and word offsets in D. A word offset
 * is written as {@code -offset * unit} and a byte offset as
 * {@code bank * 256 + 256 - offset}, which is what the 68000 decoders keep in
 * a register, and stream A is padded to an even length for their word-wide
 * refill.
 *
 * <p>Matches longer than {@code maxOpLength} units are split, since the
 * decoders count an operation in a word; a literal run cannot be, and
 * {@link Result#longestOp()} reports what came out. A copy from the literal
 * stream is written as an offset beyond the window: the window plus the
 * literal units between its source and itself, counted from what was actually
 * written, and strictly shorter than that count - the one copy that would not
 * be gives up its last unit to a literal. A stream written from two parses
 * back to back, the intro and the loop of a rewind stream, merges two literal
 * runs that meet at the seam, and writes a one-unit rep the intro left no
 * offset for as a literal.
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
                         int controlBits, boolean repeatWord, int runs, int endBits) {

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
            return controlBits - endBits + 8 * (literal.length + byteOffsets.length
                    + wordOffsets.length) - (repeatWord ? 16 : 0);
        }
    }

    private final int[] units;
    private final int unit;
    private final int window;
    private final boolean runs;
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
    private int runBlocks;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream currently holds, whether the
    // first block - which has no flag - is still to come, and how many
    // literal units precede each position written so far.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = St4Optimizer.INITIAL_OFFSET;
    private boolean first = true;
    private final int[] literalsBefore;

    private St4Compressor(int[] units, int unit, int window, boolean runs) {
        this.units = units;
        this.unit = unit;
        this.window = window;
        this.runs = runs;
        this.literal = new byte[Math.max(unit, units.length * unit)];
        this.literalsBefore = new int[units.length + 1];
    }

    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength) {
        return compress(optimal, units, unit, maxOpLength, -1);
    }

    /**
     * As above, but the stream repeats from unit {@code repeatIndex}: the
     * container encodes {@code units[0..R) units[R..O)} forever, the distance
     * O-R written as one last word offset. -1 means a plain end.
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
        return compress(optimal, units, unit, maxOpLength, repeatIndex, window, false);
    }

    /**
     * As above, in the experimental run-block format when {@code runs}: a
     * {@link St4Block#RUN} block is written as the end code's class followed
     * by the gamma of its length and one literal unit, and the end itself by
     * the one gamma a run cannot have, a single 0 bit, before its repeat bit.
     * The 68000 decoders do not read this format; the reference does.
     */
    public static Result compress(St4Block optimal, int[] units, int unit, int maxOpLength,
                                  int repeatIndex, int window, boolean runs) {
        assert -1 <= repeatIndex && repeatIndex < units.length
                : "the loop point must be a unit of the stream itself";
        return new St4Compressor(units, unit, window, runs).run(new St4Block[] {optimal},
                maxOpLength, repeatIndex, -1);
    }

    /**
     * A stream that loops by rewind: the intro {@code units[0..R)} - null when
     * R is 0 - and the loop {@code units[R..O)} come from separate parses, so
     * no match in the loop reaches before unit {@code rewindIndex}, where the
     * caller saves the decoder's state to replay from. The stream ends plainly.
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
        return new St4Compressor(units, unit, window, false).run(chains, maxOpLength, -1,
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
                if (block.offset() == St4Block.RUN) {
                    emitRun(length, maxOpLength);
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
        if (runs) {
            writeBit(false);                        // gamma(1): the run no run can be
        }
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
                bitsWritten, repeatIndex >= 0, runBlocks, runs ? 5 : 4);
    }

    /**
     * A run block: one literal unit, then {@code size - 1} repeats of it. The
     * flag and the end code's class, the gamma of the size, the unit into the
     * literal stream; the decoder is left at offset one, as after a match at
     * it.
     */
    private void emitRun(int size, int maxOpLength) {
        assert runs : "run blocks need the run-block format";
        assert size >= 2 && size <= maxOpLength : "a run block is 2..maxOpLength units";
        flushLiterals();
        assert !first : "a stream opens with literals, never a run";
        writeBit(true);
        writeBit(false);
        writeBit(true);
        writeInterlacedEliasGamma(size);
        Units.write(literal, literalIndex, units[readIndex], unit);
        literalIndex += unit;
        literalsBefore[readIndex + 1] = literalsBefore[readIndex] + 1;
        for (int i = 1; i < size; i++) {
            literalsBefore[readIndex + i + 1] = literalsBefore[readIndex + 1];
        }
        lastOffset = 1;
        operations++;
        runBlocks++;
        readIndex += size;
        longestOp = Math.max(longestOp, size);
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
