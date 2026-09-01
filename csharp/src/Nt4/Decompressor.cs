// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// The readable ST4 decoder: what the 68000 versions have to agree with, in
/// C#.
/// </summary>
/// <remarks>
/// It is the ZX1 state machine with three changes. Literals come from stream B
/// and offsets from stream C or D - by width - rather than from the stream the
/// bits live in; every length and offset is counted in units, so each step
/// moves k bytes; and the end marker carries one more bit, which can turn the
/// end into an endless match - the repeat. Malformed or truncated streams
/// throw <see cref="InvalidDataException"/>, where the Java reference trips a
/// <c>-ea</c> assertion.
/// </remarks>
public sealed class Decompressor
{
    private enum State
    {
        Start,
        Literals,
        Match,
        Done,
    }

    private readonly int offsetLimit;
    private readonly byte[] control;
    private readonly byte[] literal;
    private readonly byte[] byteOffsets;
    private readonly byte[] wordOffsets;
    private readonly byte[] output;
    private readonly int unit;
    private int controlIndex;
    private int literalIndex;
    private int byteOffsetIndex;
    private int wordOffsetIndex;
    private int outputIndex;
    private int bitMask;
    private int bitValue;
    private int lastOffset = Optimizer.InitialOffset;
    private int repeatIndex = -1;
    private State state = State.Start;

    private Decompressor(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, byte[] output, int unit, int offsetLimit)
    {
        this.offsetLimit = offsetLimit;
        this.control = control;
        this.literal = literal;
        this.byteOffsets = byteOffsets;
        this.wordOffsets = wordOffsets;
        this.output = output;
        this.unit = unit;
    }

    /// <summary>Decodes the streams into <paramref name="size"/> bytes.</summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <returns>The decoded bytes, padding included.</returns>
    public static byte[] Decompress(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size) =>
        Decompress(control, literal, byteOffsets, wordOffsets, unit, size,
            Format.MaxOffsetUnits(unit));

    /// <summary>
    /// What a decode produced: the output, and how the stream ended. A stream
    /// that repeats reports its loop point - the unit the output continues
    /// from after the last one, so the stream decodes as
    /// <c>units[0..R) units[R..O)</c> forever; a stream that simply ends
    /// reports -1. For a repeating stream any size from one whole pass up is
    /// decodable - the repeat fills whatever the pass itself did not.
    /// </summary>
    /// <param name="Output">The decoded bytes, padding included.</param>
    /// <param name="RepeatIndex">The loop point as a unit index, or -1.</param>
    public sealed record Decoded(byte[] Output, int RepeatIndex);

    /// <summary>
    /// As above, refusing any back-reference further than
    /// <paramref name="offsetLimit"/> units. An offset within the limit is
    /// exactly what makes a stream safe for a ring of that many units, so this
    /// is how tests hold a <c>-mN</c> stream to its ring without a ring in
    /// sight.
    /// </summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <param name="offsetLimit">The furthest back any offset may reach, in units.</param>
    /// <returns>The decoded bytes, padding included.</returns>
    /// <exception cref="ArgumentNullException">A stream is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="unit"/> is not 1, 2 or 4.</exception>
    /// <exception cref="ArgumentException"><paramref name="size"/> is not a whole number of units.</exception>
    /// <exception cref="InvalidDataException">
    /// The streams are malformed or truncated, or reach further back than
    /// <paramref name="offsetLimit"/>.
    /// </exception>
    public static byte[] Decompress(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size, int offsetLimit) =>
        Decode(control, literal, byteOffsets, wordOffsets, unit, size, offsetLimit).Output;

    /// <summary>As <see cref="Decompress(byte[], byte[], byte[], byte[], int, int, int)"/>,
    /// also reporting whether the stream repeats.</summary>
    /// <param name="control">Stream A, the bits.</param>
    /// <param name="literal">Stream B, the literal payload.</param>
    /// <param name="byteOffsets">Stream C, one byte per offset.</param>
    /// <param name="wordOffsets">Stream D, one word per offset.</param>
    /// <param name="unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="size">The output size in bytes, a multiple of the unit.</param>
    /// <param name="offsetLimit">The furthest back any offset may reach, in units.</param>
    /// <returns>The decoded bytes and the repeat offset, zero when the stream ends.</returns>
    /// <exception cref="ArgumentNullException">A stream is null.</exception>
    /// <exception cref="ArgumentOutOfRangeException"><paramref name="unit"/> is not 1, 2 or 4.</exception>
    /// <exception cref="ArgumentException"><paramref name="size"/> is not a whole number of units.</exception>
    /// <exception cref="InvalidDataException">
    /// The streams are malformed or truncated, or reach further back than
    /// <paramref name="offsetLimit"/>.
    /// </exception>
    public static Decoded Decode(byte[] control, byte[] literal, byte[] byteOffsets,
        byte[] wordOffsets, int unit, int size, int offsetLimit)
    {
        ArgumentNullException.ThrowIfNull(control);
        ArgumentNullException.ThrowIfNull(literal);
        ArgumentNullException.ThrowIfNull(byteOffsets);
        ArgumentNullException.ThrowIfNull(wordOffsets);
        if (!Format.IsUnitSize(unit))
        {
            throw new ArgumentOutOfRangeException(nameof(unit), unit, Format.CheckUnit(unit));
        }
        if (size < 0 || size % unit != 0)
        {
            throw new ArgumentException(
                $"output size {size} is not a whole number of {unit}-byte units",
                nameof(size));
        }
        var decoder = new Decompressor(control, literal, byteOffsets, wordOffsets,
            new byte[size], unit, offsetLimit);
        decoder.Run();
        return new Decoded(decoder.output, decoder.repeatIndex);
    }

