package org.st4;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The conformance kit under doc/conformance, checked against the packer.
 *
 * <p>Every container in the kit is packed here, from the input and options
 * SOURCES.md defines, and compared byte for byte with the file in the tree;
 * beside each container stand the bytes a decoder writes for it, which the
 * decompressor writes back. A container the tree does not have yet is
 * written, and SOURCES.generated.md beside the kit lists what SOURCES.md
 * then has to say.
 */
class ConformanceTest {

    private static final Path KIT_AT = Path.of("doc/conformance");

    /**
     * One container of the kit: its name, the input packed, the unit, the
     * window in units, the operation limit, the loop point in units or null,
     * whether the parse spends copies from the literal stream, how many
     * passes the kit records, and what the container reaches.
     */
    private record Source(String name, String from, byte[] input, int unit, int window,
            int maxOp, @Nullable Integer loop, boolean copies, int passes,
            String reaches) {}

    /** {@code count} bytes, byte i being {@code (i * step) % mod}. */
    private static byte[] numbers(int count, int step, int mod) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = (byte) (i * step % mod);
        }
        return out;
    }

    /** {@code pattern} laid down until {@code count} bytes stand. */
    private static byte[] tiled(byte[] pattern, int count) {
        byte[] out = new byte[count];
        for (int i = 0; i < count; i++) {
            out[i] = pattern[i % pattern.length];
        }
        return out;
    }

    /** {@code head}, then {@code tail}. */
    private static byte[] then(byte[] head, byte[] tail) {
        byte[] out = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }

    /** {@code count} bytes of {@code value}. */
    private static byte[] run(int count, int value) {
        byte[] out = new byte[count];
        Arrays.fill(out, (byte) value);
        return out;
    }

    private static final List<Source> KIT = List.of(
            new Source("k1-literals", "numbers(64, 37, 251)", numbers(64, 37, 251), 1, 4096, St4Format.MAX_OP,
                    null, false, 1,
                    "one literals block and the end code: no byte repeats within reach"),
            new Source("k1-last-offset", "run(1, $41) then run(40, $41)", then(run(1, 0x41), run(40, 0x41)), 1, 4096,
                    St4Format.MAX_OP, null, false, 1,
                    "a block at the last offset before a block sets one, which is 1 unit"
                            + " (SPEC.md 3.4)"),
            new Source("k1-bank0", "tiled(numbers(16, 37, 251), 192)", tiled(numbers(16, 37, 251), 192), 1, 4096,
                    St4Format.MAX_OP, null, false, 1,
                    "byte offsets in bank 0, 1 to 256 units back (SPEC.md 4.1)"),
            new Source("k1-bank1", "numbers(300, 37, 251) twice", then(numbers(300, 37, 251), numbers(300, 37, 251)),
                    1, 512, St4Format.MAX_OP, null, false, 1,
                    "byte offsets in bank 1, 257 to 512 units back (SPEC.md 4.1)"),
            new Source("k1-word-offset", "numbers(700, 37, 251) twice", then(numbers(700, 37, 251), numbers(700, 37, 251)),
                    1, 4096, St4Format.MAX_OP, null, false, 1,
                    "word offsets, class 0 0, past the 512 units a byte offset reaches"
                            + " (SPEC.md 4.2)"),
            new Source("k1-long-literals", "numbers(600, 97, 251)", numbers(600, 97, 251), 1, 4096, St4Format.MAX_OP,
                    null, false, 1,
                    "a literals block of more than 256 units, so its gamma runs long"),
            new Source("k1-split-op", "run(1200, $5A)", run(1200, 0x5A), 1, 4096, 64, null, false, 1,
                    "an operation split at 64 units, the limit the packer packs to"),
            new Source("k2-matches", "tiled(numbers(16, 37, 251), 256)", tiled(numbers(16, 37, 251), 256), 2, 4096,
                    St4Format.MAX_OP, null, false, 1, "a unit of 2 bytes"),
            new Source("k4-matches", "tiled(numbers(16, 37, 251), 256)", tiled(numbers(16, 37, 251), 256), 4, 4096,
                    St4Format.MAX_OP, null, false, 1, "a unit of 4 bytes"),
            new Source("k2-padded", "tiled(numbers(16, 37, 251), 255)", tiled(numbers(16, 37, 251), 255), 2, 4096,
                    St4Format.MAX_OP, null, false, 1,
                    "an input of an odd length at a unit of 2, padded to a whole unit"
                            + " (SPEC.md 1.2)"),
            new Source("k1-copies", "numbers(64, 37, 251) twice", then(numbers(64, 37, 251), numbers(64, 37, 251)),
                    1, 8, St4Format.MAX_OP, null, true, 1,
                    "copies from the literal stream, offsets above the window of 8"
                            + " (SPEC.md 5)"),
            new Source("k1-one-unit", "run(1, $37)", run(1, 0x37), 1, 4096, St4Format.MAX_OP, null, false, 1,
                    "one unit of output"),
            new Source("k1-loop-in-window", "tiled(numbers(24, 37, 251), 256)", tiled(numbers(24, 37, 251), 256), 1, 4096,
                    St4Format.MAX_OP, 32, false, 3,
                    "a loop within the window: the repeat bit and the word in stream D"
                            + " (SPEC.md 6.2), played three passes"),
            new Source("k1-loop-from-zero", "tiled(numbers(24, 37, 251), 192)", tiled(numbers(24, 37, 251), 192), 1, 4096,
                    St4Format.MAX_OP, 0, false, 3,
                    "a loop from unit 0, played three passes"),
            new Source("k1-loop-long", "numbers(64, 37, 251) then tiled(numbers(20, 97, 251), 512)",
                    then(numbers(64, 37, 251), tiled(numbers(20, 97, 251), 512)),
                    1, 64, St4Format.MAX_OP, 64, false, 3,
                    "a loop longer than the window, which the caller replays: the rewind"
                            + " point in the header (SPEC.md 6.3), played three passes"));

    /** The container of one source, as the packer writes it. */
    private static byte[] packed(Source source) {
        int[] units = Units.split(source.input(), source.unit());
        St4Compressor.Result result;
        int loop = source.loop() == null ? -1 : source.loop();
        if (loop >= 0 && units.length - loop > source.window()) {
            int[] intro = Arrays.copyOfRange(units, 0, loop);
            int[] tail = Arrays.copyOfRange(units, loop, units.length);
            result = St4Compressor.compressRewinding(
                    intro.length == 0 ? null
                            : St4Optimizer.optimize(intro, source.unit(), source.window(), false),
                    St4Optimizer.optimize(tail, source.unit(), source.window(), false),
                    units, source.unit(), source.maxOp(), loop, source.window());
        } else {
            St4Block parse = source.copies()
                    ? St4LiteralCopyOracle.optimize(units, source.unit(), source.window())
                    : St4Optimizer.optimize(units, source.unit(), source.window(), false);
            result = St4Compressor.compress(parse, units, source.unit(), source.maxOp(),
                    loop, source.window());
        }
        return St4.container(result);
    }

    /** The bytes a decoder writes for a container, over that many passes. */
    private static byte[] played(byte[] file, int passes) {
        St4Format.Container container = St4Format.read(file);
        St4Decompressor.Decoded pass = St4Decompressor.decode(container.control(),
                container.literal(), container.byteOffsets(), container.wordOffsets(),
                container.unit(), container.size(), container.window(), container.rewind());
        return Dst4.played(container, pass, passes);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** The file at that path, or null where the tree does not have it. */
    private static byte @Nullable [] fileAt(Path at) throws IOException {
        return Files.exists(at) ? Files.readAllBytes(at) : null;
    }

    @Test
    void thePackerWritesTheKit() throws IOException {
        List<String> rows = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Source source : KIT) {
            byte[] file = packed(source);
            byte[] output = played(file, 1);
            assertArrayEquals(Arrays.copyOf(source.input(),
                            Units.paddedLength(source.input().length, source.unit())),
                    output, source.name() + " unpacks to the input it was packed from");
            Path containerAt = KIT_AT.resolve("containers").resolve(source.name() + ".st4");
            Path outputAt = KIT_AT.resolve("outputs").resolve(source.name() + ".out");
            byte[] played = played(file, source.passes());
            for (Path at : List.of(containerAt.getParent(), outputAt.getParent())) {
                Files.createDirectories(at);
            }
            byte[] onDisk = fileAt(containerAt);
            if (onDisk == null) {
                Files.write(containerAt, file);
                Files.write(outputAt, played);
                missing.add(source.name());
            } else {
                assertArrayEquals(file, onDisk, containerAt + " is the container the"
                        + " packer writes from the input and options of SOURCES.md");
                assertArrayEquals(played, Files.readAllBytes(outputAt), outputAt
                        + " is what a decoder writes for that container");
            }
            rows.add("| `%s` | %s | %d | %d | %s | %s | %d | %d | %s | %s |".formatted(
                    source.name(), source.from(), source.unit(), source.window(),
                    source.maxOp() == St4Format.MAX_OP ? "" : String.valueOf(source.maxOp()),
                    source.loop() == null ? "" : String.valueOf(source.loop()),
                    source.passes(), file.length, sha256(file), source.reaches()));
        }
        String said = """
                | container | input | `-k` | `-m` | `-l` | `-r` | passes | bytes | sha256 | what it reaches |
                |---|---|---|---|---|---|---|---|---|---|
                """ + String.join("\n", rows) + "\n";
        String sources = Files.readString(KIT_AT.resolve("SOURCES.md"), StandardCharsets.UTF_8);
        if (!sources.contains(said)) {
            Files.writeString(KIT_AT.resolve("SOURCES.generated.md"), said,
                    StandardCharsets.UTF_8);
        }
        assertTrue(missing.isEmpty(), "the kit did not have " + missing
                + ", and this run wrote them");
        assertTrue(sources.contains(said), "SOURCES.md has another table than this run"
                + " writes: SOURCES.generated.md beside the kit has the rows");
    }
}
