# Copies from the literal stream: is it new, and does it work?

The question: let a back-reference reach past the ring into the literal
stream itself. The ring then holds only what a match needs that no literal
can give, packing improves for small rings, and the ring can shrink to
almost nothing. The optimizers have to know. This note records what the
literature says, what the mechanism is, and what it measured.

## Verdict

The parts are known; the combination is not; it works. Pointers into the
compressed text are the classical *macro schemes* of Storer and Szymanski,
and a dictionary placed before the output buffer is LZ4's prefix mode. No
scheme found uses an asset's own literal stream, resident because the
container is, as a dictionary that decouples the window from the ring. The
decoders need one compare per match. The parse is the work: the exact
problem is NP-complete, so the packer needs a heuristic, and with a search
over which units are literal a 16-unit ring with copies packs block-shaped
data like a ring of 256 units and more does without them, prose like a ring
of over 200 units at k = 1, and both better than a 256-unit ring at k = 2
and 4. No class code is needed: the offset's magnitude says whether it is a
match or a copy, and of the forms tried that one packs best.

## What the idea is

Stream B holds every literal of the stream in emission order, for as long
as the container is in memory: a second dictionary that never scrolls.
Anything that entered the output as a literal can be copied again from it,
however small the ring, so the ring has to hold only what is generated
rather than stored - self-overlapping copies, and whatever chains of matches
the packer still chooses. The reach is what the word offset allows: 32512
literals less the window, which with a tiny ring is a 32 KB dictionary at
k = 1, today's whole window.

## Prior art

**Macro schemes.** Storer and Szymanski (STOC 1978, JACM 1982) define a
pointer as indicating a substring of the compressed string, of the original
string, or of an external dictionary. LZ77 is the easy member: pointers into
the original string, leftward only. A pointer into the literal stream is
their *compressed-pointer* case. Finding an optimal bidirectional macro
scheme is NP-complete; Russo, Navarro, Correia and Francisco (2020) give
approximations.

**LZRR.** Nishimoto and Tabei (2018): an LZ77 parse that may copy from the
right when that is decodable, about five percent fewer phrases than LZ77.
It references the original text, not the literal payload.

**LZ4 and ZX0 prefix dictionaries.** `LZ4_decompress_safe_usingDict` treats
a separate buffer as the history before the block, and ZX0 decompresses with
prefix data the same way. External data, backward only, never the stream's
own literals.

**Static dictionaries** decoupled from the window are the ordinary mental
model for what the literal stream becomes.

## The design

The packer never writes a ring offset above the window M, so an offset of
at most M is a match exactly as before, and one beyond M copies from the
literal stream, M less than that far back from the literal read pointer:
the same three control bits, the same byte-or-word encoding, a byte offset
reaching 512 − M literals. The decoder compares an offset against M, which
it knows at build time, and copies from the read pointer instead of the
write pointer, without a wrap. Streams that never exceed M decode as they
always did, and nothing depends on where the ring or the literal stream is.

The packer is the work. The parse decides which units are literal, a copy
is valid only if its source is, and its offset counts the literals between:
the circularity that makes the general problem NP-complete, which the
position-indexed dynamic program cannot express. The most a small ring can
win is the gap to the full window, measured on the test corpora of 2 KB
and up before anything was built:

| k | ring, units | packed size against the full window |
|---:|---:|---:|
| 1 | 16 | +13.8% |
| 1 | 64 | +9.8% |
| 1 | 256 | +4.4% |
| 1 | 1024 | +2.2% |
| 2 | 64 | +9.4% |
| 4 | 16 | +7.3% |
| 4 | 256 | +3.1% |

A real gain for rings of a few hundred units or less, marginal from a
thousand up.

## The experiment

The packer, on the test corpora plus an earlier README of 15732 bytes and
a Java class file. The numbers below are the packer's own: `st4 -mN` for
the ring alone and `st4 -c120 -mN` for the ring with copies - the one-shot
parse of `St4LiteralCopyOptimizer` and then two minutes of
`St4LiteralCopySearch` per cell, which on the larger corpora is still
improving when time runs out. `St4LiteralCopyOracle`, the exhaustive search
on inputs of a dozen units, holds both to the optimum where it is known.

### How the circularity was broken

