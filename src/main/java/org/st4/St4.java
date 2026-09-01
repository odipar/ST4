package org.st4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Command-line ST4 packer: ZX1's blocks at a chosen unit granularity, with the
 * literal payload in a stream of its own.
 *
 * <p>{@code -k1} is ZX1's granularity and should pack to within a byte or two
 * of jx1; {@code -k2} and {@code -k4} trade ratio for a decoder that runs half
 * or a quarter as many operations and copies two or four bytes at a time.
 */
public final class St4 {

    private St4() {}

    public static void main(String[] args) {
        System.out.println("ST4: aligned split-stream packer v5.0 by Robbert van Dalen, "
                + "based on ZX1 v1.5 by Einar Saukas");

        int unit = 1;
        int offsetLimit = St4Format.MAX_OFFSET;
        int maxOpLength = St4Format.MAX_OP;
        int repeatIndex = -1;
        boolean forcedMode = false;
        int i = 0;
        for (; i < args.length && args[i].startsWith("-"); i++) {
            switch (args[i]) {
                case "-f" -> forcedMode = true;
                default -> {
                    if (args[i].startsWith("-k")) {
                        unit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-m")) {
                        offsetLimit = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-l")) {
                        maxOpLength = parseNumber(args[i].substring(2));
                    } else if (args[i].startsWith("-r")) {
                        repeatIndex = parseIndex(args[i].substring(2));
                    } else {
                        throw error("Invalid parameter " + args[i]);
                    }
                }
            }
        }

        String outputName;
        if (args.length == i + 1) {
            outputName = args[i] + ".st4";
        } else if (args.length == i + 2) {
            outputName = args[i + 1];
        } else {
            usage("""
                    Usage: st4 [-f] [-kK] [-mN] [-lN] [-rR] input [output.st4]
                      -f      Force overwrite of output file
                      -kK     Unit size: 1, 2 or 4 bytes (default 1). Lengths and
                              offsets count units, so the output is padded to a
                              whole number of them
                      -mN     Limit back-references to N units
                      -lN     Split matches so no operation exceeds N units
                      -rR     Loop: after the last unit, the output continues
                              from unit R, forever""");
            return;
        }

        String problem = St4Format.checkUnit(unit);
        if (!problem.isEmpty()) {
            throw error(problem);
        }
        // A word offset is stored pre-scaled, so the window is a byte figure:
        // reaching 32512 units at k=4 would not fit the word it is kept in.
        if (offsetLimit > St4Format.maxOffsetUnits(unit)) {
            offsetLimit = St4Format.maxOffsetUnits(unit);
        }

        byte[] input;
        try {
            input = Files.readAllBytes(Path.of(args[i]));
        } catch (IOException e) {
            throw error("Cannot access input file " + args[i]);
        }
        if (input.length == 0) {
            throw error("Empty input file " + args[i]);
        }

        Path outputPath = Path.of(outputName);
        if (!forcedMode && Files.exists(outputPath)) {
            throw error("Already existing output file " + outputName);
        }

        int[] units = Units.split(input, unit);
        if (repeatIndex >= units.length) {
            throw error("-r" + repeatIndex + " is not a unit of the input, which is "
                    + units.length + " units");
        }
        St4Compressor.Result result;
        if (repeatIndex >= 0 && units.length - repeatIndex > offsetLimit) {
            // The loop is longer than the window, so no match can reach across
            // it and the decoder cannot loop it alone: the caller will replay
            // the stream from the state it saved at the loop point. For every
            // pass to see the same history, the loop is parsed on its own.
            int[] intro = Arrays.copyOfRange(units, 0, repeatIndex);
            int[] loop = Arrays.copyOfRange(units, repeatIndex, units.length);
            result = St4Compressor.compressRewinding(
                    intro.length == 0 ? null : St4EventOptimizer.optimize(intro, unit, offsetLimit),
                    St4EventOptimizer.optimize(loop, unit, offsetLimit),
                    units, unit, maxOpLength, repeatIndex);
        } else {
            // The loop fits the window, so the stream loops by itself: its end
            // becomes an endless match back to the loop point.
            result = St4Compressor.compress(
                    St4EventOptimizer.optimize(units, unit, offsetLimit), units, unit,
                    maxOpLength, repeatIndex);
        }

        try {
            Files.write(outputPath, container(result));
        } catch (IOException e) {
            throw error("Cannot write output file " + outputName);
        }

        int padded = Units.paddedLength(input.length, unit);
        System.out.printf("Packed %d bytes%s into %d (%.1f%%): A %d, B %d, C %d, D %d, "
                + "%d operations%s%n",
                input.length, padded == input.length ? "" : " padded to " + padded,
                result.packedSize(), 100.0 * result.packedSize() / input.length,
                result.control().length, result.literal().length,
                result.byteOffsets().length, result.wordOffsets().length,
                result.operations(),
                repeatIndex < 0 ? "" : ", loops from unit " + repeatIndex
                        + (result.rewindIndex() < 0 ? "" : " by rewind"));
        if (result.rewindIndex() >= 0) {
            System.out.printf("The loop is longer than the -m%d window, so the decoder cannot "
                    + "loop it alone: save its state at unit %d and restore it at unit %d, "
                    + "every pass%n", offsetLimit, repeatIndex, units.length);
        }
        if (result.longestOp() > maxOpLength) {
            System.out.printf("Warning: longest operation is %d units, over the -l%d limit: "
                    + "a literal run, which the format cannot split%n",
                    result.longestOp(), maxOpLength);
        }
    }

