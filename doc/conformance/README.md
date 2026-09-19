# conformance

The kit an independent reader is written against: fifteen containers under
`containers/`, the bytes a decoder writes for each beside them in
`outputs/`, and TASK.md, which defines what a reader produces from each and
the rules it is checked against.

Every container is packed by this repository's packer from the input and
options SOURCES.md lists, and `ConformanceTest` packs each again under `mvn
test` and compares the file with it byte for byte. So the packer emits the
kit itself, and a change that moved a byte of it fails there.

The containers reach every unit size, both offset banks and the word
offsets past them at a unit of 1 and of 4, where the word stored scales by
the unit, a literals block longer than 256 units, an operation
split at a limit, an input padded to a whole unit, copies from the literal
stream, a loop within the window, a loop from unit 0, a loop longer than
the window with its rewind point, one unit of output, and a block at the
last offset before any block has set one. The Java, Go and C# trees write
the same bytes in `GoParityTest` and the C# suite, and the 68000 decoders
read containers of the same shapes under emulation.

## The runs

An implementer reads SPEC.md and TASK.md, writes a reader from those alone,
and produces the bytes a decoder writes for every container. The `outputs/`
files and SOURCES.md stand outside the run, since either has the bytes a
decoder writes in it. A run passes where every container's bytes equal the
kit's and every reading the notes record is one the document decides.

No run stands here yet. DTX's kit was read cold on 2026-09-19 and its
reader read this document for the packed payloads it has; four clauses
changed for what it found, and its notes are recorded in DTX's
`doc/conformance/README.md`. Those payloads pack without a loop, so
sections 6.2, 6.3 and the rewind point of 2.6 were outside that run: the
three containers named for them here are why this kit exists.