Pinning every copy target as literal did not survive contact with the data:
on repetitive input the first pass chains copies through each other, and
pinning every broken target forces most of the text literal; on prose
thousands of targets broke and banning them never converged. What works is
choosing the dictionary first. The literals of a full-window parse are every
first occurrence the data has, and few; they are forced to stay literal, a
copy may come only from them, and the parse is consistent by construction.
Holes of up to three units between dictionary runs are filled, and a second
pass keeps only the units the first copied from. Each pass is the ordinary
DP with the copy candidates added: a copy's offset depends only on the
literals between source and copy, which the chain prefix fixes, and the DP
needs just the cost class, byte or word, which it takes from the previous
pass and can mis-cost by eight bits at most. That parse is far too literal,
and the search starts from it: a greedy sweep frees and trims every literal
run, keeping what packs smaller, then random moves free, seed, extend or
trim runs, accepted by annealing, each step an exact parse for its
dictionary scored by what the compressor writes, with the rep of a copy in
the cost model.

### What it found

Packed size in bytes and as a share of the input, the way the packers report
it: smaller is better. Only ring decoders are compared - a stream that stays
in one buffer has the whole window and nothing to gain. "Ring alone" is the
parse at that ring size without copies, "with copies" the same ring with
copies from the literal stream.

At k = 1:

| corpus | bytes | ring | ring alone | with copies |
|---|---:|---:|---:|---:|
| far-match: a block, a run, the block | 2900 | 16 | 409 (14.1%) | 210 (7.2%) |
| period-129: 129 random bytes × 8 | 1032 | 16 | 1044 (101.2%) | 155 (15.0%) |
| period-129 | 1032 | 64 | 1044 (101.2%) | 155 (15.0%) |
| period-129 | 1032 | 256 | 135 (13.1%) | 135 (13.1%) |
| word-soup: 20 words, 400 draws | 2925 | 16 | 2797 (95.6%) | 892 (30.5%) |
| word-soup | 2925 | 64 | 2115 (72.3%) | 881 (30.1%) |
| word-soup | 2925 | 256 | 1198 (41.0%) | 847 (29.0%) |
| word-soup | 2925 | 1024 | 817 (27.9%) | 816 (27.9%) |
| class file | 11273 | 16 | 9995 (88.7%) | 7424 (65.9%) |
| class | 11273 | 64 | 8456 (75.0%) | 7111 (63.1%) |
| class | 11273 | 256 | 7156 (63.5%) | 6648 (59.0%) |
| class | 11273 | 1024 | 6241 (55.4%) | 6177 (54.8%) |
| README, prose | 15732 | 16 | 15164 (96.4%) | 10771 (68.5%) |
| README | 15732 | 64 | 13376 (85.0%) | 10498 (66.7%) |
| README | 15732 | 256 | 10515 (66.8%) | 9639 (61.3%) |
| README | 15732 | 1024 | 8863 (56.3%) | 8645 (55.0%) |
| random + its first 500 bytes | 33012 | 16 | 33151 (100.4%) | 32522 (98.5%) |
| 32 KB of one byte | 32000 | 16 | 7 (0.0%) | 7 (0.0%) |

At k = 2:

| corpus | bytes | ring | ring alone | with copies |
|---|---:|---:|---:|---:|
| far-match: a block, a run, the block | 2900 | 16 | 410 (14.1%) | 211 (7.3%) |
| period-128: 128 random bytes × 8 | 1024 | 16 | 1028 (100.4%) | 153 (14.9%) |
| period-128 | 1024 | 64 | 135 (13.2%) | 135 (13.2%) |
| word-soup: 20 words, 400 draws | 2925 | 16 | 2737 (93.6%) | 924 (31.6%) |
| word-soup | 2925 | 64 | 2157 (73.7%) | 915 (31.3%) |
| word-soup | 2925 | 256 | 1241 (42.4%) | 896 (30.6%) |
| class file | 11273 | 16 | 10747 (95.3%) | 8925 (79.2%) |
| class | 11273 | 64 | 9939 (88.2%) | 8797 (78.0%) |
| class | 11273 | 256 | 9134 (81.0%) | 8671 (76.9%) |
| README, prose | 15732 | 16 | 15442 (98.2%) | 10874 (69.1%) |
| README | 15732 | 64 | 14533 (92.4%) | 10789 (68.6%) |
| README | 15732 | 256 | 12935 (82.2%) | 10611 (67.4%) |

At k = 4:

