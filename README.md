# ST4 - split-stream, unit-aligned compression for the Atari ST

## Read this first

**AI wrote most of ST4.** Claude (Anthropic's Claude Code) wrote the three
tool trees, the 68000 decoders, the tests, the emulation rigs and most of
what is written here, under Robbert van Dalen's direction: he requested,
read and merged every change. [LICENSE](LICENSE) is the terms, and its
attribution records who did what. Whether to use software written that way
is the reader's decision, and this section is here so that the decision is
informed.

What it is built on is older than it. ST4 grew out of
[ST1](https://github.com/odipar/ST1) and, through it, Einar Saukas's
[ZX1](https://github.com/einar-saukas/ZX1), whose format and algorithm are
his. ST4_wrap.S is based on ST1_wrap.S, which OpenAI Codex wrote for ST1.

## What ST4 is

ST4 is a compression format for the plain 68000, with small decoders and a
packer in three trees. It keeps ZX1's three block types and changes three
things: the blocks are written into four streams instead of one, lengths
and offsets count units of 1, 2 or 4 bytes, and a match may reach past the
decoder's ring into the literal stream.

The streams and the units serve the 68000. Each stream has one kind of
value, so the decoder reads each of them the fastest way it has: the bit
stream refills a word at a time, and literals copy with `move.w` or
`move.l` because their alignment no longer depends on the bytes around
them. Units make one operation move 2 or 4 bytes, so there are half or a
quarter as many operations.

The unit size `k` is a trade. An offset or length that is not a multiple of
`k` cannot be stored, so `k` of 2 or 4 pays some compression for speed: a
good trade on data that is itself word- or long-shaped - 68000 code, word
tables, speedcode, register streams - and a bad one elsewhere, so `k` is
chosen per asset and recorded in the header. At `k` of 1 and the 511-byte
window ZX1 reaches, ST4 writes 4.8 per cent fewer bytes over eight inputs
of prose and code ([research.md](doc/research.md), What ST4 at k = 1 is
worth against ZX1).

A stream can loop: packed with a loop point, it plays its intro once and
its loop forever through a ring far smaller than itself. And a stream
packed for a small ring can copy from the literal stream, which stays in
memory with the container, so the ring keeps only what the literals leave.

The name follows the family: ZX1 for the ZX Spectrum, ST1 for the Atari ST,
and 4 for the widest unit and the Mega ST4.

[YMX](https://github.com/odipar/YMX) is the family this repository belongs
to: a design document defining how YMXS, YMXR, DTX and ST4 fit together.
DTX packs a column of a table as an ST4 data set, and a YMXR tune reaches
the chips through both.

## The documents

| document | contents |
|---|---|
| [requirements.md](doc/requirements.md) | what the format, the packers and the decoders have to do |
| [SPEC.md](doc/SPEC.md) | the container, the streams, the blocks, the offsets, copies and loops |
| [decoders.md](doc/decoders.md) | the three 68000 decoders: which one, the state, the ladders |
| [tools.md](doc/tools.md) | the tools and their flags, the three trees, the optimizers, the search, the tests |
| [glossary.md](doc/glossary.md) | every term, and the document that explains it |
| [research.md](doc/research.md) | what was measured: rings, algorithms, the search, the memory |
| [RELEASES.md](doc/RELEASES.md) | what a release contains, and every one published |
| [near-offset.md](doc/near-offset.md) | a fifth class code, priced and parked |

## Usage

```sh
bin/st4  [-c[S]] [-kK] [-mN] [-lN] [-pN] [-rR] [-silent] < input > output.st4
bin/dst4 [-rN] [-silent] < input.st4 > output

bin/st4 -k2 -m480 < tune.bin | bin/dst4 > back.bin
cd go && go build ./cmd/...
```

Every tool reads standard input, writes standard output and reports on
standard error, so one pipes into the next and `-silent` leaves the report
off. [tools.md](doc/tools.md) defines every flag, and
[decoders.md](doc/decoders.md) which decoder reads what a flag writes.

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
