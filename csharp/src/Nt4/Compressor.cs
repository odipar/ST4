// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>Writes an ST4 parse out as four streams.</summary>
/// <remarks>
/// <para>Stream A carries nothing but bits - the block-type flags and the
/// gamma lengths. Stream B carries nothing but literal units. Streams C and D
/// carry the offsets, split by width: bytes in C, words in D. A word offset is
/// written as <c>-offset * unit</c>, which is exactly what the 68000 decoders
/// keep in a register; a byte offset is written as
/// <c>bank * 256 + 256 - offset</c>, pre-negated the same way. Stream A is
/// padded to an even length, because the 68000 decoders refill their bit queue
/// with a <c>move.w</c>.</para>
/// <para>Matches longer than the operation limit are split: the 68000 decoders
/// count an operation's remaining length in a word, so nothing may exceed
/// 65535 units. A literal run cannot be split - after a literal run a 0 bit
/// means a match, so the format has no way to say "more literals" - and
/// <see cref="Result.LongestOp"/> reports what actually came out.</para>
/// <para>A stream may be written from more than one parse, back to back: that
/// is how a loop longer than the window is packed, the intro and the loop each
/// parsed on their own so that nothing in the loop reaches before it. Two
/// literal runs that meet at the seam become one, since the format cannot say
/// "literals again"; and a one-unit match that the loop's parse meant as a rep
/// of the stream's initial offset goes out as a literal when the intro left a
/// different offset behind. Every flag is derived from the stream as actually
/// written, so the seam is otherwise just a block boundary.</para>
/// </remarks>
public sealed class Compressor
{
    /// <summary>The four streams, and what the caller needs to know about them.</summary>
    /// <param name="Control">Stream A, the bits, padded to an even length.</param>
    /// <param name="Literal">Stream B, the literal payload, whole units.</param>
    /// <param name="ByteOffsets">Stream C, one byte per offset.</param>
    /// <param name="WordOffsets">Stream D, one word per offset.</param>
    /// <param name="Unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="PaddedSize">The output size in bytes, a multiple of the unit.</param>
    /// <param name="LongestOp">Longest literal run or match emitted, in units.</param>
    /// <param name="Operations">How many operations the streams hold.</param>
    /// <param name="RewindIndex">The loop point of a stream the caller loops by rewind, in units, or -1.</param>
    public sealed record Result(byte[] Control, byte[] Literal, byte[] ByteOffsets,
        byte[] WordOffsets, int Unit, int PaddedSize, int LongestOp, int Operations,
        int RewindIndex)
    {
        /// <summary>Bytes all four streams take together, which is what a comparison wants.</summary>
        public int PackedSize =>
            Control.Length + Literal.Length + ByteOffsets.Length + WordOffsets.Length;
    }

    private readonly int[] units;
    private readonly int unit;
    private byte[] control = new byte[256];
    private int controlIndex;
    private byte[] literal;
    private int literalIndex;
    private byte[] byteOffsets = new byte[64];
    private int byteOffsetIndex;
    private byte[] wordOffsets = new byte[64];
    private int wordOffsetIndex;
    private int bitMask;
    private int bitIndex;
    private int longestOp;
    private int operations;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream currently holds, and whether the
    // first block - which has no flag - is still to come.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = Optimizer.InitialOffset;
    private bool first = true;

    private Compressor(int[] units, int unit)
    {
        this.units = units;
        this.unit = unit;
        this.literal = new byte[Math.Max(unit, units.Length * unit)];
    }

    /// <summary>Compresses an optimal parse into the four streams.</summary>
    /// <param name="optimal">Final block of a parse of <paramref name="units"/>.</param>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <returns>The four streams and their metadata.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="optimal"/> or <paramref name="units"/> is null.</exception>
    public static Result Compress(Block optimal, int[] units, int unit, int maxOpLength) =>
        Compress(optimal, units, unit, maxOpLength, -1);

