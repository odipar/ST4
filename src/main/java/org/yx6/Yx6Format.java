package org.yx6;

/**
 * The {@code .yx6} container: a fixed header followed by one embedded ST4
 * container per YM2149 sound register section.
 *
 * <p>Every field is big-endian, which is what the 68000 player reads directly
 * out of the loaded file. The header is a fixed size so the player can index
 * the stream table without parsing anything.
 *
 * <pre>
 *   0   4  'YX6!'
 *   4   2  format version (2)
 *   6   2  flags: bit 0 set when the tune loops
 *   8   4  O, the number of frames
 *  12   2  player frequency in Hz (50 for a standard ST tune)
 *  14   2  S, the stream count (14: R0..R13)
 *  16   2  N, the ring size in bytes each stream decodes through
 *  18   2  C, the chunk size one ST4_resume call produces
 *  20   4  L, the loop frame; equal to O when the tune does not loop
 *  24   4  YM master clock in Hz, informational
 *  28   4*S  byte offset of each intro section, covering frames [0, L)
 *  84   4*S  byte offset of each loop section, covering frames [L, O)
 * 140   ...  the packed sections
 * </pre>
 *
 * <p>Each section is a complete, standard ST4 container - twenty-byte header,
 * then its four streams - packed at unit size 1, placed on a long boundary so
 * the container's own alignment promises hold. The player opens each with the
 * eight-instruction sequence ST4.S documents, and {@code dst4} can unpack any
 * section straight out of the file for debugging.
 *
 * <p>Each register is packed as two sections rather than one, because looping
 * a stream means starting a decoder over: the intro covers the frames before
 * the loop point and the loop covers the rest, and the player restarts the
 * loop decoders every time round. A tune that loops from the start has no
 * intro sections, and one that does not loop has no loop sections; the unused
 * half of the table is zero.
 *
 * <p>The player needs {@code O}, {@code L}, {@code N}, {@code C} and the
 * offsets; the packed sizes are implied by the next offset and never needed,
 * because ST4_wrap counts output units rather than input bytes.
 */
public final class Yx6Format {

    /** {@code 'YX6!'}, the first four bytes of every file. */
    public static final int MAGIC = 0x59583621;

    /** The only version this release writes or reads: 3 moved the payload
     * from ZX1 streams to embedded ST4 containers. */
    public static final int VERSION = 3;

    /** Flag bit 0: the tune loops back to {@code L} instead of ending. */
    public static final int FLAG_LOOPS = 1;

    /** R0..R13: the YM2149 sound registers. R14/R15 are I/O ports, never played. */
    public static final int STREAMS = 14;

    public static final int OFFSET_MAGIC = 0;
    public static final int OFFSET_VERSION = 4;
    public static final int OFFSET_FLAGS = 6;
    public static final int OFFSET_FRAMES = 8;
    public static final int OFFSET_PLAYER_HZ = 12;
    public static final int OFFSET_STREAM_COUNT = 14;
    public static final int OFFSET_RING_SIZE = 16;
    public static final int OFFSET_CHUNK = 18;
    public static final int OFFSET_LOOP_FRAME = 20;
    public static final int OFFSET_MASTER_CLOCK = 24;
    public static final int OFFSET_INTRO_TABLE = 28;
    public static final int OFFSET_LOOP_TABLE = OFFSET_INTRO_TABLE + 4 * STREAMS;

    public static final int HEADER_SIZE = OFFSET_LOOP_TABLE + 4 * STREAMS;

    /** Default ring size: the size the timings in the README are quoted for. */
    public static final int DEFAULT_RING_SIZE = 1024;

    /**
     * Default chunk size, and the group size the round-robin player is built
     * around: one refill per VBL covers all {@value #STREAMS} registers within
     * a 16-VBL cycle, with two VBLs to spare.
     */
    public static final int DEFAULT_CHUNK = 16;

    private Yx6Format() {}

    /**
     * Checks a ring/chunk pair against what both the format and the player
     * require, and returns the reason it is unusable, or null when it is fine.
     *
     * <p>{@code N mod C = 0} is ST4_wrap's own rule. {@code C >= S} is the
     * player's: the refill schedule gives each register one VBL of its own
     * inside a group. {@code N >= 2C} keeps the group being read and the group
     * being written from sharing ring space.
     */
    public static String checkShape(int ringSize, int chunk) {
        return checkShape(ringSize, chunk, 1);
    }

    /**
     * As above, for sections packed at {@code unit} bytes per ST4 unit. The
     * chunk must be whole units, since one refill call's budget is C/unit.
     */
    public static String checkShape(int ringSize, int chunk, int unit) {
        if (!org.st4.St4Format.isUnitSize(unit)) {
            return org.st4.St4Format.checkUnit(unit);
        }
        if (chunk % unit != 0) {
            return "chunk " + chunk + " is not a whole number of " + unit + "-byte units";
        }
        if (chunk < STREAMS) {
            return "chunk " + chunk + " is below the " + STREAMS
                    + " streams, so the round-robin refill cannot fit in one cycle";
        }
        if (ringSize < 2 * chunk) {
            return "ring " + ringSize + " must hold two chunks of " + chunk;
        }
        if (ringSize % chunk != 0) {
            return "ring " + ringSize + " is not a multiple of chunk " + chunk;
        }
        if (ringSize > 65535) {
            return "ring " + ringSize + " exceeds the 65535-byte decoder limit";
        }
        return "";
    }
}
