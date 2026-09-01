package org.st4;

/**
 * ST4: ZX1's three block types at a chosen unit granularity, split across four
 * streams so a 68000 can read each of them the fastest way that exists for it.
 *
 * <p>A ZX1 stream interleaves everything: flag bits, gamma lengths, offset
 * bytes and literal payload all share one byte sequence. Two things follow from
 * that, and both cost real cycles. The literal payload lands at whatever parity
 * the preceding control bytes leave it at, so a 68000 can only ever copy it a
 * byte at a time - a {@code move.w} or {@code move.l} needs both source and
 * destination even. And the bit reservoir sits in the same sequence as the
 * offset bytes, so it can only ever be refilled a byte at a time: a
 * {@code move.w (a0)+,d0} would desynchronise the moment an offset byte moved
 * the pointer by one. ST4 splits all three apart:
 *
 * <ul>
 *   <li><b>stream A</b> - nothing but bits: the block-type flags and the
 *       interlaced Elias gamma lengths. Because no byte-sized read ever comes
 *       out of it, the reservoir refills a word at a time, halving the
 *       refills.</li>
 *   <li><b>stream B</b> - the byte offsets, one byte each.</li>
 *   <li><b>stream C</b> - the word offsets, one word each, so it is
 *       word-aligned by construction and a match source is one
 *       {@code move.w} away.</li>
 *   <li><b>stream D</b> - the literal payload, nothing else. Its alignment is
 *       therefore a property of the format rather than luck, and it comes
 *       last, so it runs to the end of the file.</li>
 * </ul>
 *
 * <p>Which of the two an offset came from used to be encoded in the low bit of
 * the offset byte itself, ZX1-style; it is now a control code, so neither
 * stream carries a selector and each one holds values of a single width. That
 * costs <em>two</em> control bits per new-offset match rather than one, and the
 * second is not a filler. Stream A's decoder skips the refill check on every
 * bit but a gamma continuation, which is sound only because each operation
 * contributes an even number of bits - a gamma is odd, its flag makes it even.
 * One extra bit would make it odd again and cost a check on every data bit, far
 * more than the offsets save. So a new-offset match spends two: the first says
 * byte or word, and the second picks which 256-unit bank a byte offset names,
 * which is what keeps the split from costing ratio.
 *
 * <pre>
 *   1 0   byte offset from stream B, 1..256 units
 *   1 1   byte offset from stream B, 257..512 units
 *   0 0   word offset from stream C
 *   0 1   end of stream: one more bit says whether it truly ends
 * </pre>
 *
 * <p>The end code is followed by a single bit. A 0 ends the stream as it
 * always did. A 1 means the stream <em>repeats</em> from a loop point R: the
 * container encodes the infinite input {@code units[0..R) units[R..O)}
 * repeated forever, so after the last unit the output continues from unit R
 * and never stops. What stream C stores as its one last word is the distance
 * O-R back to the loop point - an offset like any other - and the decoder
 * becomes an endless match at it. A decoder driven by budgets simply never
 * runs dry; the reference decoder fills whatever output it was asked for. The
 * loop distance obeys the same limits as any other offset, which is what
 * keeps a looping stream safe for the ring it was packed for.
 *
 * <p>On top of that, lengths and offsets are counted in <em>units</em> of
 * {@code k} bytes, where k is 1, 2 or 4. At k = 1 that is ZX1's parse with the
 * payload moved out. At k = 2 or 4 every operation covers k times as much
 * output, so the decoder runs k times fewer operations and can copy k bytes at
 * a time - and, for free, a one-byte offset reaches 128 units instead of 128
 * bytes.
 *
 * <p>The cost is quantisation: an offset or length that is not a multiple of k
 * cannot be expressed, so the packer only finds matches that line up with the
 * unit grid. That is a bargain on data whose structure is k-aligned and a
 * disaster on data that is not, which is why the mode is chosen per asset and
 * recorded in the header.
 *
 * <p>The header is twenty-four bytes and holds only what cannot be worked
 * out:
 *
 * <pre>
 *   0   4  signature: 'S', '4', format version, k
 *   4   4  O, the output size in bytes; always a multiple of k
 *   8   4  stream B, the byte offsets, as a byte offset from the header
 *  12   4  stream C, the word offsets
 *  16   4  stream D, the literals
 *  20   4  the rewind point in bytes, or $FFFFFFFF when there is none
 *  24  ..  streams A, B, C and D, in that order
 * </pre>
 *
 * <p>The rewind point is how a stream loops when its loop is longer than the
 * window: the decoder cannot match that far back, so the caller replays the
 * encoded stream instead. Every decoder keeps its whole state in registers,
 * so the caller saves them when the output reaches the rewind point and
 * restores them - all but the write pointer - when it reaches O, and does so
 * every pass. The packer makes that sound by parsing the loop {@code [R,O)}
 * on its own, so no match in it reaches before R or straddles R: every pass
 * then sees the same history, whatever came before. The field is set only
 * when the caller has something to do; a stream that ends, or that loops by
 * itself through the repeat bit, says $FFFFFFFF.
 *
 * <p>Everything else follows from those. Stream A begins where the header ends,
 * so it needs no field. No length is stored: the streams are laid out in order,
 * so each one runs to the next, and the last runs to the end of the file. None
 * of the four decoders reads a length anyway - it stops on the end marker and
 * the other streams run out with it.
 *
 * <p>Stream D, the literal payload, comes last - version 4 had it second - so
 * it runs to the end of the file. A ring buffer placed directly after the
 * container therefore sits flush against the literal data, and since the
 * stream holds whole units it also ends on a unit boundary: the not yet
 * consumed literals occupy a known stretch of memory just below the ring,
 * which a packer that knows the caller's layout can let matches reach into.
 *
 * <p>The shape is chosen for the 68000 that has to load it. The signature packs
 * the magic, the version AND the unit size into one long, so a decoder built
 * for a particular k proves an asset matches it with a single {@code cmp.l}
 * rather than three compares. Each stream starts on a long boundary, so a wide
 * move is safe at every unit size. And the offsets are relative to the header
 * rather than absolute, so a loader that has the asset's address in a register
 * needs one {@code adda.l} per stream and no relocation:
 *
 * <pre>
 *         lea     asset(pc),a3
 *         cmp.l   #ST4_SIGNATURE,(a3)     ; magic, version and k in one compare
 *         bne.s   wrong_asset
 *         lea     24(a3),a0               ; stream A, where the header ends
 *         movea.l a3,a2
 *         adda.l  16(a3),a2               ; stream D, the literals
 *         movea.l a3,a4
 *         adda.l  8(a3),a4                ; stream B, the byte offsets
 *         movea.l a3,a5
 *         adda.l  12(a3),a5               ; stream C, the word offsets
 * </pre>
 *
 * <p>A derived length can be up to three bytes longer than what the packer
 * wrote, because a stream is padded to the next long boundary. Nothing reads
 * the padding.
 */
