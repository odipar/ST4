# ST4 - split-stream, unit-aligned compression for the Atari ST

ST4 is a compression format for the plain 68000, with small decoders and a
Java packer. It grew out of [ST1](https://github.com/odipar/ST1) and, through
it, Einar Saukas's [ZX1](https://github.com/einar-saukas/ZX1). It keeps
ZX1's three block types and changes three things: the blocks are written
into four streams instead of one, lengths and offsets count units of 1, 2 or
4 bytes, and a match may reach past the decoder's ring into the literal
stream.

The streams and the units serve the 68000. With each stream holding one kind
of value, the decoder reads each of them the fastest way it has: the bit
stream refills a word at a time, and literals copy with `move.w` or `move.l`
because their alignment no longer depends on the bytes around them. Units
make one operation move 2 or 4 bytes, so there are half or a quarter as many
operations.

The unit size k is a trade. An offset or length that is not a multiple of k
cannot be stored, so k = 2 or 4 pays some compression for speed: a good
trade on data that is itself word- or long-shaped - 68000 code, word tables,
speedcode, register streams - and a bad one elsewhere, so k is chosen per
asset and recorded in the header. At k = 1 ST4 packs to within a percent of
ZX1.

A stream can loop: packed with a loop point, it plays its intro once and its
loop forever through a ring far smaller than itself. And a stream packed for
a small ring can copy from its own literal stream, which stays in memory
with the container, so the ring holds only what the literals cannot give.

The name follows the family: ZX1 for the ZX Spectrum, ST1 for the Atari ST,
and 4 for the widest unit and the Mega ST4.

## The format

All lengths and offsets count units of k bytes. Input that is not a whole
number of units is padded with zeros, and the padding is part of the stored
output. A container holds four streams:

| stream | holds |
|---|---|
| A | all the bits: flags, class bits and lengths |
| B | the literal data, whole units |
| C | byte offsets, one byte each |
| D | word offsets, one word each |

Bits are read from stream A most significant first. Lengths are interlaced
Elias gamma: each binary digit of the value below its leading 1 follows a
`1` marker bit, most significant first, and a `0` bit ends the value. So 1
is `0`, 2 is `100`, 3 is `110`, 4 is `10100`.

The data is a sequence of three block types:

| block | bits | data |
|---|---|---|
| literals | gamma(length) | length units from stream B |
| match at the last offset | gamma(length) | copied from the current offset |
| match at a new offset | 2 class bits, gamma(length - 1) | one byte from C or one word from D |

One flag bit says which block comes next. After literals, `0` starts a match
at the last offset and `1` a match at a new offset, so two literal runs in a
row cannot occur. After a match, `0` starts literals and `1` a match at a
new offset. The first block is literals and has no flag bit. Stream A, read
left to right:

```
open:            gamma(n)                   n literal units from stream B

after literals:  0 gamma(n)                 n units from the last offset
                 1 cc <offset> gamma(n-1)   a new offset, then n units

after a match:   0 gamma(n)                 n literal units from stream B
                 1 cc <offset> gamma(n-1)   a new offset, then n units
```

The offset itself is not in stream A: the two class bits `cc` say where it
comes from.

The class bits select the offset's stream and reach, or end the data:

| class | meaning |
|---|---|
| `1 0` | byte offset from stream C, 1..256 units back |
| `1 1` | byte offset from stream C, 257..512 units back |
| `0 0` | word offset from stream D |
| `0 1` | end of the data, followed by one repeat bit |

A byte offset of n units in bank b is stored as the byte 256(b + 1) - n. A
word offset of n units is stored big-endian as 65536 - nk, which is -nk as
the decoder holds it, so installing one is a single move. No offset reaches
further back than 32512 bytes, at any k, and a new-offset match is at least
2 units long, which is why it stores gamma(length - 1).

The repeat bit says whether the stream ends there. A `0` ends it. A `1`
loops it: one last word offset is read from stream D and the stream becomes
an endless match that far back; see [Loops](#loops).

What an offset reaches depends on the window M the stream was packed for,
which the header records. An offset of at most M is a match: it copies
output from that many units back. An offset above M is a copy from the
literal stream: it copies `offset - M` literal units from behind the
stream B read pointer and leaves the pointer where it is. The distance
counts literal units, so a byte offset reaches `512 - M` literals back.
Two rules follow. A copy advances its offset by what it copies, so a copy
cut short continues where it stopped and a match at the last offset after
a copy resumes just past it, shifted by the literals in between. And a copy
is strictly shorter than its distance, so the offset never reaches zero. A
stream packed without copies never exceeds M.

At k = 1 and M = 4, an input that repeats its first three units eight units
back cannot match them, since 8 is beyond M, but can copy them:

```
input       a b c d e f g h a b c
position    0 1 2 3 4 5 6 7 8 9 10

parse       8 literals, then a copy of 3 units, 8 units back

stream B    a b c d e f g h |            the read pointer is past 8 literals
            ^               ^
            source: 8 literals behind    the pointer stays here

wire        offset = M + 8 = 12          beyond M, so a copy
            length 3 < 8                 the copy stays behind the pointer
```

After the copy the offset is 12 - 3 = 9, still beyond M. Were one literal
`q` to follow and then a match at the last offset, the read pointer would
be past 9 literals and the source 9 - M = 5 literals behind it, at `e`: one
past where the copy stopped, shifted by the literal between.

Together with its flag, a block is an even number of bits: a gamma is an
odd count, and the flag or class bits make it even. That is why a new
offset spends two class bits rather than one, and it lets the decoders skip
the refill check on every read but three: a gamma continuation bit, the
class bit right after a flag, and the repeat bit after the end code. Stream
A is padded to an even length so the last refill finds a whole word.

### The container

A container is twenty-eight bytes of header, then the streams:

```
 0  4  signature: 'S', '4', format version (7), k
 4  4  O, the output size in bytes, a multiple of k
 8  4  where stream B starts, in bytes from the header
12  4  where stream C starts
16  4  where stream D starts
20  4  the rewind point in bytes, or $FFFFFFFF when there is none
24  4  M, the window in units
28 ..  streams A, B, C and D in that order, each starting on a long boundary
```

The header holds what the streams cannot give. Stream A begins where the
header ends. No stream length is stored: each stream runs to the next, and
the decoder stops on the end code. The rewind point is set only for a
stream the caller replays. The signature fits one long, so a decoder built
for one k accepts or rejects an asset with a single `cmp.l`, and the stream
starts are header-relative, so opening a container is one `adda.l` per
stream.

### Loops

A container packed with a loop point R stands for the infinite input
`[0,R)` `[R,O)*`: after its last unit, the output continues from unit R.
There are two ways to loop, and the loop's length against the window picks
one.

A loop that fits the window loops by itself. The repeat bit is set, and the
word after it in stream D is the distance O - R back to the loop point. The
decoder installs it as any other offset and matches it forever, so after
one pass every unit is the one O - R units back. It costs the container two
bytes, and the pass is packed as it would be without the loop.

A loop longer than the window is replayed from the encoded stream. The
stream ends plainly and the header gives the rewind point. The caller saves
the decoder's state when the output reaches R and restores it, all but the
write pointer, when it reaches O, every pass. For every pass to see the
same history, the loop `[R,O)` is packed on its own: no match in it reaches
before R or straddles R. The cost is the first window's worth of the loop,
which cannot reference the intro: on the test corpora, 0.4 to 1.3% of the
packed size.

## 68000 decoders

Three decoders, each built for one unit size with `ST4_UNIT`:

| decoder | k = 1 | k = 2 | k = 4 | calls |
|---|---:|---:|---:|---|
| [ST4.S](68k/ST4.S) | 306 B | 308 B | 310 B | `ST4_init`, `ST4_decompress`, `ST4_resume` |
| [ST4_wrap.S](68k/ST4_wrap.S) | 324 B | 328 B | 330 B | `ST4_init`, `ST4_resume` |
| [ST4_ring.S](68k/ST4_ring.S) | 386 B | 394 B | 396 B | `ST4_init`, `ST4_resume` |

### Which one

ST4.S decodes into one buffer, in one call or by stopping and resuming. The
other two stream through a ring. ST4_wrap.S is for a caller that knows the
sizes and counts the wraps itself; it has no DONE state. ST4_ring.S stops
each call at the ring end, for callers with variable call sizes.

### The copy ladders

Each decoder runs two copy ladders: match runs of at most sixteen units, on
measured streams four of every five, take a counter-free ladder that falls
straight into what comes next; literals and longer runs take a counted one.
Measured on real streams, ST4_wrap spends 12 to 14% fewer cycles in a
small-budget streaming loop and 3 to 5% fewer in bulk, with no case slower.

### The state

The state is held in registers:

```
container                registers
+-----------------+
| header, 28 bytes|
+-----------------+
| A  bits         |  a0   flags, class bits and lengths; d0.w is the bit queue
+-----------------+
| B  literals     |  a2   the next literal, and the source of a copy
+-----------------+
| C  byte offsets |  a4
+-----------------+
| D  word offsets |  a5   each word is -offset*k, installed with one move
+-----------------+
output               a1   the write pointer
                     d1.w the units left in the operation
                     d2.w the offset: +offset*k during literals,
                          -offset*k during a match, zero when done
```

The sign of `d2` is the state. The two ring decoders hold `d1` and `d2` as
longs and keep the ring's bounds in the upper halves, which is how a match
that reaches back past the ring start finds its source at the other end.
Only `a6`, `d6` and `d7` survive a call.

The bit queue is read with `add.w d0,d0`, which moves the top bit into the
carry. A sentinel `1` below the bits marks the end of the queue:

```
after a refill   b14 b13 .. b0 1      fifteen bits, the sentinel below
add.w d0,d0      carry <- b14         the rest move up one
d0 = 0           the sentinel left    move.w (a0)+,d0 then addx.w d0,d0
```

A block and its flag are an even number of bits and a refill is sixteen, so
the queue can run out only on a gamma continuation bit, the class bit right
after a flag, and the repeat bit; every other read skips the test.
The destination, stream B and the ring start on a unit boundary, and the
ring size is a whole number of units, so a wide move never lands on an odd
address. Each file states its contract and its numbered assumptions.

### Loops

A stream that loops by itself arms its endless match at the end code and
re-arms it 65535 units at a time: the bit queue is set to zero, a value no
read leaves it at, so the transition that would parse the next block re-arms
instead. That adds one branch to a match-to-literals transition and one
checked bit to the end code: streams that end pay 0.05 to 0.4% more cycles
than decoders without loop code, and the loop itself runs at or below the
pass's own rate, since it only copies. Such a stream never reaches DONE;
drive it through `ST4_resume` with budgets and stop when you have enough,
since `ST4_decompress` drains until DONE. A loop the caller replays needs no
decoder code: when the output reaches the rewind point, save `a0`, `a2`,
`a4`, `a5`, `d0`, `d1` and `d2`; when it reaches O, restore them, `a1`
staying where the ring has got to, and carry on. Arrange the budgets so a
call ends on both points; a state saved mid-operation replays like any
other.

### Copies from the literal stream

A stream with copies from the literal stream needs a decoder built with
`ST4_WINDOW equ 1`, and the window it was packed for, the header's field at
byte 24: `ST4_init` takes it in `d3`, in bytes, and writes it into the two
instructions that use it. For the ring decoders that is the ring size
`ST4_init` has already. Such a build tells a copy from a match by magnitude,
a `cmp.w` and a short branch per match segment, and takes a copy's source
from the stream B read pointer with one `lea` in place of the ring
arithmetic a match needs:

```
d2 >= -M*k    a match     a3 = a1 + d2            the output, M units back at most
d2 <  -M*k    a copy      a3 = a2 + M*k + d2      stream B, offset-M units behind a2
                          d2 += n*k               the offset advances by the segment
```

One build per unit size serves every window, and the decoder holds no
state for it: the window sits in its code. So the decoder is code in RAM,
and a 68030 caller flushes the instruction cache after `ST4_init`. A window
build is 30 to 40 bytes larger, and a build without `ST4_WINDOW` is byte for
byte the decoder above. Measured on the test corpora, streams without copies
pay 2.0 to 4.0% more cycles on a window build than on a plain one, and a
stream with copies runs at the rate its operation count sets: word-soup at
k = 1 packed with copies for a 16-unit ring decodes in ST4_wrap at 66.5
cycles per unit, where the same data packed without them for a 256-unit
ring takes 64.8, and for the 16-unit ring, nearly all of it literals, 42.0
for three times the bytes.

### What to feed them

The decoders do not check their input; use trusted files made at build time.
The packers keep every operation within the decoders' 16-bit counters. For a
ring of N units, pack with `-mN` so the decoder never needs data that has
left the ring, which also decides how a loop is packed; add `-c` and build
with `ST4_WINDOW equ 1` to let the ring reach the literals it has read.

## Java tools

```sh
mvn package
java -ea -cp target/classes org.st4.St4  [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
java -ea -cp target/classes org.st4.Dst4 [-f] [-rN] input.st4 [output]
```

### Packing

`st4` packs, reporting progress and a time estimate as it works. `-kK`
selects the unit size, `-mN` limits how far back matches reach, `-lN` splits
long matches - the default already fits the 68000 decoders - and `-rR` loops
the stream from unit R, `-r0` from its start. When the loop is longer than
`-m`, `st4` says at which unit to save the decoder's state and at which to
restore it. `-c` lets a match beyond `-m` copy from the literal stream,
reporting each opening pass as it runs, and `-cS` then searches for S
seconds for a better parse, printing each improvement.

### Unpacking

`dst4` unpacks, and is the readable reference the 68000 decoders are checked
against. Its output is padded to a whole number of units, as the format
stores it: for a looping stream one whole pass, and it says where the loop
is. `-rN` writes the pass and then N - 1 repeats of the loop section, as a
decoder driven past the end would.

### The optimizers

Three optimizers select the blocks of a stream without copies, all held to
each other by tests:

- **St4Optimizer**, the readable reference. It tries every choice at every
  position and keeps the cheapest.
- **St4FastOptimizer**, the same choices on plain arrays: the same bytes
  out, measured 4 to 7 times faster.
- **St4EventOptimizer**, the default. It only works where a repeated stretch
  of data starts or ends, which on repetitive data happens thousands of
  times less often than the positions the others visit. Same packed size,
  not always the same bytes; it falls back to the fast one when the data
  repeats in stretches too short to profit.

Measured on the optimizer alone:

| corpus | reference | fast | event-driven |
|---|---:|---:|---:|
| 880 KB disk image, `-m1024`, k = 4 | 163 s | 37 s | 0.4 s |
| 300 KB slice, full window, k = 4 | 24 s | 5.7 s | 0.12 s |
| 32 KB of 68000 code, k = 1 | 9.8 s | 1.5 s | falls back to fast |

### The search

Copies need a different parse, since a copy is valid only where its source
is literal and its distance counts the literals between: the best chain so
far no longer gives the best parse, and the exact optimum is NP-hard.
**St4LiteralCopySearch** packs them. Its parser is the fast optimizer's
dynamic program with copies added, exact for a given set of forced literals,
the dictionary. `-c` runs its opening passes: the dictionary is the literals
of a full-window parse, holes of a few units filled, shrunk to what gets
copied from, up to four times. `-cS` then searches over dictionaries for S
seconds: a greedy sweep frees and trims every literal run, keeping what
packs smaller, and random moves free, seed, extend or trim runs, accepted
when they pack smaller and by annealing when they do not, every step scored
by what the compressor writes. A step is some ten milliseconds on this
README. **St4LiteralCopyOracle** tries every parse on inputs of a dozen
units; against it the opening passes are within a percent of the optimum
and the search reaches it on 59 of 60. On this README at k = 1 with a
64-unit window, the opening passes pack to 78.2% of the input, ten minutes
of search to 60.8%, a 256-unit ring without copies to 66.3%, and the whole
window to 46.1%. [doc/research.md](doc/research.md) has the test corpora
and five YMX tunes at every ring size.

## C# tools

[csharp/](csharp/README.md) is `nt4`, a .NET 10 port of the Java tools: the
same library classes, the same optimizers, the same options. The containers
are interchangeable, measured byte-identical to the Java packer's on real
data at every unit size, looping or not, and the tests are the Java suite,
corpus for corpus. The port is not necessary: the Java tools are the
reference and complete on their own, and the port follows them when that is
worth the work.

```sh
dotnet run --project csharp/src/Nt4.Cli -- [-f] [-c[S]] [-kK] [-mN] [-lN] [-rR] input [output.st4]
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
python3 68k/test/emu/bench_bits.py        # why the lengths are Elias gamma
```

The Python rigs need `mvn compile`, [rmac](http://rmac.is-slick.com) and
`pip install unicorn`: they pack every corpus with the real packer, assemble
the real decoders, decode under emulation as a plain 68000, and check every
output byte, the exact consumption of all four streams, ring guard bands and
the register state. The two loop rigs drive all three decoders as a caller
would, budgets, snapshots and restores included, through more than two
passes, against the infinite input the container stands for, and hold
`dst4 -rN` to the same output.

## ST1

The ZX1 decoders this grew from and the jx1 packer are in
[odipar/ST1](https://github.com/odipar/ST1). ST4 forked from it at
`odipar/ST1@132aef0`; the emulator harness and the MC68000 cycle tables in
the rigs are carried copies of that repository's, which remains the
authority on ST1's own timing.

## License and attribution

The license is ST1's, which follows the original ZX1; see [LICENSE](LICENSE).
The compressor is BSD 3-Clause. The decompressors may be used freely,
including commercially, if your program's documentation says that ZX1 was
used through ST4, st4, or nt4.

The ZX1 format and algorithm are by Einar Saukas. The ST4 format and
additions are (c) 2026 Robbert van Dalen. Claude (Anthropic's Claude Code)
wrote the Java and C# tools, the 68000 decoders, the tests, and the
optimization work, under Robbert's direction. ST4_wrap.S is based on
ST1_wrap.S, which OpenAI Codex wrote for ST1; see [LICENSE](LICENSE).

Special thanks to Sandor Drieënhuizen and Wietze Spijkerman for their
support, proofreading, and ideas.
