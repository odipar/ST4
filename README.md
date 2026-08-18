# ST4 — split-stream, unit-aligned compression for the Atari ST

ST4 is a compression format and toolchain for the plain 68000, grown out of
[ST1](https://github.com/odipar/ST1) and, through it, Einar Saukas's
[ZX1](https://github.com/einar-saukas/ZX1). It keeps ZX1's three block types
and its optimal parse, but writes the pieces into **four streams** instead of
one, and counts lengths and offsets in **units** of 1, 2 or 4 bytes. Each
stream holds one kind of thing, which lets a 68000 read each of them the
fastest way that exists for it; the units let a decoder move 2 or 4 bytes per
operation and run that many fewer operations.

The name follows the family joke — ZX1 signs the ZX Spectrum, ST1 the Atari
ST — and the 4 is both the widest unit and a nod to the Mega ST4.

| stream | holds | why it is its own stream |
|---|---|---|
| **A** | flag bits and Elias gamma lengths | nothing but bits, so the queue refills with a `move.w` — impossible while an offset byte can move the same pointer by one |
| **B** | literal payload | alignment becomes a property of the format, so literals copy `move.w`/`move.l` at a time |
| **C** | byte offsets | uniform width, no in-band selector to unpack |
| **D** | word offsets | word-aligned by construction, stored as `-offset*UNIT` — exactly what the decoder keeps in `d2` |

The unit size is a trade the packer reports and the header records: an offset
or length that is not a multiple of k cannot be expressed, so k = 2 or 4 pays
ratio for speed and only on k-aligned data — 68000 code streams, word/long
tables, speedcode — is the trade good. At k = 1 ST4 packs to within a percent
of ZX1 in either direction.

## The format

A new-offset match spends the flag bit plus two class bits:

```
1 0   byte offset from stream C, 1..256 units
1 1   byte offset from stream C, 257..512 units
0 0   word offset from stream D
0 1   end of stream
```

Two bits, not one, and that is forced: the decoder skips the bit-queue refill
check on everything but a gamma continuation, which is sound only because each
operation is an even number of bits. The second bit does work instead of
padding — it picks a byte offset's 256-unit bank, which is what keeps the
split from costing ratio.

A container is twenty bytes of header and the four streams in order, each on a
long boundary:

```
 0  4  signature: 'S', '4', format version, k
 4  4  O, the output size in bytes
 8  4  stream B, as a byte offset from the start of the header
12  4  stream C
16  4  stream D
20 ..  stream A, then B, then C, then D
```

Nothing else is stored because nothing else has to be: stream A begins where
the header ends, each stream runs to the next, and no decoder reads a length —
it stops on the end marker. The signature packs magic, version and unit size
into one long, so a decoder built for one k proves an asset matches it with a
single `cmp.l`; the offsets are header-relative, so opening a container is one
`adda.l` per stream and no relocation. The eight instructions that do it are
in [ST4.S](68k/ST4.S).

## 68000 decoders

Three decoders, each parameterised by a build-time `ST4_UNIT` — nine builds,
no runtime choice of width:

| | k=1 | k=2 | k=4 | calls |
|---|---:|---:|---:|---|
| [ST4.S](68k/ST4.S) | 220 | 222 | 224 | `ST4_init`, `ST4_decompress`, `ST4_resume` |
| [ST4_wrap.S](68k/ST4_wrap.S) | 238 | 242 | 246 | `ST4_init`, `ST4_resume` — counted wrap, no DONE state |
| [ST4_ring.S](68k/ST4_ring.S) | 288 | 296 | 304 | `ST4_init`, `ST4_resume` — general ring |

State lives in registers: `a0`/`a1`/`a2`/`a4`/`a5` for the streams, write
pointer and offsets, `d0.w` the bit queue, `d1.w`/`d2.w` the counters. Only
`a6`, `d6` and `d7` survive a call untouched. The window is 32512 **bytes** at
every unit size, because a word offset is stored pre-scaled and pre-negated;
the ring size must be a multiple of the unit. Each file documents its exact
contract and numbered assumptions.

## Java tools

```sh
mvn package
java -ea -cp target/classes org.st4.St4  [-kK] [-mN] [-lN] input [output.st4]
java -ea -cp target/classes org.st4.Dst4 input.st4 [output]
```

`st4` packs, reporting an exact percentage and a fitted time estimate while
the parser works; `dst4` unpacks, and is the readable reference the 68000
decoders are checked against. Its output is padded to a whole number of units,
which is what the format stores. `-mN` limits back-references to N units for
ring use; `-lN` splits matches so no operation exceeds N units — use
`-l65535` for the 68000 decoders.

Three optimizers produce the parse, all held to each other by tests:

- **St4Optimizer** — the reference: ZX1's optimal parser at unit granularity,
  a block object per candidate. The specification the others are checked
  against.
- **St4FastOptimizer** — the same DP on primitive arrays, chain rebuilt from
  per-position descriptors afterwards. Byte-identical output, measured 4–7×
  faster: the reference allocates gigabytes of losing candidates — 37 GB for
  one 300 KB pack.
- **St4EventOptimizer** — the CLI's default: cost-identical to the DP but
  driven by match-run boundary events instead of per-(position, offset) steps
  — on one measured disk image, 2,922 run boundaries against 76 million steps.
  It falls back to the fast DP when a cheap event count says the data is
  run-churny. Its chain can differ from the DP's where candidates tie; its
  cost array cannot, and the equivalence test asserts that element for
  element.

Measured on the optimizer alone, k = 4:

| corpus | reference | fast DP | event engine |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024` | 163s | 37s | **0.4s** |
| 300 KB slice, full window | 24s | 5.7s | **0.12s** |
| 32 KB of 68000 code (k = 1) | 9.8s | 1.5s | gates to the DP |

The `st4` CLI needs no flag for any of this: with no byte-identity contract to
protect, the engine simply is the default. The jx1 and nx1 packers in
[odipar/ST1](https://github.com/odipar/ST1) carry the same three classes but
expose the engine behind their `-q` switch instead, because their default
output must stay byte-identical to the original ZX1 C compressor — `-q` there
means the same packed size, two orders of magnitude faster on repetitive data,
different ties.

## YX6: the YM chiptune player

[yx6/](yx6/README.md) is the proof the streaming decoders earn their keep: a
Java packer turns a YM6 dump into fourteen ST4 containers - one per YM2149
register, each a stream of one register's values, exactly the event engine's
kind of data - and an 888-byte 68000 player decodes them through fourteen
small rings with ST4_wrap, one refill per VBL, about 2,200 cycles a frame on
real hardware. Loop points restart a register's stream mid-refill; effects are
masked at pack time.

```sh
mvn -q compile exec:exec@yx6 -Dargs="-f song.ym song.yx6"
yx6/mkprg.sh song.yx6                 # -> SONG.PRG
```

## Tests

```sh
mvn test                                  # round-trips, containers, optimizer equivalence
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

## ST1

ST1 — the ZX1 decoders this grew from, and the jx1 packer — lives in
[odipar/ST1](https://github.com/odipar/ST1). ST4 forked from
it at `odipar/ST1@132aef0`; the shared emulator harness and the MC68000 cycle
knowledge in the rigs are carried copies of that repository's, which remains
authoritative for ST1's own timing tables.

## License and attribution

The license follows the original ZX1; see [LICENSE](LICENSE). The compressor
is BSD 3-Clause. The decompressors may be used freely, including commercially,
if your program's documentation says that ZX1 was used through ST4.

The ZX1 format and algorithm are by Einar Saukas. ST4 is © 2026 Robbert van
Dalen. Claude (Anthropic's Claude Code) wrote the format, the Java tools, the
68000 decoders, the tests and the optimization work, under Robbert's
direction.
