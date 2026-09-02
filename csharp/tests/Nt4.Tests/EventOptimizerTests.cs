// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Text;
using Xunit;

namespace Nt4.Tests;

/// <summary>
/// <see cref="EventOptimizer"/> against <see cref="FastOptimizer"/>: the
/// optimum is unique, so the cost arrays are equal element for element, the
/// strongest check on an optimizer that breaks ties differently. The rebuilt
/// chain decompresses back to the input and packs to the same size, give or
/// take stream padding.
/// </summary>
public sealed class EventOptimizerTests
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
    public void ComputesTheExactSameCosts()
    {
        foreach (byte[] input in Inputs())
        {
            foreach (int unit in new[] { 1, 2, 4 })
            {
                foreach (int window in new[] { 16, 64, 1024, Format.MaxOffsetUnits(unit) })
                {
                    int[] units = Units.Split(input, unit);
                    Assert.Equal(FastOptimizer.Costs(units, unit, window),
                        EventOptimizer.Costs(units, unit, window));
                }
            }
        }
    }

    [Fact]
    public void ItsChainsRoundTripAtTheSameSize()
    {
        foreach (byte[] input in Inputs())
        {
            foreach (int unit in new[] { 1, 2, 4 })
            {
                foreach (int window in new[] { 64, Format.MaxOffsetUnits(unit) })
                {
                    int[] units = Units.Split(input, unit);
                    Compressor.Result packed = Compressor.Compress(
                        EventOptimizer.Optimize(units, unit, window, false),
                        units, unit, Format.MaxOp);
                    byte[] padded = new byte[packed.PaddedSize];
                    Array.Copy(input, padded, Math.Min(input.Length, padded.Length));
                    Assert.Equal(padded, Decompressor.Decompress(
                        packed.Control, packed.Literal, packed.ByteOffsets,
                        packed.WordOffsets, unit, packed.PaddedSize));
                    Compressor.Result reference = Compressor.Compress(
                        FastOptimizer.Optimize(units, unit, window, false),
                        units, unit, Format.MaxOp);
                    Assert.True(Math.Abs(packed.PackedSize - reference.PackedSize) <= 4,
                        $"{input.Length} bytes, k={unit}, m={window}: "
                            + $"{packed.PackedSize} vs {reference.PackedSize}");
                }
            }
        }
    }
}
