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

A stream can also be endless. Packed with a loop point, it plays its intro
once and its loop forever, through a ring far smaller than itself — by itself
when the loop fits the decoder's window, and by rewinding the encoded stream
when it does not.

The name follows the family joke: ZX1 signs the ZX Spectrum, ST1 the Atari ST,
and the 4 is both the widest unit and a nod to the Mega ST4.

## The format

All lengths and offsets count units of k bytes. Input that is not a whole
number of units is padded with zeros, and the padding is part of the stored
output. A container holds four streams, in this order:

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
match, new offset    2 class bits + one value from B or C, then gamma(length-1)
```

One flag bit says which block comes next. After literals, `0` starts a match
at the last offset and `1` a match at a new offset — two literal runs in a row
cannot happen. After a match, `0` starts literals and `1` a match at a new
offset. The first block is always literals and has no flag bit.

The two class bits of a new offset pick its stream and reach, or end the data:

```
1 0   byte offset from stream C, 1..256 units back
1 1   byte offset from stream C, 257..512 units back
0 0   word offset from stream D
0 1   end of the data, followed by one repeat bit
```

A byte offset of n units in bank b is stored as the byte 256·(b+1) − n. A word
offset of n units is stored big-endian as 65536 − n·k, which is −n·k exactly
as the decoder keeps it, so installing one is a single move. No offset may
reach further back than 32512 bytes, at any k, and a new-offset match is at
least 2 units long — which is why it stores gamma(length−1).

The repeat bit says whether the stream ends there. A `0` ends it. A `1` makes
it loop: one last word offset is read from stream D and the stream becomes an
endless match that far back — see [Loops](#loops).

What an offset reaches depends on the window M the stream was packed for,
which the header records. An offset of at most M is a match, and copies
output from that many units back. An offset beyond M is a *copy from the
literal stream*: it copies literal units from M less than that far behind
the literal read pointer, in stream B, and leaves the pointer where it was —
see [Copies from the literal stream](#copies-from-the-literal-stream).
Streams packed without copies never exceed M and decode as they always did.

Together with its flag, a block is an even number of bits: a gamma value is
an odd count, and the flag or class bits make it even. That is why a new
offset spends two class bits rather than one, and the even rhythm is what
lets the decoders skip the refill check on every read but three: a gamma
continuation bit, the class bit right after a flag, and the repeat bit after
the end code. Stream A is padded to an even length so the last refill finds a
whole word.

### The container

A container is twenty-eight bytes of header, then the streams:

```
 0  4  signature: 'S', '4', format version (7), k
 4  4  O, the output size in bytes, a multiple of k
 8  4  where stream B, the literal data, starts, in bytes from the header
