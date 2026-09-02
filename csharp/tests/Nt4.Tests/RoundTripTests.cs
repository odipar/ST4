// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

using System.Text;
using Xunit;

namespace Nt4.Tests;

/// <summary>
/// The Java <c>St4RoundTripTest</c>, corpus for corpus: pack with the
/// reference optimizer, unpack with the reference decoder, and check the
/// container against the format's promises.
/// </summary>
public sealed class RoundTripTests
{
    private static Compressor.Result Pack(byte[] input, int unit) =>
        Pack(input, unit, Format.MaxOffsetUnits(unit), Format.MaxOp);

    private static Compressor.Result Pack(byte[] input, int unit, int offsetLimit,
        int maxOpLength)
    {
        int[] units = Units.Split(input, unit);
        // No progress bar: a test run is not a person waiting at a terminal.
        return Compressor.Compress(
            Optimizer.Optimize(units, unit, offsetLimit, false), units, unit, maxOpLength);
    }

    private static byte[] Unpack(Compressor.Result packed) =>
        Decompressor.Decompress(packed.Control, packed.Literal, packed.ByteOffsets,
            packed.WordOffsets, packed.Unit, packed.PaddedSize);

    private static byte[] Unpack(Compressor.Result packed, int offsetLimit) =>
        Decompressor.Decompress(packed.Control, packed.Literal, packed.ByteOffsets,
            packed.WordOffsets, packed.Unit, packed.PaddedSize, offsetLimit);

    /// <summary>
    /// Packs a stream that loops by rewind: the intro and the loop from unit
    /// <paramref name="index"/> parsed on their own, as the packer does when the
    /// loop is longer than the window.
    /// </summary>
    private static Compressor.Result PackRewinding(byte[] input, int unit, int window, int index)
    {
        int[] units = Units.Split(input, unit);
        int[] intro = units[..index];
        int[] loop = units[index..];
        return Compressor.CompressRewinding(
            intro.Length == 0 ? null : Optimizer.Optimize(intro, unit, window, false),
            Optimizer.Optimize(loop, unit, window, false), units, unit, Format.MaxOp, index);
    }

    /// <summary>Packs a stream that loops: after its last unit it continues from unit <paramref name="index"/>.</summary>
    private static Compressor.Result PackRepeating(byte[] input, int unit, int index)
    {
        int[] units = Units.Split(input, unit);
        return Compressor.Compress(
            Optimizer.Optimize(units, unit, Format.MaxOffsetUnits(unit), false),
            units, unit, Format.MaxOp, index);
    }

    private static byte[] Padded(byte[] input, int unit)
    {
        byte[] padded = new byte[Units.PaddedLength(input.Length, unit)];
        input.CopyTo(padded, 0);
        return padded;
    }

    private static List<byte[]> Inputs()
    {
        byte[] random = new byte[997];
        new JavaRandom(7).NextBytes(random);
        byte[] allSame = new byte[1000];
        Array.Fill(allSame, (byte)'A');
        byte[] period = new byte[1024];
        for (int i = 0; i < period.Length; i++)
        {
            period[i] = (byte)(i % 12);
        }
        byte[] words = new byte[2048];
        for (int i = 0; i < words.Length; i += 2)
        {
            words[i] = (byte)(i / 64);
            words[i + 1] = (byte)(i % 7);
        }
        return
        [
            [42], [1, 2, 3], random, allSame, period, words,
            Encoding.ASCII.GetBytes(string.Concat(
                Enumerable.Repeat("abracadabra hocus pocus ", 20))),
        ];
    }