    /**
     * Twenty-four bytes of header, then A, C, D and B in order, each starting
     * on a long boundary. Nothing says how long a stream is: it runs to the
     * next - and B, the literal payload, runs to the end of the file, so it
     * borders whatever the caller loads after the container.
     */
    // Public because a container is also how other formats embed an ST4
    // stream, many of them at once.
    public static byte[] container(St4Compressor.Result result) {
        int controlAt = St4Format.HEADER_SIZE;                  // already a multiple of 4
        int byteAt = align(controlAt + result.control().length);
        int wordAt = align(byteAt + result.byteOffsets().length);
        int literalAt = align(wordAt + result.wordOffsets().length);
        byte[] file = new byte[literalAt + result.literal().length];

        putLong(file, St4Format.OFFSET_SIGNATURE, St4Format.signature(result.unit()));
        putLong(file, St4Format.OFFSET_SIZE, result.paddedSize());
        putLong(file, St4Format.OFFSET_LITERAL, literalAt);
        putLong(file, St4Format.OFFSET_BYTE_OFFSETS, byteAt);
        putLong(file, St4Format.OFFSET_WORD_OFFSETS, wordAt);
        putLong(file, St4Format.OFFSET_REWIND, result.rewindIndex() < 0
                ? St4Format.NO_REWIND : result.rewindIndex() * result.unit());
        System.arraycopy(result.control(), 0, file, controlAt, result.control().length);
        System.arraycopy(result.literal(), 0, file, literalAt, result.literal().length);
        System.arraycopy(result.byteOffsets(), 0, file, byteAt,
                result.byteOffsets().length);
        System.arraycopy(result.wordOffsets(), 0, file, wordAt,
                result.wordOffsets().length);
        return file;
    }

    private static int align(int at) {
        return at + ((-at) & 3);
    }

    private static void putWord(byte[] file, int at, int value) {
        file[at] = (byte) (value >>> 8);
        file[at + 1] = (byte) value;
    }

    private static void putLong(byte[] file, int at, int value) {
        putWord(file, at, value >>> 16);
        putWord(file, at + 2, value);
    }

    private static RuntimeException error(String message) {
        System.err.println("Error: " + message);
        System.exit(1);
        throw new AssertionError("unreachable");
    }

    private static void usage(String text) {
        System.err.println(text);
        System.exit(1);
    }

    private static int parseNumber(String argument) {
        try {
            int value = Integer.parseInt(argument);
            if (value <= 0) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }

    /** As {@link #parseNumber}, but an index may be zero: -r0 loops it all. */
    private static int parseIndex(String argument) {
        try {
            int value = Integer.parseInt(argument);
            if (value < 0) {
                throw error("Invalid parameter value " + argument);
            }
            return value;
        } catch (NumberFormatException e) {
            throw error("Invalid parameter value " + argument);
        }
    }
}
