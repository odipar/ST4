# The releases

What a release contains is defined here, and each one published is listed
below it.

## What a release contains

`release/publish.sh` writes `dist/release` ([tools.md](tools.md)), and
`release/manifest.sh` the manifest in it:

- one zip a platform, over six: Windows, macOS and Linux, each on x64 and
  arm64. A zip has `st4` and `dst4` in it as executables, with no runtime
  to install beside them
- `MANIFEST.txt`: every zip's size and sha256, what is in it, and the
  source commit the release was built from

The version names every zip. It is read out of `pom.xml`, or named as the
script's one argument.

The six are built from `go/`, since `go build` cross-compiles to any target
from any host: a release build runs the Go tree alone. The three trees
write the same bytes ([requirements.md](requirements.md), R4.2), so a
release is built from one of them. Before the release directory is written,
the pair built for the host runs from outside the repository with an empty
environment, a file in and the same bytes back.

## The two lines of tags

This repository publishes under two names:

| tag | what it names |
|---|---|
| `vN.0` | the format. `v7.0` is format 7 ([SPEC.md](SPEC.md) 8.1) |
| `go/vX.Y.Z` | the tools, and the Go module `github.com/odipar/st4/go` |

A Go module in a subdirectory is versioned by a tag with the directory in
front of it, so the module's tags read `go/v0.1.3`. The module's number is
the tools', not the format's: a module is versioned from v0, and a major
above 1 in a module path needs a matching suffix the format never had.

DTX, YMXS and YMXR each publish one line of tags, `vX.Y.Z`, and push
`go/vX.Y.Z` beside it for the submodule. This repository is the one where a
plain `vN.0` means the format.

## Published

### go/v0.1.12, 2026-09-22

<https://github.com/odipar/ST4/releases/tag/go/v0.1.12>, built from the
commit tagged `go/v0.1.12`.

What a caller sees when a tool reports something: tools.md writes those
lines down for the first time, and the Java tree, the Go tree and the C#
tree are read against them. Two of the three reported an input the other
two report, or none at all.

- **`dst4 -rN` on a stream that does not loop.** The Java tree and the C#
  tree report `The stream does not loop, so -rN has nothing to repeat`
  and exit 1; the Go tree unpacked one pass and exited 0. Measured on a
  4,000-byte input packed with no loop: the two exits differed and the
  Go tree's output was the pass. The Go tree reports it now.
- **A read or a write that fails.** The Java tree and the Go tree report
  `Cannot read standard input` and `Cannot write standard output`; the C#
  tree let the runtime report it. Both tools of the C# tree report those
  lines now.
- **The lines are in the document.** tools.md has a table for the
  container reader, one for the decoder and one for either tool, and
  `ConsistencyTest` reads twelve of those lines against the three trees:
  the longest run of words between the figures a tool writes into a line
  must appear in each. A line reworded in one tree, or in the document
  alone, fails there.

The six executables are built from `go/`, where one line moved, so a
`dst4` of this release differs from `go/v0.1.11`'s.

Checks: `mvn -o clean test` green, 73 tests; `dotnet test` green, 26;
`go test ./...` green.

### go/v0.1.11, 2026-09-18

<https://github.com/odipar/ST4/releases/tag/go/v0.1.11>, built from the
commit tagged `go/v0.1.11`.

The rigs read one harness, one build runs at a time, and a quoted block
keeps its words. Every file the six executables are built from matches
v0.1.9 - `go/`, `68k/` and `csharp/` - so they are that release's
bytes and the Go module is unchanged. What this release has in it is the
Java tree's style check, the script under `bin/` and the Python rigs.

- **The six rigs read one harness.** Each of the five rigs beside
  `test_st4.py` had a separate copy of four blocks: a decoder built at a
  unit size, the three stream regions mapped and seeded with the registers
  a decoder reads them through, a guard band and a write hook around a
  ring, and the read of all four streams to their ends. `test_st4.py` is
  the module the other five load, so the blocks are there now:
  `assemble`, `seed`, `guarded`, `drained`, `looped` and `played`. The
  rigs lose 372 lines and gain 136, and each one has the calls its subject
  needs.
