# Two more encodings, measured on tunes: the run block and cross-stream copies

The tune measurements in [research.md](research.md) end with a cost: a
register stream packed for a 128-byte ring is half again to three times the
size it is at 960, copies or not. Two ideas came out of looking at why. A
*run block*: a value that changes and then holds is the commonest shape in
a register stream, and the format has no cheap way to say "this new unit,
n times" when the last offset is not one. And *copies across streams*: the
three channels of a chip play the same figures, so one stream's literals
could serve the others, and the copy mechanism already reads literals from
wherever the literal read pointer points. This note takes both to the data
before either touches a decoder: the same four YMX example tunes and
Synergy's Wicked Polygons 2 from its test set, fourteen minutes of music,
their twenty-five stream vectors each, at k = 2, packed with the search.

## Verdict

**The run block is worth two to four percent, and one operation where there
are two.** It needs no new bit: the end code's class carries it, a literal
unit from stream B repeated n times, with the end becoming the one run
length a run cannot use. The search's own parses hold 75 to 164 new-offset
matches at offset one per short tune and 1600 to 3300 in Wicked Polygons 2,
which is what the block replaces, eight to ten bits apiece. The player's decoder gets a fill loop
and leaves offset one installed, so the reps after it are one bit.

**Copies across streams are real but small on these tunes, and absent on
the long one.** Packing all twenty-five streams as one at the full window
saves six to eight percent against packing the four short tunes' streams
alone, an upper bound on what any cross-stream scheme can reach, and a
third of a percent on Wicked Polygons 2; where there is anything it lies
almost entirely in the three channels' period streams, where a pair shares
eight to twenty-one percent. The mechanism
costs the decoders nothing - a copy reads `back` literal units behind its
read pointer, and a layout that puts one stream's literal block directly
after another's makes the far distances land there - but it needs a prefix
dictionary in the packer to measure what a small ring gets of that bound,
and a proxy without it says nothing at small rings.

**The lever the same data shows is elsewhere.** Sizing the rings per stream
takes a tune from 24000 bytes of ring RAM to 7000-11500 with no growth in
the file, since most streams need no ring to speak of; that is a YMX change
and needs nothing from the format.

## The run block

### What it is

A hold - the previous unit again for n units - is a match at offset one. It
costs one bit and a gamma when offset one is already the last offset, and
eleven bits and a gamma when it is not, which is right after every far copy
or match diverges: the value changes, one literal, then the new value holds.
Today that is a literal run of one and a new-offset match: 1 + 1 + 8 for
the literal, then 1 + 2 + 8 + gamma(n − 1) for the hold, 21 bits plus the
gamma. The run block says both at once, "this unit, n times":

```
flag 1, class 0 1, gamma(n − 1), and one unit read from stream B
```

11 bits plus the gamma, the value in the literal stream where the literals
are, so stream A's parity is untouched: a flag, two class bits and an odd
gamma make an even count, as every operation must, or the decoders pay a
refill check per data bit. The class `0 1` is the end marker today, read
once per stream; a run of one unit has no reason to exist, so the end
becomes `0 1` followed by gamma(1), the single `0` bit, and then its repeat
bit as now. One bit more per stream, once.

After a run the decoder holds offset one, so a value that changes again and
holds again is a literal and a one-bit rep, as it is today when the offset
is already one. The run block only pays where the offset was something
else; that is the case the search's parses count below.

### What the data says

The search's parse of every stream at k = 2, walked as the compressor walks
it, counting what each operation is. A hold here is a new-offset match at
offset one, the operation a run block would replace; its saving is taken as
eight bits, the offset byte, which undercounts by the literal run's two bits
and by the runs the parse would choose once they were cheaper.

| tune, ring | bytes | literal units | ring matches new, rep | copies new, rep | holds | holds save |
|---|---:|---:|---:|---:|---:|---:|
| Dark Side of the Spoon 1, 128 | 6191 | 1070 | 1535, 128 | 330, 5 | 100 | 100 bytes, 1.6% |
| Dark Side of the Spoon 1, 256 | 3812 | 819 | 811, 135 | 109, 0 | 99 | 99 bytes, 2.6% |
| Dark Side of the Spoon 1, 960 | 3604 | 784 | 784, 140 | 47, 0 | 87 | 87 bytes, 2.4% |
| Amiga Demo, 128 | 5301 | 973 | 1199, 155 | 238, 1 | 158 | 158 bytes, 3.0% |
| Amiga Demo, 256 | 4956 | 946 | 1101, 159 | 203, 1 | 164 | 164 bytes, 3.3% |
| Amiga Demo, 960 | 2781 | 680 | 505, 111 | 23, 0 | 75 | 75 bytes, 2.7% |
| Wicked Polygons 2, 128 | 99571 | 17113 | 23336, 2107 | 8210, 170 | 3304 | 3304 bytes, 3.3% |
| Wicked Polygons 2, 256 | 85332 | 16090 | 19985, 1968 | 5025, 85 | 2431 | 2431 bytes, 2.8% |
| Wicked Polygons 2, 960 | 67510 | 13804 | 15954, 1793 | 1589, 37 | 1588 | 1588 bytes, 2.4% |

