# Matching into the literal stream: is it new, and does it work?

The question: put the ring directly behind stream D, the literal data, so that
a back-reference can reach past the ring into the literals themselves. Then
the ring no longer has to hold what a match wants, packing improves for small
rings, and the ring can shrink to almost nothing. The optimizers have to know.

This note records what the literature says, what the mechanism really is,
and what it would take.

## Verdict

The parts are known; the combination is not; it is feasible. Pointers into the
compressed text and pointers that reach forward are the classical *macro
scheme* family of Storer and Szymanski, and a dictionary placed flush before
the output buffer is LZ4's prefix mode. No scheme found uses an asset's own
literal stream, resident by layout, as a dictionary that reaches both the
consumed and the not yet emitted literals, to decouple the window from the
ring. The decoders need little. The parse is the work: the exact problem is in
an NP-complete class, so the packer needs a two-pass heuristic.

## What the idea is, precisely

With stream D last in the container and the ring directly behind it, memory
reads `… D | ring`. A match copies from `write − distance`. Below the ring
start that address lands in D, and D holds every literal of the stream in
emission order: the consumed ones, already output and possibly long gone from
the ring, and the unconsumed ones, which are future output. D is a second
dictionary that never scrolls.

- Anything that entered the output as a literal can be copied again from D,
  however small the ring. The ring has to hold only what is *generated* rather
  than stored: self-overlapping copies — RLE-like periods — and whatever
  match-of-match chains the packer still chooses.
- Forward references become possible. Data that appears at p₁ and later as a
  literal run at p₂ can be a copy from D at p₁ and the literal run at p₂. That
  is no cheaper by itself, but it needs no ring history.
- The reach into D is what the word offset allows: 32512 bytes less the ring
  phase. With a tiny ring that is a 32 KB literal dictionary, which is
  today's full window at k = 1.

## Prior art

**Macro schemes.** Storer and Szymanski's model (STOC 1978, JACM 1982)
defines a pointer as indicating "a substring of the compressed string, a
substring of the original string, or a substring of some other string such as
an external dictionary". LZ77 is the restricted, easy member: pointers into the
original string, leftward only. A pointer into the literal stream is their
*compressed-pointer* case; reaching future literals is *bidirectional*.
Finding an optimal bidirectional macro scheme is NP-complete; Russo, Navarro,
Correia and Francisco (2020) give approximations, and validity is only that
decoding never loops. The forward pointers here are the trivially acyclic
kind: a literal is an explicit symbol, never a pointer.

**LZRR.** Nishimoto and Tabei (2018), "LZ77 parsing with right reference": a
practical left-to-right parse that may copy from the right when that is
decodable, with about five percent fewer phrases than LZ77 on benchmarks. The
closest published relative of the forward-reference half. It references the
original text, not the literal payload.

**LZ4 prefix and external dictionaries.** `LZ4_decompress_safe_usingDict`
treats a separate buffer as the history before the block. Its header notes
that "decompression speed can be substantially increased when dst ==
dictStart + dictSize" — a dictionary flush before the buffer costs nothing,
and anywhere else the decoder pays a range check per match. Streaming
decompression requires the last 64 KB to stay resident, and admits ring
buffers under 64 KB only in a synchronized mode. Requirement (1) of the idea
is exactly prefix mode; the difference is that LZ4's dictionary is external
data, not the stream's own literals, and never contains the future.

**ZX0**, this format's ancestor, supports in-place overlap with a "delta"
margin and "decompression with prefix data for improved compression" — the
same family's external-dictionary trick. External data, backward only.

**Static dictionaries** decoupled from the window are the ordinary mental
model for what D becomes: a fixed dictionary and a sliding window, addressed
by one offset space when contiguous.

## Feasibility

**The linear decoder, ST4.S: free.** If the caller loads the container so that
D ends where the output buffer begins, references into D need no code at all.
Version 5 put D last for exactly this.