- **One build runs at a time.** `bin/st4 -k2 < tune.bin | bin/dst4` starts
  both tools at once, and with a source newer than the last build both
  found a build owed and both ran Maven into the same `target/classes`:
  measured here with a wrapper counting invocations, that pipeline ran
  `mvn` twice in the same second. `bin/st4-run` builds under the lock
  `target/.building` now, as YMXS's runner has since it hit the same race.
- **A code span that wraps is quoted whole.** The style check blanked a
  code span a line at a time while the rest of it reads a paragraph
  joined, so a span broken by a wrap was two unpaired backticks and its
  words were read as prose - and the pairing ran from one span's closing
  backtick to the next span's opening one, which blanked the prose
  between them. The blanking runs over the joined lines now.
- **A fenced block is quoted as a code span is.** A fence broke the
  paragraph and was read alone, and the lines inside it were read as
  paragraphs of this tree: 53 blocks, 106 fences and 188 lines across the
  four repositories, every one of them a command, a file, a run of output
  or a diagram. The check reads past a block and the two fences around it.

### go/v0.1.10, 2026-09-18

<https://github.com/odipar/ST4/releases/tag/go/v0.1.10>, built from the
commit tagged `go/v0.1.10`.

The document checks are one package. Every file a tool is built from
matches v0.1.9 - `go/`, `68k/`, `csharp/`, `bin/` and every
document - so the six executables are that release's and the Go module is
its bytes.

- **`org.st4.doc.Documents`** reads a link that resolves, one wrap width, a
  glossary in order and the rows it is read from. Those four were written in
  each of the four repositories of the family, and the copies had drifted in
  both directions. This tree skipped fenced blocks and anchors where DTX
  read them; YMXS named the line a broken link is written at, and YMXS and
  YMXR both reported how many documents they had read, where this tree
  reported neither. The package reads the best of the four and is carried
  here from DTX, where it is kept, as `org.st4.style` is.
- `ConsistencyTest` is 54 lines shorter for it, and reads the figures of
  this repository as it did.

### go/v0.1.9, 2026-09-18

<https://github.com/odipar/ST4/releases/tag/go/v0.1.9>, built from the
commit tagged `go/v0.1.9`.

The style check, and the comments it read. The tools write the bytes
v0.1.8 wrote: the three trees' packers moved in their comments alone, and
the pair built here packs README.md, SPEC.md and research.md at k of 1, 2
and 4 to the bytes a build of the tag before writes.

- **The struck list is a document.** The check was a list of 104 phrases in
  a test class, matched as substrings. `org.st4.style` reads `STRUCK.md` -
  a section a rule of AGENTS.md, an entry a name, a pattern and the samples
  the pattern is and is not in - and runs over every document and every
  code comment. `HouseStyleTest` reads every sample back, so a pattern that
  drifts fails there rather than in review. The package is carried from
  DTX, which wrote it, and the four repositories of the family run the same
  370 lines.
- **The cleft was struck in AGENTS.md and in no list.** Adding the entry
  found 44 of the 67 lines reworded here, most of them in research.md. Each
  paragraph with those lines in it is rewrapped, and every other paragraph is
  the one the release before has.
- The `names` entry is empty: the only names this tree spells a struck
  construct inside are Go identifiers, which the check reads past.

### go/v0.1.8, 2026-09-17

<https://github.com/odipar/ST4/releases/tag/go/v0.1.8>, built from the
commit tagged `go/v0.1.8`.

The documents alone. The tools write the bytes v0.1.7 wrote, the Go module
is v0.1.7's, and the three 68000 decoders are v0.1.5's: every file under
`src/main`, `go/`, `csharp/src` and `68k/` matches v0.1.7.

