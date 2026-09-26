# The tools

Two tools, in three trees that write the same bytes: `st4` packs and `dst4`
unpacks. Every tool reads standard input, writes standard output and
reports on standard error, so one pipes into the next and `-silent` leaves
the report off.

```sh
bin/st4  [-c[S]] [-kK] [-mN] [-lN] [-pN] [-rR] [-silent] < input > output.st4
bin/dst4 [-rN] [-silent] < input.st4 > output

bin/st4 -k2 < tune.bin | bin/dst4 > back.bin
```

## The flags

| flag | what it sets |
|---|---|
| `-kK` | the unit size: 1, 2 or 4 bytes, 1 by default (SPEC.md 1) |
| `-mN` | how far back a match reaches, in units: the ring a decoder keeps |
| `-lN` | the units an operation runs to, split where it would exceed them |
| `-rR` | the loop point, in units; `-r0` loops from the start (SPEC.md 6) |
| `-c` | a match beyond `-m` copies from the literal stream (SPEC.md 5) |
| `-cS` | the same, and a search of S seconds for a better parse |
| `-pN` | N bits charged on every block besides what it writes, so the parse prefers fewer, longer ones |
| `-silent` | the report off standard error, the output as it was |
| `-rN` (dst4) | the pass and then N - 1 repeats of the loop section |

`st4` reports progress and a time estimate as it works. Where a loop is
longer than `-m` it reports the unit at which to save the decoder's state
and the unit at which to restore it (SPEC.md 6.3).

`dst4` is the readable reference the 68000 decoders are checked against.
Its output is padded to a whole number of units, as the format stores it
(SPEC.md 1.2): for a looping stream one whole pass, and it reports where
the loop is.

## What the tools report

Every line below reaches standard error, an error prefixed `Error: ` and
the tool exiting 1. The three trees report the same line for the same
input, which `ConsistencyTest` reads against each of them.

`dst4`, reading a container (SPEC.md 3.1):

| condition | reported as |
|---|---|
| the file is under 20 bytes | `too short to be an ST4 file` |
| the signature's high word is other than the magic | `not an ST4 file` |
| the signature's version byte is V, other than 7 | `ST4 format version V, not 7` |
| the unit byte K is other than 1, 2 or 4 | `unit size K is not 1, 2 or 4` |
| the output size N is negative, or other than a whole number of units | `output size N is not a whole number of K-byte units` |
| the rewind point R is other than -1 and outside the output's units | `rewind point R is not a unit of the output` |
| the window W is outside 1 to M units, M the reach at that unit | `window W is not 1..M units` |
| stream S, B, C or D, begins off a long boundary | `stream S does not start on a long boundary` |
| stream S begins before the one before it, or past the file | `stream S lies outside the file` |

`dst4`, decoding the streams (SPEC.md 3.2, 6.2):

| condition | reported as |
|---|---|
| a copy of L units from B units back reads past the literal read pointer | `a copy of L units from B units back does not stay behind the literal read pointer` |
| the loop distance D units reaches past the window W | `the loop distance D units reaches past the W-unit window` |
| `-rN` with N above 1 on a container whose rewind point is -1 | `The stream does not loop, so -rN has nothing to repeat` |

Either tool:

| condition | reported as |
|---|---|
| a read of standard input fails | `Cannot read standard input` |
| a write of standard output fails | `Cannot write standard output` |

`st4` reports two notes on standard error as it packs, the tool exiting
0: `The loop is longer than the -mN window, so the decoder cannot loop
it after the first pass` where the loop the caller asked for reaches
past the window, and `Warning: longest operation is L units, over the
-lN limit: a literal run always reaches` where an operation runs past
the limit.

## The three trees

| tree | what it is |
|---|---|
| `src/main/java/org/st4` | the reference: `org.st4.St4` and `org.st4.Dst4`, which `bin/st4` and `bin/dst4` run out of `target/classes` |
| [`go/`](../go) | the port the releases ship, one executable a tool a platform |
| [`csharp/`](../csharp/README.md) | `nt4`, a .NET 10 port of the same classes and options |

The three write the same bytes for the same input and flags, which
`GoParityTest` reads back over four inputs at ten flag settings, and the
C# suite over its corpora.

```sh
cd go && go build ./cmd/...
go get github.com/odipar/st4/go

dotnet run --project csharp/src/Nt4.Cli -- -k2 < input > output.st4

release/publish.sh          # six platforms, one zip each, into dist/release
```

The Go module is `github.com/odipar/st4/go`, tagged `go/vX.Y.Z`. Its
version is the tools', not the format's: the format is 7 and the module
starts at v0.1.0, since a Go module is versioned from v0.

## The optimizers

Three optimizers select the blocks of a stream without copies, and tests
check the three against each other:

- **St4Optimizer**, the readable reference. It tries every choice at every
  position and keeps the cheapest.
- **St4FastOptimizer**, the same choices on plain arrays: the same bytes
  out, measured 4 to 7 times faster on data that repeats at range, and as
  fast as the reference on data that repeats less.
- **St4EventOptimizer**, the default. It works at the positions where a
  repeated stretch of data starts or ends, on repetitive data thousands of
  times fewer than the positions the others visit. It packs to the same
  size, in bytes that can differ, and falls back to the fast one where the
  data repeats in stretches too short to profit.

