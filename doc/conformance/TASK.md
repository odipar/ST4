# task

What to produce, the containers, and the rules.

## What to produce

For each `NAME.st4` under `containers/`, the bytes a decoder writes for it:
`O` bytes for a container that ends, `O` and then two more passes of its
loop section for one that loops, `O` being the output size its header
records. A container loops where its rewind point is other than $FFFFFFFF
or its repeat bit is 1 (SPEC.md 6.1).

A pass of the loop section is the output from the loop point to the end: a
stream that loops within the window matches its way through the next pass
by itself, and one that loops by rewind is replayed by the caller, which
saves the decoder's state at the loop point and restores it at the end
(SPEC.md 6.2, 6.3).

The reader writes each to `out/NAME.out`, beside `containers/`.

## The containers

`SOURCES.md` lists them, and is left out of a run against this kit, since
the input a container packs has the bytes a decoder writes in it. Each
container is complete: the header of SPEC.md 2.1, then its four streams.

## The rules

- SPEC.md defines the format. A container of this kit is version 7 and is
  packed at the window its header records, which a reader of copies needs
  (SPEC.md 7.3, 7.4).
- A reader reads `k`, `O`, the stream starts, the rewind point and `M` out
  of the header, and reads the container alone.
- The bytes a reader writes are compared whole with the kit's
  `outputs/NAME.out`, which is outside a run. A byte that differs fails
  the container.

A reader that produces every `.out` file from every `.st4` file reads ST4.
How fast it runs, and how it is called, are outside this kit.

## Also produce

`READ.md`: every file and page read, listed; where an implementation of
anything was read, the list names it.

`NOTES.md`: every place the specification left a choice. For each, the
section, what it omits, what was assumed, and the wording proposed. Mark
each entry **decides output** or **leaves output as it is**, by whether
the assumption changed a byte emitted.
