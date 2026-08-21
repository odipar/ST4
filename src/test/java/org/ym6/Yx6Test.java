package org.ym6;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import org.yx6.Tune;
import org.yx6.Yx6Format;

/**
 * The .ym packer's own decisions, as far as they can be made without a
 * command line: which frame a YM dump may have duplicated when its shape has
 * to be padded to a unit boundary.
 *
 * <p>The stretching itself is {@link Tune#padToUnit}'s and is tested through
 * the engine. What is tested here is the half only this front end can supply -
 * the predicate that reads a RAW YM dump and says a frame is safe to hold a
 * tick longer - and that the two fit together.
 */
final class Yx6Test {

    private static final int FRAMES = 1500;

    private static Ym6Reader.Song song() {
        byte[][] registers = Ym6TestData.registers(FRAMES);
        return Ym6Reader.read(Ym6TestData.file(registers, FRAMES, true));
    }

    @Test
    void padsOddShapesWithSafeDuplicateFrames() {
        Ym6Reader.Song dump = song();               // an even-shaped tune:
        Tune source = YmEffects.tune(dump);
        assertSame(source, Yx6.padToUnit(dump, source, 200, 2));  // nothing to do

        // An odd loop split: one duplicated frame evens it, and the whole
        // tune grows by one more to keep the length even too.
        Tune padded = Yx6.padToUnit(dump, source, 201, 2);
        assertNotNull(padded);
        assertEquals(source.frames() + 2, padded.frames());
        assertEquals(202, padded.loopFrame());
        // Every stream grew by the same two frames, because a frame is a
        // column across all of them and the effects would otherwise play
        // against the wrong registers from the duplicate on.
        for (byte[] codes : padded.codes()) {
            assertEquals(padded.frames(), codes.length);
        }
        // The intro gained a duplicate near the split: frame content around
        // it is a copy, and everything before is untouched.
        for (int r = 0; r < Yx6Format.REGISTER_STREAMS; r++) {
            assertEquals(source.registers()[r][0], padded.registers()[r][0]);
        }
    }
}
