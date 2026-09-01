// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// Optimal LZ parser for ST4: ZX1's optimal parser, moved from bytes to k-byte
/// units.
/// </summary>
/// <remarks>
/// <para>The algorithm is unchanged - for every position it keeps, per offset,
/// the cheapest chain that ends in a literal run and the cheapest that ends in
/// a match, then picks the best of the two - because the format's shape is
/// unchanged. Only the units differ, and with them two things in the cost
/// model: a literal unit costs <c>8 * k</c> bits rather than 8, and an offset
/// counts units rather than bytes. A new-offset match pays three control bits
/// - the flag and the two that name its offset's width and bank - plus eight
/// or sixteen for the offset itself.</para>
/// <para>This is the readable reference the fast implementations are checked
/// against; it allocates a block per candidate and most of them lose. The
/// result is a chain of <see cref="Block"/>s, last block first, which
/// <see cref="Compressor"/> walks in reverse. The Java reference inlines the
/// progress report; this port uses <see cref="ProgressMeter"/>, which prints
/// the same lines.</para>
/// </remarks>
public static class Optimizer
{
    /// <summary>The offset a stream starts with, as ZX1: one unit.</summary>
    public const int InitialOffset = 1;

    /// <summary>
    /// Returns the last block of the optimal parse of <paramref name="units"/>,
    /// drawing a progress bar on stdout while it works.
    /// </summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <returns>The final block of the optimal parse chain.</returns>
    public static Block Optimize(int[] units, int unit, int offsetLimit) =>
        Optimize(units, unit, offsetLimit, true);

    /// <summary>Returns the last block of the optimal parse of <paramref name="units"/>.</summary>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit, which sets what a literal costs.</param>
    /// <param name="offsetLimit">The furthest a match may reach back, in units.</param>
    /// <param name="progress">Whether to report on stdout, as <see cref="ProgressMeter"/>.</param>
    /// <returns>The final block of the optimal parse chain.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="units"/> is null.</exception>
    public static Block Optimize(int[] units, int unit, int offsetLimit, bool progress)
    {
        ArgumentNullException.ThrowIfNull(units);
        int literalBits = 8 * unit;
        int maxOffset = OffsetCeiling(units.Length - 1, offsetLimit);
        var lastLiteral = new Block?[maxOffset + 1];
        var lastMatch = new Block?[maxOffset + 1];
        var optimal = new Block?[units.Length];
        int[] matchLength = new int[maxOffset + 1];
        int[] bestLength = new int[Math.Max(units.Length, 3)];
        bestLength[2] = 2;

        // A fake block for the first real one to chain from.
        lastMatch[InitialOffset] = new Block(-1, -1, InitialOffset, null);

        var meter = new ProgressMeter(
            ProgressMeter.TotalSteps(units.Length, 0, offsetLimit), progress);

        for (int index = 0; index < units.Length; index++)
        {
            maxOffset = OffsetCeiling(index, offsetLimit);
            int bestLengthSize = 2;
            for (int offset = 1; offset <= maxOffset; offset++)
            {
                if (index != 0 && index >= offset && units[index] == units[index - offset])
                {
                    // Match reusing the last offset: its length may be one unit,
                    // which at k = 4 replaces four bytes with a couple of bits.
                    Block? literal = lastLiteral[offset];
                    if (literal != null)
                    {
                        int length = index - literal.Index;
                        int bits = literal.Bits + 1 + EliasGammaBits(length);
                        var match = new Block(bits, index, offset, literal);
                        lastMatch[offset] = match;
                        optimal[index] = Better(optimal[index], match);
                    }
                    // Match with a new offset, which the format cannot express
                    // shorter than two units.
                    if (++matchLength[offset] > 1)
                    {
                        if (bestLengthSize < matchLength[offset])
                        {
                            Block best = optimal[index - bestLength[bestLengthSize]]!;
                            int bestBits = best.Bits + EliasGammaBits(bestLength[bestLengthSize] - 1);
                            do
                            {
                                bestLengthSize++;
                                Block shorter = optimal[index - bestLengthSize]!;
                                int shorterBits = shorter.Bits + EliasGammaBits(bestLengthSize - 1);
                                if (shorterBits <= bestBits)
                                {
                                    bestLength[bestLengthSize] = bestLengthSize;
                                    bestBits = shorterBits;
                                }
                                else
                                {
                                    bestLength[bestLengthSize] = bestLength[bestLengthSize - 1];
                                }
                            }
                            while (bestLengthSize < matchLength[offset]);
                        }
                        int length = bestLength[matchLength[offset]];
                        Block previous = optimal[index - length]!;
                        int bits = previous.Bits + 3
                            + (offset > Format.ByteOffsetLimit ? 16 : 8)
                            + EliasGammaBits(length - 1);
                        Block? match = lastMatch[offset];
                        if (match == null || match.Index != index || match.Bits > bits)
                        {
                            match = new Block(bits, index, offset, previous);
                            lastMatch[offset] = match;
                            optimal[index] = Better(optimal[index], match);
                        }
                    }
                }
                else
                {
                    // Literals: the run's length goes in stream A, its payload
                    // in stream D, and both are paid for here.
                    matchLength[offset] = 0;
                    Block? match = lastMatch[offset];
                    if (match != null)
                    {
                        int length = index - match.Index;
                        int bits = match.Bits + 1 + EliasGammaBits(length)
                            + length * literalBits;
                        var literal = new Block(bits, index, 0, match);
                        lastLiteral[offset] = literal;
                        optimal[index] = Better(optimal[index], literal);
                    }
                }
            }
            meter.Advance(maxOffset);
        }
        meter.Finish();

        return optimal[units.Length - 1]!;
    }

    private static int OffsetCeiling(int index, int offsetLimit) =>
        Math.Clamp(index, InitialOffset, offsetLimit);

    private static int EliasGammaBits(int value)
    {
        int bits = 1;
        while ((value >>= 1) != 0)
        {
            bits += 2;
        }
        return bits;
    }

    private static Block Better(Block? current, Block candidate) =>
        current == null || current.Bits > candidate.Bits ? candidate : current;
}