12  4  where stream C, the byte offsets, starts
16  4  where stream D, the word offsets, starts
20  4  the rewind point in bytes, or $FFFFFFFF when there is none
24  4  M, the window in units: matches within it, copies from B beyond it
28 ..  streams A, B, C and D in that order, each starting on a long boundary
```

The header holds only what cannot be worked out from the streams. Stream A
begins where the header ends, so it needs no field; no stream length is kept,
because each stream runs to the next and the decoder stops on the end code;
the rewind point is a fact a caller cannot derive — where to save the
decoder's state to replay a loop — so it is set only when a caller has that
to do; and the window is what tells a match from a copy. The signature fits
one long, so a decoder built for one k accepts or rejects an asset with a
single `cmp.l`, and the stream starts are header-relative, so opening a
container is one `adda.l` per stream. The eight instructions that do it are
in [ST4.S](68k/ST4.S).

Stream B, the literal data, sits second, right after the bits, as version 4
had it. Versions 5 and 6 put it last so that a ring placed directly after the
container would border the literal data — but a copy from the literal stream
is measured from the literal read pointer, not from the ring, so nothing in
the decoders depends on where the ring is, and version 7 put the stream back.

### Copies from the literal stream

A ring holds the last M units of output, and a match can reach no further.
But every literal the stream ever had is still in memory, in stream B, in
order, for as long as the container is — and a copy from the literal stream
reaches them at any ring size. The packer writes one as an offset beyond M:
the window plus the number of literal units between the copy's source and
the copy, which is exactly what the decoder walks back from its literal read
pointer. Nothing in the stream says which is which; the magnitude does, the
control bits are the same three, and a byte offset reaches 512 − M literals.

Two rules follow from the decoder, which has nothing but the offset register
to remember a copy by. A copy interrupted by the budget or the ring end must
continue where it stopped, so a copy advances its offset by what it copied,
and a rep after a copy therefore resumes just past it, from wherever the read
pointer has got to. And a copy must be strictly shorter than its distance, or
the offset would reach zero; the packer keeps it so.

`st4 -c` turns copies on. The parse behind them cannot be exactly optimal —
the parse decides which units are literal, a copy is only valid if its source
is, and its offset counts the literals between, so the best chain so far no
longer decides the best parse and the optimum is NP-hard — so the dictionary
is chosen first, the literals of a full-window parse, and the parse is exact
for it - the search's opening passes, which is all `-c` alone runs.
`St4LiteralCopyOracle` tries every parse on inputs small enough for that,
and puts those passes within about a percent of the true optimum on those.

`st4 -cS` keeps going for S seconds. `St4LiteralCopySearch` descends and
anneals over which units are literal — a greedy sweep that frees and trims
every literal run, then random moves that free, seed, extend or trim runs,
accepted when they pack smaller and now and then when they do not — and
scores each step by an exact parse for that choice and by what the
compressor then writes, so its number is the packed size. The parse is the
fast optimizer's DP with copies added, their sources found through the
dictionary's two-unit chains, the rep of a copy in its cost model, restarted
from a checkpoint before the first changed unit: some ten milliseconds a
step on this README. On the tiny inputs the oracle can exhaust, it reaches
the optimum on 59 of 60. On this README at k = 1 with a 64-unit window, the
one-shot parse packs to 78.2% of the input and a 256-unit ring without
copies to 66.3%; ten minutes of search take the 64-unit ring to 60.8%, and
it was still improving when time ran out. The whole window packs to 46.1%.

What copies buy is the ring: measured
on the test corpora with two minutes of search per file, a 16-unit ring with
copies packs word-soup at k = 1 to 30.5% of the input where the ring alone
gives 95.6%, prose like a ring of over 200 units, and at k = 2 and 4 better
than a 256-unit ring alone; [doc/research.md](doc/research.md) has the
tables. The decoders take them when built with `ST4_WINDOW`; see
[68000 decoders](#68000-decoders).

### Loops

`st4 -rR` names a loop point, a unit index. The container then stands for the
infinite input `[0,R)` `[R,O)*`: after its last unit, the output continues
from unit R and never stops. That is how a small ring holds an endless stream
— a looping sample, a register dump, a pattern — and `-r0` loops the whole
stream. There are two ways to loop, and the packer picks one by whether the
loop fits the window it was packed for.

**A loop that fits the window loops by itself.** The stream's repeat bit is
set, and the word that follows it in stream D is the distance O−R back to the
loop point. The decoder installs it as any other offset and matches it
forever, so after one pass every unit is the one O−R units back. It costs the
container two bytes and the pass is packed exactly as it would be without
the loop. Its reach is the window's: the endless match reads the loop back out
of the ring, so `[R,O)` must fit `-m`.

**A loop longer than the window is replayed from the encoded stream.** The
stream ends plainly and the header names the rewind point. Every decoder
keeps its whole state in seven registers, so the caller saves them when the
output reaches R and restores them — all but the write pointer — when it
reaches O, every pass; the container is in memory anyway, so a ring of any
size will do. What makes the replay sound is the parse. In pass one the loop
follows the intro; in every later pass it follows itself; so the loop
`[R,O)` is packed on its own, and no match in it reaches before R or
straddles R — every pass sees the same history. The cost is confined to the
first window's worth of the loop, which cannot reference the intro: on the
test corpora, 0.4–1.3% of the packed size.

## 68000 decoders

Three decoders, each built for one unit size with `ST4_UNIT` — nine builds, no
runtime choice of width — and, when the stream has copies from the literal
stream, for its window with `ST4_WINDOW`:

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

Loops cost the decoders little. A stream that loops by itself arms its endless
match at the end code and re-arms it 65535 units at a time, forever: the bit
queue is pinned to zero, a value no real read leaves it in, so the transition
that would parse the next block re-arms instead. That adds one branch to a
match-to-literals transition and one checked bit to the end code; measured
against the version 4 decoders on the same corpora, streams that end pay
0.05–0.4% more cycles, and the loop itself runs at or below the pass's own
rate, since it only copies. Such a stream never reaches DONE: drive it through
`ST4_resume` with budgets and stop when you have enough — `ST4_decompress`,
which drains until DONE, would never return. A loop the caller replays needs
no decoder code at all: when the output reaches the header's rewind point,
save `a0`, `a2`, `a4`, `a5`, `d0`, `d1` and `d2`; when it reaches O, restore
them — `a1` stays where the ring has got to — and carry on. Arrange the
budgets so a call ends exactly on both points; a state saved mid-operation
replays like any other, because there is nothing else to it.

Copies from the literal stream cost one compare. A decoder built with
`ST4_WINDOW` set to the stream's window M tells a copy from a match by
magnitude — a `cmp.w` and a short branch per match segment — and a copy's
source is then a `lea` from the stream B read pointer, in place of the ring
arithmetic a match needs. There is no state and no install-time work: the
window is a constant, so a stream with copies needs the build made for its
header window, which is one `cmp.l` at offset 24, and a build without
`ST4_WINDOW` is byte for byte the decoder above. A window build is 14–22
bytes larger. Measured on the test corpora, streams without copies pay
1.8–3.9% more cycles on a window build than on a plain one, and a stream
with copies runs at the rate its operation count sets: word-soup at k = 1
packed with copies for a 16-unit ring decodes in ST4_wrap at 65.9 cycles
per unit, where the same data packed without them for a 256-unit ring takes
64.8 — and for the 16-unit ring, almost all of it literals, 42.0 for three
times the bytes. At one window a copy is a little cheaper than a match in
the ring decoders, since it skips the wrap.

The decoders do not check their input. Use trusted files made at build time.
The packers already keep every operation within the decoders' 16-bit counters;
for a ring of N units, pack with `-mN` so the decoder never needs data that
has already left the ring — which is also what decides how a loop is packed.
Add `-c`, and build the decoder with `ST4_WINDOW equ N`, to let the ring
reach the literals it has already read.

## Java tools

```sh
mvn package
java -ea -cp target/classes org.st4.St4  [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
java -ea -cp target/classes org.st4.Dst4 [-f] [-rN] input.st4 [output]
```

`st4` packs, reporting progress and a time estimate as it works; `dst4`
unpacks, and is the readable reference the 68000 decoders are checked against.
Its output is padded to a whole number of units, which is what the format
stores — for a looping stream, one whole pass, and it says where the loop is;
`-rN` plays the loop N times, the pass and then N−1 repeats of its loop
section, as a decoder driven past the end would.
`-kK` picks the unit size, `-mN` limits how far back matches may reach, `-lN`
splits long matches — the default already fits the 68000 decoders — and `-rR`
makes the stream loop from unit R. When the loop is longer than `-m`, `st4`
says at which unit to save the decoder's state and at which to restore it.
`-c` lets a match beyond `-m` copy from the literal stream, parsed in one
pass of the search's parser; `-cS` searches for S seconds for a better parse
from there, printing each improvement as it finds it.

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
same library classes, the same optimizers, the same options. The containers
are interchangeable — measured byte-identical to the Java packer's on real
data at every unit size, looping or not — and the tests are the Java suite,
corpus for corpus. The port is not necessary: the Java tools are the
reference and complete on their own, and the port follows them when that is
worth the work, not as a rule.

```sh
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-c] [-kK] [-mN] [-lN] [-rR] input [output.st4]
```

## Tests

```sh
mvn test                                  # round-trips, containers, loops, copies, optimizers
dotnet test csharp/Nt4.slnx -c Release    # the C# port, same corpora
python3 68k/test/emu/test_st4.py          # linear decoder vs the Java packer, k = 1, 2, 4
python3 68k/test/emu/test_st4_wrap.py     # counted wrap, every unit size
python3 68k/test/emu/test_st4_ring.py     # general ring, both wrap modes, oversized budgets
python3 68k/test/emu/test_st4_repeat.py   # streams that loop by themselves, past two passes
python3 68k/test/emu/test_st4_rewind.py   # loops longer than the ring, replayed by rewind
python3 68k/test/emu/test_st4_copies.py   # copies from the literal stream, on window builds
java -ea -cp target/classes org.st4.St4 -k1 -m64 -c600 README.md   # ten minutes of search
python3 68k/test/emu/bench_bits.py        # why the lengths are still Elias gamma
```

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer, assemble
the real decoders, decode under emulation as a plain 68000, and check every
output byte, exact consumption of all four streams, ring guard bands and the
packed register metadata. The two loop rigs drive all three decoders the way
a caller would — budgets, snapshots and restores included — through more
than two passes, against the infinite input the container stands for.

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
