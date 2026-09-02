package org.st4;

/**
 * ST4: ZX1's three block types at a chosen unit granularity, split across four
 * streams so a 68000 can read each of them the fastest way it has.
 *
 * <p>A ZX1 stream interleaves flags, lengths, offsets and literals in one byte
 * sequence, so a 68000 can copy literals and refill its bit reservoir only a
 * byte at a time. ST4 splits them: stream A holds nothing but bits - the flags
 * and the interlaced Elias gamma lengths - so the reservoir refills a word at
 * a time; stream B the literal payload, whole units; stream C the byte
 * offsets; stream D the word offsets, word-aligned by construction. Which
 * stream an offset comes from is a control code rather than a bit of the
 * offset, so each stream holds values of one width:
 *
 * <pre>
 *   1 0   byte offset from stream C, 1..256 units
 *   1 1   byte offset from stream C, 257..512 units
 *   0 0   word offset from stream D
 *   0 1   end of stream: one more bit says whether it truly ends
 * </pre>
 *
 * Two class bits rather than one keep every operation an even number of bits,
 * which is what lets a decoder skip the refill check on every bit but a gamma
 * continuation.
 *
 * <p>Lengths and offsets count units of k bytes, k being 1, 2 or 4. At k = 1
 * this is ZX1's parse with the payload moved out; at 2 or 4 every operation
 * covers k times as much and the decoder copies k bytes at a time, at the
 * cost that only k-aligned matches can be expressed.
 *
 * <p>An offset of at most the window M, which the header records, is a match
 * from the output. One beyond M is a copy from the literal stream: it copies
 * {@code offset - M} units from behind the literal read pointer, leaves the
 * pointer where it was, and advances the offset by what it copied, so a rep
 * after a copy resumes just past it; every copy is strictly shorter than its
 * distance. Streams packed without copies never exceed M.
 *
 * <p>The end code's extra bit: 0 ends the stream; 1 repeats it from a loop
 * point R - the container encodes {@code units[0..R) units[R..O)} forever -
 * with the distance O-R as one last word in stream D, which the decoder
 * matches endlessly. A loop longer than the window is replayed instead: the
 * header names the rewind point in bytes, the caller saves the decoder's
 * registers there and restores them, all but the write pointer, at O, and the
 * packer parses the loop on its own so every pass sees the same history.
 *
 * <p>The header is twenty-eight bytes:
 *
 * <pre>
 *   0   4  signature: 'S', '4', format version, k
 *   4   4  O, the output size in bytes, a multiple of k
 *   8   4  stream B, the literals, as a byte offset from the header
 *  12   4  stream C, the byte offsets
 *  16   4  stream D, the word offsets
 *  20   4  the rewind point in bytes, or $FFFFFFFF when there is none
 *  24   4  M, the window in units
 *  28  ..  streams A, B, C and D, in that order, each on a long boundary
 * </pre>
 *
 * Stream A begins where the header ends, each stream runs to the next and no
 * length is stored: the decoders stop on the end marker. The signature packs
 * magic, version and k into one long, so a decoder built for one k checks an
 * asset with a single {@code cmp.l}, and the starts are header-relative, so
 * opening a container is one {@code adda.l} per stream. A derived length can
 * be up to three bytes of padding longer than what was written.
 */
public final class St4Format {

    /** {@code 'S4'}, the top half of every signature. */
    public static final int MAGIC = 0x53340000;

    /**
     * Version 7 put the literal payload back second, as version 4 had it,
     * since nothing in the decoders depends on where the ring is - A, B, C,
     * D as they lie, B the literals. Version 6 let an offset beyond the
     * window copy from the literal stream, and recorded the window in the
     * header. Version 5 laid the streams out in file order with the literal
     * payload last, and gave the end marker its repeat bit and the header its
     * rewind point. Version 4 cut the header to what cannot be derived.
     */
    public static final int VERSION = 7;

    public static final int OFFSET_SIGNATURE = 0;
    public static final int OFFSET_SIZE = 4;
    public static final int OFFSET_LITERAL = 8;
    public static final int OFFSET_BYTE_OFFSETS = 12;
    public static final int OFFSET_WORD_OFFSETS = 16;
    public static final int OFFSET_REWIND = 20;
    public static final int OFFSET_WINDOW = 24;
    public static final int HEADER_SIZE = 28;

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
     * size, the rewind point in bytes - {@link #NO_REWIND} when the caller
     * has nothing to do - and the window in units, beyond which an offset
     * copies from the literal stream.
     */
    public record Container(int unit, int size, byte[] control, byte[] literal,
                            byte[] byteOffsets, byte[] wordOffsets, int rewind, int window) {}

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
        int window = longAt(file, OFFSET_WINDOW);
        if (window < 1 || window > maxOffsetUnits(unit)) {
            throw new IllegalArgumentException(
                    "window " + window + " is not 1.." + maxOffsetUnits(unit) + " units");
        }

        // The streams lie in the file as A, B, C, D: the bits, the literal
        // payload, the byte offsets and the word offsets.
        int[] edge = {HEADER_SIZE, longAt(file, OFFSET_LITERAL),
                      longAt(file, OFFSET_BYTE_OFFSETS), longAt(file, OFFSET_WORD_OFFSETS),
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
                java.util.Arrays.copyOfRange(file, edge[1], edge[2]),
                java.util.Arrays.copyOfRange(file, edge[2], edge[3]),
                java.util.Arrays.copyOfRange(file, edge[3], edge[4]), rewind, window);
    }

    private static int longAt(byte[] file, int at) {
        return (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
                | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
    }
}