    [Fact]
    public void RoundTripsAtEveryUnitSize()
    {
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                Compressor.Result packed = Pack(input, unit);
                Assert.Equal(Padded(input, unit), Unpack(packed));
            }
        }
    }

    [Fact]
    public void EveryStreamIsAWholeNumberOfUnits()
    {
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                Compressor.Result packed = Pack(input, unit);
                Assert.Equal(0, packed.Literal.Length % unit);
                Assert.Equal(0, packed.Control.Length % 2);
                Assert.Equal(0, packed.WordOffsets.Length % 2);
                Assert.Equal(0, packed.PaddedSize % unit);
            }
        }
    }

    [Fact]
    public void LimitedOffsetsStayInsideTheirWindow()
    {
        // -mN is what makes a stream safe for an N-unit ring; decoding through
        // exactly that much history has to reproduce the input.
        byte[] input = new byte[8000];
        var random = new JavaRandom(11);
        for (int i = 0; i < input.Length; i++)
        {
            input[i] = (byte)(random.NextInt(4) * 17 + i % 3);
        }
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (int window in new[] { 64, 256, 4096 })
            {
                Compressor.Result packed = Pack(input, unit, window, Format.MaxOp);
                Assert.Equal(Padded(input, unit), Unpack(packed, window));
            }
        }
    }

    [Fact]
    public void TheLimitCheckingDecoderRefusesAWiderStream()
    {
        // Decoding through an offset limit is how tests hold a -mN stream to
        // its ring, so a stream that reaches further must fail loudly rather
        // than pretend - random data repeated once guarantees one far match.
        byte[] half = new byte[1000];
        new JavaRandom(5).NextBytes(half);
        byte[] input = new byte[2000];
        half.CopyTo(input, 0);
        half.CopyTo(input, 1000);
        Compressor.Result wide = Pack(input, 1);
        Assert.Throws<InvalidDataException>(() => Unpack(wide, 8));
    }

    [Fact]
    public void SplittingKeepsOperationsInsideAWordCounter()
    {
        byte[] input = new byte[40000];
        Array.Fill(input, (byte)0x5A);
        foreach (int unit in new[] { 1, 2, 4 })
        {
            Compressor.Result packed = Pack(input, unit,
                Format.MaxOffsetUnits(unit), 1000);
            Assert.True(packed.LongestOp <= 1000,
                $"unit {unit} emitted an operation of {packed.LongestOp}");
            Assert.Equal(Padded(input, unit), Unpack(packed));
        }
    }

    [Fact]
    public void ARepeatingStreamFillsAnyOutputBeyondOnePass()
    {
        // A stream that loops from unit R decodes as the infinite input
        // units[0..R) units[R..O)*: past one whole pass, every unit is the one
        // O-R units back. Any output size from one pass up must decode, and
        // every byte past the pass must obey that recurrence.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                int total = Units.PaddedLength(input.Length, unit) / unit;
                foreach (int index in new SortedSet<int> { 0, total / 3, total - 1 })
                {
                    Compressor.Result packed = PackRepeating(input, unit, index);
                    byte[] pass = Padded(input, unit);
                    byte[] expected = new byte[pass.Length + ((2 * total) + 3) * unit];
                    pass.CopyTo(expected, 0);
                    for (int at = pass.Length; at < expected.Length; at++)
                    {
                        expected[at] = expected[at - ((total - index) * unit)];
                    }
                    Assert.Equal(expected, Decompressor.Decompress(
                        packed.Control, packed.Literal, packed.ByteOffsets,
                        packed.WordOffsets, unit, expected.Length));
                    // One exact pass is still decodable: the repeat has no room
                    // and the streams must come out fully consumed anyway.
                    Assert.Equal(pass, Unpack(packed));
                }
            }
        }
    }

    [Fact]
    public void ARewindStreamDecodesToItsPassAndNeverReachesBeforeTheLoop()
    {
        // The loop is parsed on its own, so replaying it from the state saved
        // at the loop point sees the same history every pass. The reference
        // holds a stream to that: from the rewind point on, no match may reach
        // before it - and the pass must still be the input.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                int total = Units.PaddedLength(input.Length, unit) / unit;
                foreach (int index in new SortedSet<int> { 0, total / 3, Math.Max(0, total - 65) })
                {
                    if (index >= total)
                    {
                        continue;
                    }
                    Compressor.Result packed = PackRewinding(input, unit, 64, index);
                    Assert.Equal(index, packed.RewindIndex);
                    Decompressor.Decoded decoded = Decompressor.Decode(
                        packed.Control, packed.Literal, packed.ByteOffsets,
                        packed.WordOffsets, unit, packed.PaddedSize, 64, index * unit);
                    Assert.Equal(Padded(input, unit), decoded.Output);
                    Assert.Equal(-1, decoded.RepeatIndex);

                    // The container names the rewind point, in bytes like O.
                    Format.Container read = Format.Read(Nt4.Container(packed));
                    Assert.Equal(index * unit, read.Rewind);
                }
            }
        }
    }

    [Fact]
    public void TheSeamOfARewindStreamIsAbsorbed()
    {
        // The loop's parse assumes the stream's initial offset at its start, so
        // its first match may be a one-unit rep of offset one - which, after an
        // intro that left another offset behind, the format cannot write. The
        // compressor turns that unit into a literal; the stream must still
        // decode, and still hold to its rewind point.
        byte[] input = Encoding.ASCII.GetBytes("xyzxyzxyzxyzabb");
        Compressor.Result packed = PackRewinding(input, 1, 512, 12);
        Assert.Equal(input, Decompressor.Decode(packed.Control, packed.Literal,
            packed.ByteOffsets, packed.WordOffsets, 1, input.Length, 512, 12).Output);
        // And two literal runs meeting at the seam become one, which the
        // format demands: 256 distinct bytes hold no match at all, so an
        // intro and a loop cut from them are one literal run each - and one
        // together.
        byte[] distinct = new byte[256];
        for (int i = 0; i < distinct.Length; i++)
        {
            distinct[i] = (byte)i;
        }
        Compressor.Result merged = PackRewinding(distinct, 1, 512, 100);
        Assert.Equal(distinct, Decompressor.Decode(merged.Control, merged.Literal,
            merged.ByteOffsets, merged.WordOffsets, 1, distinct.Length, 512, 100).Output);
        Assert.Equal(1, merged.Operations);
    }

    [Fact]
    public void TheRewindCheckRefusesALoopThatReachesBeforeItsPoint()
    {
        // A stream packed without the rewind constraint lets its second half
        // match its first; replayed from the halfway point it would read what
        // the ring held instead, so the reference must refuse it there.
        byte[] half = new byte[1000];
        new JavaRandom(5).NextBytes(half);
        byte[] input = new byte[2000];
        half.CopyTo(input, 0);
        half.CopyTo(input, 1000);
        Compressor.Result plain = Pack(input, 1);
        Assert.Throws<InvalidDataException>(() => Decompressor.Decode(
            plain.Control, plain.Literal, plain.ByteOffsets, plain.WordOffsets,
            1, 2000, Format.MaxOffsetUnits(1), 1000));
        // The same data packed for rewind at 1000 passes the same check.
        Compressor.Result rewound = PackRewinding(input, 1, 512, 1000);
        Assert.Equal(input, Decompressor.Decode(rewound.Control, rewound.Literal,
            rewound.ByteOffsets, rewound.WordOffsets, 1, 2000, 512, 1000).Output);
    }

    [Fact]
    public void TheDecoderReportsHowAStreamEnds()
    {
        byte[] input = Encoding.ASCII.GetBytes(
            "the tail of this sentence loops. and loops. ");
        Compressor.Result plain = Pack(input, 1);
        Assert.Equal(-1, Decompressor.Decode(plain.Control, plain.Literal,
            plain.ByteOffsets, plain.WordOffsets, 1, plain.PaddedSize,
            Format.MaxOffsetUnits(1)).RepeatIndex);
        Compressor.Result looped = PackRepeating(input, 1, 12);
        Assert.Equal(12, Decompressor.Decode(looped.Control, looped.Literal,
            looped.ByteOffsets, looped.WordOffsets, 1, looped.PaddedSize,
            Format.MaxOffsetUnits(1)).RepeatIndex);
    }

    [Fact]
    public void TheLimitCheckingDecoderRefusesAWideRepeat()
    {
        // The loop distance is a match offset like any other: one that reaches
        // further back than the ring keeps must fail loudly, exactly as a wide
        // offset does. Looping 400 units from unit 100 is 300 units back.
        byte[] input = new byte[400];
        new JavaRandom(3).NextBytes(input);
        int[] units = Units.Split(input, 1);
        Compressor.Result wide = Compressor.Compress(
            Optimizer.Optimize(units, 1, 64, false), units, 1, Format.MaxOp, 100);
        Assert.Throws<InvalidDataException>(() => Decompressor.Decompress(
            wide.Control, wide.Literal, wide.ByteOffsets, wide.WordOffsets, 1, 800, 64));
    }

    [Fact]
    public void CopiesFromTheLiteralStreamRoundTripAtSmallWindows()
    {
        // A parse whose matches keep within a small window and whose copies
        // reach the literal stream beyond it must decode at that window, and
        // is never dearer than the same window without copies.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                int[] units = Units.Split(input, unit);
                foreach (int window in new[] { 4, 16, 64 })
                {
                    Block parse = LiteralCopySearch.Optimize(units, unit, window, Format.MaxOp, 0, 1);
                    Compressor.Result packed = Compressor.Compress(parse, units, unit,
                        Format.MaxOp, -1, window);
                    Assert.Equal(Padded(input, unit), Decompressor.Decompress(
                        packed.Control, packed.Literal, packed.ByteOffsets,
                        packed.WordOffsets, unit, packed.PaddedSize, window));
                    Assert.Equal(window, Format.Read(Nt4.Container(packed)).Window);
                    Compressor.Result plain = Pack(input, unit, window, Format.MaxOp);
                    Assert.True(packed.Bits <= plain.Bits,
                        $"unit {unit}, window {window}: {packed.Bits} bits with copies, {plain.Bits} without");
                }
            }
        }
    }

    [Fact]
    public void TheOracleCostsExactlyWhatTheCompressorWrites()
    {
        // The oracle claims to know the format's every cost; the compressor is
        // the authority. Every oracle parse must write to its own bit count
        // and decode.
        var random = new JavaRandom(17);
        for (int trial = 0; trial < 60; trial++)
        {
            int count = 6 + random.NextInt(6);
            int[] units = new int[count];
            for (int i = 0; i < count; i++)
            {
                units[i] = random.NextInt(3);
            }
            byte[] input = new byte[count];
            for (int i = 0; i < count; i++)
            {
                input[i] = (byte)units[i];
            }
            int window = 2 + random.NextInt(3);
            Block parse = LiteralCopyOracle.Optimize(units, 1, window);
            Compressor.Result packed = Compressor.Compress(parse, units, 1, Format.MaxOp, -1,
                window);
            Assert.Equal(parse.Bits, packed.Bits);
            Assert.Equal(input, Decompressor.Decompress(packed.Control, packed.Literal,
                packed.ByteOffsets, packed.WordOffsets, 1, count, window));
        }
    }

    [Fact]
    public void TheOpeningPassesAreMeasuredAgainstTheOracle()
    {
        // The search's opening passes - what nt4 -c alone writes - choose a
        // dictionary first and are exact for it; the oracle tries everything.
        // The passes can only be dearer, and how much dearer is a number.
        var random = new JavaRandom(23);
        int oracleBits = 0;
        int heuristicBits = 0;
        for (int trial = 0; trial < 60; trial++)
        {
            int count = 6 + random.NextInt(6);
            int[] units = new int[count];
            for (int i = 0; i < count; i++)
            {
                units[i] = random.NextInt(3);
            }
            int window = 2 + random.NextInt(3);
            int oracle = LiteralCopyOracle.Optimize(units, 1, window).Bits;
            Block parse = LiteralCopySearch.Optimize(units, 1, window, Format.MaxOp, 0, trial);
            int heuristic = Compressor.Compress(parse, units, 1, Format.MaxOp, -1, window).Bits;
            Assert.True(oracle <= heuristic, $"trial {trial}: the oracle is the optimum");
            oracleBits += oracle;
            heuristicBits += heuristic;
        }
        Assert.True(heuristicBits <= oracleBits * 1.5, "within reach of the optimum");
    }

    /// <summary>
    /// ZX1's packed size for each of <see cref="Inputs"/>, recorded from jx1
    /// in odipar/ST1 at commit 132aef0, exactly as the Java suite holds them.
    /// </summary>
    private static readonly int[] Zx1Sizes = [4, 6, 1006, 6, 19, 383, 26];

    [Fact]
    public void TheSearchStartsWhereTheHeuristicEndsAndIsMeasuredAgainstTheOracle()
    {
        // The search descends and anneals over dictionaries from the
        // heuristic's, scoring each by what the compressor writes, so it can
        // only improve on the heuristic - and on inputs small enough to
        // exhaust, how often it reaches the optimum is a number.
        var random = new JavaRandom(29);
        int oracleBits = 0;
        int heuristicBits = 0;
        int searchBits = 0;
        int optimal = 0;
        for (int trial = 0; trial < 60; trial++)
        {
            int count = 6 + random.NextInt(6);
            int[] units = new int[count];
            byte[] input = new byte[count];
            for (int i = 0; i < count; i++)
            {
                units[i] = random.NextInt(3);
                input[i] = (byte)units[i];
            }
            int window = 2 + random.NextInt(3);
            int oracle = LiteralCopyOracle.Optimize(units, 1, window).Bits;
            int heuristic = Compressor.Compress(
                LiteralCopySearch.Optimize(units, 1, window, Format.MaxOp, 0, trial), units, 1,
                Format.MaxOp, -1, window).Bits;
            Block parse = LiteralCopySearch.Optimize(units, 1, window, Format.MaxOp, 200, trial);
            Compressor.Result packed = Compressor.Compress(parse, units, 1, Format.MaxOp, -1,
                window);
            string shape = $"trial {trial}, window {window}";
            Assert.True(oracle <= packed.Bits, $"{shape}: the oracle is the optimum");
            Assert.Equal(input, Decompressor.Decompress(packed.Control, packed.Literal,
                packed.ByteOffsets, packed.WordOffsets, 1, count, window));
            oracleBits += oracle;
            heuristicBits += heuristic;
            searchBits += packed.Bits;
            optimal += packed.Bits == oracle ? 1 : 0;
        }
        Assert.True(searchBits <= heuristicBits, "the search starts where the heuristic ends");
        Assert.True(optimal >= 45, $"the search reaches the optimum on most small inputs: {optimal} of 60");
    }

    [Fact]
    public void TheSearchIsReproducibleAndItsParsesDecode()
    {
        // A seeded search is a function of its input: the same steps give the
        // same parse. Every parse it scores decodes, whatever the corpus or
        // window, and its best is never dearer than the heuristic's.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                int[] units = Units.Split(input, unit);
                foreach (int window in new[] { 4, 16, 64 })
                {
                    Block parse = LiteralCopySearch.Optimize(units, unit, window, Format.MaxOp,
                        40, 5);
                    Compressor.Result packed = Compressor.Compress(parse, units, unit,
                        Format.MaxOp, -1, window);
                    Assert.Equal(Padded(input, unit), Decompressor.Decompress(packed.Control,
                        packed.Literal, packed.ByteOffsets, packed.WordOffsets, unit,
                        packed.PaddedSize, window));
                    Compressor.Result again = Compressor.Compress(
                        LiteralCopySearch.Optimize(units, unit, window, Format.MaxOp, 40, 5),
                        units, unit, Format.MaxOp, -1, window);
                    Assert.Equal(packed.Control, again.Control);
                    Assert.Equal(packed.Literal, again.Literal);
                    Compressor.Result passes = Compressor.Compress(
                        LiteralCopySearch.Optimize(units, unit, window, Format.MaxOp, 0, 5), units,
                        unit, Format.MaxOp, -1, window);
                    Assert.True(packed.Bits <= passes.Bits,
                        $"unit {unit}, {input.Length} bytes, window {window}: "
                        + $"{packed.Bits} bits searched, {passes.Bits} from the passes");
                }
            }
        }
    }

    [Fact]
    public void TheSearchParserRestartsFromItsCheckpointsExactly()
    {
        // A parse restarted from a checkpoint before the first changed unit
        // must be the parse from scratch, block for block - accepted or
        // not, and whatever the parses in between did to the arrays. At a
        // 512-unit window a single parse makes more nodes than a small
        // input's worth, which is where the pool's compaction has to know
        // what a full parse takes rather than go round again.
        var random = new JavaRandom(31);
        int count = 6000;
        int[] units = new int[count];
        for (int i = 0; i < count; i++)
        {
            units[i] = i > 40 && random.NextInt(3) > 0 ? units[i - 1 - random.NextInt(40)]
                : random.NextInt(6);
        }
        foreach (int window in new[] { 16, 512 })
        {
            var parser = new LiteralCopySearch.Parser(units, 1, window);
            bool[] dictionary = new bool[count];
            for (int i = 0; i < count; i++)
            {
                dictionary[i] = random.NextInt(4) == 0;
            }
            for (int trial = 0; trial < 40; trial++)
            {
                int at = random.NextInt(count);
                int size = 1 + random.NextInt(24);
                bool value = random.NextBoolean();
                Array.Fill(dictionary, value, at, Math.Min(count, at + size) - at);
                Block restarted = parser.Parse(dictionary);
                Block fresh = new LiteralCopySearch.Parser(units, 1, window).Parse(dictionary);
                List<Block> a = LiteralCopySearch.Blocks(restarted);
                List<Block> b = LiteralCopySearch.Blocks(fresh);
                Assert.Equal(b.Count, a.Count);
                for (int i = 0; i < a.Count; i++)
                {
                    Assert.Equal(b[i].Index, a[i].Index);
                    Assert.Equal(b[i].Offset, a[i].Offset);
                    Assert.Equal(b[i].Bits, a[i].Bits);
                }
                if (random.NextBoolean())
                {
                    parser.Accept();
                }
            }
        }
    }

    [Fact]
    public void TheUnpackerPlaysALoopAsManyTimesAsAsked()
    {
        // dnt4 -rN plays a looping stream's loop N times: the pass, then N-1
        // repeats of its loop section - whether the stream loops by itself,
        // where the decoder fills the length, or by rewind, where the pass's
        // loop section is replayed. A stream that does not loop has one pass.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                byte[] pass = Padded(input, unit);
                int total = pass.Length / unit;
                foreach (int index in new SortedSet<int> { 0, total / 3, total - 1 })
                {
                    foreach (bool rewind in new[] { false, true })
                    {
                        int window = rewind ? Math.Max(1, (total - index) / 2) : total;
                        Compressor.Result packed = rewind
                            ? PackRewinding(input, unit, window, index)
                            : PackRepeating(input, unit, index);
                        Format.Container container = Format.Read(Nt4.Container(packed));
                        Decompressor.Decoded decoded = Decompressor.Decode(container.Control,
                            container.Literal, container.ByteOffsets, container.WordOffsets, unit,
                            container.Size, container.Window, container.Rewind);
                        Assert.Equal(pass, decoded.Output);
                        foreach (int times in new[] { 1, 2, 4 })
                        {
                            int loop = pass.Length - index * unit;
                            byte[] expected = new byte[pass.Length + (times - 1) * loop];
                            pass.CopyTo(expected, 0);
                            for (int at = pass.Length; at < expected.Length; at++)
                            {
                                expected[at] = expected[at - loop];
                            }
                            Assert.Equal(expected, Dnt4.Played(container, decoded, times));
                        }
                    }
                }
                Format.Container plain = Format.Read(Nt4.Container(Pack(input, unit)));
                Decompressor.Decoded once = Decompressor.Decode(plain.Control, plain.Literal,
                    plain.ByteOffsets, plain.WordOffsets, unit, plain.Size, plain.Window,
                    plain.Rewind);
                Assert.Equal(pass, Dnt4.Played(plain, once, 1));
                Assert.Throws<ArgumentException>(() => Dnt4.Played(plain, once, 2));
            }
        }
    }

    [Fact]
    public void UnitOneStaysWithinAFewPercentOfZx1()
    {
        // k=1 is ZX1's parse with everything moved into its own stream. Splitting
        // by offset width costs two control bits per new-offset match and gives
        // back a byte offset that reaches 512 units instead of 128, so the sizes
        // no longer match exactly - but they must stay close.
        List<byte[]> inputs = Inputs();
        for (int i = 0; i < inputs.Count; i++)
        {
            Compressor.Result packed = Pack(inputs[i], 1);
            int zx1 = Zx1Sizes[i];
            Assert.True(packed.PackedSize <= zx1 + 8 + zx1 / 20,
                $"ST4 k=1 {packed.PackedSize} vs jx1's {zx1}");
        }
    }

    [Fact]
    public void TheHeaderIsTwentyEightBytesAndSaysOnlyWhatCannotBeDerived()
    {
        byte[] input = Encoding.ASCII.GetBytes(
            "a header should hold nothing that follows from the rest");
        foreach (int unit in new[] { 1, 2, 4 })
        {
            Compressor.Result packed = Pack(input, unit);
            byte[] file = Nt4.Container(packed);

            Assert.Equal(28, Format.HeaderSize);
            // A stream that ends has no rewind point: nothing for a caller to do.
            Assert.Equal(Format.NoRewind, LongAt(file, Format.OffsetRewind));
            // The window a decoder needs to tell a match from a copy: here the
            // widest, since the pack had no limit.
            Assert.Equal(Format.MaxOffsetUnits(unit), LongAt(file, Format.OffsetWindow));
            // One long carries magic, version and k, so a decoder built for one
            // unit size checks an asset against itself with a single cmp.l.
            Assert.Equal(Format.Signature(unit), LongAt(file, Format.OffsetSignature));
            Assert.Equal(packed.PaddedSize, LongAt(file, Format.OffsetSize));

            int literalAt = LongAt(file, Format.OffsetLiteral);
            int byteAt = LongAt(file, Format.OffsetByteOffsets);
            int wordAt = LongAt(file, Format.OffsetWordOffsets);
            Assert.Equal(0, literalAt % 4);
            Assert.Equal(0, byteAt % 4);
            Assert.Equal(0, wordAt % 4);

            // The layout is A, B, C, D: the bits, the literal payload right
            // after them, the byte offsets, the word offsets to the end.
            Assert.True(literalAt <= byteAt && byteAt <= wordAt, "streams run A, B, C, D");
            Assert.Equal(Format.HeaderSize + ((packed.Control.Length + 3) & ~3), literalAt);
            Assert.Equal(file.Length, wordAt + packed.WordOffsets.Length);

            // Stream A needs no field: it begins where the header ends. Every
            // other start is one adda.l from the asset's own address.
            Assert.Equal(packed.Control,
                file[Format.HeaderSize..(Format.HeaderSize + packed.Control.Length)]);
            Assert.Equal(packed.Literal, file[literalAt..(literalAt + packed.Literal.Length)]);
            Assert.Equal(packed.ByteOffsets, file[byteAt..(byteAt + packed.ByteOffsets.Length)]);
            Assert.Equal(packed.WordOffsets, file[wordAt..(wordAt + packed.WordOffsets.Length)]);

            // A derived length is the real one, or up to three bytes of padding
            // longer - and nothing reads the padding.
            Format.Container read = Format.Read(file);
            AssertPadded(packed.Control, read.Control);
            AssertPadded(packed.Literal, read.Literal);
            AssertPadded(packed.ByteOffsets, read.ByteOffsets);
            AssertPadded(packed.WordOffsets, read.WordOffsets);
        }
    }

    private static void AssertPadded(byte[] written, byte[] derived)
    {
        Assert.True(derived.Length >= written.Length && derived.Length - written.Length < 4,
            $"derived {derived.Length} from a written {written.Length}");
        Assert.Equal(written, derived[..written.Length]);
    }

    [Fact]
    public void AContainerReadsBackAsTheStreamsThatWentIn()
    {
        // What dnt4 does: header in, four streams out, decoded without being
        // told anything the file does not already say.
        foreach (int unit in new[] { 1, 2, 4 })
        {
            foreach (byte[] input in Inputs())
            {
                byte[] file = Nt4.Container(Pack(input, unit));
                Format.Container read = Format.Read(file);
                Assert.Equal(unit, read.Unit);
                Assert.Equal(Padded(input, unit), Decompressor.Decompress(
                    read.Control, read.Literal, read.ByteOffsets,
                    read.WordOffsets, read.Unit, read.Size));
            }
        }
    }

    [Fact]
    public void ABrokenContainerSaysWhatIsWrongWithIt()
    {
        byte[] good = Nt4.Container(Pack(
            Encoding.ASCII.GetBytes("a container has to be checked"), 2));

        Assert.Throws<InvalidDataException>(() => Format.Read(good[..8]));

        byte[] wrongMagic = (byte[])good.Clone();
        wrongMagic[0] ^= 0xFF;
        Assert.Throws<InvalidDataException>(() => Format.Read(wrongMagic));

        byte[] wrongVersion = (byte[])good.Clone();
        wrongVersion[Format.OffsetSignature + 2] = Format.Version + 1;
        Assert.Throws<InvalidDataException>(() => Format.Read(wrongVersion));

        byte[] wrongUnit = (byte[])good.Clone();
        wrongUnit[Format.OffsetSignature + 3] = 3;
        Assert.Throws<InvalidDataException>(() => Format.Read(wrongUnit));

        byte[] strayStream = (byte[])good.Clone();
        strayStream[Format.OffsetByteOffsets + 1] = 0x7F;
        Assert.Throws<InvalidDataException>(() => Format.Read(strayStream));

        byte[] outOfOrder = (byte[])good.Clone();   // C before B ends
        outOfOrder[Format.OffsetByteOffsets + 3] = 0;
        Assert.Throws<InvalidDataException>(() => Format.Read(outOfOrder));

        byte[] misaligned = (byte[])good.Clone();
        misaligned[Format.OffsetLiteral + 3] += 1;
        Assert.Throws<InvalidDataException>(() => Format.Read(misaligned));

        byte[] strayRewind = (byte[])good.Clone();  // a rewind point past the output
        strayRewind[Format.OffsetRewind + 2] = 0x7F;
        strayRewind[Format.OffsetRewind + 3] = 0;
        Assert.Throws<InvalidDataException>(() => Format.Read(strayRewind));

        byte[] noWindow = (byte[])good.Clone();     // a window no offset could keep to
        noWindow[Format.OffsetWindow + 2] = 0;
        noWindow[Format.OffsetWindow + 3] = 0;
        Assert.Throws<InvalidDataException>(() => Format.Read(noWindow));
    }

    private static int LongAt(byte[] file, int at) =>
        (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
            | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
}
