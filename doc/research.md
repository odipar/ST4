# Copies from the literal stream: is it new, and does it work?

The question: let a back-reference reach past the ring into the literal
stream itself. The ring then keeps only what a match needs beyond the
literals, packing improves for small rings, and the ring can shrink far.
The optimizers have to know. This note records what the literature says,
what the mechanism is, and what the measurements read.

## Verdict

The parts are known; the combination is not; it works. Pointers into the
compressed text are the classical *macro schemes* of Storer and Szymanski,
and a dictionary placed before the output buffer is LZ4's prefix mode. No
scheme found uses the literal stream of an asset, resident because the
container is, as a dictionary that decouples the window from the ring. The
decoders need one compare per match. The parse is the work: the exact
problem is NP-complete, so the packer needs a heuristic, and with a search
over which units are literal a 16-unit ring with copies packs block-shaped
data like a ring of 256 units and more does without them, prose like a ring
of over 200 units at k = 1, and both better than a 256-unit ring at k = 2
and 4. No class code is needed: the offset's magnitude says whether it is a
match or a copy, and of the forms tried that one packs best.

## What the idea is

Stream B has every literal of the stream in emission order, for as long
as the container is in memory: a second dictionary that never scrolls.
Anything that entered the output as a literal can be copied again from it,
however small the ring, so the ring needs only what is generated rather
than stored - self-overlapping copies, and the chains of matches the packer
still chooses. The reach is what the word offset allows: 32512
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
reaching 512 - M literals. The decoder compares an offset against M, which
it knows at build time, and copies from the read pointer instead of the
write pointer, without a wrap. Streams that never exceed M decode as they
always did, and the position of the ring and the literal stream is free.

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
passes of `St4LiteralCopySearch` and then two minutes of its search per
cell, which on the larger corpora is still
improving when time runs out. `St4LiteralCopyOracle`, the exhaustive search
on inputs of a dozen units, reads both against the known optimum.

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
needs just the cost class, byte or word, which it reads from the previous
pass and can mis-cost by eight bits at most. That parse is far too literal,
and the search starts from it: a greedy sweep frees and trims every literal
run, keeping what packs smaller, then random moves free, seed, extend or
trim runs, accepted by annealing, each step an exact parse for its
dictionary scored by what the compressor writes, with the rep of a copy in
the cost model.

### What it found

Packed size in bytes and as a share of the input, the way the packers report
it: smaller is better. Only ring decoders are compared - a stream that stays
in one buffer has the whole window already. "Ring alone" is the
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
| far-match | 1 | 7.2% | any ring shorter than the gap reads 14.1% |
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
eleven of a compiled effect script - each decoded through a separate ring
of 960 bytes by default. The four example tunes of that repository and one
long one, Synergy's Wicked Polygons 2 at 43132 frames, their stream vectors
built as its encoder builds them, packed here one section per stream: the
previous ST4, which is what YMX packs with today, against this one with
thirty seconds of search per stream, at the player's ring, at one larger
and at three smaller. Sizes are the four ST4 streams in bytes, summed over
the twenty-five streams, without headers or padding.

At k = 1:

| tune | frames | ring, bytes | previous ST4 | with copies | smaller by |
|---|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 6144 | 1024 | 3501 | 3424 | 2% |
|  |  | 960 | 3519 | 3428 | 3% |
|  |  | 512 | 3710 | 3584 | 3% |
|  |  | 256 | 3895 | 3645 | 6% |
|  |  | 128 | 7762 | 6384 | 18% |
| Amiga Demo, Overscan screen | 6912 | 1024 | 2441 | 2414 | 1% |
|  |  | 960 | 2447 | 2420 | 1% |
|  |  | 512 | 2461 | 2436 | 1% |
|  |  | 256 | 5379 | 4696 | 13% |
|  |  | 128 | 5881 | 5082 | 14% |
| Mad Max 1 | 3838 | 1024 | 856 | 845 | 1% |
|  |  | 960 | 856 | 847 | 1% |
|  |  | 512 | 946 | 903 | 5% |
|  |  | 256 | 1601 | 1447 | 10% |
|  |  | 128 | 1861 | 1677 | 10% |
| Cuddly, main menu | 6650 | 1024 | 1063 | 1051 | 1% |
|  |  | 960 | 1063 | 1051 | 1% |
|  |  | 512 | 1063 | 1051 | 1% |
|  |  | 256 | 4646 | 3766 | 19% |
|  |  | 128 | 7083 | 5941 | 16% |
| Synergy, Wicked Polygons 2 | 43132 | 1024 | 56781 | 55961 | 1% |
|  |  | 960 | 57280 | 56442 | 1% |
|  |  | 512 | 61175 | 60074 | 2% |
|  |  | 256 | 76034 | 73527 | 3% |
|  |  | 128 | 94955 | 90684 | 4% |

At k = 2:

| tune | frames | ring, bytes | previous ST4 | with copies | smaller by |
|---|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 6144 | 1024 | 3714 | 3606 | 3% |
|  |  | 960 | 3737 | 3593 | 4% |
|  |  | 512 | 3980 | 3711 | 7% |
|  |  | 256 | 4198 | 3797 | 10% |
|  |  | 128 | 8096 | 5829 | 28% |
| Amiga Demo, Overscan screen | 6912 | 1024 | 2827 | 2774 | 2% |
|  |  | 960 | 2853 | 2780 | 3% |
|  |  | 512 | 2884 | 2806 | 3% |
|  |  | 256 | 6545 | 4837 | 26% |
|  |  | 128 | 7074 | 5185 | 27% |
| Mad Max 1 | 3838 | 1024 | 954 | 922 | 3% |
|  |  | 960 | 954 | 918 | 4% |
|  |  | 512 | 1155 | 980 | 15% |
|  |  | 256 | 1940 | 1494 | 23% |
|  |  | 128 | 2342 | 1609 | 31% |
| Cuddly, main menu | 6650 | 1024 | 1123 | 1111 | 1% |
|  |  | 960 | 1123 | 1111 | 1% |
|  |  | 512 | 1123 | 1108 | 1% |
|  |  | 256 | 4885 | 3149 | 36% |
|  |  | 128 | 7197 | 4973 | 31% |
| Synergy, Wicked Polygons 2 | 43132 | 1024 | 69836 | 67048 | 4% |
|  |  | 960 | 70472 | 67377 | 4% |
|  |  | 512 | 77371 | 71288 | 8% |
|  |  | 256 | 97391 | 84400 | 13% |
|  |  | 128 | 121815 | 100493 | 18% |

At the player's ring and above it the copies gain one to four percent: the
ring already has what these tunes repeat. The gain is in shrinking
it: one to fifteen percent at 512 bytes, three to nineteen at 256 and 128
at k = 1, and ten to thirty-six at k = 2, where a byte offset reaches twice
as far. What that buys is RAM: the rings cost 25 × N bytes, 25600 at 1024,
24000 at 960, 12800 at 512, 6400 at 256, 3200 at 128. Read that way, a tune
packed with copies for another ring against the same tune packed as YMX
packs it today, for its 960-byte ring:

| tune | k | previous ST4, ring 960 | with copies, ring 1024 | against it | with copies, ring 512 | against it | with copies, ring 256 | against it | with copies, ring 128 | against it |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 1 | 3519 | 3424 | -3% | 3584 | +2% | 3645 | +4% | 6384 | +81% |
|  | 2 | 3737 | 3606 | -4% | 3711 | -1% | 3797 | +2% | 5829 | +56% |
| Amiga Demo, Overscan screen | 1 | 2447 | 2414 | -1% | 2436 | 0% | 4696 | +92% | 5082 | +108% |
|  | 2 | 2853 | 2774 | -3% | 2806 | -2% | 4837 | +70% | 5185 | +82% |
| Mad Max 1 | 1 | 856 | 845 | -1% | 903 | +5% | 1447 | +69% | 1677 | +96% |
|  | 2 | 954 | 922 | -3% | 980 | +3% | 1494 | +57% | 1609 | +69% |
| Cuddly, main menu | 1 | 1063 | 1051 | -1% | 1051 | -1% | 3766 | +254% | 5941 | +459% |
|  | 2 | 1123 | 1111 | -1% | 1108 | -1% | 3149 | +180% | 4973 | +343% |
| Synergy, Wicked Polygons 2 | 1 | 57280 | 55961 | -2% | 60074 | +5% | 73527 | +28% | 90684 | +58% |
|  | 2 | 70472 | 67048 | -5% | 71288 | +1% | 84400 | +20% | 100493 | +43% |

At 512 bytes, half the ring RAM, every tune packs to within five percent
of what it packs to today, most to within two, the long one included. Dark
Side of the Spoon costs two to four percent more at a quarter. The others do
not get that far, since a period stream that repeats at long range is
match-shaped rather than literal-shaped, and shrinking the ring costs a
tune that packed smallest the most. The tune data needs no change for
this: copies read the literal stream out of the file the player
already keeps in memory.

### What decides it

`st4 -c` writes the one-shot parse and the decoders read it when built with
`ST4_WINDOW`; `st4 -cS` searches for S seconds beyond it, descending and
annealing over which units are literal with an exact parse for each choice
and the rep of a copy in its cost model - on this README at k = 1 and a
64-unit window that brings the one-shot parse's 78% of the input to 61% in
ten minutes, still improving. What is left is one-unit copies in the cost
model, and the same candidates in the fast optimizer, the event-driven one
falling back.

## Choosing the dictionary by the window

The search opens from the literals of a full-reach parse, so its starting
dictionary is the same at every window. The question this section answers:
when the window is small, would a dictionary built for that window pack
smaller? The case for yes is simple. A repeat within the window is served by
a match and needs no dictionary entry; a repeat beyond the window needs its
source kept as literals. A dictionary built at the window should therefore
contain exactly what the copies need and no more. Measured, it packs larger
on every corpus here but one.

### Why a larger dictionary cannot win

A dictionary is a set of units forced to be literal. That makes it a
constraint, not a resource: every unit in it costs 8k bits in stream B
regardless of what the parse could have done with it. The best dictionary is
therefore the smallest one from which the rest of the input can still be
built.

There is a hard floor on how small. A unit whose value has not appeared
earlier in the stream cannot be copied and cannot be matched, so it is a
literal in every possible parse. The first occurrence of each distinct value
is a lower bound on any dictionary, and the opening dictionary is already
close to it:

| input | k | units | floor | opening dictionary | at the window |
|---|---:|---:|---:|---:|---:|
| Deeper's thirty columns | 2 | 149760 | 1793 (1.2%) | 2220 (1.5%) | 5465 (3.6%) |
| prose | 1 | 20842 | 86 (0.4%) | 1707 (8.2%) | 10974 (52.7%) |

On register columns the opening dictionary is within a quarter of the floor:
there is no room below it. A dictionary built at the window is two and a
half times the floor, and one built by marking the source of every repeat
beyond the window reaches 50 to 82 percent of the input.

### What it packed

Deeper's thirty columns, 9,984 rows each, at k = 2 and a window of 60 units,
summed over the columns:

| dictionary | packed bytes | against the opening one |
|---|---:|---:|
| the opening one, a full-reach parse's literals | 17796 | |
| a parse at the window | 21304 | +20% |
| the source of every repeat beyond the window | 36966 | +108% |

The corpora at k = 1, one-shot parses:

| corpus | M | opening dictionary | at the window | difference |
|---|---:|---:|---:|---:|
| far-match | 16 | 448 | 840 | +392 |
| period-129 | 16 | 204 | 1064 | +860 |
| word-soup | 16 | 1188 | 2398 | +1210 |
| word-soup | 64 | 1032 | 1630 | +598 |
| class file | 16 | 5948 | 7148 | +1200 |
| class file | 64 | 5576 | 6146 | +570 |
| prose | 16 | 17610 | 20002 | +2392 |
| prose | 64 | 15832 | 15506 | -326 |

Only one cell of ten packs smaller: prose at 64 units, by 2 percent. Prose
is also where the floor lies furthest below the opening dictionary, so it is
the one corpus with room to find. Everywhere else the larger dictionary pays
for its extra literals and the extra copies do not pay them back.

### How often the ring is used on periodic input

A window-built dictionary assumes the ring is consulted rarely. On register
columns it is consulted most of the time. The same tune packed through a
120-byte ring, counting every reference:

| tune | matches | copies | reps | matches, as a share of references |
|---|---:|---:|---:|---:|
| Deeper | 3744 | 684 | 790 | 84.5% |
| DitherDance | 860 | 93 | 200 | 90.2% |
| low | 3614 | 425 | 763 | 89.5% |

A match and a copy cost the same eleven bits, so the parse picks whichever
reaches further, and on a column that repeats every few rows that is the
match. Shrinking the ring further, to push more references onto the
dictionary, packs larger rather than smaller: Deeper goes from 18,384 bytes
at a ring of 120 to 27,712 at 60.

### Where the search's gain comes from

The search does improve on the opening dictionary, by one to fifteen
percent, and it does so by moving above the floor: freeing or seeding a
literal run where making one unit literal lets many later units copy from
it. The floor is the right place to start. A dictionary chosen by the window
starts above it and has to come back down.

## Folding the class bits into the offset byte

A reference is written as 1 flag bit, 2 class bits and one byte of stream C,
or a word of stream D for an offset beyond 512 units. With a small window M
the offset byte has spare values: a match needs only 1..M, so M+1..255 can
encode a near copy directly, and 0 can mark an escape to a word. The byte
then identifies itself and the two class bits are not needed: 11 bits down
to 9 per reference.

Through a 120-byte ring at k = 2, where M is 60 units, 98.6 percent of a
tune's references fit the near bank and none needs a word, so nearly every
reference would use the short form:

| tune | references | near bank | far bank | word |
|---|---:|---:|---:|---:|
| Deeper at a ring of 120 | 4428 | 4364 | 64 | 0 |
| Deeper at a ring of 960 | 2397 | 2096 | 256 | 45 |

### What it saves

In bytes, summed over the thirty columns:

| tune | packed at a ring of 120 | folded | smaller by |
|---|---:|---:|---:|
| Deeper | 18384 | 17409 | 5.3% |
| DitherDance | 16680 | 16442 | 1.4% |
| low | 17620 | 16899 | 4.1% |

In cycles, measured by `68k/test/emu/bench_offset.py`, which assembles both
decode paths out of ST4_wrap.S and counts them over the references of a real
stream: the offset path drops from about 59 cycles to about 38. Counting
also the stream A refills that the two removed bits no longer cause, the
saving is about 24 cycles per reference. References make up 4,428 of the
6,609 operations at that ring, so an operation of 225 to 240 cycles loses
about 16 of them: seven percent.

### Why it was left alone

Seven percent of a decode that is itself a small part of a frame. One column
is refilled per row, and at a ring of 120 a refill parses 0.66 operations on
average, so a 50 Hz frame of 160,000 cycles spends about 153 of them in ST4.
The change would save about 11. The costliest frame parses eight operations
and would save 128.

In bytes, the change lands on a resident total the ring change has already
cut. A tune with its rings goes from 43,528 bytes at a ring of 960 to 24,456
at 120; the folded byte would bring that to 23,481. The ring is worth 44
percent, the format change a further 4.0, 1.1 and 3.0 percent on the three
tunes.

The cost of the change is a format version bump, three decoders at three
unit sizes, the Java, Go and C# packers, and every packed asset in
existence. That is not worth one to four percent. The measurement is
recorded so the question need not be reopened without one.

## A penalty a block, against the costliest frame

A decoder parses one block at a time, and a DTX2 refill parses whichever
blocks begin inside its window. So what a frame pays for is the number of
blocks it meets, not the bytes the column packs to. The parser can be asked
for fewer blocks: `st4 -pN` charges N extra bits on every block beyond what
the block writes, so a chain of fewer, longer blocks wins wherever the bit
costs are close. At a penalty of zero the reference parser produces exactly
the bytes the event-driven parser does, which is what makes the rows below
comparable.

Deeper's thirty columns at k = 2 (a window of 15 units) and low's thirty at
k = 1 (a window of 30):

| tune | penalty | bytes | against 0 | blocks a window | worst | 8 or more |
|---|---:|---:|---:|---:|---:|---:|
| Deeper, k = 2 | 0 | 11956 | | 0.367 | 9 | 0.03% |
| | 4 | 11972 | +0.1% | 0.358 | 9 | 0.01% |
| | 8 | 12060 | +0.9% | 0.349 | 9 | 0.01% |
| | 16 | 12292 | +2.8% | 0.333 | 7 | 0.00% |
| | 32 | 12804 | +7.1% | 0.314 | 7 | 0.00% |
| | 64 | 14368 | +20.2% | 0.287 | 7 | 0.00% |
| low, k = 1 | 0 | 10780 | | 0.503 | 15 | 0.81% |
| | 8 | 10970 | +1.8% | 0.437 | 12 | 0.40% |
| | 16 | 11304 | +4.9% | 0.413 | 11 | 0.26% |
| | 32 | 11956 | +10.9% | 0.389 | 10 | 0.12% |

### What it is worth

The worst window is what a demo has to budget for. At k = 1 it was 15
blocks, the most a 30-unit window fits at one block per unit; a penalty
of 8 bits brings it to 12 for under two percent more file. At k = 2 a
penalty of 16 brings 9 down to 7 for 2.8 percent, and 32 and 64 buy no
further reduction: the worst window stays at 7 while the bytes keep rising.

At 225 to 240 cycles a block, against the 6,656 cycles in the thirteen
scanlines YMXR's R4.5 allows the worst frame, the refill at k = 1 was 52
percent of that budget; a penalty of 8 makes it 42.

### Neither parser is exact at a penalty

The event-driven optimizer now applies the penalty too, so `-pN` no longer
needs the reference parser's quadratic time. Comparing the two found that
they disagree at any penalty above zero, and an exact parse of the same
inputs showed that neither is right.

The exact parse is a dynamic program over position and last offset that
tries every literal run, every rep and every new offset at every length. It
is far too slow for a real column, but it settles small cases:

| input | penalty | exact | reference | event-driven |
|---|---:|---:|---:|---:|
| `7 7` | 16 | 35 | 43 | 35 |
| `5 5 5 5` | 64 | 101 | 141 | 101 |
| `9 8 9 8 7 7 7 1` | 16 | 87 | 107 | 107 |
| a column's first 200 units | 8 | 461 | 465 | 465 |
| the same | 16 | 533 | 569 | 569 |
| the same | 32 | 677 | 777 | 777 |

Both parsers are exact at a penalty of zero, which their tests confirm. The
gap opens as the penalty grows: none at 4, 0.9 percent at 8, 6.8 at 16, 14.8
at 32 on the column above. Another column is exact at every penalty tried.

The cause is ZX1's parse structure. A literal run is only ever extended at a
position where no offset matches, and the state kept per offset is the
cheapest chain ending in a literal run or in a match at that offset. That is
sufficient when only bits count. A charge per block makes the number of
blocks matter as well, and those figures do not record it.

So the byte figures in the table above are what these parsers reach, not
what an exact penalised parse would cost: the true price of a shorter worst
window is lower than they show. The window counts stand, since they were
read from files that were really written.

### Where the event-driven parser is exact

The gap closes at the penalties that matter. Seven columns of a tune, the
first 300 bytes of each, against the exact parse at k = 2 with a window of
64 units:

| penalty | columns exact | the widest gap |
|---:|---:|---:|
| 2 | 7 of 7 | none |
| 4 | 7 of 7 | none |
| 8 | 5 of 7 | 0.65% |

A penalty of 2 or 4 is exact on every column tried, and 8 is within a
percent, so a sweep over that range measures the parse rather than the
parser.

### The sweep

Fourteen corpus tunes, every column packed at each penalty, the windows
counted from the resulting files. A refill is 15 units at k = 2 and 30 at k
= 1.

| unit | tunes | penalty | bytes | against 0 | the widest window | a tune's worst, mean |
|---:|---:|---:|---:|---:|---:|---:|
| 1 | 4 | 0 | 11362 | | 14 | 13.0 |
| | | 2 | 11380 | +0.16% | 13 | 11.8 |
| | | 4 | 11430 | +0.60% | 13 | 11.5 |
| | | 8 | 11588 | +1.99% | 10 | 9.8 |
| 2 | 10 | 0 | 51880 | | 11 | 9.4 |
| | | 2 | 51908 | +0.05% | 11 | 9.2 |
| | | 4 | 51992 | +0.22% | 11 | 8.8 |
| | | 8 | 52280 | +0.77% | 11 | 8.7 |

