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

A stream may end by repeating instead of stopping. `st4 -rR` names a loop
point: the container then encodes the infinite input `[0,R)` `[R,O)*` — after
the last unit, the output continues from unit R and never stops — which is
how a small ring holds an 'infinite' stream: a looping sample, a register
dump, a pattern. One repeat bit follows the end code, and when it is set one
last word offset is read from stream D — the distance O−R back to the loop
point — and the stream becomes an endless match at it. The distance obeys the
same limits as any other offset, and that is also the mechanism's reach: the
looped span `[R,O)` must fit the window the stream was packed for, since the
endless match reads it back out of the ring.

One flag bit says which block comes next. After literals, `0` starts a match
at the last offset and `1` a match at a new offset — two literal runs in a row
cannot happen. After a match, `0` starts literals and `1` a match at a new
offset. The first block is always literals and has no flag bit.

The two class bits of a new offset pick its stream and reach, or end the file:

```
1 0   byte offset from stream C, 1..256 units back
1 1   byte offset from stream C, 257..512 units back
0 0   word offset from stream D
0 1   end of stream, then the repeat bit: 0 ends it, 1 loops it forever
```

A byte offset of n units in bank b is stored as the byte 256·(b+1) − n. A word
offset of n units is stored big-endian as 65536 − n·k, which is −n·k exactly
as the decoder keeps it, so installing one is a single move. No offset may
reach further back than 32512 bytes, at any k, and a new-offset match is at
least 2 units long — which is why it stores gamma(length−1).

Together with its flag, a block is an even number of bits: a gamma value is
an odd count, and the flag or class bits make it even. That is why a new
offset spends two class bits rather than one, and the even rhythm is what
lets the decoders skip the refill check on every read but two: a gamma
continuation bit, and the class bit right after a flag. Stream A is padded to
an even length so the last refill finds a whole word.

A container is twenty bytes of header, then the streams in order:

```
 0  4  signature: 'S', '4', format version (5), k
 4  4  output size in bytes, a multiple of k
 8  4  where stream B starts, in bytes from the start of the header
12  4  where stream C starts
16  4  where stream D starts
20 ..  stream A, then C, then D, then B, each starting on a long boundary
```

Nothing else is stored because nothing else is needed: stream A begins where
the header ends, each stream runs to the next, and no stream length is kept —
the decoder stops at the end marker. The signature fits one long, so a decoder
built for one k accepts or rejects an asset with a single `cmp.l`, and the
stream starts are header-relative, so opening a container is one `adda.l` per
stream. The eight instructions that do it are in [ST4.S](68k/ST4.S).

Stream B sits last — version 5 moved it there from second — so the literal
payload runs to the end of the file and ends on a unit boundary. A ring buffer
placed directly after the container therefore borders the literal data: at any
moment during a decode, the literals not yet consumed occupy a known stretch
of memory just below the ring, which a packer that knows the caller's layout
can let matches reach into.

## 68000 decoders

Three decoders, each built for one unit size with `ST4_UNIT` — nine builds, no
runtime choice of width:

| | k=1 | k=2 | k=4 | calls |
|---|---:|---:|---:|---|
| [ST4.S](68k/ST4.S) | 306 B | 308 B | 310 B | `ST4_init`, `ST4_decompress`, `ST4_resume` |
| [ST4_wrap.S](68k/ST4_wrap.S) | 324 B | 328 B | 330 B | `ST4_init`, `ST4_resume` — counted wrap, no DONE state |
| [ST4_ring.S](68k/ST4_ring.S) | 386 B | 394 B | 396 B | `ST4_init`, `ST4_resume` — general ring |

Use [ST4.S](68k/ST4.S) when the whole output stays in one buffer; it can
decode everything in one call or stop and resume. The other two stream through
a small ring. Use [ST4_wrap.S](68k/ST4_wrap.S) when the sizes and call pattern
are known and your caller counts the wraps; use
[ST4_ring.S](68k/ST4_ring.S) for variable call sizes — it stops each call at
the ring end.

All three decode repeating streams: the end marker installs the repeat offset
and the decoder re-arms the same match 65535 units at a time, forever. The
bit queue is pinned to zero — a value no real read leaves it in — so the only
cost to a stream that does end is one extra branch on a match-to-literals
transition. A repeating stream never reaches DONE: drive it through
`ST4_resume` with budgets and stop when you have enough. `ST4_decompress`,
which drains until DONE, would never return.

