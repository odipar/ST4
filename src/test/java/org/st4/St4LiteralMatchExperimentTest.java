package org.st4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

final class St4LiteralMatchExperimentTest {

    /**
     * A block, a long run, the block again: at a 16-unit ring the second
     * block is unreachable, and a copy from the literal stream reaches it.
     */
    @Test
    void aCopyFromTheLiteralStreamReachesWhatTheRingForgot() {
        byte[] block = new byte[200];
        new Random(1).nextBytes(block);
        byte[] input = new byte[2900];
        System.arraycopy(block, 0, input, 0, 200);
        java.util.Arrays.fill(input, 200, 2700, (byte) 'x');
        System.arraycopy(block, 0, input, 2700, 200);
        int[] units = Units.split(input, 1);
        int reach = St4Format.maxOffsetUnits(1);

        St4Block full = St4Optimizer.optimize(units, 1, reach, false);
        St4Block ring = St4Optimizer.optimize(units, 1, 16, false);
        St4LiteralMatchExperiment.Outcome a2 = St4LiteralMatchExperiment.run(
                units, 1, 16, reach, St4LiteralMatchExperiment.Scheme.A2, full);

        assertTrue(a2.converged(), "the parse points only at literals");
        assertEquals(1, a2.references(), "one copy of the block from the literal stream");
        assertTrue(a2.bits() < ring.bits(), "cheaper than the ring alone");
        // The copy's offset counts literals, so it is a byte where the full
        // parse needs a word: a hair cheaper than the full window itself.
        assertTrue(a2.bits() <= full.bits(), "no worse than the full window");
    }
}
