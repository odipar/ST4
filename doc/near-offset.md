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

**The end code is the one to grow.** It stands once a stream where a word
offset stands 698 times over the 120 columns of the corpus, so the bits the
third class bit costs are 120 against 698. Measured on the parse the
packer writes:

| the code that grows | four bits of offset | five | six |
|---|---|---|---|
| the word-offset code | 2.83% | 2.82% | 1.72% |
| the end code | 3.78% | **4.25%** | 3.50% |

**Five bits is also the only width the format's parity admits.** A block
with its flag is an even number of bits: a gamma is odd, and the flag with
the class bits makes it even, which lets a 68000 decoder skip the refill
check on every read but three. A near block is flag, class, offset and
gamma, so flag plus class plus offset has to be odd: three class bits and
five of offset is nine, and four or six would be even. The width the
measurement picks is the width the format needs.

The end block itself becomes odd, being a flag, three class bits and a
repeat bit. It is the last block of a stream and stream A is padded to an
even length, so what that costs a decoder is a question for the 68000 side
rather than for the format.

## What it is worth

Over the 120 columns of one chiptune channel each, packed at `-k2` through
a 256-byte ring, where the packer writes 124,220 bytes:

| offset | matches | share |
|---|---|---|
| 1 to 16, four bits | 9,592 | 34.0% |
| **1 to 32, five bits** | **14,362** | **50.9%** |
| 1 to 64, six bits | 17,788 | 63.0% |
| past 512, a word | 698 | 2.5% |

A near match spends seven bits against the ten it spends now, and the word
offset and the end each spend one more: 14,362 x 3 - 698 - 120 = 42,268
bits, 5,284 bytes of 124,220, **4.25 per cent**.

That is a floor. The parse it is priced on was chosen under the costs as
they are; a parse made under the new ones reaches for more near matches.

## What it costs to decode

A byte offset is `move.b (a2)+,d1` and an `ext.w`, 16 cycles, with the two
class bits 24: 40 cycles. A five-bit near offset is five reads out of
stream A at 12 cycles a bit, 60, with three class bits 36: 96. Over 14,362
near matches and 898,830 output bytes that is **0.78 cycles a byte on the
19.00 the format decodes at, 4.1 per cent**, and the third class bit on
every other new offset is beside it.

So the trade is 4.25 per cent of the file against about 4 per cent of the
decode, and the file figure is the one that grows when the parse is made
under the new costs.

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
- What a parse made under the new costs finds, which the 4.25 per cent does
  not include.
