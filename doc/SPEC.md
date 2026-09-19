# The ST4 format

What the bytes are, and what a reader reads out of them. A clause is
numbered so another document can cite it. The terms are
[glossary.md](glossary.md)'s, and what this repository has to do is
[requirements.md](requirements.md).

A container stands for one output: a run of bytes, a whole number of units
long. What produced it, and what a caller does with it, are outside this
document.

## 1. Units

**1.1** A unit is `k` bytes, and `k` is 1, 2 or 4. Every length and every
offset counts units.

**1.2** Input that is not a whole number of units is padded with zeros to
one. The padding is part of the output the container stands for, so a
reader of the output reads it back.

**1.3** The unit is a trade rather than a property of the data: an offset
or a length that is not a multiple of `k` cannot be stored, so `k` of 2 or
4 pays compression for speed. A file records the `k` it was packed at
(2.1).

## 2. The container

**2.1** A container is twenty-eight bytes of header, then four streams:

```
 0  4  signature: 'S', '4', format version (7), k
 4  4  O, the output size in bytes, a multiple of k
 8  4  where stream B starts, in bytes from the header
12  4  where stream C starts
16  4  where stream D starts
20  4  the rewind point in bytes, or $FFFFFFFF where there is none
24  4  M, the window in units
28 ..  streams A, B, C and D in that order, each starting on a long
```

**2.2** Every field of more than one byte is most significant byte first.

**2.3** Stream A begins where the header ends. No stream length is stored:
each stream runs to the next and stream D to the end of the container,
and a reader stops on the end code (3.6). Each begins on a long (2.1), so
the bytes between the last one a stream uses and the next stream are
padding.

**2.4** The signature fills one long, so a decoder built for one `k`
accepts or rejects a container with a single comparison.

**2.5** The stream starts count from the header's first byte, so opening a
container is one addition a stream.

**2.6** The rewind point is set for a stream a caller replays (6.3), and is
$FFFFFFFF otherwise.

## 3. The blocks

**3.1** The data is a sequence of three kinds of block:

| block | bits | data |
|---|---|---|
| literals | gamma(length) | length units from stream B |
| match at the last offset | gamma(length) | copied from the last offset |
| match at a new offset | 2 class bits, gamma(length - 1) | one byte from C or one word from D |

**3.2** Bits are read from stream A most significant first.

**3.2.1** A match copies unit by unit as the output grows, so a match
longer than its offset repeats the units it has just written.

**3.3** A length is an interlaced Elias gamma: each binary digit of the
value below its leading 1 follows a `1` marker bit, most significant first,
and a `0` bit ends the value. So 1 is `0`, 2 is `100`, 3 is `110`, 4 is
`10100`.

**3.4** One flag bit says which block comes next. After literals, `0`
starts a match at the last offset and `1` a match at a new offset, so two
literal runs in a row cannot occur. After a match, `0` starts literals and
`1` a match at a new offset. A literals block leaves the last offset as it
is, so a block at the last offset after literals matches where the block
before them did. The first block is literals and has no flag bit. A
decoder starts with the last offset at 1 unit, which a block at the last
offset reads before any block has set one:

```
open:            gamma(n)                   n literal units from stream B

after literals:  0 gamma(n)                 n units from the last offset
                 1 cc <offset> gamma(n-1)   a new offset, then n units

after a match:   0 gamma(n)                 n literal units from stream B
                 1 cc <offset> gamma(n-1)   a new offset, then n units
```

**3.5** The two class bits `cc` of a new offset select the stream the
offset comes from, and its reach. A block reads the next entry of that
stream, the entries of each standing in the order the blocks that read
them do, and the left bit of a pair below is the one a reader reads
first:

| class | meaning |
|---|---|
| `1 0` | byte offset from stream C, 1 to 256 units back |
| `1 1` | byte offset from stream C, 257 to 512 units back |
| `0 0` | word offset from stream D |
| `0 1` | the data ends, and one repeat bit follows |

**3.6** The end code stands where the output reaches `O` (2.1), and is
the class `0 1`. The repeat bit after it says
whether the stream ends there: a `0` ends it, and a `1` loops it (6.2).

**3.7** A new-offset match is two units long at least, which is why it
stores gamma of the length less one.

