// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// The one-shot optimizer for streams whose matches beyond the window copy
/// from the literal stream: exact for a given dictionary, the dictionary a
/// choice - the twin of the Java <c>St4LiteralCopyOptimizer</c>.
/// </summary>
/// <remarks>
/// A copy is valid only if its source is literal in the same parse, which
/// makes the exact optimum NP-hard. The dictionary is chosen first - the
/// literals of a full-window parse, forced to stay literal, holes filled - a
/// copy may come only from them, and the parse is the reference DP with the
/// copy candidates added; passes shrink the dictionary to what was copied
/// from. <see cref="LiteralCopyOracle"/> measures it, and
/// <see cref="LiteralCopySearch"/> keeps searching from here.
/// </remarks>
public static class LiteralCopyOptimizer
{
    /// <summary>Holes of up to this many units between dictionary runs are filled.</summary>
    private const int Hole = 3;

    /// <summary>Passes of shrinking the dictionary to what was copied from.</summary>
    private const int Passes = 4;

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    private static Block Better(Block? current, Block candidate) =>
        current == null || current.Bits > candidate.Bits ? candidate : current;

    /// <summary>
    /// Returns the last block of a parse of <paramref name="units"/> whose
    /// matches keep within <paramref name="window"/> units and whose copies
    /// from the literal stream are stored as negative offsets.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="window">The furthest a match may reach back, in units; beyond it an offset is a copy.</param>
    /// <param name="progress">Whether to report the passes on stdout.</param>
    /// <returns>The final block of the parse.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static Block Optimize(int[] units, int unit, int window, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        int count = units.Length;
        int reach = Format.MaxOffsetUnits(unit);
        // The dictionary: what a parse with the whole window leaves literal.
        Block full = EventOptimizer.Optimize(units, unit, reach, false);
        bool[] forced = Filled(LiteralMask(full, count));
        int[] before = LiteralsBefore(forced);
        Block? best = null;
        for (int pass = 1; pass <= Passes; pass++)
        {
            if (progress)
            {
                Console.WriteLine($"pass {pass}: {forced.Count(f => f)} dictionary units");
            }
            Block chain = Parse(units, unit, window, reach, forced, before);
            if (best == null || chain.Bits < best.Bits)
            {
                best = chain;
            }
            bool[] literal = LiteralMask(chain, count);
            bool[] referenced = new bool[count];
            int previous = -1;
            foreach (Block block in Blocks(chain))
            {
                if (block.Offset < 0)
                {
                    int distance = -block.Offset;
                    for (int p = previous + 1; p <= block.Index; p++)
                    {
                        referenced[p - distance] = true;
                    }
                }
                previous = block.Index;
            }
            bool[] next = Filled(referenced);
            if (next.SequenceEqual(forced))
            {
                break;
            }
            forced = next;
            before = LiteralsBefore(literal);
        }
        return best!;
    }

    /// <summary>
    /// The DP of <see cref="Optimizer.Optimize(int[], int, int, bool)"/>, with
    /// the copy candidates added: a match whose source is beyond the window is
    /// allowed only where the source is a dictionary unit, and is costed as a
    /// copy. A dictionary unit is never matched over.
    /// </summary>
    private static Block Parse(int[] units, int unit, int window, int reach,
        bool[] forced, int[] literalsBefore)
    {
        int count = units.Length;
        int literalBits = 8 * unit;
        int width = Math.Max(1, count - 1);
        var lastLiteral = new Block?[width + 1];
        var lastMatch = new Block?[width + 1];
        var optimal = new Block?[count];
        int[] matchLength = new int[width + 1];
        int[] bestLength = new int[Math.Max(count, 3)];
        bestLength[2] = 2;
        lastMatch[Optimizer.InitialOffset] = new Block(-1, -1, Optimizer.InitialOffset, null);

        for (int index = 0; index < count; index++)
        {
            int maxOffset = Math.Max(1, index);
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++)
            {
                bool inWindow = offset <= window;
                bool matches = index != 0 && index >= offset
                    && units[index] == units[index - offset] && !forced[index]
                    && (inWindow || forced[index - offset]);
                // The reference DP skips the literal candidate at an offset
                // that matches, since the rep stands in for it. A copy can be
                // refused, so an offset that produced nothing falls through.
                bool produced = false;
                if (matches)
                {
                    if (inWindow)
                    {
                        // Match reusing the last offset, after a literal run.
                        Block? literal = lastLiteral[offset];
                        if (literal != null)
                        {
                            int length = index - literal.Index;
                            int bits = literal.Bits + 1 + EliasGammaBits(length);
                            var match = new Block(bits, index, offset, literal);
                            lastMatch[offset] = match;
                            optimal[index] = Better(optimal[index], match);
                            produced = true;
                        }
                    }
                    if (++matchLength[offset] > 1)
                    {
                        if (bestLengthSize < matchLength[offset])
                        {
                            int bits = optimal[index - bestLength[bestLengthSize]]!.Bits
                                + EliasGammaBits(bestLength[bestLengthSize] - 1);
                            do
                            {
                                bestLengthSize++;
                                int shorterBits = optimal[index - bestLengthSize]!.Bits
                                    + EliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bits)
                                {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bits = shorterBits;
                                }
                                else
                                {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            }
                            while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        int start = index - length + 1;
                        Block previous = optimal[start - 1]!;
                        int candidateBits;
                        int stored;
                        if (inWindow)
                        {
                            candidateBits = previous.Bits + 3
                                + (offset > Format.ByteOffsetLimit ? 16 : 8)
                                + EliasGammaBits(length - 1);
                            stored = offset;
                        }
                        else
                        {
                            // The literals between the source and here, as the
                            // previous pass counted them; the copy must stay
                            // strictly behind the read pointer.
                            int back = literalsBefore[start] - literalsBefore[start - offset];
                            int wire = window + back;
                            if (back <= length || wire > reach)
                            {
                                continue;
                            }
                            candidateBits = previous.Bits + 3
                                + (wire > Format.ByteOffsetLimit ? 16 : 8)
                                + EliasGammaBits(length - 1);
                            stored = -offset;
                        }
                        Block? match = lastMatch[offset];
                        if (match == null || match.Index != index || match.Bits > candidateBits)
                        {
                            match = new Block(candidateBits, index, stored, previous);
                            lastMatch[offset] = match;
                            optimal[index] = Better(optimal[index], match);
                        }
                        produced = true;
                    }
                }
                if (!matches || !produced)
                {
                    if (!matches)
                    {
                        matchLength[offset] = 0;
                    }
                    Block? match = lastMatch[offset];
                    if (match != null)
                    {
                        int length = index - match.Index;
                        int bits = match.Bits + 1 + EliasGammaBits(length) + length * literalBits;
                        var literal = new Block(bits, index, 0, match);
                        lastLiteral[offset] = literal;
                        optimal[index] = Better(optimal[index], literal);
                    }
                }
            }
        }
        return optimal[count - 1]!;
    }

    /// <summary>The blocks of a chain, first block first.</summary>
    internal static List<Block> Blocks(Block chain)
    {
        var list = new List<Block>();
        for (Block? block = chain; block != null && block.Index >= 0; block = block.Chain)
        {
            list.Add(block);
        }
        list.Reverse();
        return list;
    }

    internal static bool[] LiteralMask(Block chain, int count)
    {
        bool[] literal = new bool[count];
        int previous = -1;
        foreach (Block block in Blocks(chain))
        {
            if (block.Offset == 0)
            {
                for (int p = previous + 1; p <= block.Index; p++)
                {
                    literal[p] = true;
                }
            }
            previous = block.Index;
        }
        return literal;
    }

    private static int[] LiteralsBefore(bool[] literal)
    {
        int[] before = new int[literal.Length + 1];
        for (int p = 0; p < literal.Length; p++)
        {
            before[p + 1] = before[p] + (literal[p] ? 1 : 0);
        }
        return before;
    }

    private static bool[] Filled(bool[] dictionary)
    {
        bool[] result = (bool[])dictionary.Clone();
        int run = 0;
        for (int p = 0; p < result.Length; p++)
        {
            if (result[p])
            {
                if (run > 0 && run <= Hole)
                {
                    for (int q = p - run; q < p; q++)
                    {
                        result[q] = true;
                    }
                }
                run = 0;
            }
            else
            {
                run++;
            }
        }
        return result;
    }
}
