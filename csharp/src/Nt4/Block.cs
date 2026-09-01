// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// One block of a parse, chained to the block before it: the last block of a
/// parse is the parse.
/// </summary>
/// <param name="Bits">Cost of the whole chain up to and including this block.</param>
/// <param name="Index">The last unit the block covers.</param>
/// <param name="Offset">
/// Zero for a literal run; a positive offset for a match that copies output
/// from that many units back; a negative offset for a copy from the literal
/// stream, whose source starts that many units back in the output and must be
/// literal there. The compressor turns the latter into an offset beyond the
/// window.
/// </param>
/// <param name="Chain">The preceding block, or <see langword="null"/> for the parser's fake head.</param>
public sealed record Block(int Bits, int Index, int Offset, Block? Chain);