- **The near offset is priced and parked.** research.md and near-offset.md
  read a near offset at the class tree the format's parity rule allows, a
  flag, three class bits and five offset bits, which saves two bits a near
  match against the four the first reckoning claimed: 2.88 per cent of the
  corpus it was measured on, and 3.57 per cent of thirty columns of real
  tune data parsed for it at a ring of 256 and k = 2. Decode costs 4.7 per
  cent more there and 6.6 per cent on the tune data, so the two documents
  are headed by what the measurement found: the decode costs more than the
  file saves.
- **The figures YMXR measured are read again here.** An operation costs 200
  to 270 cycles to parse where research.md read 225 to 240, so the worst
  window at k = 1 is 45 to 61 per cent of YMXR's frame budget where the
  sentence read 52, a penalty of 8 bits brings it to 36 to 49 where it read
  42, and the near offset's saving is six to eight per cent of an operation
  where it read seven.

### go/v0.1.7, 2026-09-17

<https://github.com/odipar/ST4/releases/tag/go/v0.1.7>, built from the
commit tagged `go/v0.1.7`.

The checks alone. The tools write the bytes v0.1.6 wrote, the Go module is
v0.1.6's, and the three 68000 decoders are v0.1.5's: every file under
`src/main`, `go/`, `csharp/src` and `68k/` matches v0.1.6.

- `everyClauseCitedIsDefined` reads every `<document>.md N` citation
  against the clauses that document defines. It read `SPEC.md N` alone
  before, and a citation of tools.md, decoders.md or research.md by number
  went unread. 43 citations are read and each resolves.
- The clause reader accepts any numbered heading and any bold clause number
  rather than SPEC.md's two shapes, so a document numbered another way is
  read as it is.
- DTX and YMXR carry the same check now, and YMXR and YMXS read a citation
  written through a link as this one always has.

### go/v0.1.6, 2026-09-17

<https://github.com/odipar/ST4/releases/tag/go/v0.1.6>, built from the
commit tagged `go/v0.1.6`.

A copy the offsets cannot reach is written as literals. Every other stream
packs to the bytes v0.1.5 wrote, and the three 68000 decoders are v0.1.5's.

- `st4 -k1 -m16 -c` over 32,512 units of random data and its first 500
  units again ended the Go tool at `a copy reaches past the offsets` and
  the C# port at the same message, while the Java tool, whose check is an
  assertion and whose script runs without `-ea`, wrote a container with an
  offset the format does not encode (SPEC.md 4.3).
- The parse costs a copy with the literal count of its dictionary, a lower
  bound on the literals between a source and the copy, so it may choose one
  that reads further back than an offset reaches. The compressor reads that
  distance before it writes the offset and puts the units of such a copy in
  as literals: they decode the same, and a later copy may read them. The
  same lines go into all three trees, and the three pack that stream to
  the same bytes.
- Copies exist only where the window is well under the 32,512 bytes an
  offset reaches, so the default window never met this and a ring of 960 or
  of 16 does.
- What moves: the broken case alone. README.md, doc/SPEC.md and 68k/ST4.S
  pack byte for byte as before at `-k1`, `-k2 -c`, `-k1 -m960 -c`, `-k2
  -m256 -c` and `-k4 -c`.

`St4RoundTripTest` and the C# `RoundTripTests` pack that stream and decode
it, and `GoParityTest` packs it in both trees and requires the same bytes:
its four inputs are each under 32,512 bytes, so no flag setting over them
reaches this.

### go/v0.1.5, 2026-09-16

<https://github.com/odipar/ST4/releases/tag/go/v0.1.5>, built from the
commit tagged `go/v0.1.5`.

Two of the three 68000 decoders enter on an instruction rather than on a
branch, and ST4_wrap's shape is tightened further. The tools write the
bytes v0.1.4 wrote: the Go module is v0.1.4's, no packer having changed.

