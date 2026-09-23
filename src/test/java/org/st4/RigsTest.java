package org.st4;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The six rigs under {@code 68k/test/emu}, each decoder of 68k/ under
 * unicorn against the packer: every output byte, the four streams read to
 * their ends, the caller's registers and the ring's guard bands.
 *
 * <p>The rigs ran by hand alone, and a run by hand once passed on the
 * containers an old packer wrote, since the cache kept them by their input
 * and the format version. The cache now keeps them by the build of the
 * packer as well (test_st4.py), and this runs all six on every build, side
 * by side: a warm cache is about twenty seconds, and a cold one packs every
 * container again.
 *
 * <p>Skipped where python3 with unicorn, or rmac on the path, is absent,
 * which bin/suite requires.
 */
final class RigsTest {

    /** Each rig, and the line it closes on where every check of it passes. */
    private static final Map<String, String> RIGS = new LinkedHashMap<>();

    static {
        RIGS.put("test_st4.py", "ALL ST4 TESTS PASS");
        RIGS.put("test_st4_wrap.py", "ALL ST4 WRAP TESTS PASS");
        RIGS.put("test_st4_ring.py", "ALL ST4 RING TESTS PASS");
        RIGS.put("test_st4_repeat.py", "ALL ST4 REPEAT TESTS PASS");
        RIGS.put("test_st4_rewind.py", "ALL ST4 REWIND TESTS PASS");
        RIGS.put("test_st4_copies.py", "ALL ST4 COPY TESTS PASS");
    }

    private static final Path EMU = Path.of("68k", "test", "emu");

    private static boolean runs(String... argv) {
        try {
            return new ProcessBuilder(argv).redirectErrorStream(true)
                    .start().waitFor() == 0;
        } catch (IOException | InterruptedException no) {
            return false;
        }
    }

    private static boolean onThePath(String tool) {
        for (String at : System.getenv("PATH").split(":")) {
            if (Files.isExecutable(Path.of(at, tool))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyRigDecodesWhatThePackerWrites() throws Exception {
        Assumptions.assumeTrue(runs("python3", "-c", "import unicorn"),
                "no python3 with unicorn in it");
        Assumptions.assumeTrue(onThePath("rmac"), "no rmac on the path");
        Assumptions.assumeTrue(Files.isDirectory(Path.of("target/classes/org/st4")),
                "the packer is not built");
        // Each rig writes into a file, so a rig that reports at length
        // leaves the others running rather than filling a pipe read later.
        Path out = Files.createTempDirectory("st4-rigs");
        Map<String, Process> running = new LinkedHashMap<>();
        for (String rig : RIGS.keySet()) {
            running.put(rig, new ProcessBuilder("python3", EMU.resolve(rig).toString())
                    .redirectErrorStream(true)
                    .redirectOutput(out.resolve(rig + ".txt").toFile())
                    .start());
        }
        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Process> one : running.entrySet()) {
            int exit = one.getValue().waitFor();
            String said = Files.readString(out.resolve(one.getKey() + ".txt"),
                    StandardCharsets.UTF_8);
            if (exit != 0 || !said.contains(RIGS.get(one.getKey()))) {
                wrong.add(one.getKey() + " exits " + exit + ":\n" + said);
            }
        }
        assertTrue(wrong.isEmpty(), () -> String.join("\n", wrong));
    }
}
