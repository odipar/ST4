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

    /// <summary>
    /// ZX1's packed size for each of <see cref="Inputs"/>, recorded from jx1
    /// in odipar/ST1 at commit 132aef0, exactly as the Java suite holds them.
    /// </summary>
    private static readonly int[] Zx1Sizes = [4, 6, 1006, 6, 19, 383, 26];

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
    public void TheHeaderIsTwentyBytesAndSaysOnlyWhatCannotBeDerived()
    {
        byte[] input = Encoding.ASCII.GetBytes(
            "a header should hold nothing that follows from the rest");
        foreach (int unit in new[] { 1, 2, 4 })
        {
            Compressor.Result packed = Pack(input, unit);
            byte[] file = Nt4.Container(packed);

            Assert.Equal(20, Format.HeaderSize);
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

            // The layout is A, C, D, B: the literal payload runs to the end of
            // the file, so it borders whatever the caller loads after it.
            Assert.True(byteAt <= wordAt && wordAt <= literalAt, "streams run A, C, D, B");
            Assert.Equal(file.Length, literalAt + packed.Literal.Length);

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

        byte[] outOfOrder = (byte[])good.Clone();   // C before the header ends
        outOfOrder[Format.OffsetByteOffsets + 3] = 0;
        Assert.Throws<InvalidDataException>(() => Format.Read(outOfOrder));

        byte[] misaligned = (byte[])good.Clone();
        misaligned[Format.OffsetLiteral + 3] += 1;
        Assert.Throws<InvalidDataException>(() => Format.Read(misaligned));
    }

    private static int LongAt(byte[] file, int at) =>
        (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
            | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
}