**The ring decoders: not free by adjacency alone.** Today a source below the
ring start means "wrap to the other end", and the same condition cannot also
mean "read D". Two ways out:

1. A no-wrap variant: the write pointer wraps, sources are linear, everything
   below the ring is D. The previous lap becomes unreachable, but D covers
   what it held, except self-overlapping periods, which stay within the
   current phase anyway.
2. A *literal-stream match*: a class code for a copy relative to `a2`, the
   literal read pointer, with a signed offset from stream B or C. About twenty
   bytes of decoder, two more control bits per such match, and the even
   rhythm survives with one extra bit. It needs no layout constraint, so
   requirement (1) stops being a must, and the packer never needs to know D's
   final length.

The class code is the better design, and it is what makes the packer
tractable.

**The packer: the work.** The dictionary depends on the parse, because the
parse decides which units are literals, and a reference into D depends on how
many literals precede its target. That circularity is why the general problem
is NP-complete; the position-indexed dynamic program cannot express it
directly. The practical route is two passes: a standard parse gives D₁; a
second parse admits copies from D₁'s literal runs, pins every run it
references as literal, and iterates once. With `a2`-relative offsets the
backward references are clean in the dynamic program — each chain knows its
own literal count — and only the forward ones need the pinning. The reference
and fast optimizers can take this; the event-driven one would fall back, since
references into D need an occurrence index over D rather than run events.

**The gain, bounded from today's packer.** The most the idea can win is the
gap between a small window and the full one, since D restores the full reach.
On the five test corpora of 2 KB and up:

| k | ring, units | packed size against the full window |
|---:|---:|---:|
| 1 | 16 | +13.8% |
| 1 | 64 | +9.8% |
| 1 | 256 | +4.4% |
| 1 | 1024 | +2.2% |
| 2 | 64 | +9.4% |
| 4 | 16 | +7.3% |
| 4 | 256 | +3.1% |

A reference into D is nearly always a word offset, a byte more than a ring
match, so it pays where the small ring forced *literals* — which is where the
gap comes from. A real gain for rings of a few hundred units or less, marginal
from a thousand up. The compelling case is a rewind loop through a tiny ring,
packing close to full-window quality.

## The next step

Do the packer half first, in Java only: the two-pass parse with pinning,
measured as packed size at rings of 16, 64 and 256 units against today's
numbers. Touch the bitstream and the decoders only once those numbers say so.

## Sources

- J. A. Storer, T. G. Szymanski, *The macro model for data compression
  (extended abstract)*, STOC 1978 —
  <https://www.semanticscholar.org/paper/686b27e3d215720b57c6c498ddb734b6faab578b>
- J. A. Storer, T. G. Szymanski, *Data compression via textual substitution*,
  JACM 29(4), 1982 — <https://dl.acm.org/doi/10.1145/322344.322346>
- L. M. S. Russo, G. Navarro, A. Correia, A. P. Francisco, *Approximating
  Optimal Bidirectional Macro Schemes*, 2020 —
  <https://arxiv.org/abs/2003.02336>
- T. Nishimoto, Y. Tabei, *LZRR: LZ77 Parsing with Right Reference*, 2018 —
  <https://arxiv.org/abs/1812.04261>
- LZ4, `lz4.h`: `LZ4_decompress_safe_usingDict` and streaming decompression —
  <https://raw.githubusercontent.com/lz4/lz4/dev/lib/lz4.h>, manual at
  <https://fossies.org/linux/lz4/doc/lz4_manual.html>
- E. Saukas, ZX0 — <https://github.com/einar-saukas/ZX0>
- Wikibooks, *Data Compression: dictionary compression* —
  <https://en.wikibooks.org/wiki/Data_Compression/Dictionary_compression>
- encode.su, *LZ style compression with static dictionary* —
  <https://encode.su/threads/2995-LZ-style-compression-with-static-Dictionary>