**A penalty is for unit 1.** A unit-1 refill spans 30 units, twice a unit-2
refill, so twice as many blocks can land in it. A penalty of 8 brings the
widest window from 14 to 10 and cuts the windows needing 8 blocks or more
from 1.45 to 0.80 percent, for two percent more bytes. At unit 2 the widest
window is 11 at every penalty tried, the mean of the tunes' worst windows
moves only from 9.4 to 8.7, and windows needing 8 or more stay at 0.08
percent: there is little to gain.

A few tunes come out a few bytes smaller with a penalty than without. That
is stream padding to long boundaries over thirty columns, not a parse that
beat the unpenalised optimum.

## Sources

- J. A. Storer, T. G. Szymanski, *The macro model for data compression
  (extended abstract)*, STOC 1978 -
  <https://www.semanticscholar.org/paper/686b27e3d215720b57c6c498ddb734b6faab578b>
- J. A. Storer, T. G. Szymanski, *Data compression via textual substitution*,
  JACM 29(4), 1982 - <https://dl.acm.org/doi/10.1145/322344.322346>
- L. M. S. Russo, G. Navarro, A. Correia, A. P. Francisco, *Approximating
  Optimal Bidirectional Macro Schemes*, 2020 -
  <https://arxiv.org/abs/2003.02336>
- T. Nishimoto, Y. Tabei, *LZRR: LZ77 Parsing with Right Reference*, 2018 -
  <https://arxiv.org/abs/1812.04261>
- LZ4, `lz4.h`: `LZ4_decompress_safe_usingDict` and streaming decompression -
  <https://raw.githubusercontent.com/lz4/lz4/dev/lib/lz4.h>, manual at
  <https://fossies.org/linux/lz4/doc/lz4_manual.html>
- E. Saukas, ZX0 - <https://github.com/einar-saukas/ZX0>
- Wikibooks, *Data Compression: dictionary compression* -
  <https://en.wikibooks.org/wiki/Data_Compression/Dictionary_compression>
- encode.su, *LZ style compression with static dictionary* -
  <https://encode.su/threads/2995-LZ-style-compression-with-static-Dictionary>

# Can a step skip the tail it has already parsed?

The note above reads 22 to 80 per cent of a step's tail as work already
done: the parse rejoins the parse before it and agrees with it to the end
of the column. This reads whether a step can stop there.

## Verdict

**Not exactly.** A copy reads from any literal before it, and the literals
before a position set every later copy's offset, so a dictionary changed
anywhere changes both what the tail may copy and what a copy there costs.
Two parses in one state at a checkpoint part again as soon as the tail
reads a unit the change touched. The state is not the whole of what a tail
depends on; the dictionary is.

**Heuristically it is a wash, and worse where it counts.** A parse that
stops where its state equals the state of the parse before it, and whose
chain reads that parse's blocks past there, runs a fifth of a column
against two thirds. The steps that buys read half a per cent better on a
24-column subset and 0.35 per cent worse over the whole corpus, and they
are approximate, so the packer writes other bytes: measured here, not
shipped.

## What the stop is

At each checkpoint the parse compares its state with the state the accepted
parse had there: every state's cost and end, every literal run, the counts
and lists of the copies a distance is open at, the winner at every
position, and every value of the literal channel. Where the two are equal
the parse stops, and its chain is the blocks it made up to there and the
accepted parse's blocks past there.

The comparison is equality, not equality up to a constant. Up to a constant
is what the reading above found, and it is not enough to rest on: the
channel's values before the change stand where they were while those after
it move, so a class whose window spans the change can pick one end in one
parse and another in the other.

## What it costs and buys

Over the 24-column subset, where the default writes 23,124 bytes:

| the search | steps a column | of a column parsed | seconds | bytes |
|---|---|---|---|---|
| exact | 500 | 66% | 27 | 22,208 |
| exact | 700 | 70% | 37 | 22,080 |
| exact | 2,000 | 70% | 107 | 21,760 |
| the stop | 500 | 19% | 8 | 22,776 |
| the stop | 1,500 | 21% | 23 | 22,280 |
| the stop | 2,500 | 20% | 38 | **21,962** |

At equal steps the stop reads worse, which is the approximation showing:
22,776 against 22,208 at 500 steps. At equal time it reads better, by 0.53
per cent at 37 seconds: 21,962 against 22,080. A parse runs a fifth of a
column rather than two thirds, and 56 per cent of the parses stop early.

**The whole corpus reads the other way.** At equal time over the 120
columns, the exact search writes 120,020 bytes in 341 seconds at 1,000
steps a column and the stop writes **120,440** in 337 at 3,400 steps: 0.35
per cent worse, where the subset read 0.53 per cent better. The cheaper
steps are bought at a price that the wider reading charges in full.

That is the second reading where the 24-column subset points the other way
from the 120 - the opening of destroy and repair is the first, at 0.97 per
cent on the subset against 0.12 on the corpus. A subset reads a search's
shape; what a change is worth is a figure only the corpus settles.

## Where that leaves it

The trade is a search that makes more, cheaper steps against one that makes
fewer exact ones. Half a per cent at equal time is worth having, and what
it costs is the property every reading in these notes rests on: that a step
weighs the parse a dictionary would write. Shipping it means all three
trees stopping at the same checkpoint on the same comparison, since the
parity between them is on the bytes out.

# What a step of the search costs

The search grinds: the note above reads it still improving at 4,000 steps a
column, where a step is one dictionary, one parse of it and one count of
the compressor's bits. So what a step costs is what the search is worth in
a second of it.

## Where the time goes

Six columns at 400 steps each, under a CPU profile:

| | share |
|---|---|
| the parse | 83% |
| of it: the literal channel's reads of the min-tree | 13% |
| of it: the best-split table | 13% |
| of it: the ring loop and the rest | the remainder |
| the compressor's count of the bits | below a per cent |

A step is a parse. The count that scores it does not register.

## A parse runs to the end of the column

A parse restarts at the checkpoint before the first changed unit, which is
why the checkpoint grid reads flat in the note above: it saves the prefix,
and what it cannot save is the tail. Over 2,428 steps of six columns, the
first changed unit stands at 41 per cent of the column on average and **a
parse runs 66 per cent of it**.

Most of that tail is work already done. Reading each step's parse against
the parse before it, the two agree on the winner at every position from
some point on, their costs apart by one constant:

| column | the tail a step re-parses | where the two parses rejoin | work already done |
|---|---|---|---|
| 9,750 units | 3,478 | 697 | 80% |
| 3,844 units | 1,590 | 828 | 48% |
| 4,150 units | 2,019 | 540 | 73% |
| 5,248 units | 4,103 | 3,182 | 22% |

In 13 to 16 of every 40 steps the parse past the change is the same parse.
A step that stopped where the two rejoin, and read the rest off the parse
before it, would cost about half of what it costs. That is unwritten: the
literal channel reads back over the whole prefix, so two parses that rejoin
can part again, and a test that says when they have is the piece still
missing.

## The literal channel reads its least in one step