Measured on the optimizer alone, on corpora outside this repository:

| corpus | reference | fast | event-driven |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024`, k = 4 | 163 s | 37 s | 0.4 s |
| 300 KB slice, full window, k = 4 | 24 s | 5.7 s | 0.12 s |
| 32 KB of 68000 code, k = 1 | 9.8 s | 1.5 s | falls back to fast |

Repetition, rather than size, separates the three.
[research.md](research.md) measures them again on two corpora made from
this repository: over 900 KB of its sources the three are within 12 per
cent of one another, and over one 4 KB block repeated to the same length
the event optimizer is 246 times the reference.

## The search

Copies need a different parse, since a copy is valid only where its source
is literal and its distance counts the literals between: the best chain so
far no longer finds the best parse, and the exact optimum is NP-hard.
**St4LiteralCopySearch** packs them. Its parser is the fast optimizer's
dynamic program with copies added, exact for a fixed set of forced
literals, the dictionary.

A copy is costed with the literal count of that dictionary, a lower bound
on the literals between a source and the copy, so the parse may choose one
whose offset, the window plus that distance, runs past what an offset
reaches (SPEC.md 4.3). The compressor writes the units of such a copy as
literals: they decode the same, and a later copy may read them.

`-c` runs its opening passes: the dictionary is the literals of a
full-window parse, holes of a few units filled, shrunk to what is copied
from, up to four times.

`-cS` then searches over dictionaries for S seconds: a greedy sweep frees
and trims every literal run, keeping what packs smaller, and then steps at
odds of two, four, four, one, four and four of twenty - free a run, seed
literals, extend a run, trim a run, merge two runs, grow a run where a copy
reads from - with one left for freeing and seeding together. A step is
accepted when it packs smaller and by annealing when it does not, and every
step is scored by what the compressor writes.

**St4LiteralCopyOracle** tries every parse on inputs of a dozen units;
against it the opening passes are 0.8 per cent above the optimum and the
search 0.4 per cent, reaching it on 56 of 60. `ConsistencyTest` measures
those three again and reads this sentence for them.
[research.md](research.md) measures what the search leaves, what each move
is worth, and what a ring costs.

## The tests

```sh
mvn test                                  # round-trips, containers, loops, copies, optimizers, Go parity
dotnet test csharp/Nt4.slnx -c Release    # the C# port, same corpora
python3 68k/test/emu/test_st4.py          # linear decoder vs the Java packer, k = 1, 2, 4
python3 68k/test/emu/test_st4_wrap.py     # counted wrap, every unit size
python3 68k/test/emu/test_st4_ring.py     # general ring, both wrap modes, oversized budgets
python3 68k/test/emu/test_st4_repeat.py   # streams that loop by themselves, past two passes
python3 68k/test/emu/test_st4_rewind.py   # loops longer than the ring, replayed by rewind
python3 68k/test/emu/test_st4_copies.py   # copies from the literal stream, on window builds
python3 68k/test/emu/bench_bits.py        # why the lengths are Elias gamma
python3 68k/test/emu/bench_offset.py      # why the class bits select the stream
python3 68k/test/emu/bench_decode.py      # cycles a unit, plain build against window
```

`.github/workflows/test.yml` runs `bin/suite` on a GitHub runner, with
Go, the .NET SDK, rmac and unicorn on it so that no check skips, and the
rigs' containers kept between runs under a key of the packer's sources.
No push starts it: a caller starts it from the Actions tab or by `gh
workflow run test.yml`.

`bin/suite [maven argument ...]` runs that suite on the caller's
machine: `mvn test`, whose `RigsTest` runs the six rigs above side by
side, and then `dotnet test csharp/Nt4.slnx`. A skipped test is a check
that did not run, so the script requires go, rmac and dotnet on the path
and python3 with unicorn in it, exit 2 where one of them is absent, and
reads the count of skipped tests off both runs, exit 1 where either
count is above 0. The three benchmarks run by hand.

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer,
assemble the real decoders, decode under emulation as a plain 68000, and
check every output byte, the exact consumption of all four streams, ring
guard bands and the register state. The two loop rigs drive all three
decoders as a caller would, budgets, snapshots and restores included,
through more than two passes, against the infinite output the container
encodes, and read `dst4 -rN` back against the same bytes.

`test_st4.py` is the harness the other five read: it packs and unpacks
through the real tools, builds a decoder at a unit size, seeds the four
streams and the registers a decoder reads them through, guards a ring
against a write outside it, and reads every stream to its end. A rig
reads the harness for those and has the calls its subject needs.

The harness keeps every container the packer writes and every output
`dst4 -rN` writes in `.st4` beside the rigs, in a directory named by a
hash of the class files of `org.st4`, and removes a directory of another
build on its first call. A warm cache runs the six rigs side by side in
17 seconds on macOS and 24 on a GitHub runner. A cold one starts the JVM
once for each container and each output, more than a thousand times, so
its cost is the cost of a JVM's start: 70 seconds on the runner and 835
on macOS. The key of the input and the format version alone let the rigs
pass on the containers an old packer wrote.
