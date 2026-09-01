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
an NP-complete class, so the packer needs a heuristic - and one is measured
below. With the dictionary chosen first, a 16-unit ring with copies from D
packs block-shaped data like a ring of 256 units and more does today, and
prose like a ring of 80 to 120 units. No class code is needed: the offset's
magnitude says which, and that form packs best; the ring-behind-D form
loses.

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
mean "read D". Three ways out:

1. A no-wrap variant: the write pointer wraps, sources are linear, everything
   below the ring is D, anchored at its end. The previous lap becomes
   unreachable, so no match may cross a lap, and D's length must be known
   before any offset into it can be written.
2. A *literal-stream match* with a class code of its own: a copy relative to
   `a2`, the literal read pointer, two more control bits per such match, no
   layout constraint.
3. The same by magnitude, with no class code at all. The packer never writes
   a ring offset above `-m` = N, so an offset of at most N units is a ring
   match exactly as today, and one beyond N copies from the literal stream,
   offset minus N literals back from the read pointer. Same three control
   bits, same byte-or-word encoding, no layout constraint. The decoder
   compares an offset against N once, when it installs it, and copies from
   the read pointer instead of the write pointer, without a wrap; the
   existing streams, which never exceed N, decode unchanged.

The third is the design: it anchors at the read pointer, which the packer
knows at every point, keeps the ring's whole history, and costs the stream
nothing.

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

## The experiment

The packer half, in Java: `St4LiteralMatchExperiment`, a variant of the
reference optimizer with the far candidates added, run on the test corpora
plus this README and a Java class file. It reports each parse's bit cost,
which is the packed size up to padding. Run it with

```sh
java -cp target/classes org.st4.St4LiteralMatchExperiment [-kK] [-rN,N,..] file...
```

### How the circularity was broken

The pinning plan above did not survive contact with the data. On repetitive
input the first pass chains copies through each other - period 3 copies
period 2 copies period 1 - and pinning every broken target forces most of
the text literal. Nor does "literal in the best chain ending here" stand in
for "literal in the final chain" on prose: thousands of targets broke and
banning them never converged. What works is choosing the dictionary first.
The literals of a full-window parse are every first occurrence the data has,
and few; they are forced to stay literal, a copy may come only from them, and
the parse is consistent by construction. Holes of up to three units between
dictionary runs are filled, because an accidental one-unit rep inside a
period otherwise splits every later copy of it in two. A second pass keeps
only the dictionary units the first actually copied from and frees the rest.

Each pass is then the ordinary position-indexed DP, `O(n × reach)` - the
experiment loops every offset, `O(n²)`, only because an `a2` distance can be
within reach when the output distance is not - and one to four passes settle.
The dependency the question feared, every offset into D shifting with every
literal decision, never reaches the DP: with `a2`-relative offsets a backward
copy's offset depends only on the literals between target and copy, which the
chain prefix fixes, and the DP needs just the cost class, byte or word, which
it estimates from the previous pass and can only mis-cost by eight bits.

Three addressings were costed. **A2**: a class code of its own, five
control bits, the offset relative to the literal read pointer, and ring
matches as the ring decoders make them today. **By magnitude**: the same
offset space as a ring match, an offset beyond N reaching the literal stream
N less than that far back from the read pointer - three control bits, and a
byte offset reaches 512 minus N literals. **Ring behind D**: no class code
either, but the ring directly behind D with an ordinary offset reading D past
the ring start, anchored at D's end; the write pointer wraps by lap, sources
never do, so no match may cross a lap and ring matches reach only into the
current lap.

### What it found

Packed size in bytes and as a share of the input, the way the packers report
it: smaller is better. Only ring decoders are compared - a stream that stays
in one buffer has the whole window and nothing to gain. "Ring alone" is
today's parse at that ring size, "copies from D" the class-code form, "no
class code" the ring-behind-D form.

At k = 1:

| corpus | bytes | ring | ring alone | class code | by magnitude | ring behind D |
|---|---:|---:|---:|---:|---:|---:|
| far-match: a block, a run, the block | 2900 | 16 | 408 (14.1%) | 210 (7.2%) | 210 (7.2%) | 586 (20.2%) |
| period-129: 129 random bytes × 8 | 1032 | 16 | 1043 (101.1%) | 156 (15.1%) | 154 (14.9%) | 270 (26.2%) |
| period-129 | 1032 | 64 | 1043 (101.1%) | 156 (15.1%) | 154 (14.9%) | 183 (17.7%) |
| period-129 | 1032 | 256 | 135 (13.1%) | 135 (13.1%) | 135 (13.1%) | 160 (15.5%) |
| word-soup: 20 words, 400 draws | 2925 | 16 | 2795 (95.6%) | 1008 (34.5%) | 924 (31.6%) | 1127 (38.5%) |
| word-soup | 2925 | 64 | 2114 (72.3%) | 946 (32.3%) | 889 (30.4%) | 966 (33.0%) |
| word-soup | 2925 | 256 | 1198 (41.0%) | 864 (29.5%) | 847 (29.0%) | 890 (30.4%) |
| word-soup | 2925 | 1024 | 817 (27.9%) | 806 (27.6%) | 815 (27.9%) | 832 (28.4%) |
| class file | 11273 | 16 | 9993 (88.6%) | 8757 (77.7%) | 8288 (73.5%) | 9780 (86.8%) |
| class | 11273 | 64 | 8456 (75.0%) | 7770 (68.9%) | 7542 (66.9%) | 8787 (77.9%) |
| class | 11273 | 256 | 7155 (63.5%) | 6836 (60.6%) | 6802 (60.3%) | 7593 (67.4%) |
| class | 11273 | 1024 | 6240 (55.4%) | 6185 (54.9%) | 6199 (55.0%) | 6559 (58.2%) |
| README, prose | 15732 | 16 | 15163 (96.4%) | 13548 (86.1%) | 12321 (78.3%) | 14436 (91.8%) |
| README | 15732 | 64 | 13376 (85.0%) | 12231 (77.7%) | 11508 (73.2%) | 13364 (84.9%) |
| README | 15732 | 256 | 10514 (66.8%) | 10063 (64.0%) | 9927 (63.1%) | 11459 (72.8%) |
| README | 15732 | 1024 | 8863 (56.3%) | 8687 (55.2%) | 8663 (55.1%) | 9653 (61.4%) |
| random + its first 500 bytes | 33012 | 16 | 33150 (100.4%) | 32521 (98.5%) | 33018 (100.0%) | 32620 (98.8%) |
| 32 KB of one byte | 32000 | 16 | 5 (0.0%) | 5 (0.0%) | 5 (0.0%) | 4500 (14.1%) |

