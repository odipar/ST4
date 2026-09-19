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

**The first run**, 2026-09-19, against the kit at sixteen containers. The
implementer wrote a reader of 177 lines from SPEC.md and TASK.md alone
and produced all sixteen outputs byte for byte, the three looping
containers over three passes among them, so 6.2's endless match and 6.3's
caller replay came off the document. Its notes had 13 entries with 2
marked *decides output*, and six clauses changed for what it found.

- 5.2 read that a copy "advances its offset" where a copy's offset falls
  by what it copies, and the sentence after it, written the same morning,
  had a block at the last offset read the output once copies brought the
  offset to `M` or below. 5.3 keeps a copy shorter than its distance, so
  the offset stays above `M` and that block is a copy again: the clause
  read a case the format cannot reach, and reads the invariant now.
- 5.3 left "its distance" to the reader, which 5.4 uses for the
  `offset - M` literal units. The clause names them.
- 6.1 reads `R` in units where 2.1 records the rewind point in bytes,
  which a unit of 1 hides.
- 6.2 leaves `R` out of the container: the clause reads it as `O` less
  the distance in stream D.
- 6.3 rules out a match that straddles `R` and left open whether the
  caller's save point stands inside a block. It does: the first block of
  `k1-loop-long` is 84 literals with `R` at 64, and a reader that saved
  at a block's end alone writes 1,560 bytes where 1,600 stand.
- 2.3 runs each stream to the next where 2.1 begins each on a long, so
  the bytes between are padding rather than data.

**The second run**, the same day, against the kit with the clauses of the
first in it and a second implementer. The reader was 261 lines and
produced all sixteen outputs byte for byte again; its notes had 20
entries with 6 marked *decides output*, and eight places changed.

- 6.2 read the loop word as the distance `O - R`, where 2.1 records `O` in
  bytes and 6.1 reads `R` in units. It is `O` over `k` less `R`, in units,
  stored as 4.2 stores an offset: both loop containers here are a unit of
  1, where the two readings agree.
- 4.2's word is the units times `k` where 4.1's byte is the units
  themselves, which the clause reads now; `k4-word-offset` is where the
  two part.
- 3.8 read that stream A is padded "to an even length" where the sentence
  before it counts bits and the reason counts words. It is bytes.
- 3.6 reads that the end code stands where the output reaches `O`, which
  2.1 and 2.3 both bear on and neither said.
- 6.1 reads that a container has one loop form: the repeat bit and no
  rewind point, or a rewind point and that bit clear.
- 5.1 read that a byte offset reaches `512 - M` literals back, which is
  bank 1's reach; one in bank 0 reaches `256 - M`.
- 3.2's sentence on which bit of a class pair a reader reads first stands
  with 3.5's table now, and 3.1 reads the last offset by the name the rest
  of the document has.

A third reader of DTX's kit, whose payloads this document packs, read three
more places on 2026-09-19. A copy had no row in 3.1 and no case in 3.4, so a
reader had to decide that a copy is a match block for both, which governs
every byte of that kit's `dtx2-copies` after its second block: 5.1 reads it
now. 5.1's reach by bank is a bound rather than a distance, and 6.2's loop
word is the next entry of stream D.

DTX's kit was read cold the same day and its reader read this document
for the packed payloads it has; those payloads pack without a loop, so
sections 6.2, 6.3 and the rewind point of 2.6 were outside that run, and
the three containers named for them here are why this kit exists.
