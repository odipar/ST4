# glossary

Every term this repository defines, one line each. The document beside a
term explains it at length. A term that changes here changes there in the
same change (requirements.md, R0.6 to R0.8).

| term | what it is | explained in |
|---|---|---|
| bank | Which 256 units a byte offset counts in: bank 0 reaches 1 to 256 units back, bank 1 reaches 257 to 512. | SPEC.md 3.5 |
| bit queue | The word of stream A a decoder reads its bits out of, with a sentinel below them marking where the word runs out. | decoders.md, the state |
| block | One step of the data: a literal run, a match at the last offset, or a match at a new offset. | SPEC.md 3.1 |
| budget | The units one `ST4_resume` call emits before it returns, which the caller sets. | decoders.md, the state |
| chain | The blocks of a parse, each naming the one before it, which the packer walks back to write the streams. | tools.md, the optimizers |
| class bits | The two bits that select where a new offset comes from, or end the data. | SPEC.md 3.5 |
| container | One packed file: twenty-eight bytes of header, then streams A, B, C and D. | SPEC.md 2 |
| copy | A block whose offset is beyond the window, which reads from the literal stream rather than from the output. | SPEC.md 5 |
| copy ladder | The unrolled run of moves a decoder copies a match or a literal run with, one counted and one not. | decoders.md, the copy ladders |
| dictionary | The units a search forces to be literal, which a copy may read from. | tools.md, the search |
| end code | The class `0 1`, which ends the data and is followed by the repeat bit. | SPEC.md 3.6 |
| gamma | An interlaced Elias gamma, how every length is stored. | SPEC.md 3.3 |
| `k` | The unit size in bytes: 1, 2 or 4. | SPEC.md 1.1 |
| literal run | A block of units read straight from stream B. | SPEC.md 3.1 |
| loop point | The unit a looping container continues from after its last, `R` in the packer's `-rR`. | SPEC.md 6.1 |
| `M` | The window: what the packer was told a decoder keeps, in units, and the boundary between a match and a copy. | SPEC.md 4.4 |
| match | A block that reads the output back, at the last offset or at a new one. | SPEC.md 3.1 |
| `O` | The output size in bytes, a multiple of `k`. | SPEC.md 2.1 |
| offset | How far back a match or a copy reads, in units. | SPEC.md 4 |
| opening passes | What `-c` alone runs: the dictionary of a full-window parse, shrunk to what is copied from, up to four times. | tools.md, the search |
| operation | One block as a decoder runs it, whose length its 16-bit counter bounds. | decoders.md, the state |
| parse | The blocks a packer chose for one input, which the streams encode. | tools.md, the optimizers |
| rewind point | The byte at which a caller saves a decoder's state, for a loop replayed rather than matched. | SPEC.md 6.3 |
| ring | The buffer a streaming decoder writes into and matches back through, `M` units of it. DTX and YMXR name the same thing a ring. | decoders.md, which one |
| segment | The part of a match one copy ladder runs, which a ring splits at every wrap. | decoders.md, the copy ladders |
| stream A | The bits: flags, class bits and lengths. | SPEC.md 2.1 |
| stream B | The literal data, whole units, which a copy also reads from. | SPEC.md 2.1 |
| stream C | The byte offsets, one byte each. | SPEC.md 2.1 |
| stream D | The word offsets, one word each. | SPEC.md 2.1 |
| unit | The `k` bytes every length and offset counts in. | SPEC.md 1.1 |
| window | `M`, which see. A decoder that streams keeps a ring that wide; one that decodes into a whole buffer keeps no ring, and the window is the boundary alone. | SPEC.md 4.4 |
| window build | A decoder assembled with `ST4_WINDOW equ 1`, which reads a container with copies. | decoders.md, copies from the literal stream |
