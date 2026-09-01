package org.st4;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The packer half of doc/research.md, as an experiment: what a parse gains
 * when a match may copy from the literal stream, so the ring need not hold
 * what the match wants.
 *
 * <p>A literal-stream match is an ordinary far match whose source is literal
 * in the same parse: the units it copies sit in stream D, in order, whatever
 * the ring has forgotten. That is the circularity - the parse decides which
 * units are literal, and a match into D depends on that - and this class
 * breaks it by choosing the dictionary first. The literals of a full-window
 * parse are every first occurrence the data has, and few; they are forced to
 * stay literal, a literal-stream match may copy only from them, and the parse
 * is consistent by construction. Holes of a few units between dictionary
 * runs - an accidental one-unit rep, say - are filled, since a copy cannot
 * step over what is not in D. A second pass keeps only the dictionary units
 * the first one actually copied from, and frees the rest to be matched.
 *
 * <p>Two ways of addressing D are costed side by side:
 *
 * <ul>
 *   <li>{@link Scheme#A2} - a class code of its own, five control bits, and
 *       an offset relative to the literal read pointer. Ring matches are what
 *       the ring decoders do today: any source within N units, wrapped.</li>
 *   <li>{@link Scheme#END} - no class code at all. The ring lies directly
 *       behind D, so an ordinary offset that reaches below the ring start
 *       reads D, anchored at its end; the write pointer wraps by lap and
 *       sources never do. Ring matches may only reach into the current lap,
 *       no match may cross a lap, and D's length has to be known before any
 *       offset into it can be written - it is taken from the previous pass,
 *       and the difference is padding.</li>
 * </ul>
 *
 * <p>Neither the compressor nor the decoders know these blocks; the numbers
 * are the parses' bit costs, which is what a packed size is up to padding.
 * Only backward copies are tried, with no rep form; the note argues a forward
 * copy saves what a backward one does, and the rep is a refinement.
 *
 * <pre>
 *   java -cp target/classes org.st4.St4LiteralMatchExperiment [-kK] [-rN,N,..] file...
 * </pre>
 */
public final class St4LiteralMatchExperiment {

    /** How a literal-stream match is addressed, and what it costs. */
    enum Scheme { A2, END }

    /** Control bits of a literal-stream match under {@link Scheme#A2}. */
    private static final int CONTROL_A2 = 5;

    /** Control bits of an ordinary new-offset match: the flag and two class bits. */
    private static final int CONTROL_MATCH = 3;

    /** The furthest an A2 byte offset reaches, in units, one bank. */
    private static final int A2_BYTE_REACH = 256;

    private static final int PASSES = 8;

    /** What one scheme came to on one input at one ring size. */
    record Outcome(int bits, int passes, int references, int dictionary, int padding,
                   boolean converged) {}

    private St4LiteralMatchExperiment() {}

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    private static St4Block better(@Nullable St4Block current, St4Block candidate) {
        return current == null || current.bits() > candidate.bits() ? candidate : current;
    }

    /**
     * The reference DP of {@link St4Optimizer}, with the far candidates added:
     * a match whose source is not in the ring is allowed only where the
     * source is an {@code allowed} literal, and costed by the scheme. A
     * literal-stream match is stored with a negative offset.
     *
     * @param forced          the dictionary: units kept literal, the only ones a
     *                        literal-stream match may copy from
     * @param literalsBefore  literal units before each position, in the previous parse
     * @param assumedLiterals the previous parse's literal count, D's length for END
     */
    private static St4Block parse(int[] units, int unit, int ring, int reach, Scheme scheme,
                                  boolean[] forced, int[] literalsBefore,
                                  int assumedLiterals) {
        int count = units.length;
        int literalBits = 8 * unit;
        int width = Math.max(1, count - 1);
        var lastLiteral = new @Nullable St4Block[width + 1];
        var lastMatch = new @Nullable St4Block[width + 1];
        var optimal = new @Nullable St4Block[count];
        int[] matchLength = new int[width + 1];
        int[] bestLength = new int[Math.max(count, 3)];
        bestLength[2] = 2;
        lastMatch[St4Optimizer.INITIAL_OFFSET] =
                new St4Block(-1, -1, St4Optimizer.INITIAL_OFFSET, null);

        for (int index = 0; index < count; index++) {
            int maxOffset = Math.max(1, index);
            int phase = index % ring;                   // units of this lap before index
            boolean lapStart = scheme == Scheme.END && phase == 0;
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++) {
                if (lapStart) {
                    matchLength[offset] = 0;            // END: no match crosses a lap
                }
                // Where the source lies: in the ring's history, or in D.
                boolean inRing = scheme == Scheme.A2 ? offset <= ring : offset <= phase;
                // A dictionary unit stays literal, and only a dictionary unit
                // can be copied from D.
                boolean matches = index != 0 && index >= offset
                        && units[index] == units[index - offset] && !forced[index]
                        && (inRing || forced[index - offset]);
                // The reference DP skips the literal candidate at an offset
                // that matches, since the rep stands in for it. Here a match
                // can be refused - END at a lap start, a copy from D of one
                // unit - so an offset that produced nothing falls through.
                boolean produced = false;
                if (matches) {
                    if (inRing) {
                        // Match reusing the last offset, after a literal run.
                        St4Block literal = lastLiteral[offset];
                        if (literal != null) {
                            int length = index - literal.index();
                            boolean fits = scheme == Scheme.A2
                                    || (length <= phase + 1 && offset <= phase - length + 1);
                            if (fits) {
                                int bits = literal.bits() + 1 + eliasGammaBits(length);
                                St4Block match = new St4Block(bits, index, offset, literal);
                                lastMatch[offset] = match;
                                optimal[index] = better(optimal[index], match);
                                produced = true;
                            }
                        }
                    }
                    if (++matchLength[offset] > 1) {
                        if (bestLengthSize < matchLength[offset]) {
                            St4Block best = optimal[index - bestLength[bestLengthSize]];
                            assert best != null;
                            int bits = best.bits() + eliasGammaBits(bestLength[bestLengthSize] - 1);
                            do {
                                bestLengthSize++;
                                St4Block shorter = optimal[index - bestLengthSize];
                                assert shorter != null;
                                int shorterBits = shorter.bits() + eliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bits) {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bits = shorterBits;
                                } else {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            } while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        int start = index - length + 1;
                        St4Block previous = optimal[start - 1];
                        assert previous != null;
                        int bits;
                        int stored;
                        if (inRing) {
                            if (scheme == Scheme.END && offset > phase - length + 1) {
                                continue;               // the block would leave the lap
                            }
                            bits = previous.bits() + CONTROL_MATCH
                                    + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                    + eliasGammaBits(length - 1);
                            stored = offset;
                        } else if (scheme == Scheme.A2) {
                            // Relative to the literal read pointer: how many
                            // literals lie between the source and here.
                            int back = literalsBefore[start] - literalsBefore[start - offset];
                            if (back > reach) {
                                continue;
                            }
                            bits = previous.bits() + CONTROL_A2
                                    + (back > A2_BYTE_REACH ? 16 : 8)
                                    + eliasGammaBits(length - 1);
                            stored = -offset;
                        } else {
                            // Anchored at D's end: the lap so far, then back
                            // from the end of D to the source's literal.
                            int distance = (phase - length + 1)
                                    + (assumedLiterals - literalsBefore[start - offset]);
                            if (distance > reach) {
                                continue;
                            }
                            bits = previous.bits() + CONTROL_MATCH
                                    + (distance > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                    + eliasGammaBits(length - 1);
                            stored = -offset;
                        }
                        St4Block match = lastMatch[offset];
                        if (match == null || match.index() != index || match.bits() > bits) {
                            match = new St4Block(bits, index, stored, previous);
                            lastMatch[offset] = match;
                            optimal[index] = better(optimal[index], match);
                        }
                        produced = true;
                    }
                }
                if (!matches || !produced) {
                    if (!matches) {
                        matchLength[offset] = 0;
                    }
                    St4Block match = lastMatch[offset];
                    if (match != null) {
                        int length = index - match.index();
                        int bits = match.bits() + 1 + eliasGammaBits(length) + length * literalBits;
                        St4Block literal = new St4Block(bits, index, 0, match);
                        lastLiteral[offset] = literal;
                        optimal[index] = better(optimal[index], literal);
                    }
                }
            }
        }
        St4Block last = optimal[count - 1];
        assert last != null;
        return last;
    }

    /** The blocks of a chain, first block first. */
    private static List<St4Block> blocks(St4Block chain) {
        var list = new ArrayList<St4Block>();
        for (St4Block block = chain; block != null && block.index() >= 0; block = block.chain()) {
            list.add(block);
        }
        java.util.Collections.reverse(list);
        return list;
    }

    private static boolean[] literalMask(St4Block chain, int count) {
        boolean[] literal = new boolean[count];
        int previous = -1;
        for (St4Block block : blocks(chain)) {
            if (block.offset() == 0) {
                for (int p = previous + 1; p <= block.index(); p++) {
                    literal[p] = true;
                }
            }
            previous = block.index();
        }
        return literal;
    }

    private static int[] literalsBefore(boolean[] literal) {
        int[] before = new int[literal.length + 1];
        for (int p = 0; p < literal.length; p++) {
            before[p + 1] = before[p] + (literal[p] ? 1 : 0);
        }
        return before;
    }

    /** Holes of up to this many units between dictionary runs are filled. */
    private static final int HOLE = 3;

    private static boolean[] filled(boolean[] dictionary) {
        boolean[] result = dictionary.clone();
        int run = 0;
        for (int p = 0; p < result.length; p++) {
            if (result[p]) {
                if (run > 0 && run <= HOLE) {
                    for (int q = p - run; q < p; q++) {
                        result[q] = true;
                    }
                }
                run = 0;
            } else {
                run++;
            }
        }
        return result;
    }

    /**
     * Runs one scheme: the dictionary is the full-window parse's literals,
     * then whatever of it the parse copied from, until that holds still.
     */
    static Outcome run(int[] units, int unit, int ring, int reach, Scheme scheme,
                       St4Block fullParse) {
        int count = units.length;
        boolean[] forced = filled(literalMask(fullParse, count));
        int[] before = literalsBefore(forced);
        int assumed = before[count];
        Outcome best = null;
        for (int pass = 1; ; pass++) {
            St4Block chain = parse(units, unit, ring, reach, scheme, forced, before, assumed);
            boolean[] literal = literalMask(chain, count);
            int[] now = literalsBefore(literal);
            boolean[] referenced = new boolean[count];
            int references = 0;
            int previous = -1;
            for (St4Block block : blocks(chain)) {
                if (block.offset() < 0) {
                    references++;
                    int distance = -block.offset();
                    for (int p = previous + 1; p <= block.index(); p++) {
                        assert literal[p - distance] : "a copy from D points at a literal";
                        referenced[p - distance] = true;
                    }
                }
                previous = block.index();
            }
            int actual = now[count];
            int padding = scheme == Scheme.END ? Math.max(0, assumed - actual) * unit * 8 : 0;
            boolean valid = !(scheme == Scheme.END && actual > assumed);
            int dictionary = 0;
            for (boolean f : forced) {
                dictionary += f ? 1 : 0;
            }
            Outcome outcome = new Outcome(chain.bits() + padding, pass, references, dictionary,
                    padding, valid);
            if (valid && (best == null || outcome.bits() < best.bits())) {
                best = outcome;
            }
            boolean[] next = filled(referenced);
            boolean settled = java.util.Arrays.equals(next, forced)
                    && (scheme != Scheme.END || actual == assumed);
            if (settled || pass == PASSES) {
                return best != null ? best : outcome;
            }
            forced = next;
            before = now;
            assumed = actual;
        }
    }

    public static void main(String[] args) throws IOException {
        int unit = 1;
        int[] rings = {16, 64, 256};
        var files = new ArrayList<Path>();
        for (String arg : args) {
            if (arg.startsWith("-k")) {
                unit = Integer.parseInt(arg.substring(2));
            } else if (arg.startsWith("-r")) {
                rings = java.util.Arrays.stream(arg.substring(2).split(","))
                        .mapToInt(Integer::parseInt).toArray();
            } else {
                files.add(Path.of(arg));
            }
        }
        int reach = St4Format.maxOffsetUnits(unit);
        System.out.printf("k=%d: packed bytes, as the parse's bits, with the ratio to the input; "
                + "refs is copies from D, ps the passes, dict the dictionary units%n", unit);
        System.out.printf("%-12s %6s %5s %14s %14s   %14s %5s %3s %6s   %14s %5s %3s %6s %5s%n",
                "corpus", "bytes", "ring", "full window", "ring alone", "copies from D", "refs",
                "ps", "dict", "no class code", "refs", "ps", "dict", "pad");
        for (Path file : files) {
            byte[] input = Files.readAllBytes(file);
            int[] units = Units.split(input, unit);
            St4Block full = St4Optimizer.optimize(units, unit, reach, false);
            for (int ring : rings) {
                St4Block ringParse = St4Optimizer.optimize(units, unit, ring, false);
                Outcome a2 = run(units, unit, ring, reach, Scheme.A2, full);
                Outcome end = run(units, unit, ring, reach, Scheme.END, full);
                System.out.printf("%-12s %6d %5d %14s %14s   %14s %5d %3d %6d   %14s %5d %3d %6d %5d%n",
                        file.getFileName().toString().replace(".bin", ""), input.length, ring,
                        packed(full.bits(), input.length), packed(ringParse.bits(), input.length),
                        packed(a2.bits(), input.length) + (a2.converged() ? "" : "?"),
                        a2.references(), a2.passes(), a2.dictionary(),
                        packed(end.bits(), input.length) + (end.converged() ? "" : "?"),
                        end.references(), end.passes(), end.dictionary(), end.padding() / 8);
            }
        }
    }

    /** Bytes and the ratio to the input, as the packers report it. */
    private static String packed(int bits, int size) {
        int bytes = (bits + 7) / 8;
        return String.format("%d (%.1f%%)", bytes, 100.0 * bytes / size);
    }
}
