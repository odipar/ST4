# task

What to produce, the containers, and the rules.

## What to produce

For each `NAME.st4` under `containers/`, the bytes a decoder writes for it:
`O` bytes for a container that ends, `O` and then two more passes of its
loop section for one that loops, `O` being the output size its header
records. `SOURCES.md` names the passes each container is read for; a
container that loops is read for three.

A pass of the loop section is the output from the loop point to the end: a
stream that loops within the window matches its way through the next pass
by itself, and one that loops by rewind is replayed by the caller, which
saves the decoder's state at the loop point and restores it at the end
(SPEC.md 6.2, 6.3).

## The containers

`SOURCES.md` lists them, and stands outside a run against this kit, since
the input a container packs has the bytes a decoder writes in it. Each
container is complete: the header of SPEC.md 2.1, then its four streams.

## The rules

- SPEC.md defines the format. A container of this kit is version 7 and is
  packed at the window its header records, which a reader of copies needs
  (SPEC.md 7.3, 7.4).
- A reader reads `k`, `O`, the stream starts, the rewind point and `M` out
  of the header, and reads the container alone.
- The bytes a reader writes are compared with `outputs/NAME.out` whole. A
  byte that differs fails the container.

A reader that produces every `.out` file from every `.st4` file reads ST4.
How fast it runs, and how it is called, stand outside this kit.
