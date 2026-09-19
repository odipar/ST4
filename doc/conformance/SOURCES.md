# The containers

Each container of the kit, the input it packs and the options it was packed
with. `ConformanceTest` builds every input from the rule below, packs it at
these options and compares the bytes with the file in `containers/`, so the
packer writes the kit and a change that moves a byte of it fails there.

Four rules build the inputs, over bytes:

| rule | the bytes |
|---|---|
| `numbers(n, s, m)` | n bytes, byte i being (i times s) mod m |
| `tiled(p, n)` | n bytes, byte i being byte i mod length of p of the pattern p |
| `run(n, v)` | n bytes of v |
| `x then y` | the bytes of x, then those of y |

`-k` is the unit, `-m` the window in units, `-l` the units an operation runs
to where the packer packs to a limit, and `-r` the loop point in units. The
passes column is how many the kit records in `outputs/`: one pass for a
container that ends, and three for one that loops (SPEC.md 6).

| container | input | `-k` | `-m` | `-l` | `-r` | passes | bytes | sha256 | what it reaches |
|---|---|---|---|---|---|---|---|---|---|
| `k1-literals` | numbers(64, 37, 251) | 1 | 4096 |  |  | 1 | 96 | c3cc115a85b504a7 | one literals block and the end code: no byte repeats within reach |
| `k1-last-offset` | run(1, $41) then run(40, $41) | 1 | 4096 |  |  | 1 | 36 | 8775ba5c90eb0685 | a block at the last offset before a block sets one, which is 1 unit (SPEC.md 3.4) |
| `k1-bank0` | tiled(numbers(16, 37, 251), 192) | 1 | 4096 |  |  | 1 | 52 | 345288c46cf6a011 | byte offsets in bank 0, 1 to 256 units back (SPEC.md 4.1) |
| `k1-bank1` | numbers(300, 37, 251) twice | 1 | 512 |  |  | 1 | 292 | 0c24a18d68a54a14 | byte offsets in bank 1, 257 to 512 units back (SPEC.md 4.1) |
| `k1-word-offset` | numbers(700, 37, 251) twice | 1 | 4096 |  |  | 1 | 294 | 5639a82a86e299ab | word offsets, class 0 0, past the 512 units a byte offset reaches (SPEC.md 4.2) |
| `k1-long-literals` | numbers(600, 97, 251) | 1 | 4096 |  |  | 1 | 292 | 0cbc4896a7fe4b25 | a literals block of more than 256 units, so its gamma runs long |
| `k1-split-op` | run(1200, $5A) | 1 | 4096 | 64 |  | 1 | 88 | fc8797e6c5f23ff1 | an operation split at 64 units, the limit the packer packs to |
| `k2-matches` | tiled(numbers(16, 37, 251), 256) | 2 | 4096 |  |  | 1 | 52 | d766e00980c57e5a | a unit of 2 bytes |
| `k4-matches` | tiled(numbers(16, 37, 251), 256) | 4 | 4096 |  |  | 1 | 52 | 68e96c6067b91885 | a unit of 4 bytes |
| `k2-padded` | tiled(numbers(16, 37, 251), 255) | 2 | 4096 |  |  | 1 | 56 | 19ccdfb310774b3d | an input of an odd length at a unit of 2, padded to a whole unit (SPEC.md 1.2) |
| `k1-copies` | numbers(64, 37, 251) twice | 1 | 8 |  |  | 1 | 104 | 61bcbb7957f2c5ed | copies from the literal stream, offsets above the window of 8 (SPEC.md 5) |
| `k1-one-unit` | run(1, $37) | 1 | 4096 |  |  | 1 | 36 | c34bf886c551de4f | one unit of output |
| `k1-loop-in-window` | tiled(numbers(24, 37, 251), 256) | 1 | 4096 |  | 32 | 3 | 62 | 17ab3d87768198f7 | a loop within the window: the repeat bit and the word in stream D (SPEC.md 6.2), played three passes |
| `k1-loop-from-zero` | tiled(numbers(24, 37, 251), 192) | 1 | 4096 |  | 0 | 3 | 62 | ffbc080b62bd7749 | a loop from unit 0, played three passes |
| `k1-loop-long` | numbers(64, 37, 251) then tiled(numbers(20, 97, 251), 512) | 1 | 64 |  | 64 | 3 | 124 | be61f2987da5cca6 | a loop longer than the window, which the caller replays: the rewind point in the header (SPEC.md 6.3), played three passes |
