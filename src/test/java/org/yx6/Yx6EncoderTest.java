package org.yx6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.st4.St4Decompressor;
import org.st4.St4Format;
import org.junit.jupiter.api.Test;

final class Yx6EncoderTest {

    private static final int FRAMES = 1500;

    private static Ym6Reader.Song song(boolean interleaved) {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, interleaved));
    }

    private static int word(byte[] file, int at) {
        return ((file[at] & 0xFF) << 8) | (file[at + 1] & 0xFF);
    }

    private static int longAt(byte[] file, int at) {
        return (word(file, at) << 16) | word(file, at + 2);
    }

    @Test
    void headerDescribesTheStreams() {
        Yx6Encoder.Result result = Yx6Encoder.encode(song(true), 960, 24, false);
        byte[] file = result.file();

        assertEquals(Yx6Format.MAGIC, longAt(file, Yx6Format.OFFSET_MAGIC));
        assertEquals(Yx6Format.VERSION, word(file, Yx6Format.OFFSET_VERSION));
        assertEquals(0, word(file, Yx6Format.OFFSET_FLAGS), "a play-once tune does not loop");
        assertEquals(FRAMES, longAt(file, Yx6Format.OFFSET_LOOP_FRAME),
                "a play-once tune loops at its end");
        assertEquals(FRAMES, longAt(file, Yx6Format.OFFSET_FRAMES));
        assertEquals(50, word(file, Yx6Format.OFFSET_PLAYER_HZ));
        assertEquals(Yx6Format.STREAMS, word(file, Yx6Format.OFFSET_STREAM_COUNT));
        assertEquals(960, word(file, Yx6Format.OFFSET_RING_SIZE));
        assertEquals(24, word(file, Yx6Format.OFFSET_CHUNK));

        // The table is in register order, long-aligned per section (each is a
        // complete ST4 container with alignment promises of its own), and
        // covers the whole file up to the final alignment pad.
        int expected = Yx6Format.HEADER_SIZE;
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            expected += (-expected) & 3;
            assertEquals(expected, longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register),
                    "offset of section " + register);
            expected += result.streams().get(register).packedSize();
        }
        assertTrue(file.length - expected < 4, "nothing after the last section but padding");
    }

    /** What stream {@code index} should decode to: a masked register, or one
     * of the four effect vectors. */
    static byte[] expectedVector(Ym6Reader.Song source, int index) {
        if (index < Yx6Format.REGISTER_STREAMS) {
            return Ym2149.mask(index, source.registers()[index]);
        }
        return YmEffects.extract(source).streams()[index - Yx6Format.REGISTER_STREAMS];
    }

    @Test
    void everyStreamUnpacksToItsVector() {
        Ym6Reader.Song source = song(true);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 960, 24, false);
        byte[] file = result.file();

        for (int stream = 0; stream < Yx6Format.STREAMS; stream++) {
            int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * stream);
            byte[] unpacked = unpack(file, from,
                    result.streams().get(stream).packedSize(), Integer.MAX_VALUE);
            assertArrayEquals(expectedVector(source, stream), unpacked,
                    "stream " + stream + " does not decode to its vector");
        }
    }

    @Test
    void interleavedAndPerFrameFilesPackIdentically() {
        assertArrayEquals(Yx6Encoder.encode(song(true), 960, 24, false).file(),
                Yx6Encoder.encode(song(false), 960, 24, false).file());
    }

    @Test
    void everyStreamSurvivesItsOwnRing() {
        // -mN is what makes a stream safe for an N-byte ring: decoding it
        // through exactly that ring must never need a byte that has left it.
        // A too-far offset does not fail loudly - it reads whatever the ring
        // has wrapped onto - so the output comparison is the check.
        Ym6Reader.Song source = song(true);
        for (int ring : new int[] {48, 240, 960}) {
            Yx6Encoder.Result result = Yx6Encoder.encode(source, ring, 24, false);
            byte[] file = result.file();
            for (int stream = 0; stream < Yx6Format.STREAMS; stream++) {
                int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * stream);
                assertArrayEquals(expectedVector(source, stream),
                        unpack(file, from, result.streams().get(stream).packedSize(), ring),
                        "stream " + stream + " needs more than a " + ring + "-byte ring");
            }
        }
    }

    @Test
    void everySectionIsAStandardUnitOneContainer() {
        // The player opens each section with ST4's own eight-instruction
        // sequence, so each must be a complete k=1 container whose recorded
        // size is the section's frame count exactly (k=1 pads nothing). The
        // player's C-sized-call shape itself is exercised by the emulation
        // rig, through the real 68000 decoder.
        Ym6Reader.Song source = song(true);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 240, 24, false);
        byte[] file = result.file();

        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register);
            assertEquals(0, from % 4, "section " + register + " starts long-aligned");
            St4Format.Container section = St4Format.read(Arrays.copyOfRange(
                    file, from, from + result.streams().get(register).packedSize()));
            assertEquals(1, section.unit(), "section " + register + " is not k=1");
            assertEquals(FRAMES, section.size(), "section " + register + " frame count");
        }
    }

    @Test
    void everyOperationFitsAWordCounter() {
        assertTrue(Yx6Encoder.encode(song(true), 960, 24, false).longestOp() <= 65535);
    }

    @Test
    void rejectsShapesThePlayerCannotRun() {
        Ym6Reader.Song source = song(true);
        // Fewer values per call than registers: the round-robin cannot fit.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 960, 13, false));
        // Ring smaller than two chunks: the group being written would land on
        // the group being read.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 24, 24, false));
        // ST1_wrap needs the chunk to divide the ring.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 1000, 24, false));
        // The burst reads register k's ring through an assembled-in k*N
        // displacement: 13*N must fit a signed word, so N stops at 2520.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 2544, 24, false));
    }

    @Test
    void padsOddShapesWithSafeDuplicateFrames() {
        Ym6Reader.Song source = song(true);         // an even-shaped tune:
        assertSame(source, Yx6.padToUnit(source, 200, 2));  // nothing to do

        // An odd loop split: one duplicated frame evens it, and the whole
        // tune grows by one more to keep the length even too.
        Ym6Reader.Song padded = Yx6.padToUnit(source, 201, 2);
        assertNotNull(padded);
        assertEquals(source.frames() + 2, padded.frames());
        assertEquals(202, padded.loopFrame());
        // The intro gained a duplicate near the split: frame content around
        // it is a copy, and everything before is untouched.
        for (int r = 0; r < 14; r++) {
            assertEquals(source.registers()[r][0], padded.registers()[r][0]);
        }
    }
    @Test
    void widerUnitsRoundTripAndAreRejectedWhenTheyCannot() {
        // FRAMES is even, so k=2 works end to end; each section must carry
        // k=2 in its signature, which is what the player checks its build
        // against. A loop frame that is not a whole number of units cannot be
        // packed at all - a padded section would decode one extra value into
        // the ring, and it would be played.
        Ym6Reader.Song source = song(true);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 960, 24, -1, false, 2);
        byte[] file = result.file();
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register);
            St4Format.Container section = St4Format.read(Arrays.copyOfRange(
                    file, from, from + result.streams().get(register).packedSize()));
            assertEquals(2, section.unit(), "section " + register);
            assertArrayEquals(expectedVector(source, register),
                    unpack(file, from, result.streams().get(register).packedSize(), 480),
                    "section " + register + " at unit 2");
        }
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 960, 24, 397, false, 2),
                "an odd loop frame cannot be a whole number of 2-byte units");
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 960, 30, -1, false, 4),
                "a chunk of 30 is not a whole number of 4-byte units");
    }

    @Test
    void drumsTravelWithEndMarkers() {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        Ym6Reader.Song source = Ym6Reader.read(
                Ym6TestData.file(registers, FRAMES, true, "YM6!", 50, 2, 0));
        byte[] file = Yx6Encoder.encode(source, 960, 24, false).file();

        assertEquals(2, word(file, Yx6Format.OFFSET_DRUM_COUNT));
        int table = longAt(file, Yx6Format.OFFSET_DRUM_TABLE);
        assertTrue(table > 0, "the drum table exists");
        for (int i = 0; i < 2; i++) {
            int at = longAt(file, table + Yx6Format.DRUM_ENTRY_SIZE * i);
            int length = word(file, table + Yx6Format.DRUM_ENTRY_SIZE * i + 4);
            assertEquals(3, length, "the test drums are three samples long");
            for (int j = 0; j < length; j++) {
                assertTrue((file[at + j] & 0xFF) <= 15,
                        "sample bytes are PSG-ready volumes");
            }
            assertEquals(Yx6Format.DRUM_END_MARK, file[at + length] & 0xFF,
                    "drum " + i + " ends with the marker the ISR stops on");
        }
    }

    @Test
    void aDrumlessFileHasNoDrumTable() {
        byte[] file = Yx6Encoder.encode(song(true), 960, 24, false).file();
        assertEquals(0, longAt(file, Yx6Format.OFFSET_DRUM_TABLE));
        assertEquals(0, word(file, Yx6Format.OFFSET_DRUM_COUNT));
    }

    /** Unpacks one embedded ST4 container, holding it to an offset limit. */
    private static byte[] unpack(byte[] file, int from, int packedSize, int offsetLimit) {
        St4Format.Container section = St4Format.read(
                Arrays.copyOfRange(file, from, from + packedSize));
        return St4Decompressor.decompress(section.control(), section.literal(),
                section.byteOffsets(), section.wordOffsets(), section.unit(),
                section.size(), offsetLimit);
    }
}
