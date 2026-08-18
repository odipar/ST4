// ZX1 by Einar Saukas; ST4 and this C# port by Claude (Anthropic's Claude
// Code) under Robbert van Dalen's direction. See LICENSE for the terms.

namespace Nt4;

/// <summary>
/// One block of an ST4 parse chain: a literal run when <see cref="Offset"/> is
/// zero, otherwise a match.
/// </summary>
/// <param name="Bits">Cost of the whole chain up to and including this block.</param>
/// <param name="Index">The last unit the block covers.</param>
/// <param name="Offset">Zero for literals; otherwise the match distance in units.</param>
/// <param name="Chain">The preceding block, or <see langword="null"/> for the parser's fake head.</param>
public sealed record Block(int Bits, int Index, int Offset, Block? Chain);
