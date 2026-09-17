# A near offset in five bits

Half the matches a stream makes reach back 32 units or fewer, and each of
them spends a byte on an offset five bits would carry. This is what adding
a near offset would be worth, what it would cost, and what building it
touches. It is parked here so the decision rests on figures rather than on
a memory of them. None of it is built.

The measurement it rests on is in [research.md](research.md), under *What a
short-offset class is worth against the packer*.

## What stands today

A match at a new offset spends a flag bit, two class bits, gamma of the
length less one, and the offset itself out of stream C or stream D:

| class | meaning |
|---|---|
| `10` | byte offset from C, 1 to 256 units back |
| `11` | byte offset from C, 257 to 512 units back |
| `00` | word offset from D |
| `01` | the data ends, and one repeat bit follows |

## The change

A fifth outcome: an offset of 1 to 32 units, five bits read from stream A
rather than a byte from stream C. Two class bits name four outcomes, so one
of the four grows a third bit and its two children are the old meaning and
the new one.

**The end code is the one to grow, and parity settles it.** A block with
its flag is an even number of bits: a gamma is odd, and the flag with the
class bits makes it even, which lets a 68000 decoder skip the refill check
on every read but three. Grow the word-offset code and a word block is
flag, three class bits and sixteen, an even count against an odd gamma,
698 times over the corpus. Grow the end code and the block that turns odd
is the end, once a stream, at the end of a stream A padded to an even
length. So the end code grows, its two children are the end and the near
offset, and a near offset is read in three class bits.

**Five bits of offset is then the width parity admits.** A near block is
flag, class, offset and gamma, so flag plus class plus offset has to be
odd: three class bits and five of offset is nine, where four or six would
be even.

So a near match spends eight bits of class and offset where it spends ten
today, and the end code spends one more. Measured on the parse the
packer writes, over the 120 columns of the corpus:

| the tree | a near block | over the corpus | the parity rule |
|---|---|---|---|
| three class bits and five of offset, the end code grown | 9 bits with its flag | **2.88%** | kept |
| two class bits and five, the end and the word code grown | 8 bits with its flag | 4.25% | broken, 14,362 times |

This document read the second row until the arithmetic was checked against
the parity rule above, and research.md's table beside it prices the same
way. The second tree puts the near offset in the freed two-bit code and
grows the end and the word code instead, which saves three bits a near
match rather than two. What it costs is the property the format is shaped
around: a near block of flag, two class bits, five of offset and an odd
gamma is odd, and a decoder reads it with the refill check the parity was
there to spare. The end block itself becomes odd, being a flag, three class
bits and a repeat bit. It is the last block of a stream and stream A is
padded to an even length, so what that costs a decoder is a question for
the 68000 side rather than for the format.

## What it is worth

Over the 120 columns of one chiptune channel each, packed at `-k2` through
a 256-byte ring, where the packer writes 124,220 bytes:

| offset | matches | share |
|---|---|---|
| 1 to 16, four bits | 9,592 | 34.0% |
| **1 to 32, five bits** | **14,362** | **50.9%** |
| 1 to 64, six bits | 17,788 | 63.0% |
| past 512, a word | 698 | 2.5% |

A near match spends eight bits of class and offset against the ten it
spends now, and the end code spends one more: 14,362 x 2 - 120 = 28,604
bits, 3,576 bytes of 124,220, **2.88 per cent**.

That is a floor. The parse it is priced on was chosen under the costs as
they are; a parse made under the new ones reaches for more near matches,
and the section below measures what that adds.

## What a parse made under the new costs finds

The floor above is priced on a parse the old costs chose. This parses again
with the near class in the cost model, so the parse reaches for it.

The corpus is thirty columns of real tunes, the three tone-period low bytes
of the ten dumps under YMXR's `ym/test`, 90,138 bytes in all, packed a
column at a time at `-k2` through a 256-byte ring, without copies.
`St4Optimizer` with the one cost line changed makes the second parse, and
both parses are priced under both models. The model reproduces the packer:
it counts 14,025 bytes where the packer writes 14,092 of payload, the
difference being the padding each stream ends on, and on the longest column
it counts 14,701 bits where the packer reports 14,712.

| | bytes | against today |
|---|---|---|
| today's costs, today's parse | 14,025 | |
| the near class, today's parse | 13,562 | -3.30% |
| the near class, parsed for it | 13,525 | **-3.57%** |

**Parsing for the class adds 0.27 points to the floor's 3.30**, a twelfth
of it. The parse does reach: near matches rise from 1,879 to 2,309 and byte
offsets fall from 1,026 to 923, and each swap trades one encoding for
another of nearly the same cost.

Note: at a 256-byte ring without copies every offset fits a byte, so this
corpus has no word offset in it at all. The 698 word offsets of the corpus
above are the copies, which are written as offsets beyond the window.

## What it costs to decode

A byte offset is `move.b (a2)+,d1` and an `ext.w`, 16 cycles, with the two
class bits 24: 40 cycles. A five-bit near offset is five reads out of
stream A at 12 cycles a bit, 60, with three class bits 36: 96. Over 14,362
near matches and 898,830 output bytes the 56 cycles between them are **0.89
cycles a byte on the 19.00 the format decodes at, 4.7 per cent**. A byte
offset and a word offset keep the two class bits they read today, so the
near block and the end block are the only ones that read three.

Measured the same way over the thirty columns of the section above, decoded
by ST4.S at `k` of 2 under the rig's cycle counter: the stream costs 21.86
cycles a byte today, and 2,309 near matches at 56 cycles each add 1.43,
**6.6 per cent of the decode for the 3.57 per cent of the file**.

So the decode is the dearer side of the trade on either corpus, and parsing
for the class moves the file figure a quarter of a point.

## What building it touches

- **ST4**: the cost model, the compressor, the decompressor and the copies
  search's costing of a copy, in the Go, Java and C# trees; SPEC.md,
  glossary.md and decoders.md; the format version, 7 to 8.
- **The 68000 decoders**: `ST4.S`, `ST4_ring.S` and `ST4_wrap.S`, each
  gaining a third class bit on the end path and a five-bit read on the near
  path, then their sizes and cycles measured again and the emulation rig's
  tables with them.
- **DTX**: the three carried copies; the twenty-two images, whose bytes
  move, so `StabilityTest`, `BlobTest`, `ImageTest`, the images zip and the
  manifest move with them; performance.md measured again; the conformance
  kit written again, its files being the bytes a reader is checked
  against.
- **YMXR**: its conformance kit, the SNDH and PRG sizes, and
  performance.md.
- **Releases** in order: ST4, then DTX, then YMXR.

## What it risks

A reader of format 7 reads format 8 as an error, which the version byte
already provides for, so what breaks is a decoder already in a binary
somewhere rather than a file that can be packed again.

The decode cost lands in YMXR's frame budget, which performance.md prices.

What DTX and YMXR say about a byte staying where it was is spent once,
deliberately: two conformance kits and twenty-two images are written again
in the same change, and a reader of them cannot tell a mistake from the
change intended without reading the parse.

## What is open

- Whether the end code or the word code grows. The end code is the measured
  choice and the larger saving; it makes the end marker three bits, which
  the loop path reads as well.
- What the near reach should be at each unit. An offset counts units, so
  five bits reach 32 units: 32 bytes at `k` of 1 and 128 at `k` of 4.
- What the third class bit and the five-bit read cost a decoder in bytes,
  against ST4_wrap's 324 to 330.
- What it is worth with copies on. The corpus above is packed without them,
  where every offset fits a byte; with copies a stream has word offsets in
  it, and a near class competes with them for the same class codes.
