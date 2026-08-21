package org.yx6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.st4.St4Decompressor;
import org.st4.St4Format;
import org.junit.jupiter.api.Test;

/** The loop split: two sets of streams, and what the player is promised. */
final class Yx6LoopTest {

    private static final int FRAMES = 900;

    private static Ym6Reader.Song song(int loopFrame) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 0, loopFrame));
    }

    /** The same tune with its effect codes silenced: the register-section
     * properties below are about the split itself, and a held effect
     * crossing the wrap would rotate it. */
    private static Ym6Reader.Song quiet(int loopFrame) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        for (int frame = 0; frame < FRAMES; frame++) {
            registers[1][frame] &= ~0x30;       // no slot-1 code
            registers[3][frame] &= ~0x30;       // no slot-2 voice bits
        }
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 0, loopFrame));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static int longAt(byte[] file, int at) {
        return (word(file, at) << 16) | word(file, at + 2);
    }

    private static byte[] stream(byte[] file, int table, int register, int packedSize) {
        int from = longAt(file, table + 4 * register);
        return unpack(file, from, packedSize, Integer.MAX_VALUE);
    }

    /** Unpacks one embedded ST4 container, holding it to an offset limit. */
    private static byte[] unpack(byte[] file, int from, int packedSize, int offsetLimit) {
        St4Format.Container section = St4Format.read(
                Arrays.copyOfRange(file, from, from + packedSize));
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size(), offsetLimit);
    }


    private static int packedSize(Yx6Encoder.Result result, int register, boolean loop) {
        return result.streams().stream()
                .filter(s -> s.register() == register && s.loop() == loop)
                .findFirst().orElseThrow().packedSize();
    }

    @Test
    void eachSectionUnpacksToItsOwnSliceOfTheRegister() {
        int loop = 397;                             // not a multiple of the chunk
        Ym6Reader.Song source = song(loop);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 960, 24, loop, false);
        byte[] file = result.file();

        // This tune holds an effect across the wrap, so the split rotates
        // until both arrivals agree; the header carries the played shape.
        int split = result.script().split();
        assertEquals(Yx6Format.FLAG_LOOPS,
                word(file, Yx6Format.OFFSET_FLAGS) & Yx6Format.FLAG_LOOPS);
        assertEquals(split, longAt(file, Yx6Format.OFFSET_LOOP_FRAME));
        assertEquals(result.script().frames(), longAt(file, Yx6Format.OFFSET_FRAMES));

        byte[][] expected = Yx6EncoderTest.expectedVectors(source, loop, 1);
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            byte[] whole = expected[register];
            assertArrayEquals(Arrays.copyOfRange(whole, 0, split),
                    stream(file, Yx6Format.OFFSET_INTRO_TABLE, register,
                            packedSize(result, register, false)),
                    "intro stream " + register);
            assertArrayEquals(Arrays.copyOfRange(whole, split, whole.length),
                    stream(file, Yx6Format.OFFSET_LOOP_TABLE, register,
                            packedSize(result, register, true)),
                    "loop stream " + register);
        }
    }

    @Test
    void loopingFromTheStartPacksNoIntro() {
        Yx6Encoder.Result result = Yx6Encoder.encode(quiet(0), 960, 24, 0, false);
        byte[] file = result.file();

        assertEquals(0, longAt(file, Yx6Format.OFFSET_LOOP_FRAME));
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            assertEquals(0, longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register),
                    "intro offset " + register + " should be unused");
            assertTrue(longAt(file, Yx6Format.OFFSET_LOOP_TABLE + 4 * register) > 0);
        }
        assertEquals(Yx6Format.STREAMS, result.streams().size());
    }

    @Test
    void aHeldEffectAcrossTheWrapRotatesTheSplit() {
        // The effectful tune holds a SID from frame 0: the first arrival at
        // the loop head (pristine) and the wrap arrival (running) disagree,
        // so the split rotates past the start and a short intro appears -
        // the frames it absorbs exist twice, compiled differently.
        Yx6Encoder.Result result = Yx6Encoder.encode(song(0), 960, 24, 0, false);
        int split = result.script().split();
        assertTrue(split > 0, "the wrap state cannot match the pristine start");
        assertEquals(FRAMES + split, result.script().frames());
        byte[] file = result.file();
        assertEquals(split, longAt(file, Yx6Format.OFFSET_LOOP_FRAME));
        assertTrue(longAt(file, Yx6Format.OFFSET_INTRO_TABLE) > 0,
                "the rotation gives the loop-from-zero tune an intro");
    }

    @Test
    void playingOncePacksNoLoop() {
        Yx6Encoder.Result result = Yx6Encoder.encode(song(0), 960, 24, -1, false);
        byte[] file = result.file();

        assertEquals(0, word(file, Yx6Format.OFFSET_FLAGS) & Yx6Format.FLAG_LOOPS);
        assertEquals(FRAMES, longAt(file, Yx6Format.OFFSET_LOOP_FRAME),
                "the intro covers everything, so the split sits at the end");
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            assertTrue(longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register) > 0);
            assertEquals(0, longAt(file, Yx6Format.OFFSET_LOOP_TABLE + 4 * register),
                    "loop offset " + register + " should be unused");
        }
    }

    @Test
    void theSplitCostsRatioButLoopingFromZeroDoesNot() {
        int whole = Yx6Encoder.encode(quiet(0), 960, 24, -1, false).packedSize();
        int fromStart = Yx6Encoder.encode(quiet(0), 960, 24, 0, false).packedSize();
        int fromMiddle = Yx6Encoder.encode(quiet(450), 960, 24, 450, false).packedSize();

        assertEquals(whole, fromStart, "one section either way, so the same bytes");
        assertTrue(fromMiddle > whole, "splitting a register costs some ratio");
    }

    @Test
    void rejectsALoopFrameOutsideTheTune() {
        Ym6Reader.Song source = song(0);
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 960, 24, FRAMES, false));
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 960, 24, FRAMES + 10, false));
    }

    @Test
    void bothSectionsStayInsideTheRingAndTheWordCounters() {
        int ring = 240;
        Yx6Encoder.Result result = Yx6Encoder.encode(song(397), ring, 24, 397, false);
        byte[] file = result.file();
        assertTrue(result.longestOp() <= 65535);

        for (Yx6Encoder.Stream stream : result.streams()) {
            int table = stream.loop() ? Yx6Format.OFFSET_LOOP_TABLE
                    : Yx6Format.OFFSET_INTRO_TABLE;
            int from = longAt(file, table + 4 * stream.register());
            // An offset within the ring is exactly what ring-safety means;
            // the limit-checking reference throws on anything further.
            assertEquals(stream.frames(),
                    unpack(file, from, stream.packedSize(), ring).length,
                    "stream " + stream.register() + (stream.loop() ? " loop" : " intro")
                            + " needs more than a " + ring + "-byte ring");
        }
    }

    @Test
    void theLoopFrameComesFromTheYmHeaderUnlessOverridden() {
        // What the CLI reads: a YM6 file carries its own loop frame.
        assertEquals(397, song(397).loopFrame());
        assertNotEquals(397, Yx6Encoder.encode(song(397), 960, 24, 0, false).loopFrame());
    }
}
