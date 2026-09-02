// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Text;
using Xunit;

namespace Nt4.Tests;

/// <summary>
/// <see cref="FastOptimizer"/> against <see cref="Optimizer"/>: the fast one
/// finds the same parse, so all four streams match byte for byte on every
/// input shape, unit size and window, lone matches before an offset's first
/// state, degenerate runs and inputs of a byte or two included.
/// </summary>
public sealed class FastOptimizerTests
{
    private static List<byte[]> Inputs()
    {
        byte[] random = new byte[4096];
        new JavaRandom(7).NextBytes(random);
        byte[] sparse = new byte[4096];
        var r = new JavaRandom(11);
        for (int i = 0; i < sparse.Length; i++)
        {
            sparse[i] = (byte)(r.NextInt(4) * 17 + i % 3);
        }
        byte[] allSame = new byte[3000];
        Array.Fill(allSame, (byte)'A');
        byte[] period = new byte[4096];
        for (int i = 0; i < period.Length; i++)
        {
            period[i] = (byte)(i % 3);
        }
        // A lone early match pair far apart, then dense matches: exercises
        // offsets whose first state appears long after their first match.
        byte[] lone = new byte[2048];
        r = new JavaRandom(3);
        for (int i = 0; i < lone.Length; i++)
        {
            lone[i] = (byte)r.NextInt(256);
        }
        Array.Copy(lone, 0, lone, 1500, 300);
        return
        [
            [42], [1, 2, 3], [7, 7], random, sparse, allSame, period, lone,
            Encoding.ASCII.GetBytes(string.Concat(
                Enumerable.Repeat("abracadabra hocus pocus ", 40))),
        ];
    }

    [Fact]
    public void FindsTheExactSameParse()
    {
        foreach (byte[] input in Inputs())
        {
            foreach (int unit in new[] { 1, 2, 4 })
            {
                foreach (int window in new[] { 16, 64, 1024, Format.MaxOffsetUnits(unit) })
                {
                    int[] units = Units.Split(input, unit);
                    Compressor.Result reference = Compressor.Compress(
                        Optimizer.Optimize(units, unit, window, false),
                        units, unit, Format.MaxOp);
                    Compressor.Result fast = Compressor.Compress(
                        FastOptimizer.Optimize(units, unit, window, false),
                        units, unit, Format.MaxOp);
                    Assert.Equal(reference.Control, fast.Control);
                    Assert.Equal(reference.Literal, fast.Literal);
                    Assert.Equal(reference.ByteOffsets, fast.ByteOffsets);
                    Assert.Equal(reference.WordOffsets, fast.WordOffsets);
                    Assert.Equal(reference.PaddedSize, fast.PaddedSize);
                }
            }
        }
    }
}
