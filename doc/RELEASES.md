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