    private void Run()
    {
        while (state != State.Done)
        {
            switch (state)
            {
                case State.Start:
                    BeginLiterals();
                    break;
                case State.Literals:
                    if (ReadBit())
                    {
                        BeginMatchFromNewOffset();
                    }
                    else
                    {
                        BeginMatchFromLastOffset();
                    }
                    break;
                case State.Match:
                    if (ReadBit())
                    {
                        BeginMatchFromNewOffset();
                    }
                    else
                    {
                        BeginLiterals();
                    }
                    break;
                case State.Done:
                default:
                    throw new InvalidOperationException("unreachable");
            }
        }
        if (outputIndex != output.Length)
        {
            throw new InvalidDataException("the streams did not fill the output");
        }
    }

    private void BeginLiterals()
    {
        int length = ReadInterlacedEliasGamma();
        if (literalIndex + length * unit > literal.Length)
        {
            throw new InvalidDataException("truncated literal stream");
        }
        for (int i = 0; i < length * unit; i++)
        {
            output[outputIndex++] = literal[literalIndex++];
        }
        state = State.Literals;
    }

    private void BeginMatchFromLastOffset()
    {
        Copy(ReadInterlacedEliasGamma());
        state = State.Match;
    }

    private void BeginMatchFromNewOffset()
    {
        // Two class bits: byte or word, then which bank - or, for a word, the
        // one code that means the stream is over.
        if (ReadBit())
        {
            int bank = ReadBit() ? 1 : 0;
            if (byteOffsetIndex >= byteOffsets.Length)
            {
                throw new InvalidDataException("truncated byte offsets");
            }
            lastOffset = bank * 256 + 256 - byteOffsets[byteOffsetIndex++];
        }
        else
        {
            if (ReadBit())
            {
                EndOrRepeat();
                return;
            }
            lastOffset = ReadWordOffset();
        }
        if (lastOffset <= 0)
        {
            throw new InvalidDataException("an offset must reach back at least one unit");
        }
        if (lastOffset > offsetLimit)
        {
            throw new InvalidDataException(
                $"offset {lastOffset} units reaches past the {offsetLimit}-unit limit");
        }
        Copy(ReadInterlacedEliasGamma() + 1);
        state = State.Match;
    }

    /// <summary>
    /// The end code's extra bit: a plain end, or the repeat - one last word
    /// offset from stream D, matched until the output the caller asked for is
    /// full. The 68000 decoders run the same match 65535 units at a time,
    /// re-armed forever.
    /// </summary>
    private void EndOrRepeat()
    {
        if (ReadBit())
        {
            // Stream D holds the distance back to the loop point; the loop
            // point itself is where the pass so far ends, minus that.
            int distance = ReadWordOffset();
            if (distance <= 0)
            {
                throw new InvalidDataException("a repeat must reach back at least one unit");
            }
            if (distance > offsetLimit)
            {
                throw new InvalidDataException(
                    $"the loop distance {distance} units reaches past the "
                    + $"{offsetLimit}-unit limit");
            }
            repeatIndex = (outputIndex / unit) - distance;
            if (repeatIndex < 0)
            {
                throw new InvalidDataException("the loop point must be a unit of the stream");
            }
            lastOffset = distance;
            int remaining = (output.Length - outputIndex) / unit;
            if (remaining > 0)
            {
                Copy(remaining);
            }
        }
        state = State.Done;
    }

    private int ReadWordOffset()
    {
        if (wordOffsetIndex + 2 > wordOffsets.Length)
        {
            throw new InvalidDataException("truncated word offsets");
        }
        int scaled = wordOffsets[wordOffsetIndex] << 8
            | wordOffsets[wordOffsetIndex + 1];
        wordOffsetIndex += 2;
        return ((1 << 16) - scaled) / unit;   // stored as -offset * unit
    }

    /// <summary>Copies <paramref name="length"/> units from <c>lastOffset</c> units back.</summary>
    private void Copy(int length)
    {
        int distance = lastOffset * unit;
        if (distance > outputIndex)
        {
            throw new InvalidDataException("match reaches before the output");
        }
        if (outputIndex + length * unit > output.Length)
        {
            throw new InvalidDataException("the streams overfill the output");
        }
        for (int i = 0; i < length * unit; i++)
        {
            output[outputIndex] = output[outputIndex - distance];
            outputIndex++;
        }
    }

    private int ReadControl()
    {
        if (controlIndex >= control.Length)
        {
            throw new InvalidDataException("truncated control stream");
        }
        return control[controlIndex++];
    }

    private bool ReadBit()
    {
        bitMask >>= 1;
        if (bitMask == 0)
        {
            bitMask = 128;
            bitValue = ReadControl();
        }
        return (bitValue & bitMask) != 0;
    }

    private int ReadInterlacedEliasGamma()
    {
        int value = 1;
        while (ReadBit())
        {
            value = value << 1 | (ReadBit() ? 1 : 0);
        }
        return value;
    }
}
