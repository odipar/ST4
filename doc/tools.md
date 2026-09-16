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
longer than `-m` it names the unit at which to save the decoder's state and
the unit at which to restore it (SPEC.md 6.3).

`dst4` is the readable reference the 68000 decoders are checked against.
Its output is padded to a whole number of units, as the format stores it
(SPEC.md 1.2): for a looping stream one whole pass, and it names where the
loop is.

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
  out, measured 4 to 7 times faster on data that repeats at range, and no
  faster on data that does not.
- **St4EventOptimizer**, the default. It only works where a repeated
  stretch of data starts or ends, which on repetitive data happens
  thousands of times less often than the positions the others visit. Same
  packed size, not always the same bytes; it falls back to the fast one
  where the data repeats in stretches too short to profit.

Measured on the optimizer alone, on corpora outside this repository:

| corpus | reference | fast | event-driven |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024`, k = 4 | 163 s | 37 s | 0.4 s |
| 300 KB slice, full window, k = 4 | 24 s | 5.7 s | 0.12 s |
| 32 KB of 68000 code, k = 1 | 9.8 s | 1.5 s | falls back to fast |

The repetition separates the three, not the size.
[research.md](research.md) measures them again on two corpora made from this
repository: over 900 KB of its sources the three are within 12 per cent of
one another, and over one 4 KB block repeated to the same length the event
optimizer is 246 times the reference.

## The search

Copies need a different parse, since a copy is valid only where its source
is literal and its distance counts the literals between: the best chain so
far no longer finds the best parse, and the exact optimum is NP-hard.
**St4LiteralCopySearch** packs them. Its parser is the fast optimizer's
dynamic program with copies added, exact for a fixed set of forced
literals, the dictionary.

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
[research.md](research.md) reads what the search leaves, what each move is
worth, and what a ring costs.

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

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer,
assemble the real decoders, decode under emulation as a plain 68000, and
check every output byte, the exact consumption of all four streams, ring
guard bands and the register state. The two loop rigs drive all three
decoders as a caller would, budgets, snapshots and restores included,
through more than two passes, against the infinite output the container
stands for, and read `dst4 -rN` back against the same bytes.
