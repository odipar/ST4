# The 68000 decoders

Three decoders, each built for one unit size with `ST4_UNIT`. Each file
defines its contract and its numbered assumptions; this document is what
they share and how a caller chooses between them. The format they read is
[SPEC.md](SPEC.md).

| decoder | k = 1 | k = 2 | k = 4 | calls |
|---|---:|---:|---:|---|
| [ST4.S](../68k/ST4.S) | 304 B | 306 B | 308 B | `ST4_init`, `ST4_decompress`, `ST4_resume` |
| [ST4_wrap.S](../68k/ST4_wrap.S) | 310 B | 314 B | 316 B | `ST4_init`, `ST4_resume` |
| [ST4_ring.S](../68k/ST4_ring.S) | 386 B | 394 B | 396 B | `ST4_init`, `ST4_resume` |

## Which one

ST4.S decodes into one buffer, in one call or by stopping and resuming. The
other two stream through a ring. ST4_wrap.S is for a caller that knows the
sizes and counts the wraps itself; it has no DONE state. ST4_ring.S stops
each call at the ring end, for callers with variable call sizes.

## The copy ladders

Each decoder runs two copy ladders: match runs of at most sixteen units, on
measured streams four of every five, run a counter-free ladder that falls
straight into what comes next; literals and longer runs run a counted one.
The second ladder was worth 12 to 14% of the cycles in a small-budget
streaming loop and 3 to 5% in bulk, with no case slower, measured against
the single-ladder decoder at the release that made the change
([RELEASES.md](RELEASES.md), v0.5). That decoder is not in this tree, so
`bench_decode.py` cannot count it again.

## The state

The state is in registers:

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

The sign of `d2` is the state. The two ring decoders keep `d1` and `d2` as
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

A block and its flag are an even number of bits (SPEC.md 3.8) and a refill
is sixteen, so the queue can run out only on a gamma continuation bit, the
class bit right after a flag, and the repeat bit; every other read skips
the test. The destination, stream B and the ring start on a unit boundary,
and the ring size is a whole number of units, so a wide move never lands on
an odd address.

## Loops

A stream that loops by itself (SPEC.md 6.2) arms its endless match at the
end code and re-arms it 65535 units at a time: the bit queue is set to
zero, a value no read leaves it at, so the transition that would parse the
next block re-arms instead. That adds one branch to a match-to-literals
transition and one checked bit to the end code: streams that end paid 0.05
to 0.4% more cycles than the decoders before the loop code, measured at the
release that added it ([RELEASES.md](RELEASES.md), v7.0), and the loop
itself runs at or below the rate of the pass, since it only copies. Such a
stream never reaches DONE; drive it through `ST4_resume` with budgets and
stop when you have enough, since `ST4_decompress` drains until DONE.

A loop the caller replays (SPEC.md 6.3) needs no decoder code: when the
output reaches the rewind point, save `a0`, `a2`, `a4`, `a5`, `d0`, `d1`
and `d2`; when it reaches `O`, restore them, `a1` staying where the ring
has got to, and carry on. Arrange the budgets so a call ends on both
points; a state saved mid-operation replays like any other.

## Copies from the literal stream

A stream with copies (SPEC.md 5) needs a decoder built with `ST4_WINDOW equ
1`, and the window it was packed for, the header's field at byte 24:
`ST4_init` reads it in `d3`, in bytes, and writes it into the two
instructions that use it. For the ring decoders that is the ring size
`ST4_init` has already. Such a build tells a copy from a match by
magnitude, a `cmp.w` and a short branch a match segment, and reads a copy's
source from the stream B read pointer with one `lea` in place of the ring
arithmetic a match needs:

```
d2 >= -M*k    a match     a3 = a1 + d2            the output, M units back at most
d2 <  -M*k    a copy      a3 = a2 + M*k + d2      stream B, offset-M units behind a2
                          d2 += n*k               the offset advances by the segment
```

One build a unit size serves every window, and the decoder keeps no state
for it: the window is in its code. So the decoder is code in RAM, and a
68030 caller flushes the instruction cache after `ST4_init`. A window build
is 30 to 40 bytes larger, and a build without `ST4_WINDOW` is byte for byte
the decoder above.

`bench_decode.py` runs both builds over the same streams and counts every
instruction on an MC68000 model. Over the whole corpus at `k` of 1, 2 and
4, a stream without copies costs 2.0 to 2.6 per cent more cycles a unit on
a window build of ST4_wrap through a 256-byte ring, and 0.3 to 0.7 on a
window build of ST4.S. The compare runs once a match segment, and a ring
splits a match at every wrap where ST4.S has one segment an operation. A
stream with copies
runs at the rate its operation count sets: word-soup at `k` of 1 packed
with copies for a 16-unit ring decodes in ST4_wrap at 66.5 cycles a unit,
where the same data packed without them for a 256-unit ring costs 64.8, and
for the 16-unit ring, nearly all of it literals, 42.0 for 2.9 times the
bytes. [research.md](research.md) has the tables.

## What to feed them

The decoders do not check their input (SPEC.md 7.1); use trusted files made
at build time. The packers keep every operation within the decoders' 16-bit
counters. For a ring of `N` units, pack with `-mN` so the decoder never
needs data that has left the ring, which also decides how a loop is packed;
add `-c` and build with `ST4_WINDOW equ 1` to let the ring reach the
literals it has read. [research.md](research.md) prices the ring against
the RAM it costs.
