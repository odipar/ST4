package org.yx6;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        Yx6Encoder.Result result = Yx6Encoder.encode(song(true), 1024, 16, false);
        byte[] file = result.file();

        assertEquals(Yx6Format.MAGIC, longAt(file, Yx6Format.OFFSET_MAGIC));
        assertEquals(Yx6Format.VERSION, word(file, Yx6Format.OFFSET_VERSION));
        assertEquals(0, word(file, Yx6Format.OFFSET_FLAGS), "a play-once tune does not loop");
        assertEquals(FRAMES, longAt(file, Yx6Format.OFFSET_LOOP_FRAME),
                "a play-once tune loops at its end");
        assertEquals(FRAMES, longAt(file, Yx6Format.OFFSET_FRAMES));
        assertEquals(50, word(file, Yx6Format.OFFSET_PLAYER_HZ));
        assertEquals(Yx6Format.STREAMS, word(file, Yx6Format.OFFSET_STREAM_COUNT));
        assertEquals(1024, word(file, Yx6Format.OFFSET_RING_SIZE));
        assertEquals(16, word(file, Yx6Format.OFFSET_CHUNK));

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

    @Test
    void everyStreamUnpacksToTheMaskedRegister() {
        Ym6Reader.Song source = song(true);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 1024, 16, false);
        byte[] file = result.file();

        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register);
            byte[] unpacked = unpack(file, from,
                    result.streams().get(register).packedSize(), Integer.MAX_VALUE);
            assertArrayEquals(Ym2149.mask(register, source.registers()[register]), unpacked,
                    "stream " + register + " does not decode to the masked register");
        }
    }

    @Test
    void interleavedAndPerFrameFilesPackIdentically() {
        assertArrayEquals(Yx6Encoder.encode(song(true), 1024, 16, false).file(),
                Yx6Encoder.encode(song(false), 1024, 16, false).file());
    }

    @Test
    void everyStreamSurvivesItsOwnRing() {
        // -mN is what makes a stream safe for an N-byte ring: decoding it
        // through exactly that ring must never need a byte that has left it.
        // A too-far offset does not fail loudly - it reads whatever the ring
        // has wrapped onto - so the output comparison is the check.
        Ym6Reader.Song source = song(true);
        for (int ring : new int[] {32, 256, 1024}) {
            Yx6Encoder.Result result = Yx6Encoder.encode(source, ring, 16, false);
            byte[] file = result.file();
            for (int register = 0; register < Yx6Format.STREAMS; register++) {
                int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register);
                assertArrayEquals(Ym2149.mask(register, source.registers()[register]),
                        unpack(file, from, result.streams().get(register).packedSize(), ring),
                        "stream " + register + " needs more than a " + ring + "-byte ring");
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
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 256, 16, false);
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
        assertTrue(Yx6Encoder.encode(song(true), 1024, 16, false).longestOp() <= 65535);
    }

    @Test
    void rejectsShapesThePlayerCannotRun() {
        Ym6Reader.Song source = song(true);
        // Fewer values per call than registers: the round-robin cannot fit.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 1024, 13, false));
        // Ring smaller than two chunks: the group being written would land on
        // the group being read.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 16, 16, false));
        // ST1_wrap needs the chunk to divide the ring.
        assertThrows(IllegalArgumentException.class, () -> Yx6Encoder.encode(source, 1000, 16, false));
    }
    @Test
    void widerUnitsRoundTripAndAreRejectedWhenTheyCannot() {
        // FRAMES is even, so k=2 works end to end; each section must carry
        // k=2 in its signature, which is what the player checks its build
        // against. A loop frame that is not a whole number of units cannot be
        // packed at all - a padded section would decode one extra value into
        // the ring, and it would be played.
        Ym6Reader.Song source = song(true);
        Yx6Encoder.Result result = Yx6Encoder.encode(source, 1024, 16, -1, false, 2);
        byte[] file = result.file();
        for (int register = 0; register < Yx6Format.STREAMS; register++) {
            int from = longAt(file, Yx6Format.OFFSET_INTRO_TABLE + 4 * register);
            St4Format.Container section = St4Format.read(Arrays.copyOfRange(
                    file, from, from + result.streams().get(register).packedSize()));
            assertEquals(2, section.unit(), "section " + register);
            assertArrayEquals(Ym2149.mask(register, source.registers()[register]),
                    unpack(file, from, result.streams().get(register).packedSize(), 512),
                    "section " + register + " at unit 2");
        }
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 1024, 16, 397, false, 2),
                "an odd loop frame cannot be a whole number of 2-byte units");
        assertThrows(IllegalArgumentException.class,
                () -> Yx6Encoder.encode(source, 1024, 30, -1, false, 4),
                "a chunk of 30 is not a whole number of 4-byte units");
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