- The jump table keeps its slot addresses and changes what lives at the
  last one. `ST4_resume`'s body starts where a `bra.w` to it was, and
  `ST4_init` is reached through slot 0. A caller's `jsr base+4`, or `+8`
  under ST4.S, behaves as it did.
- ST4.S is 5.86 per cent fewer cycles at a unit a call and ST4_wrap 5.73,
  over the rig corpora at `k` of 2 through a 256-byte ring. ST4_ring is
  left alone: freeing its slot pushes five per-segment branches out of
  short range, and that costs more a segment than the slot saves a call.
- ST4_wrap also packs `ST4_init`'s d2 in three instructions rather than
  four, falls through where two `bra.s` were, tests the gamma's end before
  its refill, folds `match_next` into `match_transition`, and counts the
  counted ladder's full passes in units.
- Sizes: ST4.S 306, 308 and 310 bytes becomes 304, 306 and 308, and
  ST4_wrap.S 324, 328 and 330 becomes 310, 314 and 316.

`bench_decode.py` is new: it packs a corpus, assembles both builds of a
decoder and counts every instruction on an MC68000 model, so decoders.md's
figures are measured rather than recalled. research.md's ZX1 comparison
measures the 120 chiptune columns now rather than eight files of this
repository, three of them the decoders, which moved the corpus whenever the
code moved.

### go/v0.1.4, 2026-09-16

<https://github.com/odipar/ST4/releases/tag/go/v0.1.4>, built from the
commit tagged `go/v0.1.4`.

Two moves that read the parse for where to act, and an extend that reaches
further. `st4 -c` without seconds writes the bytes v0.1.3 wrote; a search
with seconds writes smaller files for the same seconds.

- **source** grows the dictionary where a copy reads from. The parse names
  the distance, so the move reads where to act rather than searching for
  it. **merge** fills the gap between a literal run and the one after it,
  up to 24 units, which a move that grows one run closes only by drawing
  the whole gap at once.
- The odds are now two, four, four, one, four and four of twenty - free,
  seed, extend, trim, merge, source - with one for freeing and seeding
  together, and an extend reaches twenty units where it reached eight.
- 120 columns at a second a column through a ring of 256 bytes: 122,914
  bytes against 124,404, 1.20 per cent smaller. Over 24 of them at three
  seconds a column, 2.77 per cent.
- Neither move pays alone (research.md, What the two are worth): a copy
  reads further when its source is longer, and the source is longer when
  the gap before it is closed.
- Fifty runs of five inputs at ten flag settings write the bytes v0.1.3
  wrote, the timed search apart.

The repository around the tools took the shape DTX, YMXS and YMXR share:
requirements, a specification, a glossary and this document, and a test
that reads the figures and the pointers of each back out of the tree. It
found that the differential rigs could not run from a clean checkout, and
that the ZX1 comparison in the README had no measurement behind it.

### go/v0.1.3, 2026-09-16

<https://github.com/odipar/ST4/releases/tag/go/v0.1.3>, built from
`aa01118`.

The search's odds follow what its moves save. `st4 -c` without seconds
writes the bytes v0.1.2 wrote; a search with seconds writes smaller files
for the same seconds.

- What each move saved had never been read. Over 24 columns at 1,000 steps
  extend was accepted 203 times and saved 4,528 bits, where free was
  accepted 4,889 times and saved 892, those being the sideways and uphill
  moves the annealing accepts rather than gains. The odds gave extend three
  of twenty, and are now two, four, twelve, one and one.
- 120 columns at a second a column: 121,052 bytes against 121,668, 0.51 per
  cent smaller. At three seconds a column over 24 columns, 1.3 per cent.
- The gain grows with the budget: 0.66 per cent at 300 steps, 1.3 at 1,000,
  2.2 at 3,000.

### go/v0.1.2, 2026-09-16

<https://github.com/odipar/ST4/releases/tag/go/v0.1.2>, built from
`07097fb`.