What is done instead is the 13 per cent. The channel asks, at every
position, for the best match or copy end within each gamma class of the run
length that reaches it. A class is a window in slot space that slides one
slot a position, so a queue kept least first reads its least in one step,
where the min-tree read it in a logarithm.

The parse writes the same bytes: 88 runs of eight inputs at eleven flag
settings against the packer before it, and the three trees against one
another. What moves is the time.

| | before | after |
|---|---|---|
| 500 steps a column, 24 columns | 33s | 27s |
| 2,000 steps a column, 24 columns | 133s | 107s |
| the corpus at `-c` | 5.5s | 5.2s |
| a 48 KB file at `-k1 -c`, window 32,512 | 23.1s | 22.8s |

The search is where it pays: a fifth more steps a second at a ring of 256
bytes. At a wide window the ring loop is the parse and the channel is
noise, which is what the last row reads. At three seconds a column over 24
columns the extra steps are worth 0.35 per cent: 21,936 bytes against
22,012.

# Can the search find better parses at a ring of 256 bytes?

One column of a chiptune - a channel's tone period, one byte a frame -
packed alone through a 256-byte ring, where a match beyond the ring copies
from the literal stream. `st4 -c` alone writes the opening passes;
the search that may follow is simulated annealing over which units are
literal. How near is either to what a parse could reach?

## Verdict

**The opening passes leave 5.1 per cent, and the curve is still falling.**
The search finds better parses than the default for as long as it runs: 4.4
per cent below it at ten seconds a column, 5.1 at twelve, and on a
24-column subset 8.6 per cent below at 8,000 steps a column.

**The schedule is not what bounds it.** The search already anneals, from 10
bits hot to 0.3 cold, and returns to its best after 2,000 steps without
one. **The neighbourhood is**: at the dictionary the search returns, every
single-run move is exhausted.

## The corpus

120 columns of real tunes, the three tone-period low bytes of each, 898,830
bytes in all, packed a column at a time at `-k2` through a ring of 256
bytes. ST4 without copies writes 177,356 bytes of payload over them; with
copies and the opening passes alone, 124,220.

## What the search is worth

| search a column | bytes | against the default |
|---|---|---|
| none, the opening passes | 124,220 | - |
| 1 second | 121,894 | -1.9% |
| 3 seconds | 120,192 | -3.2% |
| 10 seconds | 118,796 | **-4.4%** |

A step budget reads the same and repeats exactly, a step being one
dictionary, one parse of it and one count of the compressor's bits: 1,000
steps a column writes 120,020 bytes and 4,000 writes **117,942**, 5.1 per
cent below the default at twelve seconds a column. Deeper, on a 24-column
subset whose default is 23,124 bytes: 22,208 at 500 steps, 21,760 at 2,000
and 21,138 at 8,000, which is 8.6 per cent below the default and falling
there too.

## The incumbent is a local optimum of every single-run move

After 1,000 steps, every literal run of the dictionary was freed, trimmed
by one at either end, extended by one at either end, and shifted by one
either way, and each of those parsed and counted:

| column | units | runs | moves that pack smaller | the best of them |
|---|---|---|---|---|
| one | 9,750 | 68 | 1 of 476 | 6 bits of 12,773 |
| two | 8,256 | 72 | 1 of 504 | 30 bits of 7,267 |
| three | 9,236 | 141 | 10 of 987 | 16 bits of 22,477 |

So the search sits where the moves it makes reach, and what it finds after
that comes from the moves annealing accepts uphill.

## One chain beats a portfolio

Over a 24-column subset: five seeds of 500 steps each, the best of the five
a column, writes 22,048 bytes; one chain of 2,000 steps writes 21,760. The
spread between seeds at 500 steps is 0.9 per cent. The search is not
luck-bound, so restarts are not the lever either.

## The checkpoint grid is not the lever

A parse restarts from the last checkpoint before the first changed unit, so
a finer grid makes a move cheaper and buys steps a second. At two seconds a
column the grid reads flat: 8 slots writes 22,264 bytes and 128 slots the
same 22,264, and a floor of 128 units rather than 1,024 moves it by 0.1 per
cent either way.

## What copying would be worth if the dictionary were free

Relax the format: let a copy read from any earlier position, literal or
not, and charge the fewest literals the format allows between the source
and its use. Every parse a real dictionary allows is allowed here and costs
no more, so what the relaxed parse writes is a bound no dictionary beats:
**80,106 bytes** against the 124,220 the packer writes.

The bound is loose, and its looseness is the tension itself. The relaxed
parse reads from **40.4 per cent of all units**, and a dictionary of those
units, at 16 bits each, writes 410,792 bytes before a sweep shrinks it.
Copying wants a large dictionary and the dictionary is charged by the unit,
which is what the search is weighing at every step.

## Destroy and repair

Since every single-run move is exhausted at the incumbent, the next
neighbourhood is a window rebuilt whole: the dictionary cleared over a
window of units, rebuilt, and the column kept when it packs smaller. Two
repairs were read against the annealing at the same number of parses. One
seeds at random inside the window. The other seeds by what copying wants
there: a parse that lets a source inside the window be free names the
positions a copy would read from, and the repair seeds those and lets the
rest of the search trim them.

On a 24-column subset, 500 parses a column:

| the search | bytes | against the annealing |
|---|---|---|
| the annealing | 22,208 | - |
| a window of 256 units, seeds at random | 22,862 | +2.94% |
| a window of 256 units, seeded by what copying wants | **22,130** | **-0.35%** |
| a window of 512 units, seeded by what copying wants | 22,590 | +1.72% |
| a window of 1,024 units, seeded by what copying wants | 22,902 | +3.12% |

Random seeding rebuilds little of a cleared window, so the window is rarely
kept. Seeding by what copying wants rebuilds it, and the narrowest
window reads best of the three.

**The edge is early, not late.** At 2,000 parses a column the annealing
passes it, 21,760 against the window search's 22,044. A window rebuilt
whole moves the dictionary a long way in one step, which pays while the
dictionary is coarse and costs while it is being polished.

**So run the one and then the other.** On the same subset at 2,000 parses a
column:

| the search | bytes | against the annealing |
|---|---|---|
| the annealing alone | 21,760 | - |
| 500 parses of destroy and repair, then the annealing | 21,584 | -0.81% |
| 250 parses of destroy and repair, then the annealing | **21,548** | **-0.97%** |

The shorter of the two openings is the better, so what it buys is the
coarse shape of the dictionary rather than its detail.

**On the whole corpus it is worth far less.** At 1,000 parses a column, 250
of destroy and repair before the annealing writes 119,876 bytes against the
annealing's 120,020: 0.12 per cent, where the subset read 0.97. The subset
reads high. What the opening is worth lies between the two, and one corpus
at one budget does not settle it, so this is a result to read further
rather than one to build on.

## What is left to try

- **Seeding by what a copy would pay.** The window repair seeds every
  position copying wants inside its window. Ranked by what each saves
  against its alternative, the top of that list may be enough; seeding
  from the whole of a column does not pay, at 410,792 bytes.