| corpus | bytes | ring | ring alone | with copies |
|---|---:|---:|---:|---:|
| class file | 11273 | 16 | 11057 (98.1%) | 10266 (91.1%) |
| class | 11273 | 64 | 10822 (96.0%) | 10267 (91.1%) |
| class | 11273 | 256 | 10438 (92.6%) | 10229 (90.7%) |
| README, prose | 15732 | 16 | 15618 (99.3%) | 14572 (92.6%) |
| README | 15732 | 64 | 15398 (97.9%) | 14533 (92.4%) |
| README | 15732 | 256 | 15099 (96.0%) | 14525 (92.3%) |
| word-soup: 20 words, 400 draws | 2925 | 16 | 2827 (96.6%) | 1879 (64.2%) |
| word-soup | 2925 | 64 | 2593 (88.6%) | 1873 (64.0%) |
| word-soup | 2925 | 256 | 2067 (70.7%) | 1858 (63.5%) |
| period-128: 128 random bytes × 8 | 1024 | 16 | 1028 (100.4%) | 153 (14.9%) |

Read for the goal - the same ratio from a smaller ring - the tables say what
ring a decoder without copies needs to match a 16-unit ring with them:

| corpus | k | 16 units with copies | ring alone, for the same ratio |
|---|---:|---:|---:|
| far-match | 1 | 7.2% | any ring shorter than the gap gives 14.1% |
| period-129 | 1 | 15.0% | 256 units (13.1%); 64 units give 101.2% |
| word-soup | 1 | 30.5% | between 256 (41.0%) and 1024 (27.9%) |
| class file | 1 | 65.9% | just short of 256 units (63.5%); 64 give 75.0% |
| README | 1 | 68.5% | just short of 256 units (66.8%); 64 give 85.0% |
| class file | 2 | 79.2% | more than 256 units (81.0%) |
| README | 2 | 69.1% | more than 256 units (82.2%) |
| word-soup | 2 | 31.6% | more than 256 units (42.4%) |
| class file | 4 | 91.1% | more than 256 units (92.6%) |
| README | 4 | 92.6% | more than 256 units (96.0%) |
| word-soup | 4 | 64.2% | more than 256 units (70.7%) |

### What that means

**A smaller ring at the same ratio is real.** Where the repeats are whole
blocks or periods that lie further apart than the ring, a 16-unit ring with
copies packs like a ring of 256 units and more without them, and the copy is
a hair cheaper than a match at full reach would be, because an offset that
counts literals is a byte where one that counts output is a word. At k = 2
and k = 4, where the decoders live, a 16-unit ring with copies beats a
256-unit ring without them on all three larger corpora, and a 64-unit ring
with copies packs the README at k = 4 to 92.4% where 256 units alone give
96.0%.

**Prose gains less, and the search is what makes it gain.** A ring's
advantage on text is a cheap reference to the most recent occurrence, which
is usually match output, not a literal; a copy has to reach the first
occurrence instead, often a word offset away, unless the parse makes a
nearer occurrence literal to serve the ones after it - which is what the
search finds and a one-shot parse cannot. On the README a 16-unit ring with
copies packs to 68.5% at k = 1, just short of a 256-unit ring alone, where
the one-shot parse gave 78.3%; the class file to 65.9% against 73.5%.

**What gave up reach.** The magnitude form's reach into the literal stream
shrinks by the window, and the random stream with its head repeated shows
it: the one copy 32512 literals back no longer fits.

### Real tunes

The test corpora are synthetic; the assets this is for are register dumps.
YMX, the streaming YM player this format was split from, packs a tune as
twenty-five streams - fourteen sound registers, one value per frame, and
eleven of a compiled effect script - each decoded through its own ring of
960 bytes by default. The four example tunes of that repository and one
from its test set - Synergy's Wicked Polygons 2, fourteen minutes of music,
seven times the frames of the others - their stream vectors built as its
encoder builds them, packed here one section per stream: the previous ST4,
which is what YMX packs with today, against this one with thirty seconds of
search per stream, at the player's ring and at two smaller ones. Sizes are
the four ST4 streams in bytes, summed over the twenty-five streams, without
headers or padding. Thirty seconds on Wicked Polygons 2's 43 KB streams
converges far less than on the others' 6 KB, so its searched sizes are the
conservative ones.

At k = 1:

