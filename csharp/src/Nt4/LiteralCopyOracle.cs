// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// The true optimum of a parse with copies from the literal stream, found by
/// trying every parse the format allows - possible only on inputs of a dozen
/// units or so, and meant to measure how far
/// <see cref="LiteralCopyOptimizer"/> lands from the optimum it cannot reach
/// in polynomial time. The twin of the Java <c>St4LiteralCopyOracle</c>.
/// </summary>
/// <remarks>
/// It costs exactly what the compressor writes, and knows everything the
/// decoder does: a literal run, its flag unless it opens the stream; a match
/// within the window, new or a rep of the last offset after literals; a copy
/// from the literal stream at the window plus the literals between its source
/// and itself, strictly shorter than that count, its offset advanced by what
/// it copied; and a rep of a copy after literals, which resumes just past it
/// from wherever the read pointer has got to.
/// </remarks>
public sealed class LiteralCopyOracle
{
    private readonly int[] units;
    private readonly int literalBits;
    private readonly int window;
    private readonly int reach;

    // The parse so far: which units are literal, in emission order, and the
    // blocks chosen, so the best parse can be kept when it is found.
    private readonly bool[] literal;
    private readonly int[] literalPositions;   // output position of each literal, in order
    private int literals;
    private readonly List<int[]> blocks = [];  // {index, offset, bits} per block
    private List<int[]> best = [];
    private int bestBits = int.MaxValue;

    private LiteralCopyOracle(int[] units, int unit, int window)
    {
        this.units = units;
        this.literalBits = 8 * unit;
        this.window = window;
        this.reach = Format.MaxOffsetUnits(unit);
        this.literal = new bool[units.Length];
        this.literalPositions = new int[units.Length];
    }

    private static int EliasGammaBits(int value) =>
        2 * (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value)) + 1;

    /// <summary>
    /// The cheapest parse of <paramref name="units"/> at <paramref name="window"/>,
    /// as a chain of blocks the compressor can write - copies as negative
    /// offsets - whose bits are exactly the compressor's.
    /// </summary>
    /// <param name="units">The input as k-byte units, a dozen or so at most.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="window">The window in units.</param>
    /// <returns>The final block of the cheapest parse.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static Block Optimize(int[] units, int unit, int window)
    {
        ArgumentNullException.ThrowIfNull(units);
        var oracle = new LiteralCopyOracle(units, unit, window);
        oracle.Search(0, 0, Optimizer.InitialOffset, false, true);
        var chain = new Block(-1, -1, Optimizer.InitialOffset, null);
        foreach (int[] block in oracle.best)
        {
            chain = new Block(block[2], block[0], block[1], chain);
        }
        return chain;
    }

    private void Search(int index, int bits, int lastOffset, bool afterLiterals, bool first)
    {
        int count = units.Length;
        if (bits >= bestBits)
        {
            return;                                 // no parse from here can win
        }
        if (index == count)
        {
            bestBits = bits;
            best = blocks.Select(block => (int[])block.Clone()).ToList();
            return;
        }
        // A literal run, unless one just ended.
        if (!afterLiterals)
        {
            int mark = literals;
            for (int length = 1; index + length <= count; length++)
            {
                literal[index + length - 1] = true;
                literalPositions[literals++] = index + length - 1;
                int cost = bits + (first ? 0 : 1) + EliasGammaBits(length) + length * literalBits;
                Push(index + length - 1, 0, cost);
                Search(index + length, cost, lastOffset, true, false);
                blocks.RemoveAt(blocks.Count - 1);
            }
            for (int p = index; p < index + (literals - mark); p++)
            {
                literal[p] = false;
            }
            literals = mark;
        }
        // A rep of the last offset, after literals: a match, or a copy.
        if (afterLiterals)
        {
            if (lastOffset <= window)
            {
                if (lastOffset <= index)
                {
                    for (int length = 1; index + length <= count
                        && units[index + length - 1] == units[index + length - 1 - lastOffset];
                        length++)
                    {
                        int cost = bits + 1 + EliasGammaBits(length);
                        Push(index + length - 1, lastOffset, cost);
                        Search(index + length, cost, lastOffset, false, false);
                        blocks.RemoveAt(blocks.Count - 1);
                    }
                }
            }
            else
            {
                int back = lastOffset - window;
                if (back <= literals)
                {
                    int source = literals - back;    // the literal to copy first
                    for (int length = 1; index + length <= count && length < back
                        && units[index + length - 1] == units[literalPositions[source + length - 1]];
                        length++)
                    {
                        int distance = index - literalPositions[source];
                        int cost = bits + 1 + EliasGammaBits(length);
                        Push(index + length - 1, -distance, cost);
                        Search(index + length, cost, lastOffset - length, false, false);
                        blocks.RemoveAt(blocks.Count - 1);
                    }
                }
            }
        }
        // A match at a new offset, within the window.
        for (int offset = 1; offset <= Math.Min(index, window); offset++)
        {
            int cost = 3 + (offset > Format.ByteOffsetLimit ? 16 : 8);
            for (int length = 2; index + length <= count
                && units[index + length - 1] == units[index + length - 1 - offset];
                length++)
            {
                if (length == 2 && units[index] != units[index - offset])
                {
                    break;
                }
                int total = bits + cost + EliasGammaBits(length - 1);
                Push(index + length - 1, offset, total);
                Search(index + length, total, offset, false, false);
                blocks.RemoveAt(blocks.Count - 1);
            }
        }
        // A copy from the literal stream: from any literal so far, strictly
        // shorter than the literals between it and here.
        for (int source = 0; source < literals; source++)
        {
            int back = literals - source;
            int wire = window + back;
            if (wire > reach)
            {
                continue;
            }
            int cost = 3 + (wire > Format.ByteOffsetLimit ? 16 : 8);
            int distance = index - literalPositions[source];
            for (int length = 2; index + length <= count && length < back
                && units[index + length - 1] == units[literalPositions[source + length - 1]];
                length++)
            {
                if (length == 2 && units[index] != units[literalPositions[source]])
                {
                    break;
                }
                // The copied units must be one run in the output: the compressor
                // names the source by output position and reads it as such.
                if (literalPositions[source + length - 1] != literalPositions[source] + length - 1)
                {
                    break;
                }
                int total = bits + cost + EliasGammaBits(length - 1);
                Push(index + length - 1, -distance, total);
                Search(index + length, total, wire - length, false, false);
                blocks.RemoveAt(blocks.Count - 1);
            }
        }
    }

    private void Push(int index, int offset, int bits) => blocks.Add([index, offset, bits]);
}