- **A tighter bound.** The relaxation lets a source be free. One that
  charges a price a unit, swept over the price, would say how much of the
  4.4 per cent is reachable.

## What a caller does today

Spend the seconds on an asset that ships. `st4 -c10` writes 4.4 per cent
less than `st4 -c` over this corpus, and the tools above it name the same
budget: `dtx-write -copiesS` and the YMXR converters through it.

# What a short-offset class is worth against the packer

The models above put a short-offset class at about 2 per cent and were
2.1 and 3.6 per cent optimistic against the packer besides. This prices it
on the blocks `dtx-write` writes.

## Verdict

**4.25 per cent**, and the figure rests on which class code grows the third
bit: the end code saves 4.25 where the word-offset code saves 2.82. Five
bits beat four and six. The figure is a floor, since the parse it is priced
on was made under the old costs, and a parse under the new costs has more
short offsets in it.

It costs the decode 3.7 per cent: a near offset is five bits out of stream
A where a byte offset is one byte out of stream C.

## What the parse is

Every column of the 120 packed at `-k2 -m256 -copies`, the stream read back
block by block. The blocks decode to 124,220 bytes, the figure the packer
writes, so the figures below cover the whole parse: 8,634 literal
runs, 28,226 matches at a new offset and 2,214 at the last offset.

Half the new offsets are within 32 units.

| offset | matches | share |
|---|---|---|
| 1 to 16, four bits | 9,592 | 34.0% |
| 1 to 32, five bits | 14,362 | 50.9% |
| 1 to 64, six bits | 17,788 | 63.0% |
| 33 to 128, the ring | 6,249 | 22.1% |
| 129 to 256, a copy from bank 0 | 4,479 | 15.9% |
| 257 to 512, a copy from bank 1 | 2,438 | 8.6% |
| past 512, a word | 698 | 2.5% |

## Which code grows

A new-offset match spends a flag bit, two class bits and a byte today. A
fifth outcome needs one of the four class codes to grow a third bit, and the
code to grow is the rarest: the end code stands once a stream, 120 times
over the set, where a word offset stands 698 times.

| the code that grows | four bits | five bits | six bits |
|---|---|---|---|
| the word-offset code | 2.83% | 2.82% | 1.72% |
| the end code | 3.78% | **4.25%** | 3.50% |

At the end code a near match spends two class bits and five, seven against
the ten it spends now, and the word offset and the end each spend one more:
14,362 x 3 - 698 - 120 = 42,268 bits, 5,284 bytes of 124,220.

## What it costs to decode

A byte offset is `move.b (a2)+,d1` and an `ext.w`, 16 cycles, and the two
class bits 24: 40 cycles. A five-bit near offset is five reads out of
stream A at ST4's 12 cycles a bit, 60, and the same two class bits: 84. The
44 cycles between them, over 14,362 near matches and 898,830 output bytes,
is 0.70 cycles a byte on the 19.00 the format decodes at, 3.7 per cent.

So the trade is 4.25 per cent of the file for 3.7 per cent of the decode,
and the file figure is the one that grows when the parse is made under the
new costs.

# What the copies search costs in memory

The question: `st4 -c` on a 41 KB file ends in `OutOfMemoryError` under the
Java tools at the default heap, where the Go tools pack it. This note
records what the memory is spent on, since the first two answers were wrong.

## Verdict

The search is sound and keeps almost none of it: after it returns, the heap
has 1 MB. What it spends is churn. The section below prices the pool itself
and collects it: 6,472 MB becomes 1,386 MB, at the same bytes. Packing
`doc/research.md`, 41,743 bytes, at `-k1 -c` allocates **33 GB** in total
and asks the operating system for 15 GB, against a live set of about 5 GB.
98.2 per cent of every byte allocated is one function, `newNode`.

Java ends in `OutOfMemoryError` because the JVM caps the heap at a quarter
of the machine, 4 GB of 16; Go has no cap and reaches 6.2 GB. The two write
the same bytes. The cap is the messenger.

## What it is not

Two readings were measured and are wrong.

**Not the pool's retention.** The pool compacts at four times a full parse.
Lowering that to one makes the peak **worse**, 6,596 MB against 6,212, and
at four the limit is never reached at all: 0 compactions over the opening
passes. The pool bound is not the lever.

**Not the width of a node.** Go kept the five fields beside the kind in
`int`, eight bytes where the Java and C# pools spend four. Narrowing them to
`int32` leaves the peak inside the noise of repeated runs, 6,120 MB against
6,433 at best of three. It is worth having for the time it saves, 24.7
seconds against 61.0, and it is not the memory.

## What it is

A parse materialises one node a DP state reached, and a state is a
position and an offset. One full parse of those 41,743 units at a window of
32,512 makes **85,971,635 nodes**, and the opening passes reach 261,766,765:
6,270 a position. The pool grows to fit them, geometrically, and every
growth abandons the array it came from. That abandoned array is the 33 GB.

The two parsers beside it keep, for each position, the winner and enough to
re-derive the state, and walk the positions backwards to rebuild the chain.
Their memory is positions plus window rather than positions by offsets. On
the same input:

| parser | peak | time | bytes out |
|---|---|---|---|
| the event-driven DP | 274 MB | 3.6s | 17,614 |
| the reference DP | 258 MB | 2.9s | 17,614 |
| the copies search | 6,800 MB | 36.4s | 17,612 |

Twenty-six times the memory, for two bytes, on prose. Prose is the wrong
input for copies: on a tone-period column of 19,500 bytes at `-m256` the
copies search costs 75 MB against the event-driven parser's 9 and packs
1,494 bytes against 2,100.

## What the window is worth

The window bounds how many there are, so `-m` bounds the memory. On the
same 41 KB
input at `-k1 -c`:

| window | peak | seconds | bytes out |
|---|---|---|---|
| the default, 32,512 | 6,337 MB | 49.3 | 17,612 |
| 2,048 | 3,724 MB | 6.1 | 20,666 |
| 960 | 2,313 MB | 5.1 | 22,274 |
| 256 | 610 MB | 4.0 | 25,662 |
| 64 | 396 MB | 3.9 | 30,868 |

Every asset this format is written for names a window: a DTX2 column packs
at a ring of 960 bytes and a YMXR tune at 256. The unbounded case is a large
input that compresses badly at the widest window the format has, which is
outside what the search is for.

## What the pool keeps

The reading above priced the representation and left the pool alone. The
pool was then measured: of the nodes one parse makes, almost none are
reachable when it ends. This document at 48,063 units, `-k1 -c` at the
default window, makes **108,501,869 nodes and ends with 1,262,957
reachable**, 1.16 per cent; the opening passes make 330,000,707 and end
with 6,394,183, 1.94 per cent. The pool grew for every one of them.

So the pool collects. A collection marks from the arrays that name a node -
each state, its literal run and its predecessor, the winner and the best
match at every position the parse has written, and the checkpoints of the
base and of the parse under way - renumbers what it marked into the front
of the pool, and bounds the next collection at twice that. A node's
predecessor is a node made before it, so one pass over the pool in order
renumbers a node after the predecessor it names; the base's nodes stand
below the top a restore drops to, and the pass counts them so the top
follows.