**3.8** With its flag, a block is an even number of bits: a gamma is an odd
count, and the flag with the class bits makes it even. The first block
has no flag (3.4), so it is odd and so is the stream. Stream A is padded
to an even number of bytes, so the last refill of a decoder that reads a
word at a time finds a whole word; a container begins each stream on a
long (2.1), which pads it further.

## 4. The offsets

**4.1** A byte offset of `n` units in bank `b` is stored as the byte
256(b + 1) - n, bank 0 being class `1 0` and bank 1 class `1 1`.

**4.2** A word offset of `n` units is stored as 65536 - nk, most
significant byte first, which a decoder reads as -nk and installs with one
move. The word is the units times `k` where a byte offset (4.1) is the
units themselves, so a reader that needs `n` reads the word over `k`.

**4.3** No offset reaches further back than 32512 bytes, at any `k`.

**4.4** `M`, the window (2.1), is the figure the packer was told a
decoder keeps.
An offset of at most `M` is a match: it reads the output that many units
back. An offset above `M` is a copy from the literal stream (5).

**4.5** A stream packed without copies has no offset above `M`.

## 5. Copies from the literal stream

**5.1** A copy is a match block whose offset stands above `M` (4.4): the
flag that starts it, the class bits and the length it reads are a match's
(3.1, 3.4), and it leaves a reader where a match does. It reads
`offset - M` literal units back from the stream B read pointer, and leaves
that pointer where it is. The distance counts literal units, so a byte
offset in bank 1 reaches at most `512 - M` literals back and one in bank 0
at most `256 - M`.

**5.2** A copy's offset falls by what it copies, so a copy cut short
continues where it stopped, and a block at the last offset after a copy
resumes just past it, shifted by the literals in between. That offset
stays above `M` (5.3), so such a block is a copy again, and a decoder
that reads the offset against `M` where the block runs (4.4) reads it as
one.

**5.3** A copy is shorter than its distance, the `offset - M` literal
units of 5.1, so its offset stays above `M`.

**5.4** At `k` of 1 and `M` of 4, an input that repeats its first three
units eight units back cannot match them, 8 being beyond `M`, and copies
them instead:

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

After the copy the offset is 12 - 3 = 9, still beyond `M`. Were one literal
`q` to follow and then a match at the last offset, the read pointer would
be past 9 literals and the source 9 - `M` = 5 literals behind it, at `e`:
one past where the copy stopped, shifted by the literal between.

## 6. Loops

**6.1** A container packed with a loop point `R` stands for the infinite
output `[0,R)` `[R,O)*`: after its last unit the output continues from unit
`R`. `R` counts units here, where the header records the rewind point in
bytes (2.1). The loop's length against the window decides which of the two
forms (6.2, 6.3) a packer writes, and a container has one of them: the
repeat bit set and no rewind point, or a rewind point and that bit
clear.

**6.2** A loop within the window loops by itself. The repeat bit (3.6) is
set, and the next entry of stream D is the distance back to the loop
point in units, `O` over `k` less `R`, stored as 4.2 stores an offset; the
header's rewind point is $FFFFFFFF in this form (2.6), so a reader that
needs `R` reads it as `O` over `k` less that distance. A decoder installs
the word as any other offset and matches it forever, so after one pass
every unit is the one that many units back. It costs the container two
bytes.

**6.3** A loop longer than the window is replayed by the caller. The stream
ends plainly, and the header records the rewind point (2.6). The caller
saves the decoder's state when the output reaches `R` and restores it, all
but the write pointer, when the output reaches `O`, every pass. `R` is a
unit of the output and stands inside a block as readily as at its end, so
the caller saves at that unit. For every
pass to read the same history, the loop `[R,O)` is packed apart: no match
in it reaches before `R` or straddles `R`. The cost is the first window of
the loop, which cannot reference the intro.

## 7. What a reader assumes

**7.1** A reader does not check its input. A container is made at build
time by a packer that keeps every operation within a decoder's counters,
and a wrong byte reads as arbitrary output.

**7.2** A reader built for one unit size reads the containers of that size,
which 2.4 lets it check.

**7.3** A reader keeps `M` units of output where the container names `M`,
or the copies of 5 read what a smaller ring has dropped.

**7.4** A reader of a stream with copies is built for them, and reads `M`
out of the header (2.1) to tell a copy from a match.

## 8. Later versions

**8.1** The version byte (2.1) is 7. A reader of one version reads a
container of another as an error.

**8.2** [near-offset.md](near-offset.md) prices a fifth class code, which
would be version 8. It is not built.