public final class St4Format {

    /** {@code 'S4'}, the top half of every signature. */
    public static final int MAGIC = 0x53340000;

    /**
     * Version 5 laid the streams out in file order with the literal payload
     * last - A, B, C, D as they lie, so the literals border whatever the caller
     * places after the container - and gave the end marker its repeat bit and
     * the header its rewind point. Version 4 cut the header to what cannot be
     * derived.
     */
    public static final int VERSION = 5;

    public static final int OFFSET_SIGNATURE = 0;
    public static final int OFFSET_SIZE = 4;
    public static final int OFFSET_BYTE_OFFSETS = 8;
    public static final int OFFSET_WORD_OFFSETS = 12;
    public static final int OFFSET_LITERAL = 16;
    public static final int OFFSET_REWIND = 20;
    public static final int HEADER_SIZE = 24;

    /** The rewind field of a stream that ends or loops by itself. */
    public static final int NO_REWIND = -1;

    /**
     * Magic, version and unit size in one long, so a decoder built for one k
     * checks an asset against itself with a single {@code cmp.l}.
     */
    public static int signature(int unit) {
        return MAGIC | (VERSION << 8) | unit;
    }

    /**
     * The furthest any offset reaches, in BYTES. A word offset is stored as
     * {@code -offset * k}, which the decoder installs unchanged, so the limit
     * is what fits a signed word rather than anything about the format.
     */
    public static final int MAX_OFFSET = 32512;

