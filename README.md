# ST4 — split-stream, unit-aligned compression for the Atari ST

ST4 is a compression format for the plain 68000, with small decoders, a Java
packer and a C# port. It grew out of [ST1](https://github.com/odipar/ST1) and,
through it, Einar Saukas's [ZX1](https://github.com/einar-saukas/ZX1). It keeps
ZX1's three block types but writes them into four streams instead of one, and
counts lengths and offsets in units of 1, 2 or 4 bytes.

Both changes serve the 68000. With each stream holding one kind of thing, the
decoder can read each of them the fastest way that exists for it: the bit
stream refills a word at a time, and literals copy with `move.w` or `move.l`
because their alignment no longer depends on the bytes around them. Units make
one operation move 2 or 4 bytes, so there are half or a quarter as many
operations to do.

The unit size k is a trade. An offset or length that is not a multiple of k
cannot be stored, so k = 2 or 4 pays some compression for speed. That is a
good trade on data that is itself word- or long-shaped — 68000 code, word
tables, speedcode, register streams — and a bad one elsewhere, which is why k
is chosen per asset and recorded in the header. At k = 1 ST4 packs to within a
percent of ZX1.

The name follows the family joke: ZX1 signs the ZX Spectrum, ST1 the Atari ST,
and the 4 is both the widest unit and a nod to the Mega ST4.

## The format

All lengths and offsets count units of k bytes. Input that is not a whole
number of units is padded with zeros, and the padding is part of the stored
output. A container holds four streams:

| stream | holds |
|---|---|
| **A** | all the bits: flags, class bits and lengths |
| **B** | the literal data, whole units |
| **C** | byte offsets, one byte each |
| **D** | word offsets, one word each |

Bits are read from stream A most significant first. Lengths use interlaced
Elias gamma: each binary digit of the value below its leading 1 is written
after a `1` marker bit, most significant first, and a `0` bit ends the value.
So 1 is `0`, 2 is `100`, 3 is `110`, 4 is `10100`.

The data is a sequence of ZX1's three block types:

```
literals             gamma(length)    the next length units of stream B
match, last offset   gamma(length)    copy length units from the current offset
match, new offset    2 class bits + one value from C or D, then gamma(length-1)
```

One flag bit says which block comes next. After literals, `0` starts a match
at the last offset and `1` a match at a new offset — two literal runs in a row
cannot happen. After a match, `0` starts literals and `1` a match at a new
offset. The first block is always literals and has no flag bit.

The two class bits of a new offset pick its stream and reach, or end the file:

```
1 0   byte offset from stream C, 1..256 units back
1 1   byte offset from stream C, 257..512 units back
0 0   word offset from stream D
0 1   end of stream
```

A byte offset of n units in bank b is stored as the byte 256·(b+1) − n. A word
offset of n units is stored big-endian as 65536 − n·k, which is −n·k exactly
as the decoder keeps it, so installing one is a single move. No offset may
reach further back than 32512 bytes, at any k, and a new-offset match is at
least 2 units long — which is why it stores gamma(length−1).

Together with its flag, a block is an even number of bits: a gamma value is
an odd count, and the flag or class bits make it even. That is why a new
offset spends two class bits rather than one, and the even rhythm is what
lets the decoders refill their bit queue a whole word at a time. Stream A is
padded to an even length so the last refill finds a whole word.

A container is twenty bytes of header, then the streams in order:

```
 0  4  signature: 'S', '4', format version (4), k
 4  4  output size in bytes, a multiple of k
 8  4  where stream B starts, in bytes from the start of the header
12  4  where stream C starts
16  4  where stream D starts
20 ..  stream A, then B, then C, then D, each starting on a long boundary
```

Nothing else is stored because nothing else is needed: stream A begins where
the header ends, each stream runs to the next, and no stream length is kept —
the decoder stops at the end marker. The signature fits one long, so a decoder
built for one k accepts or rejects an asset with a single `cmp.l`, and the
stream starts are header-relative, so opening a container is one `adda.l` per
stream. The eight instructions that do it are in [ST4.S](68k/ST4.S).

## 68000 decoders

Three decoders, each built for one unit size with `ST4_UNIT` — nine builds, no
runtime choice of width:

| | k=1 | k=2 | k=4 | calls |
|---|---:|---:|---:|---|
| [ST4.S](68k/ST4.S) | 276 B | 278 B | 280 B | `ST4_init`, `ST4_decompress`, `ST4_resume` |
| [ST4_wrap.S](68k/ST4_wrap.S) | 292 B | 296 B | 298 B | `ST4_init`, `ST4_resume` — counted wrap, no DONE state |
| [ST4_ring.S](68k/ST4_ring.S) | 352 B | 360 B | 362 B | `ST4_init`, `ST4_resume` — general ring |

Use [ST4.S](68k/ST4.S) when the whole output stays in one buffer; it can
decode everything in one call or stop and resume. The other two stream through
a small ring. Use [ST4_wrap.S](68k/ST4_wrap.S) when the sizes and call pattern
are known and your caller counts the wraps; use
[ST4_ring.S](68k/ST4_ring.S) for variable call sizes — it stops each call at
the ring end.

Each decoder runs two copy ladders: match runs of at most sixteen units — on
measured streams, four of every five — take a counter-free ladder that falls
straight into what comes next, and literals and longer runs take a counted
one. Measured on real streams, that is 12–14% fewer cycles for ST4_wrap in a
small-budget streaming loop, 3–5% in bulk, with no case slower.

State lives in registers: `a0`, `a2`, `a4`, `a5` walk the four streams, `a1`
writes, `d0.w` holds the bit queue and `d1`/`d2` the counters. Only `a6`, `d6`
and `d7` survive a call untouched. The destination, stream B and the ring size
must be whole units, so a wide move never lands on an odd address. Each file
documents its exact contract and numbered assumptions.

