package org.yx6;

/**
 * Extracts the YM special effects into the four effect streams, normalizing
 * every dialect and every unplayable code away at pack time - the player
 * never sees an effect it cannot run.
 *
 * <p>A YM6 frame carries up to two effect slots, each three fields smeared
 * across spare register bits: a code nibble (type in bits 7-6, voice+1 in
 * bits 5-4, zero voice bits meaning idle) in R1 or R3, an MFP timer prescaler
 * in R6 or R8 bits 5-7, and a timer count in R14 or R15. YM5 encodes less in
 * different places: R1 bits 4-5 name a SID voice, R3 bits 4-5 a digidrum
 * voice, with the drum's prescaler always in R8 regardless of voice. Both
 * come out of here as the same two byte pairs per frame:
 *
 * <pre>
 *   E = code bits 7-4 | prescaler bits 2-0     (zero = the slot is idle)
 *   T = the timer count
 * </pre>
 *
 * <p>Codes are dropped to idle when the reference player would not start
 * them: prescaler or count of zero (wild files carry many such inert codes),
 * a rate above what a real machine survives, a drum number with no sample
 * behind it, and Sinus-SID - which no player, the format author's included,
 * has ever implemented. The drop counters report what happened.
 *
 * <p>Drum samples are converted to PSG-ready volume values here: the high
 * nibble of an 8-bit sample, or the byte as-is for a 4-bit file - exactly the
 * real-hardware mapping in the reference player's source.
 */
public final class YmEffects {

    /** The four effect types, as they sit in code bits 7-6. */
    public static final int TYPE_SID = 0x00;
    public static final int TYPE_DRUM = 0x40;
    public static final int TYPE_SINUS = 0x80;
    public static final int TYPE_BUZZER = 0xC0;

    /** The fastest timer a real player programs; above it, codes are dropped. */
    public static final int MAX_TIMER_HZ = 25600;

    /** The MFP timer clock and its prescaler table; index 0 stops the timer. */
    public static final int MFP_CLOCK = 2457600;
    private static final int[] PREDIV = {0, 4, 10, 16, 50, 64, 100, 200};

    /** The four streams, the converted drums, and what was dropped. */
    public record Extraction(byte[] e1, byte[] t1, byte[] e2, byte[] t2,
                             byte[][] drums, int inert, int tooFast, int sinus,
                             int missingDrum) {

        /** All four streams, in file order E1, T1, E2, T2. */
        public byte[][] streams() {
            return new byte[][] {e1, t1, e2, t2};
        }

        public int dropped() {
            return inert + tooFast + sinus + missingDrum;
        }
    }

    private final Ym6Reader.Song song;
    private final byte[][] drums;
    private int inert;
    private int tooFast;
    private int sinus;
    private int missingDrum;

    private YmEffects(Ym6Reader.Song song) {
        this.song = song;
        this.drums = convertDrums(song);
    }

    public static Extraction extract(Ym6Reader.Song song) {
        var effects = new YmEffects(song);
        int frames = song.frames();
        byte[] e1 = new byte[frames];
        byte[] t1 = new byte[frames];
        byte[] e2 = new byte[frames];
        byte[] t2 = new byte[frames];
        boolean ym6 = song.format().startsWith("YM6");
        for (int frame = 0; frame < frames; frame++) {
            long slot1;
            long slot2;
            if (ym6) {
                slot1 = effects.validate(effects.register(1, frame) & 0xF0,
                        effects.register(6, frame) >> 5, effects.register(14, frame), frame);
                slot2 = effects.validate(effects.register(3, frame) & 0xF0,
                        effects.register(8, frame) >> 5, effects.register(15, frame), frame);
            } else {
                // YM5: R1 bits 4-5 are a SID voice, R3 bits 4-5 a drum voice
                // (the version byte is load-bearing: the same bits mean other
                // things in YM6), and a YM5 drum's prescaler always sits in R8.
                slot1 = effects.validate(TYPE_SID | ((effects.register(1, frame) & 0x30)),
                        effects.register(6, frame) >> 5, effects.register(14, frame), frame);
                slot2 = effects.validate(TYPE_DRUM | ((effects.register(3, frame) & 0x30)),
                        effects.register(8, frame) >> 5, effects.register(15, frame), frame);
            }
            e1[frame] = (byte) (slot1 >> 8);
            t1[frame] = (byte) slot1;
            e2[frame] = (byte) (slot2 >> 8);
            t2[frame] = (byte) slot2;
        }
        return new Extraction(e1, t1, e2, t2, effects.drums, effects.inert,
                effects.tooFast, effects.sinus, effects.missingDrum);
    }

    private int register(int register, int frame) {
        return song.registers()[register][frame] & 0xFF;
    }

    /**
     * One slot's E and T bytes packed as (E << 8) | T; zero when the slot is
     * idle or the code cannot be played.
     */
    private long validate(int code, int prescaler, int count, int frame) {
        int voiceBits = code & 0x30;
        if (voiceBits == 0) {
            return 0;                           // the slot is idle this frame
        }
        int type = code & 0xC0;
        if (type == TYPE_SINUS) {
            sinus++;
            return 0;
        }
        prescaler &= 7;
        count &= 0xFF;
        if (PREDIV[prescaler] == 0 || count == 0) {
            inert++;                            // the reference player's no-op
            return 0;
        }
        int hz = MFP_CLOCK / (PREDIV[prescaler] * count);
        if (hz > MAX_TIMER_HZ) {
            tooFast++;
            return 0;
        }
        if (type == TYPE_DRUM) {
            int voice = (voiceBits >> 4) - 1;
            int number = register(8 + voice, frame) & 31;
            if (number >= drums.length) {
                missingDrum++;
                return 0;
            }
        }
        return ((long) ((code & 0xF0) | prescaler) << 8) | count;
    }

    /**
     * The drum samples as PSG volume values 0..15, one per byte, without the
     * end markers - those belong to the file layout, not the sound.
     */
    private static byte[][] convertDrums(Ym6Reader.Song song) {
        int count = Math.min(song.digidrums(), Yx6Format.MAX_DRUMS);
        byte[][] converted = new byte[count][];
        boolean fourBit = (song.attributes() & Ym6Reader.Song.A_DRUM4BITS) != 0;
        for (int i = 0; i < count; i++) {
            byte[] source = song.drums()[i];
            byte[] drum = new byte[source.length];
            for (int j = 0; j < source.length; j++) {
                drum[j] = (byte) (fourBit ? source[j] & 15 : (source[j] & 0xFF) >> 4);
            }
            converted[i] = drum;
        }
        return converted;
    }
}
