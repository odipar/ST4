// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// The ST4 container format: ZX1's three block types at a chosen unit
/// granularity, split across four streams so a 68000 can read each of them the
/// fastest way that exists for it.
/// </summary>
/// <remarks>
/// <para>Stream A holds nothing but bits - the block-type flags and the
/// interlaced Elias gamma lengths - so its reservoir refills a word at a time.
/// Stream B holds the byte offsets, stream C the word offsets and stream D the
/// literal payload, so each stream is uniform and C is word-aligned by
/// construction. Lengths and offsets count units of k bytes, where k is 1, 2
/// or 4; the Java <c>St4Format</c> is the reference and documents the control
/// codes and the reasoning at length.</para>
/// <para>The header is twenty-eight bytes and holds only what cannot be worked
/// out: a signature packing magic, version and k into one long, the padded
/// output size, where streams B, C and D begin relative to the header, the
/// rewind point, and the window. Stream A begins where the header ends, and
/// each stream runs to the next, A, B, C, D as they lie: the literal payload
/// comes last, so it runs to the end of the file and borders whatever the
/// caller loads after the container - a ring buffer, say.</para>
/// <para>An offset of at most the window M is a match; one beyond M copies
/// from the literal stream, offset minus M units behind the literal read
/// pointer, leaving the pointer where it was and advancing the offset by what
/// it copied - so a rep after a copy resumes just past it, and a copy is
/// strictly shorter than its distance. Streams packed without copies never
/// exceed M and decode as they always did.</para>
/// <para>The rewind point is how a stream loops when its loop is longer than
/// the window: the caller saves the decoder's registers when the output
/// reaches it and restores them, all but the write pointer, when it reaches O,
/// every pass. The packer parses the loop on its own so no match in it reaches
/// before the rewind point, and every pass sees the same history. The field is
/// $FFFFFFFF when the caller has nothing to do - the stream ends, or loops by
/// itself.</para>
/// <para>The end marker carries one extra bit. A 0 ends the stream; a 1 means
/// it repeats from a loop point R - the container encodes the infinite input
/// <c>units[0..R) units[R..O)</c> repeated forever. Stream C stores the
/// distance O-R back to the loop point as its one last word, and the stream
/// becomes an endless match at it.</para>
/// </remarks>
public static class Format
{
    /// <summary><c>'S4'</c>, the top half of every signature.</summary>
    public const int Magic = 0x53340000;

    /// <summary>
    /// Version 6 let an offset beyond the window copy from the literal stream,
    /// and recorded the window in the header. Version 5 laid the streams out
    /// in file order with the literal payload last - A, B, C, D as they lie -
    /// and gave the end marker its repeat bit and the header its rewind point.
    /// Version 4 cut the header to what cannot be derived.
    /// </summary>
    public const int Version = 6;

    /// <summary>Byte offset of the signature long in a container.</summary>
    public const int OffsetSignature = 0;

    /// <summary>Byte offset of the padded output size.</summary>
    public const int OffsetSize = 4;

    /// <summary>Byte offset of stream B's header-relative position: the byte offsets.</summary>
    public const int OffsetByteOffsets = 8;

    /// <summary>Byte offset of stream C's header-relative position: the word offsets.</summary>
    public const int OffsetWordOffsets = 12;

    /// <summary>Byte offset of stream D's header-relative position: the literals.</summary>
    public const int OffsetLiteral = 16;

    /// <summary>Byte offset of the rewind point, in bytes like the size.</summary>
    public const int OffsetRewind = 20;

    /// <summary>Byte offset of the window, in units.</summary>
    public const int OffsetWindow = 24;

    /// <summary>Twenty-eight bytes; stream A begins where the header ends.</summary>
    public const int HeaderSize = 28;

    /// <summary>The rewind field of a stream that ends or loops by itself.</summary>
    public const int NoRewind = -1;

    /// <summary>
    /// The furthest any offset reaches, in BYTES. A word offset is stored as
    /// <c>-offset * k</c>, which the decoder installs unchanged, so the limit
    /// is what fits a signed word rather than anything about the format.
    /// </summary>
    public const int MaxOffset = 32_512;

    /// <summary>The furthest a byte offset reaches, in units: two banks of 256.</summary>
    public const int ByteOffsetLimit = 512;

    /// <summary>The longest operation the 68000 decoders can count, in units.</summary>
    public const int MaxOp = 65_535;

    /// <summary>
    /// Magic, version and unit size in one long, so a decoder built for one k
    /// checks an asset against itself with a single <c>cmp.l</c>.
    /// </summary>
    public static int Signature(int unit) => Magic | (Version << 8) | unit;