The same bytes, a fifth more search steps a second where it counts. A CPU
profile reads a step of `st4 -cS` as 83 per cent parse, and 13 per cent of
the parse was the literal channel reading a min-tree once a gamma class a
position.

- A gamma class is a window that slides one slot a position, so a queue
  kept least first reads its least in one step. The min-tree is gone.
- 2,000 search steps a column over 24 columns: 107 s against 133 s.
- The bytes are unchanged, over 88 runs of eight inputs at eleven flag
  settings and the three trees against one another.

### go/v0.1.1, 2026-09-15

<https://github.com/odipar/ST4/releases/tag/go/v0.1.1>, built from
`19a77dd`.

The same bytes at a fifth of the memory. `st4 -c` kept a node for every
state its dynamic program reached, and 98 per cent of them were unreachable
by the time a parse ended.

- The pool now collects: it marks from the arrays that name a node,
  renumbers what it keeps into the front of the pool, and bounds the next
  collection at twice that.
- `-k1 -c` on a 48 KB file: 1,386 MB peak against 6,472 MB, and 18.1 s
  against 29.5 s.
- The Java tools, which ended that file in `OutOfMemoryError`, pack it at
  `-Xmx1g` where they needed 12 GB.

### go/v0.1.0, 2026-09-15

<https://github.com/odipar/ST4/releases/tag/go/v0.1.0>, built from
`43a68f6`.

The first release of the Go tools: `st4` and `dst4` as standalone
executables, one pair a platform.

- `github.com/odipar/st4/go/st4` is the compressor, the decompressor and
  the format they share. The trees that pack with it copy it.
- Two gaps closed on the way: neither the Go nor the C# tree read `-pN`,
  and every tree wrote its progress meter to standard output, where the
  packed bytes go.

### v7.0, 2026-09-03

<https://github.com/odipar/ST4/releases/tag/v7.0>, built from `db28210`.

The format, version 7, a clean break from version 4. The player left this
repository at this release: ST4 is the compressor, its three 68000 decoders
and their tests.

- The four streams lie in the file as A, B, C and D. The header is
  twenty-eight bytes and has two new fields in it, the rewind point and the
  window (SPEC.md 2.1).
- A stream can loop, by a match that never ends or by a rewind the caller
  drives (SPEC.md 6).
- An offset beyond the window copies from the literal stream, so a ring
  reads its history without keeping it twice (SPEC.md 5). `st4 -c` packs
  with copies, `-cS` searches for S seconds beyond the opening passes.
- `ST4_WINDOW equ 1` builds the copy code, and `ST4_init` writes the window
  into the two instructions that read it (decoders.md, copies from the
  literal stream).

### Before v7.0

The YX6 chiptune player was in this repository until v7.0, and these
releases are of the player and the format together. The player is now
[YMXR](https://github.com/odipar/YMXR)'s.

| tag | date | what it was |
|---|---|---|
| `v4.0` | 2026-08-21 | yx6 format 7: the file selects the timers |
| `v3.0` | 2026-08-21 | yx6 format 6: the tick channel, and the timer under it |
| `v2.1` | 2026-08-20 | a fix release on v2.0 |
| `v2.0` | 2026-08-20 | yx6 format 5: the compiled effect script |
| `v1.0` | 2026-08-20 | SNDH, the raster monitor, and the tools in Java |
| `v0.7` | 2026-08-19 | the player's cycles, and a bug hunt that ended in a rule |
| `v0.6` | 2026-08-19 | yx6 format 4: the YM effects |
| `v0.5` | 2026-08-18 | two copy ladders (decoders.md, the copy ladders) |
| `v0.4` | 2026-08-18 | `nt4`, the tools in C#, and the format defined |
| `v0.3` | 2026-08-18 | the player reads the four streams |
| `v0.2` | 2026-08-18 | the first release out of this repository, split from ST1 |