Each decoder runs two copy ladders: match runs of at most sixteen units — on
measured streams, four of every five — take a counter-free ladder that falls
straight into what comes next, and literals and longer runs take a counted
one. Measured on real streams, that is 12–14% fewer cycles for ST4_wrap in a
small-budget streaming loop, 3–5% in bulk, with no case slower.

State is held in registers: `a0`, `a2`, `a4`, `a5` walk the four streams, `a1`
writes, `d0.w` holds the bit queue, `d1.w` the units left in the operation and
`d2.w` the offset — signed, so its sign is also the state. The two ring
decoders keep those two as longs and pack the ring's bounds into the upper
halves, which is how a match that reaches back past the ring start finds its
source at the other end. Only `a6`, `d6` and `d7` are preserved across a
call. The destination, stream B and the ring all start on a unit
boundary, and the ring size is a whole number of units, so a wide move never
lands on an odd address. Each file documents its exact contract and numbered
assumptions.

The decoders do not check their input. Use trusted files made at build time.
The packers already keep every operation within the decoders' 16-bit counters;
for a ring of N units, pack with `-mN` so the decoder never needs data that
has already left the ring.

## Java tools

```sh
mvn package
java -ea -cp target/classes org.st4.St4  [-f] [-kK] [-mN] [-lN] [-rR] input [output.st4]
java -ea -cp target/classes org.st4.Dst4 [-f] input.st4 [output]
```

`st4` packs, reporting progress and a time estimate as it works; `dst4`
unpacks, and is the readable reference the 68000 decoders are checked against.
Its output is padded to a whole number of units, which is what the format
stores — for a repeating stream, one whole pass. `-kK` picks the unit size,
`-mN` limits how far back matches may reach, `-lN` splits long matches — the
default already fits the 68000 decoders — and `-rR` makes the stream loop:
after the last unit, the output continues from unit R, forever. `-r0` loops
the whole stream.

Three optimizers select the blocks, all held to each other by tests:

- **St4Optimizer** — the readable reference. It tries every choice at every
  position and keeps the cheapest.
- **St4FastOptimizer** — the same choices on plain arrays: the same bytes out,
  measured 4–7× faster.
- **St4EventOptimizer** — the default. It only does work where a repeated
  stretch of data starts or ends, which on repetitive data happens thousands
  of times less often than the positions the others visit. Same packed size,
  not always the same bytes; it falls back to the fast one when the data
  repeats in stretches too short to profit.

Measured on the optimizer alone:

| corpus | reference | fast | event-driven |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024`, k = 4 | 163s | 37s | **0.4s** |
| 300 KB slice, full window, k = 4 | 24s | 5.7s | **0.12s** |
| 32 KB of 68000 code, k = 1 | 9.8s | 1.5s | falls back to fast |

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
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-kK] [-mN] [-lN] [-rR] input [output.st4]
```

## Tests

```sh
mvn test                                  # round-trips, containers, optimizers
dotnet test csharp/Nt4.slnx -c Release    # the C# port, same corpora
python3 68k/test/emu/test_st4.py          # linear decoder vs the Java packer, k = 1, 2, 4
python3 68k/test/emu/test_st4_wrap.py     # counted wrap, every unit size
python3 68k/test/emu/test_st4_ring.py     # general ring, both wrap modes, oversized budgets
python3 68k/test/emu/test_st4_repeat.py   # -r streams through all three decoders, past two passes
python3 68k/test/emu/bench_bits.py        # why the lengths are still Elias gamma
```

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer, assemble
the real decoders, decode under emulation as a plain 68000, and check every
output byte, exact consumption of all four streams, ring guard bands and the
packed register metadata.

## ST1

ST1 — the ZX1 decoders this grew from, and the jx1 packer — is in
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
and C# tools, the 68000 decoders, the tests, and the optimization work, under
Robbert's direction. ST4_wrap.S is based on ST1_wrap.S, which OpenAI Codex
wrote for ST1; see [LICENSE](LICENSE).

Special thanks to Sandor Drieënhuizen and Wietze Spijkerman for their support,
proofreading, and ideas.
