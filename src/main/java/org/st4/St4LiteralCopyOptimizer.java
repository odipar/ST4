package org.st4;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one-shot optimizer for streams whose matches beyond the window copy
 * from the literal stream: exact for a given dictionary, the dictionary a
 * choice.
 *
 * <p>A copy is valid only if its source is literal in the same parse, and
 * its offset counts the literals between, so the best chain so far no longer
 * decides the best parse: the exact optimum is NP-hard. This class chooses the
 * dictionary first - the literals of a full-window parse, forced to stay
 * literal, holes of a few units between them filled - lets a copy come only
 * from them, and runs the reference DP with the copy candidates added. Passes
 * then shrink the dictionary to what was copied from, and the best pass wins.
 * Copies are costed with the previous pass's literal count, which can be off
 * by eight bits; the compressor writes the real one. {@link St4LiteralCopyOracle}
 * measures how far this lands from the optimum on inputs small enough to
 * exhaust, and {@link St4LiteralCopySearch} keeps searching from here.
 */
public final class St4LiteralCopyOptimizer {

    /** Holes of up to this many units between dictionary runs are filled. */
    private static final int HOLE = 3;

    /** Passes of shrinking the dictionary to what was copied from. */
    private static final int PASSES = 4;

    private St4LiteralCopyOptimizer() {}

    private static int eliasGammaBits(int value) {
        return 2 * (31 - Integer.numberOfLeadingZeros(value)) + 1;
    }

    private static St4Block better(@Nullable St4Block current, St4Block candidate) {
        return current == null || current.bits() > candidate.bits() ? candidate : current;
    }

    /**
     * Returns the last block of a parse of {@code units} whose matches keep
     * within {@code window} units and whose copies from the literal stream
     * are stored as negative offsets.
     *
     * @param unit     bytes per unit, which sets what a literal costs
     * @param window   the furthest a match may reach back, in units; beyond
     *                 it an offset is a copy from the literal stream
     * @param progress whether to report the passes on stdout
     */
    public static St4Block optimize(int[] units, int unit, int window, boolean progress) {
        int count = units.length;
        int reach = St4Format.maxOffsetUnits(unit);
        // The dictionary: what a parse with the whole window leaves literal.
        St4Block full = St4EventOptimizer.optimize(units, unit, reach, false);
        boolean[] forced = filled(literalMask(full, count));
        int[] before = literalsBefore(forced);
        @Nullable St4Block best = null;
        for (int pass = 1; pass <= PASSES; pass++) {
            if (progress) {
                System.out.printf("pass %d: %d dictionary units%n", pass, count(forced));
            }
            St4Block chain = parse(units, unit, window, reach, forced, before);
            if (best == null || chain.bits() < best.bits()) {
                best = chain;
            }
            boolean[] literal = literalMask(chain, count);
            boolean[] referenced = new boolean[count];
            int previous = -1;
            for (St4Block block : blocks(chain)) {
                if (block.offset() < 0) {
                    int distance = -block.offset();
                    for (int p = previous + 1; p <= block.index(); p++) {
                        assert literal[p - distance] : "a copy points at a literal";
                        referenced[p - distance] = true;
                    }
                }
                previous = block.index();
            }
            boolean[] next = filled(referenced);
            if (java.util.Arrays.equals(next, forced)) {
                break;
            }
            forced = next;
            before = literalsBefore(literal);
        }
        if (best == null) {
            throw new AssertionError("a pass was made");
        }
        return best;
    }

    /**
     * The DP of {@link St4Optimizer#optimize}, with the copy candidates added:
     * a match whose source is beyond the window is allowed only where the
     * source is a dictionary unit, and is costed as a copy. A dictionary unit
     * is never matched over.
     */
    private static St4Block parse(int[] units, int unit, int window, int reach,
                                  boolean[] forced, int[] literalsBefore) {
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
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++) {
                boolean inWindow = offset <= window;
                boolean matches = index != 0 && index >= offset
                        && units[index] == units[index - offset] && !forced[index]
                        && (inWindow || forced[index - offset]);
                // The reference DP skips the literal candidate at an offset
                // that matches, since the rep stands in for it. A copy can be
                // refused, so an offset that produced nothing falls through.
                boolean produced = false;
                if (matches) {
                    if (inWindow) {
                        // Match reusing the last offset, after a literal run.
                        St4Block literal = lastLiteral[offset];
                        if (literal != null) {
                            int length = index - literal.index();
                            int bits = literal.bits() + 1 + eliasGammaBits(length);
                            St4Block match = new St4Block(bits, index, offset, literal);
                            lastMatch[offset] = match;
                            optimal[index] = better(optimal[index], match);
                            produced = true;
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
                        if (inWindow) {
                            bits = previous.bits() + 3
                                    + (offset > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
                                    + eliasGammaBits(length - 1);
                            stored = offset;
                        } else {
                            // The literals between the source and here, as the
                            // previous pass counted them; the copy must stay
                            // strictly behind the read pointer.
                            int back = literalsBefore[start] - literalsBefore[start - offset];
                            int wire = window + back;
                            if (back <= length || wire > reach) {
                                continue;
                            }
                            bits = previous.bits() + 3
                                    + (wire > St4Format.BYTE_OFFSET_LIMIT ? 16 : 8)
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
    static List<St4Block> blocks(St4Block chain) {
        var list = new java.util.ArrayList<St4Block>();
        for (St4Block block = chain; block != null && block.index() >= 0; block = block.chain()) {
            list.add(block);
        }
        java.util.Collections.reverse(list);
        return list;
    }

    static boolean[] literalMask(St4Block chain, int count) {
        boolean[] literal = new boolean[count];
        int previous = -1;
        for (St4Block block : blocks(chain)) {
            if (block.offset() == 0) {
                for (int p = previous + 1; p <= block.index(); p++) {
                    literal[p] = true;
                }
            } else if (block.offset() == St4Block.RUN) {
                literal[previous + 1] = true;           // the run's one literal unit
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

    private static int count(boolean[] mask) {
        int n = 0;
        for (boolean b : mask) {
            n += b ? 1 : 0;
        }
        return n;
    }
}
