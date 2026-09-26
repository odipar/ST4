# ST4 - compression for the Atari ST, with small 68000 decoders

## Read this first

**AI wrote most of ST4.** Claude (Anthropic's Claude Code) wrote the three
tool trees, the 68000 decoders, the tests, the emulation rigs and most of
what is written here, under Robbert van Dalen's direction: he requested,
read and merged every change. [LICENSE](LICENSE) sets the terms, and its
attribution records who did what. This section informs the reader's
decision to use software written this way.

ST4 builds on older work. It grew out of
[ST1](https://github.com/odipar/ST1) and, through it, Einar Saukas's
[ZX1](https://github.com/einar-saukas/ZX1), whose format and algorithm are
his. ST4_wrap.S is based on ST1_wrap.S, which OpenAI Codex wrote for ST1.

## What ST4 is

ST4 compresses data on a PC and unpacks it on a Motorola 68000, the
processor of the Atari ST. A 68000 program includes a decoder and the
packed data, and unpacks the data as it runs: into a buffer the size of
the data, or a part at a time through a ring of memory far smaller than
it. A container packed with a loop point unpacks its start once and then
repeats its loop section, through the same ring.

The packer, `st4`, and a reference unpacker, `dst4`, come as tools in
three trees: Java, the reference, and ports in Go and C#. The decoders are
68000 assembly, under [`68k/`](68k).

## Getting started

Executables of `st4` and `dst4` for Windows, macOS and Linux, on x64 and
arm64, come from the [releases](https://github.com/odipar/ST4/releases),
one zip a platform ([RELEASES.md](doc/RELEASES.md)).

```sh
bin/st4  [-c[S]] [-kK] [-mN] [-lN] [-pN] [-rR] [-silent] < input > output.st4
bin/dst4 [-rN] [-silent] < input.st4 > output

bin/st4 -k2 -m480 < data.bin | bin/dst4 > back.bin
cd go && go build ./cmd/...
```

Every tool reads standard input, writes standard output and reports on
standard error, so one pipes into the next and `-silent` leaves the report
off. `-k` sets the unit and `-m` the window, the two choices below.
[tools.md](doc/tools.md) defines every flag, and
[decoders.md](doc/decoders.md) which decoder reads what a flag writes.

On the 68000 the unit is a constant, set before the decoder is included:

```
ST4_UNIT    equ     4
            include "ST4.S"
```

## How ST4 differs from ZX1

ST4 derives from Einar Saukas's ZX1. It keeps ZX1's three block types and
changes three things: the blocks are written into four streams instead of
one, lengths and offsets count units of 1, 2 or 4 bytes, and a match may
reach past the decoder's ring into the literal stream.

The streams and the units serve the 68000. Each stream has one kind of
value, so the decoder reads each of them the fastest way it has: the bit
stream refills a word at a time, and literals copy with `move.w` or
`move.l` because their alignment no longer depends on the bytes around
them. Units make one operation move 2 or 4 bytes, so there are half or a
quarter as many operations.

## The unit and the window

A wider unit makes the output larger and the decoder faster. A container
stores offsets and lengths in whole units, so a wider unit leaves the
packer fewer matches to choose from, and the decoder runs half or a quarter
as many operations. On the 120 test columns of
[research.md](doc/research.md), 898,830 bytes, at the widest window a unit
of 2 writes 32.3 per cent more than a unit of 1, and a unit of 4 writes
99.7 per cent more. Those columns are bytes, the shape a wider unit costs
most on, so `k` is chosen per asset against a decode budget rather than a
size target, and recorded in the header.

The window `M`, a flag of the packer, is the units a decoder keeps, and a
streaming decoder keeps a ring that wide. ZX1 reaches 511 bytes back, and
ST4 at a unit of 1 reaches 32,512. On the same columns, at ZX1's window the
two are level, 105,908 bytes against 105,742, and at the widest window ST4
writes 41.5 per cent fewer. The gain is reach alone.

## Words used here

| word | definition |
|---|---|
| container | one packed file: twenty-eight bytes of header, then streams A, B, C and D |
| block | one step of the data: a literal run, a match at the last offset, or a match at a new offset |
| literal run | a block of units read straight from stream B |
| match | a block that reads the output back, at the last offset or at a new one |
| copy | a block whose offset is beyond the window, which reads from the literal stream rather than from the output |
| unit | the `k` bytes every length and offset counts in: 1, 2 or 4 |
| offset | how far back a match or a copy reads, in units |
| window | `M`: the units the packer assumes a decoder keeps |
| ring | the buffer a streaming decoder writes into and matches back through, `M` units of it |
| loop point | the unit a looping container continues from after its last |

## The documents

| document | contents |
|---|---|
| [requirements.md](doc/requirements.md) | what the format, the packers and the decoders have to do |
| [SPEC.md](doc/SPEC.md) | the container, the streams, the blocks, the offsets, copies and loops |
| [decoders.md](doc/decoders.md) | the three 68000 decoders: which one to build, the state, the copy ladders |
| [tools.md](doc/tools.md) | the tools and their flags, the three trees, the optimizers, the search, the tests |
| [glossary.md](doc/glossary.md) | every term, and the document that explains it |
| [research.md](doc/research.md) | what was measured: rings, algorithms, the search, the memory |
| [RELEASES.md](doc/RELEASES.md) | what a release contains, and every one published |
| [near-offset.md](doc/near-offset.md) | a near offset in five bits, measured and parked: the decode costs more than the file saves |
| [conformance/](doc/conformance) | the kit an independent reader is written against, and the runs against it |

## Building and the tests

Java 23 and Maven for the reference tree, Go 1.24 for the port, .NET 10 for
the C# one, and for the 68000 rigs [rmac](http://rmac.is-slick.com) and
`pip install unicorn`.

```sh
mvn test                                  # the Java tree, and the Go tree against it
dotnet test csharp/Nt4.slnx -c Release    # the C# port, same corpora
python3 68k/test/emu/test_st4.py          # a real 68000 decoder under emulation
```

[tools.md](doc/tools.md) lists the rigs and what each of them reads.

## ST1

The ZX1 decoders this grew from and the jx1 packer are in
[odipar/ST1](https://github.com/odipar/ST1). ST4 forked from it at
`odipar/ST1@132aef0`; the emulator harness and the MC68000 cycle tables in
the rigs are carried copies of that repository's, which remains the
authority on ST1's timing.

## Related repositories

[YMX](https://github.com/odipar/YMX) is the family this repository belongs
to: a design document defining how YMXS, YMXR, DTX and ST4 fit together.

## License and attribution

The license is ST1's, which follows the original ZX1; see
[LICENSE](LICENSE). The compressor is BSD 3-Clause. The decompressors may
be used freely, including commercially, if your program's documentation
says that ZX1 was used through ST4, st4, or nt4.

The ZX1 format and algorithm are by Einar Saukas. The ST4 format and
additions are (c) 2026 Robbert van Dalen. Claude (Anthropic's Claude Code)
wrote the Java and C# tools, the 68000 decoders, the tests, and the
optimization work, under Robbert's direction. ST4_wrap.S is based on
ST1_wrap.S, which OpenAI Codex wrote for ST1; see [LICENSE](LICENSE).

Special thanks to Sandor Drieënhuizen and Wietze Spijkerman for their
support, proofreading, and ideas.