    /// <summary>
    /// As above, but the stream ends by repeating instead of stopping: the
    /// container encodes the infinite input <c>units[0..R) units[R..O)</c>
    /// repeated forever, so after its last unit the output continues from
    /// unit <paramref name="repeatIndex"/> and never stops. -1 means a plain
    /// end. What stream D stores is the distance O-R back to the loop point,
    /// an offset like any other, so the caller holds it to the window the
    /// stream was packed for.
    /// </summary>
    /// <param name="optimal">Final block of a parse of <paramref name="units"/>.</param>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <param name="repeatIndex">The loop point as a unit index, or -1 for a plain end.</param>
    /// <returns>The four streams and their metadata.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="optimal"/> or <paramref name="units"/> is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// <paramref name="repeatIndex"/> is not a unit of the stream itself.
    /// </exception>
    public static Result Compress(Block optimal, int[] units, int unit, int maxOpLength,
        int repeatIndex)
    {
        ArgumentNullException.ThrowIfNull(optimal);
        ArgumentNullException.ThrowIfNull(units);
        if (repeatIndex < -1 || repeatIndex >= units.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(repeatIndex), repeatIndex,
                "the loop point must be a unit of the stream itself");
        }
        return new Compressor(units, unit).Run([optimal], maxOpLength, repeatIndex, -1);
    }

    /// <summary>
    /// A stream that loops by rewind, for a loop longer than the window: the
    /// intro <c>units[0..R)</c> and the loop <c>units[R..O)</c> come from
    /// separate parses - <paramref name="intro"/> is null when R is 0 - so no
    /// match in the loop reaches before it, and the caller can replay the
    /// stream from the state it saved at unit <paramref name="rewindIndex"/>
    /// every time the output reaches O. The stream ends plainly; the rewind
    /// point goes in the header.
    /// </summary>
    /// <param name="intro">Final block of a parse of the units before the loop, or null when there are none.</param>
    /// <param name="loop">Final block of a parse of the loop's units on their own.</param>
    /// <param name="units">The whole input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <param name="rewindIndex">The loop point as a unit index.</param>
    /// <returns>The four streams and their metadata.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="loop"/> or <paramref name="units"/> is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// <paramref name="rewindIndex"/> is not a unit of the stream, or an intro is given exactly when there is none.
    /// </exception>
    public static Result CompressRewinding(Block? intro, Block loop, int[] units, int unit,
        int maxOpLength, int rewindIndex)
    {
        ArgumentNullException.ThrowIfNull(loop);
        ArgumentNullException.ThrowIfNull(units);
        if (rewindIndex < 0 || rewindIndex >= units.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(rewindIndex), rewindIndex,
                "the rewind point must be a unit of the stream itself");
        }
        if ((intro == null) != (rewindIndex == 0))
        {
            throw new ArgumentOutOfRangeException(nameof(intro), "an intro exactly when there is one");
        }
        Block[] chains = intro == null ? [loop] : [intro, loop];
        return new Compressor(units, unit).Run(chains, maxOpLength, -1, rewindIndex);
    }

    private Result Run(Block[] chains, int maxOpLength, int repeatIndex, int rewindIndex)
    {
        foreach (Block chain in chains)
        {
            // Un-reverse the chain; its head is the parser's fake block.
            var blocks = new Stack<Block>();
            for (Block? block = chain; block != null; block = block.Chain)
            {
                blocks.Push(block);
            }
            Block previous = blocks.Pop();

            foreach (Block block in blocks)
            {
                int length = block.Index - previous.Index;
                previous = block;

                if (block.Offset == 0)
                {
                    pendingLiterals += length;      // runs merge across a seam
                    continue;
                }
                int offset = block.Offset;
                // Split evenly rather than greedily: every piece after the first
                // has to be a new-offset match, and those cannot be shorter than
                // two units, so a greedy remainder of one would be unwritable.
                int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
                int baseSize = length / pieces;
                int wider = length % pieces;
                for (int piece = 0; piece < pieces; piece++)
                {
                    int size = baseSize + (piece < wider ? 1 : 0);
                    bool rep = pendingLiterals > 0 && offset == lastOffset;
                    if (size == 1 && !rep)
                    {
                        pendingLiterals++;          // the seam's one-unit rep
                        continue;
                    }
                    FlushLiterals();
                    if (rep)
                    {
                        WriteBit(false);
                        WriteInterlacedEliasGamma(size);
                    }
                    else
                    {
                        WriteBit(true);
                        WriteOffsetOf(offset);
                        WriteInterlacedEliasGamma(size - 1);
                        lastOffset = offset;
                    }
                    operations++;
                    readIndex += size;
                    longestOp = Math.Max(longestOp, size);
                }
            }
        }
        FlushLiterals();
        if (readIndex != units.Length)
        {
            throw new InvalidOperationException("the parses did not cover the input");
        }

        // End marker, then the repeat bit: end for good, or install one last
        // word offset from stream D - the distance back to the loop point -
        // and match it forever.
        WriteBit(true);
        WriteBit(false);
        WriteBit(true);
        WriteBit(repeatIndex >= 0);
        if (repeatIndex >= 0)
        {
            int scaled = (units.Length - repeatIndex) * unit;
            if (wordOffsetIndex + 2 > wordOffsets.Length)
            {
                Array.Resize(ref wordOffsets, wordOffsets.Length * 2);
            }
            wordOffsets[wordOffsetIndex++] = unchecked((byte)(-scaled >> 8));
            wordOffsets[wordOffsetIndex++] = unchecked((byte)-scaled);
        }

        return new Result(control[..(controlIndex + (controlIndex & 1))],
            literal[..literalIndex], byteOffsets[..byteOffsetIndex],
            wordOffsets[..wordOffsetIndex], unit,
            units.Length * unit, longestOp, operations, rewindIndex);
    }

    /// <summary>
    /// Writes the literal run gathered so far, if there is one: its flag -
    /// unless it opens the stream - its length, and its units into stream B.
    /// </summary>
    private void FlushLiterals()
    {
        if (pendingLiterals == 0)
        {
            return;
        }
        if (first)
        {
            first = false;                          // a stream opens with literals
        }
        else
        {
            WriteBit(false);
        }
        WriteInterlacedEliasGamma(pendingLiterals);
        for (int i = 0; i < pendingLiterals; i++)
        {
            Units.Write(literal, literalIndex, units[readIndex++], unit);
            literalIndex += unit;
        }
        operations++;
        longestOp = Math.Max(longestOp, pendingLiterals);
        pendingLiterals = 0;
    }

    /// <summary>
    /// The two class bits, then the offset itself into whichever stream it
    /// belongs to. The class bits are also what keeps the operation an even
    /// number of bits long, which is what lets the decoder skip refill checks.
    /// </summary>
    private void WriteOffsetOf(int offset)
    {
        if (offset <= Format.ByteOffsetLimit)
        {
            int bank = (offset - 1) / 256;              // 0 for 1..256, 1 for 257..512
            WriteBit(true);
            WriteBit(bank != 0);
            if (byteOffsetIndex == byteOffsets.Length)
            {
                Array.Resize(ref byteOffsets, byteOffsets.Length * 2);
            }
            byteOffsets[byteOffsetIndex++] = unchecked((byte)(bank * 256 + 256 - offset));
        }
        else
        {
            int scaled = offset * unit;
            WriteBit(false);
            WriteBit(false);
            if (wordOffsetIndex + 2 > wordOffsets.Length)
            {
                Array.Resize(ref wordOffsets, wordOffsets.Length * 2);
            }
            wordOffsets[wordOffsetIndex++] = unchecked((byte)(-scaled >> 8));
            wordOffsets[wordOffsetIndex++] = unchecked((byte)-scaled);
        }
    }

    private void WriteControl(int value)
    {
        if (controlIndex == control.Length)
        {
            Array.Resize(ref control, control.Length * 2);
        }
        control[controlIndex++] = unchecked((byte)value);
    }

    /// <summary>
    /// Bits live in stream A, in the byte reserved when the reservoir ran dry -
    /// so a set bit patches that byte where it already sits.
    /// </summary>
    private void WriteBit(bool value)
    {
        if (bitMask == 0)
        {
            bitMask = 128;
            bitIndex = controlIndex;
            WriteControl(0);
        }
        if (value)
        {
            control[bitIndex] |= unchecked((byte)bitMask);
        }
        bitMask >>= 1;
    }

    private void WriteInterlacedEliasGamma(int value)
    {
        for (int bit = HighestOneBit(value) >> 1; bit != 0; bit >>= 1)
        {
            WriteBit(true);
            WriteBit((value & bit) != 0);
        }
        WriteBit(false);
    }

    private static int HighestOneBit(int value) =>
        value == 0 ? 0 : 1 << (31 - System.Numerics.BitOperations.LeadingZeroCount((uint)value));
}