    /// <summary>Whether <paramref name="unit"/> is a unit size the format has.</summary>
    public static bool IsUnitSize(int unit) => unit is 1 or 2 or 4;

    /// <summary>The reason <paramref name="unit"/> cannot be used, or an empty string.</summary>
    public static string CheckUnit(int unit) =>
        IsUnitSize(unit) ? "" : $"unit size {unit} is not 1, 2 or 4";

    /// <summary>How far back a match may reach at this unit size, in units.</summary>
    public static int MaxOffsetUnits(int unit) => MaxOffset / unit;

    /// <summary>What a container holds: the four streams, the unit size and the output size.</summary>
    /// <param name="Unit">Bytes per unit: 1, 2 or 4.</param>
    /// <param name="Size">The padded output size in bytes, a multiple of the unit.</param>
    /// <param name="Control">Stream A, the bits.</param>
    /// <param name="Literal">Stream D, the literal payload.</param>
    /// <param name="ByteOffsets">Stream B, one byte per offset.</param>
    /// <param name="WordOffsets">Stream C, one word per offset.</param>
    /// <param name="Rewind">The rewind point in bytes, or <see cref="NoRewind"/>.</param>
    /// <param name="Window">The window in units: matches within it, copies from D beyond.</param>
    public sealed record Container(int Unit, int Size, byte[] Control, byte[] Literal,
        byte[] ByteOffsets, byte[] WordOffsets, int Rewind, int Window);

    /// <summary>
    /// Reads a container, checking everything a decoder would otherwise trust.
    /// The streams it returns may carry up to three bytes of alignment padding,
    /// since no length is stored and each stream simply runs to the next.
    /// </summary>
    /// <param name="file">The complete container, header first.</param>
    /// <returns>The unit size, output size and the four streams.</returns>
    /// <exception cref="ArgumentNullException"><paramref name="file"/> is null.</exception>
    /// <exception cref="InvalidDataException">
    /// It is not an ST4 file this build understands, or the offsets do not
    /// describe four streams laid out in order inside it.
    /// </exception>
    public static Container Read(byte[] file)
    {
        ArgumentNullException.ThrowIfNull(file);
        if (file.Length < HeaderSize)
        {
            throw new InvalidDataException("too short to be an ST4 file");
        }
        int signature = LongAt(file, OffsetSignature);
        if ((signature & unchecked((int)0xFFFF0000)) != Magic)
        {
            throw new InvalidDataException("not an ST4 file");
        }
        int version = (signature >> 8) & 0xFF;
        if (version != Version)
        {
            throw new InvalidDataException($"ST4 format version {version}, not {Version}");
        }
        int unit = signature & 0xFF;
        string problem = CheckUnit(unit);
        if (problem.Length != 0)
        {
            throw new InvalidDataException(problem);
        }
        int size = LongAt(file, OffsetSize);
        if (size < 0 || size % unit != 0)
        {
            throw new InvalidDataException(
                $"output size {size} is not a whole number of {unit}-byte units");
        }
        int rewind = LongAt(file, OffsetRewind);
        if (rewind != NoRewind && (rewind < 0 || rewind >= size || rewind % unit != 0))
        {
            throw new InvalidDataException($"rewind point {rewind} is not a unit of the output");
        }
        int window = LongAt(file, OffsetWindow);
        if (window < 1 || window > MaxOffsetUnits(unit))
        {
            throw new InvalidDataException($"window {window} is not 1..{MaxOffsetUnits(unit)} units");
        }

        // The streams lie in the file as A, B, C, D: the literal payload
        // last, so it runs to the end of the file.
        int[] edge =
        {
            HeaderSize, LongAt(file, OffsetByteOffsets), LongAt(file, OffsetWordOffsets),
            LongAt(file, OffsetLiteral), file.Length,
        };
        for (int i = 1; i < edge.Length - 1; i++)
        {
            if (edge[i] % 4 != 0)
            {
                throw new InvalidDataException(
                    $"stream {"ABCD"[i]} does not start on a long boundary");
            }
            if (edge[i] < edge[i - 1] || edge[i] > file.Length)
            {
                throw new InvalidDataException($"stream {"ABCD"[i]} lies outside the file");
            }
        }
        return new Container(unit, size,
            file[edge[0]..edge[1]], file[edge[3]..edge[4]],
            file[edge[1]..edge[2]], file[edge[2]..edge[3]], rewind, window);
    }

    private static int LongAt(byte[] file, int at) =>
        (file[at] & 0xFF) << 24 | (file[at + 1] & 0xFF) << 16
            | (file[at + 2] & 0xFF) << 8 | (file[at + 3] & 0xFF);
}