    /** The furthest a byte offset reaches, in units: two banks of 256. */
    public static final int BYTE_OFFSET_LIMIT = 512;

    /** The longest operation the 68000 decoders can count, in units. */
    public static final int MAX_OP = 65535;

    private St4Format() {}

    public static boolean isUnitSize(int unit) {
        return unit == 1 || unit == 2 || unit == 4;
    }

    /** The reason {@code unit} cannot be used, or an empty string. */
    public static String checkUnit(int unit) {
        return isUnitSize(unit) ? "" : "unit size " + unit + " is not 1, 2 or 4";
    }

    /** How far back a match may reach at this unit size, in units. */
    public static int maxOffsetUnits(int unit) {
        return MAX_OFFSET / unit;
    }

    /**
     * What a container holds: the four streams, the unit size, the output
     * size, and the rewind point in bytes - {@link #NO_REWIND} when the
     * caller has nothing to do.
     */
    public record Container(int unit, int size, byte[] control, byte[] literal,
                            byte[] byteOffsets, byte[] wordOffsets, int rewind) {}

    /**
     * Reads a container, checking everything a decoder would otherwise trust.
     * The streams it returns may carry up to three bytes of alignment padding,
     * since no length is stored and each stream simply runs to the next.
     *
     * @throws IllegalArgumentException with a printable reason if it is not an
     *     ST4 file this build understands, or if the offsets do not describe
     *     four streams laid out in order inside it
     */
    public static Container read(byte[] file) {
        if (file.length < HEADER_SIZE) {
            throw new IllegalArgumentException("too short to be an ST4 file");
        }
        int signature = longAt(file, OFFSET_SIGNATURE);
        if ((signature & 0xFFFF0000) != MAGIC) {
            throw new IllegalArgumentException("not an ST4 file");
        }
        int version = (signature >> 8) & 0xFF;
        if (version != VERSION) {
            throw new IllegalArgumentException(
                    "ST4 format version " + version + ", not " + VERSION);
        }
        int unit = signature & 0xFF;
        String problem = checkUnit(unit);
        if (!problem.isEmpty()) {
            throw new IllegalArgumentException(problem);
        }
        int size = longAt(file, OFFSET_SIZE);
        if (size < 0 || size % unit != 0) {
            throw new IllegalArgumentException(
                    "output size " + size + " is not a whole number of " + unit + "-byte units");
        }
        int rewind = longAt(file, OFFSET_REWIND);
        if (rewind != NO_REWIND && (rewind < 0 || rewind >= size || rewind % unit != 0)) {
            throw new IllegalArgumentException(
                    "rewind point " + rewind + " is not a unit of the output");
        }

        // The streams lie in the file as A, B, C, D: the literal payload
        // last, so it runs to the end of the file.
        int[] edge = {HEADER_SIZE, longAt(file, OFFSET_BYTE_OFFSETS),
                      longAt(file, OFFSET_WORD_OFFSETS), longAt(file, OFFSET_LITERAL),
                      file.length};
        for (int i = 1; i < edge.length - 1; i++) {
            if (edge[i] % 4 != 0) {
                throw new IllegalArgumentException(
                        "stream " + "ABCD".charAt(i) + " does not start on a long boundary");
            }
            if (edge[i] < edge[i - 1] || edge[i] > file.length) {
                throw new IllegalArgumentException(
                        "stream " + "ABCD".charAt(i) + " lies outside the file");
            }
        }
        return new Container(unit, size,
                java.util.Arrays.copyOfRange(file, edge[0], edge[1]),
                java.util.Arrays.copyOfRange(file, edge[3], edge[4]),
                java.util.Arrays.copyOfRange(file, edge[1], edge[2]),
                java.util.Arrays.copyOfRange(file, edge[2], edge[3]), rewind);
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
