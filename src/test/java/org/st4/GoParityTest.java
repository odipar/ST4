package org.st4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The Go tree against this one, byte for byte.
 *
 * <p>One input and one set of flags give one output, whichever tree writes
 * it: the parse, the costs and the ties are the same, so a caller who runs
 * either has the same file. The Go tools read standard input and write
 * standard output, and so do these, so the two are run the same way.
 *
 * <p>Skipped where {@code go} is off the path.
 */
final class GoParityTest {

    private static Path built;

    /** The inputs: prose, 68000 assembly and Java, so the parse meets runs,
     *  short matches and long ones. ConsistencyTest reads how many there are
     *  against what tools.md reports. */
    static final List<String> INPUTS =
            List.of("README.md", "doc/SPEC.md", "68k/ST4.S",
                    "src/main/java/org/st4/St4Compressor.java");

    /** Flags that reach every branch of the parse: the three units, a small
     *  window, copies, a penalty and an operation limit. */
    static final List<List<String>> FLAGS = List.of(
            List.of("-k1"), List.of("-k2"), List.of("-k4"),
            List.of("-k1", "-m256"), List.of("-k2", "-m960"),
            List.of("-k2", "-c"), List.of("-k1", "-p8"), List.of("-k2", "-p16"),
            List.of("-k2", "-l255"), List.of("-k4", "-m512"));

    @BeforeAll
    static void buildTheGoTools() throws IOException, InterruptedException {
        Assumptions.assumeTrue(onThePath("go"), "no go on the path");
        Path into = Files.createTempDirectory("st4-go");
        for (String tool : List.of("st4", "dst4")) {
            Process build = new ProcessBuilder("go", "build", "-o",
                    into.resolve(tool).toString(), "./cmd/" + tool)
                    .directory(Path.of("go").toFile())
                    .redirectErrorStream(true)
                    .start();
            byte[] said = build.getInputStream().readAllBytes();
            assertEquals(0, build.waitFor(), "go build " + tool + ": " + new String(said));
        }
        built = into;
    }

    private static boolean onThePath(String tool) {
        for (String at : System.getenv("PATH").split(":")) {
            if (Files.isExecutable(Path.of(at, tool))) {
                return true;
            }
        }
        return false;
    }

    /** The Go tool's output for that input and those flags. */
    private static byte[] go(String tool, byte[] input, List<String> flags)
            throws IOException, InterruptedException {
        List<String> argv = new java.util.ArrayList<>();
        argv.add(built.resolve(tool).toString());
        argv.add("-silent");
        argv.addAll(flags);
        Process ran = new ProcessBuilder(argv).start();
        ran.getOutputStream().write(input);
        ran.getOutputStream().close();
        byte[] out = ran.getInputStream().readAllBytes();
        assertEquals(0, ran.waitFor(), tool + " exits 0");
        return out;
    }

    /** This tree's output for the same, the tool run as a caller runs it. */
    private static byte[] java(Runnable tool, byte[] input) {
        InputStream stdin = System.in;
        PrintStream stdout = System.out;
        var caught = new ByteArrayOutputStream();
        try {
            System.setIn(new java.io.ByteArrayInputStream(input));
            System.setOut(new PrintStream(caught, true));
            tool.run();
        } finally {
            System.setIn(stdin);
            System.setOut(stdout);
        }
        return caught.toByteArray();
    }

    @Test
    void everyInputPacksTheSameInBothTrees() throws Exception {
        for (String named : INPUTS) {
            byte[] input = Files.readAllBytes(Path.of(named));
            for (List<String> flags : FLAGS) {
                String[] argv = new String[flags.size() + 1];
                argv[0] = "-silent";
                for (int i = 0; i < flags.size(); i++) {
                    argv[i + 1] = flags.get(i);
                }
                byte[] mine = java(() -> St4.main(argv), input);
                assertTrue(mine.length > 0, named + " " + flags + " packs to something");
                assertArrayEquals(mine, go("st4", input, flags),
                        named + " " + flags + ": the two trees pack the same");
            }
        }
    }

    @Test
    void everyContainerUnpacksTheSameInBothTrees() throws Exception {
        for (String named : INPUTS) {
            byte[] input = Files.readAllBytes(Path.of(named));
            for (List<String> flags : List.of(List.of("-k1"), List.of("-k2"),
                    List.of("-k4"), List.of("-k2", "-c"))) {
                byte[] packed = go("st4", input, flags);
                byte[] mine = java(() -> Dst4.main(new String[] {"-silent"}), packed);
                assertArrayEquals(mine, go("dst4", packed, List.of()),
                        named + " " + flags + ": the two trees unpack the same");
                assertArrayEquals(input,
                        java.util.Arrays.copyOf(mine, input.length),
                        named + " " + flags + ": the input is what comes back");
            }
        }
    }
}