At k = 4:

| corpus | bytes | ring | ring alone | class code | by magnitude |
|---|---:|---:|---:|---:|---:|
| class file | 11273 | 16 | 11055 (98.1%) | 10523 (93.3%) | 10497 (93.1%) |
| class | 11273 | 64 | 10821 (96.0%) | 10463 (92.8%) | 10443 (92.6%) |
| class | 11273 | 256 | 10437 (92.6%) | 10325 (91.6%) | 10321 (91.6%) |
| README, prose | 15732 | 16 | 15618 (99.3%) | 15239 (96.9%) | 15222 (96.8%) |
| README | 15732 | 64 | 15398 (97.9%) | 15143 (96.3%) | 15130 (96.2%) |
| README | 15732 | 256 | 15098 (96.0%) | 14928 (94.9%) | 14921 (94.8%) |
| word-soup: 20 words, 400 draws | 2925 | 16 | 2826 (96.6%) | 2117 (72.4%) | 2064 (70.6%) |
| word-soup | 2925 | 64 | 2592 (88.6%) | 2085 (71.3%) | 2042 (69.8%) |
| word-soup | 2925 | 256 | 2066 (70.6%) | 1955 (66.8%) | 1948 (66.6%) |
| period-128: 128 random bytes × 8 | 1024 | 16 | 1027 (100.3%) | 149 (14.6%) | 147 (14.4%) |

Read for the goal - the same ratio from a smaller ring - the tables say what
ring today's decoders need to match a 16-unit ring with copies from D:

| corpus | k | 16 units, copies from D by magnitude | ring alone, for the same ratio |
|---|---:|---:|---:|
| far-match | 1 | 7.2% | any ring shorter than the gap gives 14.1% |
| period-129 | 1 | 14.9% | 256 units (13.1%); 64 units give 101.1% |
| word-soup | 1 | 31.6% | between 256 (41.0%) and 1024 (27.9%) |
| class file | 1 | 73.5% | between 64 (75.0%) and 256 (63.5%) |
| README | 1 | 78.3% | between 64 (85.0%) and 256 (66.8%) |
| class file | 4 | 93.1% | between 64 (96.0%) and 256 (92.6%) |
| README | 4 | 96.8% | between 64 (97.9%) and 256 (96.0%) |
| word-soup | 4 | 70.6% | 256 units (70.6%) |

### What that means

**Of the two forms without a class code, only the magnitude split works.**
The ring placed behind D, with D's end the anchor and its laps uncrossable,
costs more than a class code saves everywhere and is catastrophic on runs:
32 KB of one byte packs to 5 bytes with a ring and to 3.7 KB without one. The
same offset space split at N - a ring match up to N, a copy from the literal
stream beyond - beats the class code on every corpus but one, by its two
control bits and its longer byte reach. What it gives up is reach into D,
which shrinks by N, and the random stream with its head repeated shows it:
the one copy 32512 literals back no longer fits. That is the design.

**A smaller ring at the same ratio is real for block-shaped repetition.**
Where the repeats are whole blocks or periods that lie further apart than the
ring - a pattern, a periodic dump, a repeated routine - a 16-unit ring with
copies from D packs like a ring of 256 units and more today, and the copy
from D is a hair cheaper than a match at full reach would be, because an
offset that counts literals is a byte where one that counts output is a word.
At k = 4, where the decoders live, a 16-unit ring with copies from D packs
like a ring of 180 to 256 units today on all three larger corpora.

**Prose gains the least.** A ring's advantage on text is a cheap reference
to the most recent occurrence, which is usually match output, not a literal;
a copy from D has to reach the first occurrence instead, often a word offset
away, and has no rep form. On the README a 16-unit ring with copies from D
packs to 78.3%, like a ring of about 120 units today at k = 1; the class file
to 73.5%, like about 80 units.

**The cost model still has slack.** Copies are backward only, at least two
units, without a rep form; a rep for the copy that continues the last one
would take back some of what prose loses. The dictionary is the full parse's
literals with holes filled, not an optimum, and the fixed point sometimes
returns to its first pass.

### What decides it

The test corpora are synthetic. Whether the demo's assets - register dumps,
speedcode, samples - are block-shaped or prose-shaped is what decides the
ring size this buys, and the experiment runs on any file. If they read like
the periodic corpora, the order of work is: the rep form and one-unit copies
in the cost model; then the decoders - an offset compared against N as it is
installed, and a copy whose source is the literal read pointer rather than
the write pointer, without a wrap, twenty-odd bytes each - with the packer
writing offsets beyond N; then the same candidates in the fast optimizer, the
event-driven one falling back.

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