Ids move, and a parse weighs its candidates by their bits alone, so the
bytes out are what they were. The node lost two of its six fields with the
collection: the kind, which the rebuild reads off the offset, since a
literal run stands at zero and a match or a copy never does, and the
length, which the rebuild never reads. The two per-state arrays that fed
them went with them.

On this document, at `-k1 -c`:

| | peak | seconds | bytes out |
|---|---|---|---|
| the pool as it was | 6,472 MB | 29.5 | 20,292 |
| the pool collected | **1,386 MB** | **18.1** | 20,292 |

The Java tools are where the reading began, and the heap they need is the
figure that moved:

| | the smallest -Xmx that packs it | seconds at -Xmx12g |
|---|---|---|
| the pool as it was | above 8 GB, packing at 12 GB | 34.8 |
| the pool collected | **1 GB** | 29.9 |

A search rather than the opening passes alone reads the same way: `-c20` at
the default window reaches the same 21,328 bytes at a peak of 1,088 MB
where it reached them at 5,869.

Checked byte for byte: 88 runs over eight inputs and eleven flag settings
against the packer before the change, 50 round trips through the
decompressor, and the three trees against each other over the same matrix.

## What is left, and where it stands

A collection keeps 10.4 million nodes at its widest. **0.9 million of them
are the parse's and 9.5 million the eight checkpoints'**: a checkpoint
keeps the chain of every state a restart from it reads. Fewer checkpoints
trade that against how far a parse re-runs, and the pool bound trades it
against time - at 1.5 times what a collection keeps the peak is 1,191 MB
and the run 20.9 seconds.

The representation is what those chains are: a node a state against a
winner a position. Re-deriving a state on demand, as optimize.go's
`rebuilder.resolveState` does for the two parsers beside it, is still the
way to be rid of them, and is still a redesign of the parse, the
checkpoints and the rebuild together. It is not written here.

# Is there a better algorithm at a ring of 256 bytes?

The question: at a 256-byte ring ST4 packs the tone-period columns of a
chiptune to 177,360 bytes where a 960-byte ring packs them to 112,972, and
a player with 256 bytes needs that 57 per cent back. This note records what
the literature offers, what each family measures on the data, and which of
them a 68000 affords.

## Verdict

Copies from the literal stream recover 1.43 of the 1.57, to 124,220. No
complete format published beats that number here. Of 108 candidates over
six branches of the literature the closest is ZX2 with a short-offset
class, 128,048 bytes, 3.1 per cent behind, and the cause repeats in every
member: the family codes a match against a window, and none of them reaches
past one.

Two edits to ST4 pay. A short-offset class of five bits is worth about 2
per cent, measured twice by separate routes. A match that reaches into the
literal stream is worth 2.7 per cent within a column, and 3.9 per cent
where it also reads the two other tone-period columns of the same tune.

A grammar packs smaller than ST4 at any ring, 100,232 bytes, and costs 302
cycles an output byte against ST4's 19. The 68000 settles that one.

## What the figures are measured on

120 columns: R0, R2 and R4, the low bytes of the three tone periods, of 40
tunes drawn from a 543-tune corpus by the rig's rule, every 13th by name.
898,830 bytes raw. Those three columns are 61 per cent of the compressed
data of a tune. Each column packs alone, as ST4 packs it, and the 28-byte
container header is excluded.

| what packs it | bytes |
|---|---|
| stored | 898,830 |
| ST4, ring 256 | 177,360 |
| ST4, ring 256 with copies | 124,220 |
| ST4, ring 960 | 112,972 |
| ST4, ring 960 with copies | 98,400 |

The constraints a candidate satisfies: a decoder buffer of about 256 bytes,
a decode cost near ST4's, and no Huffman code and no range coder. The third
is settled separately: a Huffman code capped at 8 bits, which one table
lookup decodes, saves 0 per cent on this data, because all 256 values occur
in every column and the eight commonest cover 27 to 30 per cent. An
unbounded code saves 15 per cent of the literal stream and reads it bit by
bit, which is the aligned literal copy stream B exists to provide.

## What each family measures

| family | best member | bytes | why it loses |
|---|---|---|---|
| bit-coded small-window LZSS: ZX0, ZX1, ZX2, ZX5, ZX7, aPLib, apultra, nrv2b, Pucrunch, MegaLZ, Pletter, Bitbuster, Doynax-LZ, LZB, BriefLZ | ZX2 with a short-offset class | 128,048 | ST4 descends from this family through ZX1 and ST1, and its members sit within a few per cent of each other. None reaches past the window, which is worth 1.43 here. ZX2 130,300, ZX0 131,457, aPLib about 135,600, LZB 141,113, BriefLZ 149,576. |
| byte and nibble token formats: LZ4, LZSA1, LZSA2, LZ48, LZ49, FastLZ, LZO1X, Snappy, LZJB, LZRW1 | LZ4 with an 8-bit offset | 142,525 | A token of 8 fixed bits where ST4 spends 1 to 3 on a flag, and a minimum match of 3 or 4 where 18 per cent of matches here are 2 bytes. They buy cycles rather than bytes: 60 to 90 cycles a token against ST4's 239. |
| LZ78 and LZW: LZ78, LZW, LZC, LZT, LZMW, LZAP, LZWL, LZFG, LZJ, LZD, V.42bis | LZAP, 65,536 entries | 124,152 | A tie, at about 201 KB of decoder RAM. At 4,096 entries it packs to 400,512, and the members that fit 256 bytes to 436,875 and worse. The next section has the reason. |
| context prediction: LZP1 to LZP4, the RFC 1978 PPP predictor, ROLZ, LZRW3, LZRW4 | order-2 LZP | 157,972 | The offset LZP predicts is the offset the parse selects in 1.6 to 2.2 per cent of matches, at every table size and order tried: a tone-period column repeats at the period of the musical pattern, and LZP predicts from the preceding byte. It also floors at one flag bit an output byte, 112,357 bytes, before a length is coded. |
| entropy coding: LZX, LHA, Hrust, Exomizer 2 and 3, Shrinkler, upkr, PPM, an order-1 range coder, Tunstall, move to front, BWT | - | - | Outside the third constraint, and outside the first independently: Shrinkler keeps 3,072 bytes of context, upkr 385, Exomizer 156 plus a header, against columns of 126 bytes. A binary range decode reads a bit with a `mulu.w`, 54 to 70 cycles. Order-1 coding of the literals alone is about 30 KB, a quarter of the target, which is the size of what the constraint costs. |
| time-series integer coding: Gorilla delta-of-delta, frame of reference, simple-8b, PFOR-delta, bit-plane split, zero-run coding, two-level RLE | Gorilla | 775,736 | The first difference is 0 on 16.85 per cent of frames and the second on 31.8, where the data Gorilla was written for codes 96 per cent of timestamps to one bit. An RLE pass in front of ST4 packs to 135,486: ST4's last-offset block already codes a run of n in about 2 times bits(n), and the two code the same runs twice. |
| plane splits and reorderings: byte-plane split, lo and hi interleaved, the nibble split of R1, a change-flag map, seasonal differencing | lo and hi interleaved | 172,806 | R0 and R1 cost 165,596 together today. The two change at the same instants, at note boundaries, so one match in the joint column covers both and a split pays a second full-length stream for it. ST4 packs R0 and R1 as separate columns, so the byte-plane split is in place. |
| a dictionary trained over the corpus: zstd --train, Shared Brotli, femtozip, a static phrase table | zstd with a 16 KB trained dictionary | 67,065 | Trained on the test data itself and still behind: the payload falls from 58,956 to 50,681 and the dictionary costs 16,384. Repetition lives within a tune rather than across tunes, since two tunes share little beyond short runs of common values. |
| a match at a transposed offset: copy a run and add a constant | - | - | 87.0 per cent of positions have an exact match and 86.4 a constant-offset match, and 1.8 per cent have a constant-offset match where no exact match exists. The low byte wraps at 256, so a transposed phrase keeps its offset until a wrap falls inside it. |
| Fibonacci and Golomb-Rice codes for the token fields | - | - | Start-step-stop dominates both on every field: a match length costs 6.00 bits under Fibonacci, 6.02 under Rice at its best k, and 5.65 under (3,1,8). Start-step-stop covers Rice as the member (k,0,infinity). |
| unbounded-window packers: LZR, LHA, Pack-Ice, StoneCracker | - | - | The window is the file and the decoder owns the whole output buffer, which is the opposite of a player reading one byte a column a frame. Pack-Ice decompresses backwards in place. Recorded because Pack-Ice is the first answer to the words "Atari ST packer". |
| bidirectional macro schemes, LZRR | - | - | A phrase may copy from its right, so a right-pointing phrase names bytes the player has yet to produce. |

