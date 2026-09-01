// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>Writes an ST4 parse out as four streams.</summary>
/// <remarks>
/// <para>Stream A carries nothing but bits - the block-type flags and the
/// gamma lengths. Stream D carries nothing but literal units. Streams B and C
/// carry the offsets, split by width: bytes in B, words in C. A word offset is
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
/// <para>A copy from the literal stream is written as a match whose offset lies
/// beyond the window: the window plus the number of literal units between the
/// copy's source and the copy, which is what the decoder walks back from its
/// literal read pointer. The parse names the source by its output position;
/// the count is taken here, from the literals actually written so far. A copy
/// must be strictly shorter than that count, because the decoder advances the
/// offset by what it copies and must never see it reach zero; the one copy
/// that would be exactly as long gives up its last unit to a literal.</para>
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
    /// <param name="Literal">Stream D, the literal payload, whole units.</param>
    /// <param name="ByteOffsets">Stream B, one byte per offset.</param>
    /// <param name="WordOffsets">Stream C, one word per offset.</param>
    /// <param name="Unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="PaddedSize">The output size in bytes, a multiple of the unit.</param>
    /// <param name="LongestOp">Longest literal run or match emitted, in units.</param>
    /// <param name="Operations">How many operations the streams hold.</param>
    /// <param name="RewindIndex">The loop point of a stream the caller loops by rewind, in units, or -1.</param>
    /// <param name="Window">The window the parse kept to, in units: what the header records.</param>
    /// <param name="Copies">How many blocks copied from the literal stream.</param>
    /// <param name="ControlBits">Bits written to stream A before padding.</param>
    /// <param name="RepeatWord">Whether stream C ends with the repeat's word.</param>
    public sealed record Result(byte[] Control, byte[] Literal, byte[] ByteOffsets,
        byte[] WordOffsets, int Unit, int PaddedSize, int LongestOp, int Operations,
        int RewindIndex, int Window, int Copies, int ControlBits, bool RepeatWord)
    {
        /// <summary>Bytes all four streams take together, which is what a comparison wants.</summary>
        public int PackedSize =>
            Control.Length + Literal.Length + ByteOffsets.Length + WordOffsets.Length;

        /// <summary>
        /// Bits the parse itself cost: everything written but the end code and
        /// its repeat bit, and stream A's padding - what a parse's chain counts.
        /// </summary>
        public int Bits =>
            ControlBits - 4 + 8 * (Literal.Length + ByteOffsets.Length + WordOffsets.Length)
                - (RepeatWord ? 16 : 0);
    }

    private readonly int[] units;
    private readonly int unit;
    private readonly int window;
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
    private int bitsWritten;
    private int longestOp;
    private int operations;
    private int copies;

    // The walk: where the next unit comes from, the literal run gathered but
    // not yet written, the offset the stream currently holds, whether the
    // first block - which has no flag - is still to come, and how many
    // literal units precede each position written so far.
    private int readIndex;
    private int pendingLiterals;
    private int lastOffset = Optimizer.InitialOffset;
    private bool first = true;
    private readonly int[] literalsBefore;

    private Compressor(int[] units, int unit, int window)
    {
        this.units = units;
        this.unit = unit;
        this.window = window;
        this.literal = new byte[Math.Max(unit, units.Length * unit)];
        this.literalsBefore = new int[units.Length + 1];
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
    /// end. What stream C stores is the distance O-R back to the loop point,
    /// an offset like any other, so the caller holds it to the window the
    /// stream was packed for.
    /// </summary>
    /// <param name="optimal">Final block of a parse of <paramref name="units"/>.</param>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <param name="repeatIndex">The loop point as a unit index, or -1 for a plain end.</param>
    /// <returns>The four streams and their metadata.</returns>
    public static Result Compress(Block optimal, int[] units, int unit, int maxOpLength,
        int repeatIndex) =>
        Compress(optimal, units, unit, maxOpLength, repeatIndex, Format.MaxOffsetUnits(unit));

    /// <summary>
    /// As above, for a parse made at <paramref name="window"/> units: an offset
    /// beyond it is a copy from the literal stream, so the parse's matches
    /// must keep within it and its copies are written past it.
    /// </summary>
    /// <param name="optimal">Final block of a parse of <paramref name="units"/>.</param>
    /// <param name="units">The input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <param name="repeatIndex">The loop point as a unit index, or -1 for a plain end.</param>
    /// <param name="window">The window the parse kept to, in units.</param>
    /// <returns>The four streams and their metadata.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="optimal"/> or <paramref name="units"/> is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// <paramref name="repeatIndex"/> is not a unit of the stream itself.
    /// </exception>
    public static Result Compress(Block optimal, int[] units, int unit, int maxOpLength,
        int repeatIndex, int window)
    {
        ArgumentNullException.ThrowIfNull(optimal);
        ArgumentNullException.ThrowIfNull(units);
        if (repeatIndex < -1 || repeatIndex >= units.Length)
        {
            throw new ArgumentOutOfRangeException(nameof(repeatIndex), repeatIndex,
                "the loop point must be a unit of the stream itself");
        }
        return new Compressor(units, unit, window).Run([optimal], maxOpLength, repeatIndex, -1);
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
    public static Result CompressRewinding(Block? intro, Block loop, int[] units, int unit,
        int maxOpLength, int rewindIndex) =>
        CompressRewinding(intro, loop, units, unit, maxOpLength, rewindIndex,
            Format.MaxOffsetUnits(unit));

    /// <summary>As above, for parses made at <paramref name="window"/> units.</summary>
    /// <param name="intro">Final block of a parse of the units before the loop, or null when there are none.</param>
    /// <param name="loop">Final block of a parse of the loop's units on their own.</param>
    /// <param name="units">The whole input as k-byte units.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="maxOpLength">Positive maximum length requested for each operation, in units.</param>
    /// <param name="rewindIndex">The loop point as a unit index.</param>
    /// <param name="window">The window the parses kept to, in units.</param>
    /// <returns>The four streams and their metadata.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="loop"/> or <paramref name="units"/> is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException">
    /// <paramref name="rewindIndex"/> is not a unit of the stream, or an intro is given exactly when there is none.
    /// </exception>
    public static Result CompressRewinding(Block? intro, Block loop, int[] units, int unit,
        int maxOpLength, int rewindIndex, int window)
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
        return new Compressor(units, unit, window).Run(chains, maxOpLength, -1, rewindIndex);
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
                if (block.Offset < 0)
                {
                    Copy(-block.Offset, length, maxOpLength);
                    continue;
                }
                int offset = block.Offset;
                if (offset > window)
                {
                    throw new InvalidOperationException("a match reaches past the window");
                }
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
                    EmitMatch(offset, size, rep);
                }
            }
        }
        FlushLiterals();
        if (readIndex != units.Length)
        {
            throw new InvalidOperationException("the parses did not cover the input");
        }

        // End marker, then the repeat bit: end for good, or install one last
        // word offset from stream C - the distance back to the loop point -
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
            units.Length * unit, longestOp, operations, rewindIndex, window, copies,
            bitsWritten, repeatIndex >= 0);
    }

    /// <summary>
    /// A copy from the literal stream, <paramref name="distance"/> units back
    /// in the output for <paramref name="length"/> units, in pieces the
    /// counters can hold. Each piece is written as a match at the window plus
    /// the literals between its source and itself; a piece that would be
    /// exactly as long as that count gives up its last unit to a literal, so
    /// the decoder's offset - which it advances by what it copies - never
    /// reaches zero.
    /// </summary>
    private void Copy(int distance, int length, int maxOpLength)
    {
        int pieces = maxOpLength < 3 ? 1 : (length - 1) / maxOpLength + 1;
        int baseSize = length / pieces;
        int wider = length % pieces;
        for (int piece = 0; piece < pieces; piece++)
        {
            int size = baseSize + (piece < wider ? 1 : 0);
            int start = readIndex + pendingLiterals;
            int source = start - distance;
            if (LiteralsAt(source + size) - LiteralsAt(source) != size)
            {
                throw new InvalidOperationException("a copy's source must be literal");
            }
            int back = LiteralsAt(start) - LiteralsAt(source);
            int given = 0;
            if (back == size)
            {
                if (size - 1 < 2)
                {
                    pendingLiterals += size;        // too short to write at all
                    continue;
                }
                given = 1;
                size--;
            }
            int wire = window + back;
            if (wire > Format.MaxOffsetUnits(unit))
            {
                throw new InvalidOperationException("a copy reaches past the offsets");
            }
            bool rep = pendingLiterals > 0 && wire == lastOffset;
            FlushLiterals();
            EmitMatch(wire, size, rep);
            lastOffset = wire - size;               // where the decoder leaves it
            copies++;
            pendingLiterals += given;
        }
    }

    /// <summary>
    /// Literal units before <paramref name="position"/>: recorded for what is
    /// written, counted for the run still pending.
    /// </summary>
    private int LiteralsAt(int position) =>
        position <= readIndex ? literalsBefore[position]
            : literalsBefore[readIndex] + (position - readIndex);

    private void EmitMatch(int offset, int size, bool rep)
    {
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
        for (int i = 0; i < size; i++)
        {
            literalsBefore[readIndex + i + 1] = literalsBefore[readIndex];
        }
        operations++;
        readIndex += size;
        longestOp = Math.Max(longestOp, size);
    }

    /// <summary>
    /// Writes the literal run gathered so far, if there is one: its flag -
    /// unless it opens the stream - its length, and its units into stream D.
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
            Units.Write(literal, literalIndex, units[readIndex], unit);
            literalIndex += unit;
            literalsBefore[readIndex + 1] = literalsBefore[readIndex] + 1;
            readIndex++;
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
        bitsWritten++;
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
