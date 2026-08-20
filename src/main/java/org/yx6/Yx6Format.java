package org.yx6;

/**
 * The {@code .yx6} container: a fixed header followed by one embedded ST4
 * container per stream section - fourteen frame streams carrying the
 * YM2149's sound registers, and five carrying the compiled effect script.
 *
 * <p>Every field is big-endian, which is what the 68000 player reads directly
 * out of the loaded file. The header is a fixed size so the player can index
 * the stream table without parsing anything.
 *
 * <pre>
 *   0   4  'YX6!'
 *   4   2  format version (5)
 *   6   2  flags: bit 0 set when the tune loops
 *   8   4  O, the number of frames
 *  12   2  frame rate in Hz: how often the player is called (50 usually)
 *  14   2  S, the stream count (19: R0..R13, then M A1 P1 A2 P2)
 *  16   2  N, the ring size in bytes each stream decodes through
 *  18   2  C, the chunk size one ST4_resume call produces
 *  20   4  L, the loop frame; equal to O when the tune does not loop
 *  24   4  YM master clock in Hz, informational
 *  28   4  byte offset of the sample table; zero when there are none
 *  32   2  sample count
 *  34   4*S  byte offset of each intro section, covering frames [0, L)
 * 110   4*S  byte offset of each loop section, covering frames [L, O)
 * 186   ...  the packed sections, then the sample table
 * </pre>
 *
 * <p>Streams 14-18 carry the compiled effect script, one byte per frame
 * like the registers, but they are script data rather than frame streams:
 * their bytes never reach a register. The packer replays the reference
 * player's decisions over the whole timeline and emits prepared actions -
 * M says what acts this frame (zero on the vast majority), A names each
 * tick channel's action, P carries its timer count. O and L count PLAYED frames: a loop
 * whose wrap state differs from its first arrival is rotated until the two
 * agree, so the file may carry a few frames twice, compiled differently.
 * {@link EffectScript} owns the byte semantics; see EFFECTS.md for the
 * design.
 *
 * <p>The sample table is {@code count} entries of {byte offset (long),
 * sample length (word)}, each offset pointing at PSG-ready volume bytes
 * 0..15 followed by one end marker with bit 7 set. A PCM stream plays one
 * of these out, and its tick handler stops on the marker rather than
 * counting. YM calls them digidrums, and their numbering is the YM file's.
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

    /** The only version this release writes or reads: 5 replaced the
     * interpreted effect streams with the compiled effect script. */
    public static final int VERSION = 5;

    /** Flag bit 0: the tune loops back to {@code L} instead of ending. */
    public static final int FLAG_LOOPS = 1;

    /** R0..R13 plus the script streams M, A1, P1, A2, P2. */
    public static final int STREAMS = 19;

    /** The frame streams: one per YM2149 sound register. */
    public static final int REGISTER_STREAMS = 14;

    /** Stream indices of the script data: the master byte, then
     * each slot's action and timer-count bytes. The byte semantics - the
     * verb vocabulary, the master bits, the gate mask - are
     * {@link EffectScript}'s ABI, which packer, player and rigs all cite. */
    public static final int STREAM_M = 14;
    public static final int STREAM_A1 = 15;
    public static final int STREAM_P1 = 16;
    public static final int STREAM_A2 = 17;
    public static final int STREAM_P2 = 18;

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
    public static final int OFFSET_SAMPLE_TABLE = 28;
    public static final int OFFSET_SAMPLE_COUNT = 32;
    public static final int OFFSET_INTRO_TABLE = 34;
    public static final int OFFSET_LOOP_TABLE = OFFSET_INTRO_TABLE + 4 * STREAMS;

    public static final int HEADER_SIZE = OFFSET_LOOP_TABLE + 4 * STREAMS;

    /** A drum table entry: a long offset and a word length. */
    public static final int SAMPLE_ENTRY_SIZE = 6;

    /** The byte after a drum's last sample value has this bit set; the drum
     * interrupt routine's own move.b sees it as negative and stops. */
    public static final int SAMPLE_END_MARK = 0x80;

    /** The format's ceiling: a drum number is five bits. */
    public static final int MAX_SAMPLES = 32;

    /** Default ring size: the size the timings in the README are quoted for. */
    public static final int DEFAULT_RING_SIZE = 960;

    /**
     * Default chunk size, and the group size the round-robin player is built
     * around: one refill per VBL covers all {@value #STREAMS} streams within
     * a 24-VBL cycle, with six VBLs to spare.
     */
    public static final int DEFAULT_CHUNK = 24;

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
        if (ringSize > 2520) {
            return "ring " + ringSize + " exceeds 2520: the player reads register"
                    + " k's ring through an assembled-in displacement of k*N,"
                    + " and 13*N must fit a signed word";
        }
        return "";
    }
}