## Why a dictionary cannot replace the ring

The LZ78 family keeps a dictionary where LZ77 keeps a window, which reads
as the shape a hard buffer limit wants. The arithmetic settles it:

- a ring of 256 bytes addresses 32,896 pairs of an offset and a length, at
  0 table bytes, since the phrases are the window;
- a table of 256 bytes addresses 80 phrases, at 3 bytes each.

That is a factor of 400 in phrases a byte of RAM, and a dictionary policy
moves none of it. The measurements follow: LZW with 80 string entries and a
ratio-monitored clear packs to 436,875, LZT charged for its LRU links to
516,511, a byte-aligned LZW over a reduced alphabet to 586,230. Unbounded,
the family draws level and no further, at 124,152 for about 201 KB.

One policy is worth recording although this format has no use for it.
LZC's ratio-monitored clear, which resets the dictionary where the ratio
degrades, beat a frozen dictionary by 1.6 and beat every fixed interval.

## What was built

Ten candidates were implemented against the 120 columns and read by three
verifiers each, one on the size accounting, one on decodability, one on the
buffer and the cycles.

| candidate | bytes | buffer | cycles a byte | refuted |
|---|---|---|---|---|
| ST4 over a Re-Pair axiom | 100,232 | 328 | 302.8 | none of three |
| a short-offset class, the pairing dropped | 106,750 | about 292 | about 26 | within the build below |
| one rule table for three columns | 106,758 | 5,042 | 261 | the cycles |
| Re-Pair with the rule table in the container | 108,761 | 38 | 325 | the cycles |
| paired blocks and a short-offset class | 108,771 | 292 | 26.4 | none of three |
| start-step-stop codes for lengths and offsets | 113,980 | 396 | 19.4 | decodability |
| the composite of four builds | 116,502 | 468 | 25.6 | the cycles |
| a match that reaches past the ring | 116,869 | 272 | 19 | the size basis |
| short names for recurring offsets | 126,632 | 360 | 35.2 | the cycles |
| a delta filter before ST4, the control | 134,550 | 273 | 20.4 | the cycles |
| an exact pricing parser | 137,408 | 280 | 27.4 | two of three |

The control packs to 134,550, behind the baseline, which is the floor the
rest are read against. The start-step-stop figure is refuted: its bits were
counted for an encoding a decoder cannot read.

## What survives

**A short-offset class.** The paired-blocks build swept a 2 by 2 and
reported against its heading: the pairing costs 0.9 to 2.7 per cent in
all four arms, and a short-offset class saves 1.7 to 2.4 per cent in all
four, five bits beating four everywhere. Its best arm is the class with the
pairing dropped, 106,750. The survey reads the same feature from the other
side, lifting ZX2 from 130,300 to 128,048. The section below prices it
against the packer rather than a model, and reads 4.25 per cent.

**A reach past the ring, until it saturates.** A match that reads the
literal stream is worth 2.7 per cent within a column. Beyond that it
stops, for a reason that binds any later attempt: the literal stream
contains the bytes no match covered, 6.5 per cent of the output, so it
lengthens no match, since the bytes a longer match reads are the ones an
earlier match produced. The ring measurement reads the same way. From 256
to 960 the literals fall 9 per cent and the flag, length and offset streams
fall 33: reach lengthens matches, and a literal stream does no
lengthening. An unbounded ring packs to 83,222, which is the largest single
figure on this page and what 704 more bytes of RAM buy.

## What this leaves out

- Two builds priced their parse under a model of ST4 rather than the
  packer, 2.1 and 3.6 per cent optimistic against it. The differences
  within one model, where the short-offset class and the reach figures sit,
  are sound; the absolute figures are soft by that much. Confirm the class
  against the packer before it enters the format.
- The cycle figures are counted from 68000 instruction timings rather than
  measured on hardware. ST4's 239 cycles an operation is measured, and
  the models reproduce the rig's costliest frame to within 6 per cent.
- The 3.9 per cent reach reads the literal streams of the other two columns
  of the tune, which requires a packing order and 16 bytes of pointers. The
  streams are resident already.
- The unit k above 2, and the 27 columns of a tune outside these three.

## Sources

- T. Nishimoto, Y. Tabei, *LZRR: LZ77 Parsing with Right Reference*, 2018 -
  <https://arxiv.org/abs/1812.04261>
- E. Saukas, ZX0 - <https://github.com/einar-saukas/ZX0>
- E. Marty, LZSA - <https://github.com/emmanuel-marty/lzsa>
- E. Marty, a ZX0 decompressor for the 68000 -
  <https://github.com/emmanuel-marty/unzx0_68000>
- C. Bloom, *LZP: a new data compression algorithm*, 1996 -
  <https://www.cbloom.com/papers/lzp.pdf>
- N. J. Larsson, A. Moffat, *Offline Dictionary-Based Compression*
  (Re-Pair), 1999 - <https://ieeexplore.ieee.org/document/755679>
- Wikipedia, *LZ77 and LZ78* -
  <https://en.wikipedia.org/wiki/LZ77_and_LZ78>