The decoders do not check their input. Use trusted files made at build time.
The packers already keep every operation within the decoders' 16-bit counters;
for a ring of N units, pack with `-mN` so the decoder never needs data that
has already left the ring.

## Java tools

```sh
mvn package
java -ea -cp target/classes org.st4.St4  [-f] [-kK] [-mN] [-lN] input [output.st4]
java -ea -cp target/classes org.st4.Dst4 [-f] input.st4 [output]
```

`st4` packs, reporting progress and a time estimate as it works; `dst4`
unpacks, and is the readable reference the 68000 decoders are checked against.
Its output is padded to a whole number of units, which is what the format
stores. `-kK` picks the unit size, `-mN` limits how far back matches may
reach, and `-lN` splits long matches — the default already fits the 68000
decoders.

Three optimizers choose the blocks, all held to each other by tests:

- **St4Optimizer** — the readable reference. It tries every choice at every
  position and keeps the cheapest.
- **St4FastOptimizer** — the same choices on plain arrays: the same bytes out,
  measured 4–7× faster.
- **St4EventOptimizer** — the default. It only does work where a repeated
  stretch of data starts or ends, which on repetitive data happens thousands
  of times less often than the positions the others visit. Same packed size,
  not always the same bytes; it falls back to the fast one when the data
  repeats in stretches too short to profit.

Measured on the optimizer alone, k = 4:

| corpus | reference | fast | event-driven |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024` | 163s | 37s | **0.4s** |
| 300 KB slice, full window | 24s | 5.7s | **0.12s** |
| 32 KB of 68000 code (k = 1) | 9.8s | 1.5s | falls back to fast |

The jx1 and nx1 packers in [odipar/ST1](https://github.com/odipar/ST1) carry
the same three optimizers but keep the event-driven one behind their `-q`
switch, because their default output must stay byte-identical to the original
ZX1 C compressor.

## C# tools

[csharp/](csharp/README.md) is `nt4`, a .NET 10 port of the Java tools: the
same library classes, the same three optimizers, the same options. The
containers are interchangeable — measured byte-identical to the Java packer's
on real data at every unit size — and the tests are the Java suite, corpus for
corpus.

```sh
dotnet test csharp/Nt4.slnx -c Release
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-kK] [-mN] [-lN] input [output.st4]
```

## YX6: the YM chiptune player

[yx6/](yx6/README.md) puts the streaming decoders to work: a Java packer turns
a YM chiptune dump — YM5 or YM6, LHA-archived or not — into eighteen ST4
containers: one per YM2149 sound register, plus each effect slot's code and
timer-count streams, with the digidrum samples appended as a table. A
2,260-byte 68000 player decodes them through eighteen small rings with
ST4_wrap, one refill per frame, and plays the effects — digidrums, SID
voices, the sync-buzzer — on MFP Timers A and D. On one measured tune that
costs about 1,850 cycles a frame on real hardware, effect stage included.
The packer picks k = 2 by itself when the tune's shape allows it: a few
percent fewer cycles for a few percent more bytes. Loop points restart a
stream mid-refill.

```sh
mvn -q compile exec:exec@yx6 -Dargs="-f song.ym song.yx6"
yx6/mkprg.sh song.yx6                 # -> SONG.PRG
```

## Tests

```sh
mvn test                                  # round-trips, containers, optimizer equivalence
dotnet test csharp/Nt4.slnx -c Release    # the C# port, same corpora
python3 68k/test/emu/test_st4.py          # linear decoder vs the Java packer, k = 1, 2, 4
python3 68k/test/emu/test_st4_wrap.py     # counted wrap, every unit size
python3 68k/test/emu/test_st4_ring.py     # general ring, both wrap modes, oversized budgets
python3 68k/test/emu/bench_bits.py        # why the lengths are still Elias gamma
python3 yx6/test/emu/test_yx6.py          # the YM player, against the chip writes
```

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer, assemble
the real decoders, decode under emulation as a plain 68000, and check every
output byte, exact consumption of all four streams, ring guard bands and the
packed register metadata.

## Experiments

[doc/experiments/](doc/experiments/README.md) records ideas that were
measured against the real corpus and declined, with the numbers — so a good
idea that is not worth its complexity never has to be measured twice.

## ST1

ST1 — the ZX1 decoders this grew from, and the jx1 packer — lives in
[odipar/ST1](https://github.com/odipar/ST1). ST4 forked from it at
`odipar/ST1@132aef0`; the shared emulator harness and the MC68000 cycle
knowledge in the rigs are carried copies of that repository's, which remains
authoritative for ST1's own timing tables.

## License and attribution

The license is ST1's, which follows the original ZX1; see [LICENSE](LICENSE).
The compressor is BSD 3-Clause. The decompressors may be used freely,
including commercially, if your program's documentation says that ZX1 was used
through ST4, st4, or nt4.

The ZX1 format and algorithm are by Einar Saukas. The ST4 format and additions
are © 2026 Robbert van Dalen. Claude (Anthropic's Claude Code) wrote the Java
and C# tools, the 68000 decoders, the YM player, the tests, and the
optimization work, under Robbert's direction. ST4_wrap.S is based on
ST1_wrap.S, which OpenAI Codex wrote for ST1. The YM reader's `-lh5-`
depacker is ported from Arnaud Carré's ST-Sound library, itself based on
LZH code by Haruhiko Okumura and Kerwin F. Medina; see [LICENSE](LICENSE).

Special thanks to Sandor Drieënhuizen and Wietze Spijkerman for their support,
proofreading, and ideas.