The register streams change value 26000 to 27000 times per short tune and
226000 times in Wicked Polygons 2, and the parse covers all but a small
part of those with matches and reps: the change that costs is the one after
a far reference, and those are the holds. Two to four percent, then, on
every tune and at every ring, with the literal run's bits and the search's
preference counted in.

### Prior art

VCDIFF (RFC 3284) has exactly this instruction: RUN, "a size x and a byte
b, that will be repeated x times", beside ADD for literals and COPY for
matches. Pucrunch, Pasi Ojala's packer for the Commodore machines, is the
hybrid of LZ77 and RLE the 8-bit world has used since the nineties, with
Elias gamma lengths as here. LZSA2 and ZX0 have the rep-offset command but
no run; their runs are matches at offset one, as ST4's are today.

### What it costs the decoders

One more case at the end-marker class: read a gamma, and where it is not
the single bit that means the end, read one unit from stream B and write it
gamma + 1 times, then install offset one. A fill of n units against a copy
of n units from one unit back is the same ladder with a register as the
source; the state afterwards is the match state at offset one, which the
decoders already have. Tens of bytes per decoder.

## Copies across streams

### The mechanism

A copy from the literal stream reads `back` literal units behind the read
pointer, in stream B, and nothing in the decoders depends on what lies
before stream B in memory. If input 2's literal block is placed directly
after input 1's, a copy in input 2 whose distance passes its own literals
reads input 1's, and no decoder changes: no class, no state, the same
compare per segment. What becomes a rule of the format is the layout: an
input's literal block is preceded, contiguously and in a stated order, by
the literal blocks of the inputs it references, and its copies may reach
that far. The reach is the word offset's: 32512 units less the window, in
literal units - at k = 2 the whole of a tune's literal blocks, 700 to 1100
units per stream, many times over.

The packer is where the work is. Input i is parsed with the literal streams
of the inputs before it as a prefix dictionary: literals before position
zero, sources for copies, never emitted, counted into every copy's
distance. That is a contained extension of the search's parser and the
compressor's distance count, plus the reference decoder learning to read a
source that lies in the prefix. The order of the inputs is the packer's
choice and matters, since only earlier inputs are reachable; and only their
*literals* are, so an input another should copy from must be packed with
those units literal, which the search can be told to favour.

### What the data says

Three measurements, none of them the mechanism itself, which does not exist
yet, but each a bound on it.

**How much streams share.** Every stream of a tune packed alone at the full
window, and every ordered pair packed as one; what the pair saves against
the two alone is what the second stream can take from the first, by any
match at all. The whole tune as one against the twenty-five alone is the
sum.

| tune | 25 streams alone | all as one | shared |
|---|---:|---:|---:|
| Dark Side of the Spoon 1 | 3320 | 3052 | 8% |
| Amiga Demo, Overscan screen | 2526 | 2387 | 6% |
| Mad Max 1 | 887 | 821 | 7% |
| Cuddly, main menu | 1066 | 1004 | 6% |
| Synergy, Wicked Polygons 2 | 60397 | 60196 | 0% |

Where it is: in Dark Side of the Spoon, stream 2, channel B's period low
byte, packed after stream 0, channel A's, is 21% smaller than alone; 0
after 2, 8%; 4 after 2, 9%; the period high bytes 3 after 1, 16%. In Amiga
Demo, 2 after 0 saves 11% and 1 after 8, 13%. Mad Max and Cuddly share
almost nothing between any two streams - the best pair saves six and twelve
bytes - and their six to seven percent is spread thin. Wicked Polygons 2,
fourteen minutes long, shares nothing: its best pair, channel A's period
low byte after channel B's, is four percent of a 5.8 KB stream, and the
whole tune as one is a third of a percent smaller than its streams alone.
The volume and script streams share nothing worth a copy anywhere.

**What a group gets at the player's ring, and at a quarter of it.** The
three period-low streams, the three period-high, and the three volumes,
each group packed as one with sixty seconds of search against the three
alone with thirty each:

| tune | ring | period low 0, 2, 4 | period high 1, 3, 5 | volumes 8, 9, 10 |
|---|---:|---:|---:|---:|
| Dark Side of the Spoon 1 | 960 | 2019 → 1941, 4% | 672 → 643, 4% | 465 → 443, 5% |
|  | 256 | 2118 → 2006, 5% | 727 → 708, 3% | 510 → 492, 4% |
| Amiga Demo | 960 | 1527 → 1500, 2% | 517 → 515, 0% | 421 → 420, 0% |
|  | 256 | 2781 → 2852, −3% | 1095 → 1108, −1% | 603 → 611, −1% |
| Mad Max 1 | 960 | 331 → 330, 0% | 239 → 238, 0% | 194 → 188, 3% |
|  | 256 | 484 → 483, 0% | 391 → 390, 0% | 366 → 361, 1% |
| Cuddly, main menu | 960 | 483 → 482, 0% | 167 → 166, 1% | 248 → 237, 4% |
|  | 256 | 1130 → 1330, −18% | 699 → 725, −4% | 694 → 703, −1% |
| Synergy, Wicked Polygons 2 | 960 | 20672 → 20338, 2% | 7103 → 7074, 0% | 7309 → 7251, 1% |
|  | 256 | 25169 → 25038, 1% | 10562 → 10801, −2% | 9136 → 9184, −1% |

Dark Side of the Spoon, the one tune whose channels share, keeps its three
to five percent at the smaller ring; every other group at 256 is within a
percent of nothing or below it.

**And at a small ring, nothing a proxy can say.** The same groups at a
128-byte ring pack no smaller together than alone, and often larger, up to
14%, as the losses at 256 above already are; that is the search, not the
data - a thrice longer input with the same budget converges less - and the
whole tune as one at 128 comes out above the sum of its streams for the
same reason. The small-ring number is the one that matters, since that is
where the copies are needed, and it needs the prefix dictionary to be
measured.

So the bound on the four short tunes is six to eight percent of the file at
any ring, concentrated in the period streams, with two to five percent
reachable by ordinary matches at the player's ring and the rest to be
found; and on the long tune there is nothing to find.

### Prior art

VCDIFF again: its COPY addresses "a superstring U, formed by concatenating"
the source segment and the target window, "S[0]...S[s-1]T[0]...T[t-1]", so
that one address space covers the dictionary and the output, which is the
layout rule above stated as a format. LZ4's `LZ4_decompress_safe_usingDict`
treats a buffer as the history before the block and runs fastest when the
dictionary ends where the output begins; zstd's prefix mode and its trained
dictionaries do the same at scale, and shared dictionaries over HTTP put a
dictionary both sides hold in front of every response. Nearer the chip:
FYM, the Fast YM format for the Atari 8-bit, cuts every register stream into
patterns of 16 to 128 bytes, keeps one copy of each pattern that recurs in
any register, and plays through pointers - cross-register sharing at the
pattern level, from which the whole of its gain over MYM comes. What none of
these do is what is proposed here: sources that are the inputs' own literal
streams as the decoders already read them, at no decoder cost.

## What decides it

The run block first: it is a cost-model change to the search, a day, and
the tunes give its number before a decoder is touched; then the decoders,
tens of bytes each, with the end code moving one bit. The rings per stream
belong to YMX and cost nothing here. The prefix dictionary last, since its
bound is the smallest and its measurement the dearest - unless the assets
that matter turn out to be the three-channel figures Dark Side of the Spoon
is made of, where a fifth of a period stream is another channel's.

## Sources

- RFC 3284, *The VCDIFF Generic Differencing and Compression Data Format*:
  ADD, RUN and COPY, and the superstring U —
  <https://www.rfc-editor.org/rfc/rfc3284.html>
- P. Ojala, *pucrunch: an Optimizing Hybrid LZ77 RLE Data Compression
  Program* — <https://a1bert.kapsi.fi/Dev/pucrunch/>, source at
  <https://github.com/mist64/pucrunch>
- E. Marty, LZSA: the rep-match command in LZSA2 —
  <https://github.com/emmanuel-marty/lzsa>
- E. Saukas, ZX0 — <https://github.com/einar-saukas/ZX0>
- LZ4, `LZ4_decompress_safe_usingDict` and the prefix fast path —
  <https://fossies.org/linux/lz4/doc/lz4_manual.html>
- zstd manual, prefix and dictionary compression —
  <https://facebook.github.io/zstd/zstd_manual.html>
- Chrome for Developers, *Supercharge compression efficiency with shared
  dictionaries* —
  <https://developer.chrome.com/blog/shared-dictionary-compression>
- fenarinarsa, *Fast YM file format* — <https://www.fenarinarsa.com/?p=1454>