| tune | frames | ring, bytes | previous ST4 | with copies | smaller by |
|---|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 6144 | 960 | 3519 | 3428 | 3% |
|  |  | 256 | 3895 | 3645 | 6% |
|  |  | 128 | 7762 | 6384 | 18% |
| Amiga Demo, Overscan screen | 6912 | 960 | 2447 | 2420 | 1% |
|  |  | 256 | 5379 | 4696 | 13% |
|  |  | 128 | 5881 | 5082 | 14% |
| Mad Max 1 | 3838 | 960 | 856 | 847 | 1% |
|  |  | 256 | 1601 | 1447 | 10% |
|  |  | 128 | 1861 | 1677 | 10% |
| Cuddly, main menu | 6650 | 960 | 1063 | 1051 | 1% |
|  |  | 256 | 4646 | 3766 | 19% |
|  |  | 128 | 7083 | 5941 | 16% |
| Synergy, Wicked Polygons 2 | 43132 | 960 | 57280 | 56442 | 1% |
|  |  | 256 | 76034 | 73527 | 3% |
|  |  | 128 | 94955 | 90684 | 4% |

At k = 2:

| tune | frames | ring, bytes | previous ST4 | with copies | smaller by |
|---|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 6144 | 960 | 3737 | 3593 | 4% |
|  |  | 256 | 4198 | 3797 | 10% |
|  |  | 128 | 8096 | 5829 | 28% |
| Amiga Demo, Overscan screen | 6912 | 960 | 2853 | 2780 | 3% |
|  |  | 256 | 6545 | 4837 | 26% |
|  |  | 128 | 7074 | 5185 | 27% |
| Mad Max 1 | 3838 | 960 | 954 | 918 | 4% |
|  |  | 256 | 1940 | 1494 | 23% |
|  |  | 128 | 2342 | 1609 | 31% |
| Cuddly, main menu | 6650 | 960 | 1123 | 1111 | 1% |
|  |  | 256 | 4885 | 3149 | 36% |
|  |  | 128 | 7197 | 4973 | 31% |
| Synergy, Wicked Polygons 2 | 43132 | 960 | 70472 | 67377 | 4% |
|  |  | 256 | 97391 | 84400 | 13% |
|  |  | 128 | 121815 | 100493 | 18% |

At the player's own ring the copies gain one to four percent - the ring
already holds what these tunes repeat. The gain is in shrinking it: three
to nineteen percent at 256 and 128 bytes at k = 1, and ten to thirty-six at
k = 2, where a byte offset reaches twice as far. Wicked Polygons 2 is the
odd one out in another way: it packs nineteen percent smaller at k = 1 than
at k = 2, 57280 bytes against 70472 at the player's ring, so the unit size
YMX packs with by default is the wrong one for it. What that buys is RAM: the
rings cost 25 × N bytes, 24000 at 960, 6400 at 256, 3200 at 128. Read that
way, a tune packed with copies for a smaller ring against the same tune
packed as YMX packs it today, for its 960-byte ring:

| tune | k | previous ST4, ring 960 | with copies, ring 256 | against it | with copies, ring 128 | against it |
|---|---:|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 1 | 3519 | 3645 | +4% | 6384 | +81% |
|  | 2 | 3737 | 3797 | +2% | 5829 | +56% |
| Amiga Demo, Overscan screen | 1 | 2447 | 4696 | +92% | 5082 | +108% |
|  | 2 | 2853 | 4837 | +70% | 5185 | +82% |
| Mad Max 1 | 1 | 856 | 1447 | +69% | 1677 | +96% |
|  | 2 | 954 | 1494 | +57% | 1609 | +69% |
| Cuddly, main menu | 1 | 1063 | 3766 | +254% | 5941 | +459% |
|  | 2 | 1123 | 3149 | +180% | 4973 | +343% |
| Synergy, Wicked Polygons 2 | 1 | 57280 | 73527 | +28% | 90684 | +58% |
|  | 2 | 70472 | 84400 | +20% | 100493 | +43% |

Dark Side of the Spoon costs two to four percent more at a quarter of the
ring RAM, and Wicked Polygons 2 twenty to twenty-eight. The other tunes do
not get that far, since a period stream that
repeats at long range is match-shaped rather than literal-shaped, and
shrinking the ring costs a tune that packed to almost nothing the most. The
tune data needs nothing for this: copies read the literal stream out of the
file the player already keeps in memory.

### What decides it

`st4 -c` writes the one-shot parse and the decoders take it when built with
`ST4_WINDOW`; `st4 -cS` searches for S seconds beyond it, descending and
annealing over which units are literal with an exact parse for each choice
and the rep of a copy in its cost model - on this README at k = 1 and a
64-unit window that takes the one-shot parse's 78% of the input to 61% in
ten minutes, still improving. What is left is one-unit copies in the cost
model, and the same candidates in the fast optimizer, the event-driven one
falling back.

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
