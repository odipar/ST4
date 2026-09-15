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
passes of `St4LiteralCopySearch` and then two minutes of its search per
cell, which on the larger corpora is still
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

At the player's own ring and above it the copies gain one to four percent:
the ring already holds what these tunes repeat. The gain is in shrinking
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
tune that packed to almost nothing the most. The tune data needs nothing
for this: copies read the literal stream out of the file the player
already keeps in memory.

### What decides it

`st4 -c` writes the one-shot parse and the decoders take it when built with
`ST4_WINDOW`; `st4 -cS` searches for S seconds beyond it, descending and
annealing over which units are literal with an exact parse for each choice
and the rep of a copy in its cost model - on this README at k = 1 and a
64-unit window that takes the one-shot parse's 78% of the input to 61% in
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
blocks, the most a 30-unit window can hold at one block per unit; a penalty
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
blocks matter as well, and those states do not record it.

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
side, lifting ZX2 from 130,300 to 128,048.

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
