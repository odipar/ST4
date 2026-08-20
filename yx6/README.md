# YX6 — streaming YM chiptunes on a plain 68000

YX6 is a MinYMiser-style player, now ST4-native: a YM tune packed as nineteen ST4
streams — one per sound register, plus four effect streams — each decoded
through its own small ring by [ST4_wrap.S](../68k/ST4_wrap.S). The music never
exists in memory as a whole — only rings of a few hundred bytes, refilled one
stream per frame.

It grew inside the ST1 project and moved here with it; it plays the fourteen
standard YM2149 registers, loops, and plays the YM6 special effects: SID
voice, digidrum and sync-buzzer run on MFP Timers A and D exactly as the
original replay routines ran them, from effect streams the packer normalizes
out of either YM dialect. [EFFECTS.md](EFFECTS.md) is the design; sinus-SID
is the one effect deliberately left unplayed, in the good company of every
other player including the format author's.

| Piece | What it is |
|---|---|
| [`org.yx6.Yx6`](../src/main/java/org/yx6/Yx6.java) | the packer: YM5!/YM6! in, `.yx6` out - one tune or a whole set |
| [YX6.S](YX6.S) | the player library, 2,002 bytes plus ST4_wrap's 292 |
| [YX6_sndh.S](YX6_sndh.S) + [`MkSndh`](../src/main/java/org/yx6/MkSndh.java) | the canonical container: an SNDH v2.2 file, subtunes included |
| [`YmSndh`](../src/main/java/org/yx6/YmSndh.java) | `.ym` dumps straight to one SNDH, packer flags and all |
| [YX6_player.S](YX6_player.S) + [`MkPrg`](../src/main/java/org/yx6/MkPrg.java) | a thin TOS shell around those same SNDH bytes |
| [`Play`](../src/main/java/org/yx6/Play.java) | one command: pack a `.ym`, build it, play it under Hatari |

The four build tools are Java; `mksndh.sh`, `ym_sndh.sh`, `mkprg.sh` and
`play.sh` are four-line wrappers that find the repository and the compiled
classes, so every command below is spelled the way it always was. Reading a
`.yx6` header, validating that a set shares one configuration, generating the
SNDH tags and driving rmac all live in [`org.yx6`](../src/main/java/org/yx6)
alongside the packer, which the front ends call in process rather than
through another JVM.

**Unit sizes.** `yx6 -kK` (and `play.sh -kK`) packs the register sections at
ST4 units of 1, 2 or 4 bytes: wider units hand the decoder half or a quarter
as many units per refill, at some ratio cost. The default is 2 - measured on
a real tune, 2 buys most of 4's speed for a fraction of its size cost. The
tune length, the loop frame and C must be whole units — a padded section
would decode one extra value into the ring, and it would be played — so a
tune with an odd length or loop frame is PADDED to the shape: the packer
duplicates a frame that neither writes R13 nor triggers a drum, which holds
the chip state one inaudible tick longer, and says what it did. Only when no
safe frame exists near a boundary does it fall back to `-k1`. The player is
built for one unit size and checks every section's ST4 signature against it
at init; `mkprg.sh` reads the unit out of the file's first section
automatically.

## Test driving one

Distributed `.ym` files are LHA archives; the packer unpacks them itself:

```sh
yx6/play.sh song.ym                   # 960-byte rings, 24 values per call
yx6/play.sh -n256 song.ym             # smaller rings: less RAM, worse ratio
yx6/play.sh -n2048 -c32 song.ym       # longer calls: cheaper on average
yx6/play.sh -o song.ym                # play once instead of looping
yx6/play.sh -perf song.ym             # build the raster monitor in
yx6/play.sh one.ym two.ym three.ym    # a set: number keys pick the subtune
```

[play.sh](play.sh) packs the tune, builds a player around it and starts Hatari
with sound on. **Press SPACE in the Hatari window to stop**: the program exits,
and the script closes the emulator behind it — nothing asks you to confirm
anything. Point it at your own install with `HATARI=` and `TOS=`. Everything it
builds is kept next to the tune in `<name>-n<ring>-c<chunk>/`, so you can
compare two ring sizes by ear and keep both. Hand it several tunes and they
become one program's subtunes — packed with one configuration, titled and
named from their own YM headers, switched with the number keys — in a
`<first name>+<n more>-n<ring>-c<chunk>/` directory of their own.

Or do the steps yourself:

```sh
mvn -q compile exec:exec@yx6 -Dargs="-f song.ym song.yx6"
yx6/mkprg.sh SONG.PRG song.yx6        # -> SONG.PRG, runnable on an ST
```

`mkprg.sh -m` builds the same program but has it drop a `YX6DONE.MRK` file as
it exits; that is how `play.sh` knows the tune has stopped. A plain build never
touches the disk.

`-perf` — on play.sh, mkprg.sh, mksndh.sh and ym_sndh.sh alike — assembles
the raster monitor in (`YX6_PERF equ 1`): the frame step paints the
background red for exactly as long as it runs, so its cost reads directly in
scanlines (one scanline = 512 cycles), and every timer tick paints its own
sliver — Timer A green, Timer D blue — wherever the beam happens to be.
Because ticks are far too short to count by eye, the handlers also tally an
estimated cost, and the frame burns it off as a solid yellow bar right
after its red band: the timers' total, as one readable block - after, so
the monitor never delays the register burst it is measuring. The
frame step waits for the display to start before painting anything: the VBL
fires dozens of lines above the visible screen, so an unsynced monitor draws
its bands into the top border where you cannot see them. It syncs on the
video address counter — which moves only while the chip is fetching pixels —
with a bounded loop, so a host that calls the player mid-screen gives up
rather than hangs, and the wait distorts nothing it measures. It is an estimate (10-cycle quanta, fixed per-tick costs), and it
is free when off: the default build is byte-identical to one made before the
option existed.

## The SNDH container

The canonical build of the player is an **SNDH v2.2 file** - the Atari ST's
standard music container - and the `.PRG` is a thin shell around those same
bytes. [mksndh.sh](mksndh.sh) assembles [YX6_sndh.S](YX6_sndh.S) around one
or more `.yx6` files:

```sh
java ... org.yx6.Yx6 -f one.ym two.ym three.ym build/   # a set, one config
yx6/mksndh.sh -t"My Set" myset.sndh build/*.yx6         # -> subtunes 1..3
yx6/mkprg.sh MYSET.PRG build/*.yx6                      # the same, runnable
yx6/ym_sndh.sh -t"My Set" myset.sndh one.ym two.ym      # both steps in one
```

The SNDH glue is the polite host the player's assumption 5 describes: INIT
(subtune in `d0.w`, 1-based) saves exactly what the player touches and
claims the two timers, EXIT quiesces and hands everything back, PLAY runs
one frame - every entry preserves `d0`-`a6`, nothing outside the blob is
used, the USP is never touched, and INIT called twice without an EXIT
cleans up after itself. The header carries `TC50` (play is driven at 50 Hz
from whatever the host hangs on it), `FLAG ~ady`, and a `FRMS` table -
looping tunes are marked endless, play-once tunes declare their frames -
and the subtune names, which is what a jukebox shows as its track list:
SNDH's subtunes ARE its multi-song format. Where the YM header carries
metadata, [ym_sndh.sh](ym_sndh.sh) carries it across: each tune's YM name
becomes its subtune name (the file stem when the dump says "unknown"), a
shared YM author becomes the SNDH composer (COMM), the YM player rate
becomes the TC tag (one rate per file, validated), a lone tune's name
becomes the title and a set titles itself with its songs joined - unless
-t says otherwise - and CONV records the provenance: converted from YM.
YEAR has no YM source and is honestly absent.
The file is raw and position independent; pack it with ICE 2.4 for the
archive if you like, players unpack that themselves.

One honest limitation, in the file's own header comment: a native host
that dispatches PLAY from its Timer C hook delays slot 2's timer (lower
MFP priority) by up to the length of the call - pending, never lost. VBL
and emulator hosts have no such window.

The packer's parameters are the ring size and the chunk size:

```
yx6 [-f] [-o] [-nN] [-cC] [-kK] [-lF] input.ym [output.yx6]
  -nN   ring size per stream, in bytes (default 960)
  -cC   values decoded per call, and the round-robin group size (default 24)
  -kK   ST4 unit size: 1, 2 or 4 (default: 2 when the shape allows, else 1)
  -lF   loop from frame F, overriding the YM header
  -o    play once: pack no loop section
  -drumhzH  the drum rate ceiling (default 25600): a drum asking for a
        faster timer is resampled to the highest MFP rate under the
        ceiling (windowed-sinc, pitch and duration exact), with a warning
  -sidresume  the maxYMiser SID gap model: a released SID's timer keeps
        counting (interrupt masked) and a re-arrival resumes its phase.
        The default is the ym2149-rs model: every re-arrival restarts the
        square at phase zero. Both are ordinary stream verbs - the player
        always carries both, the packer chooses per tune
```

`N` decides how much RAM the player needs (`19 × N` plus about 1.2 KB of
fixed state) and how far back the packer may reference, so it trades memory
for compression; it stops at 2520, because the player reads register `k`'s
ring through an assembled-in displacement of `k*N`. `C` must be at least
18 — one refill slot per stream — and must divide `N`, which is what lets the
player use ST4_wrap rather than the bigger general ring decoder. The packer
enforces both, packs every stream with `-mN` so no back-reference reaches
outside the ring, and with `-l65535` so no operation outruns the 68000
decoder's word counters.

On a synthetic 1500-frame tune, the nineteen streams pack from 27000 bytes to
about 3600 — the streams for registers that barely change cost a few bytes each.
On a real one they do far better: Wings of Death's level 6, digidrums and all,
packs 188784 register bytes into 5064 (2.7%).

## Playing it

```
        lea     song,a0                 ; the .yx6 file, loaded anywhere
        lea     workspace,a1            ; even address, YX6_FIXED+(19*N) bytes
        bsr     YX6_init                ; d0 = 0 when the file was accepted
   vbl:                                 ; once per frame, in supervisor mode
        lea     workspace,a0
        bsr     YX6_play                ; d0 = 0 played, 1 wrapped, -1 ended
        lea     workspace,a0
        bsr     YX6_stop                ; chip quiet, timers stopped
```

`YX6_play` clobbers `d0`–`d5` and `a0`–`a5`, and leaves `d6`, `d7` and
`a6` alone, the same promise ST4 makes - its decoder state spans `a4` and
`a5`. `YX6_init` claims MFP Timers A and D for the effect slots and
`YX6_stop` quiesces them again; neither saves nor restores any machine
state - that is the host's, and the player's header lists exactly what a
polite host must keep (vectors, timer registers, the four enable/mask bits,
the USP under `YX6_SUPER_HOST`), with [YX6_player.S](YX6_player.S) as the
worked example. Timer B stays free for rasters and Timer C stays the
system's. Include both `YX6.S` and `ST4_wrap.S`, with `ST4_UNIT equ 1`
first.

### The schedule

A tune is `O` frames long. Each stream owns a ring of `N` bytes and a saved
decoder state. On every VBL the player reads one value from each of the
nineteen rings and refills exactly one stream — stream `k` on the frame
where `frame mod C` is `k`:

```text
VBL  0: use value  0 from every stream; refill R0
VBL  1: use value  1 from every stream; refill R1
...
VBL 13: use value 13 from every stream; refill R13
VBL 14: use value 14 from every stream; refill E1
...
VBL 17: use value 17 from every stream; refill T2
VBL 18: use value 18 from every stream; no refill
...
VBL 23: use value 23 from every stream; no refill
```

Every stream is therefore one full group ahead of what is being read, and the
work per frame is flat, and ordered so the chip writes never jitter: the
burst gates, the fourteen register writes — at a fixed offset from the
call, whatever the frame's effects cost — then the script's actions, then
one 24-byte decoder call.
The player counts the calls itself and wraps a ring's write pointer when it
lands on the ring end, which is exactly ST4_wrap's contract — there is no DONE
state to poll and no bound check inside the decoder.

### Looping

A packed stream can only be restarted from its beginning, so a looping tune is
packed as two sets of streams split at the loop frame `L`: an intro covering
frames `[0, L)` and a loop covering `[L, O)`. When a register's section runs
out mid-refill the player starts its loop stream over — a fresh decoder writing
on into the same ring — so the rings hold one continuous sequence and the read
side never learns that anything happened. Nothing requires `L` to fall on a
group boundary; a refill that straddles the split just decodes two pieces.

`YX6_play` returns 1 on the frame that ends the tune (the next one is `L`), so
a caller can count passes. A tune packed with `-o` has no loop section: after
its last frame `YX6_play` returns -1 and writes nothing.

The loop frame comes from the YM header by default. The split costs a little
compression, since the loop half cannot reference the intro half — nothing at
all for a tune that loops from frame 0, and about 19% on the synthetic test
tune when it loops from the middle.

### Writing the chip

The fourteen writes are unrolled through a `YX6_WRITE` macro, one invocation per
register, so the register number is an assembled-in immediate and the value goes
straight from the ring to the chip without passing through a data register.
Register k's ring byte sits `k*N` above the cursor, and N is known at init - so
init patches each write's displacement into the instruction once, and the burst
never steps a pointer:

```
        move.b  #\number,(a2)          ; select
        move.b  $7FFF(a1),2(a2)         ; and write - k*N, patched at init
```

That is also why `N` stops at 2520: `13*N` must fit the signed 16-bit
displacement. Faster forms - `movep`, streams pre-formatted for it, streams
of ready-to-run 68000 code - were measured and declined; the numbers live in
[doc/experiments](../doc/experiments/README.md), next to the register
clustering experiment.

Two registers are not written that way. R7 gets the ST's I/O port direction bits
(`$C0`) back, because on an ST port A drives the floppy select lines. R13 is
skipped entirely on a frame whose value is `$FF`, the YM marker for "leave the
envelope alone" — writing it would restart the envelope.

Skipping registers whose value has not changed — the obvious next trick — was
measured and **dropped**: on an ST a sound-chip access costs only about 1.5
cycles more than a RAM one (32 vs 27 ticks for 56000 stores, against 12 for the
bare loop), so the compare that would save a write costs about as much as the
write.

The burst runs with interrupts masked. Selecting a register and writing it are
two bus cycles, and TOS's own handlers use the sound chip in between — its
floppy motor timeout writes port A. Without the mask a write can land on
whatever register the interrupt selected. It costs about 24 cycles a frame.

## What it does not do

* **Sinus-SID.** Never seen in a dump, and never implemented by any player -
  the packer warns and drops it.
* **YM2.** Mad Max's forty drum samples live in the player, not the file;
  supporting them means embedding the bank in the converter. Not yet.
* **Trusted input.** Beyond the magic, version and stream count, the player
  checks nothing, like the ST4 decoders it is built on.

## The `.yx6` container

Big-endian, fixed header, then the packed streams in register order:

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YX6!'` |
| 4 | 2 | format version (5) |
| 6 | 2 | flags: bit 0 set when the tune loops |
| 8 | 4 | `O`, the frame count |
| 12 | 2 | player frequency in Hz |
| 14 | 2 | `S`, the stream count (19: R0..R13, then M A1 P1 A2 P2) |
| 16 | 2 | `N`, the ring size |
| 18 | 2 | `C`, values per call |
| 20 | 4 | `L`, the loop frame; equal to `O` when the tune plays once |
| 24 | 4 | YM master clock (informational) |
| 28 | 4 | byte offset of the drum table; zero when there are no drums |
| 32 | 2 | drum count |
| 34 | 4·S | byte offset of each intro stream, covering frames `[0, L)` |
| 110 | 4·S | byte offset of each loop stream, covering frames `[L, O)` |
| 186 | … | the packed streams, then the drum table |

Packed sizes are not stored: ST4 counts output units, not input bytes, so the
player never needs them. Streams 14–18 carry the compiled effect script —
the packer replays the reference player's decisions over the whole timeline
and writes the outcomes: M says what acts each frame (bit 0/1 = a slot,
bit 2 + bits 5–3 = the burst-gate state), A names the slot's action (verb,
voice, prescaler — start, retune, stop, drum, drum-with-arbitration, or a
held reload/track), P carries the timer count. `O` and `L` count PLAYED
frames: when the effect state at the wrap differs from its first arrival,
the split rotates forward until the two agree, and the file carries those
frames twice, compiled differently. The drum table is `{offset, length}`
entries pointing at PSG-ready volume bytes, each sample closed by a byte
with bit 7 set.

## Tests

```sh
mvn test                                  # the packer: format, effects, shapes
python3 yx6/test/emu/test_yx6.py          # the player, against the YM data
python3 yx6/test/sweep.py songs/*.ym      # a whole collection, differentially
HATARI=... TOS=... yx6/test/run.sh        # the player, on emulated hardware
```

[test_yx6.py](test/emu/test_yx6.py) packs a synthetic tune with the real Java
tool, assembles YX6.S with ST4_wrap.S, runs the player under Unicorn as a plain
68000 and captures every write to `$FFFF8800`. What must match is the **chip's
contents** after each frame — computed from the YM data with no knowledge of the
packer or the player — plus the R13 writes themselves, since those restart the
envelope and are observable in their own right. It covers the default 960/24
shape, a 240-byte ring, the smallest ring that holds two groups, 64-value calls,
the tightest legal 36/18 shape, tunes shorter than a ring, a group and a single
frame, a loop point that is not on a group boundary, a loop section shorter than
one group, several passes round the loop, playing a `-o` tune past its end,
re-initialising for a second pass, and every unit size. A directed effect-stage
test then walks a tune frame by frame past the MFP: SID start, hold, retrigger
and release, a drum pair naming two samples on back-to-back frames, a drum
seizing a SID's voice, the sync-buzzer, the sanitized burst and the forced
mixer, and the ring getting every borrowed byte back - plus each tick handler
run to its `rte`.

[test/sweep.py](test/sweep.py) turns the same machinery on real tunes: it
packs each one at k=1 and replays it under Unicorn, comparing every chip
write against the YM data frame by frame - loop crossing included - one
status line per tune. The whole 544-tune jatari collection verifies clean
with it; the honest limits (effect-owned volume registers and R7 are
excluded, long tunes play their first 1200 frames) are in its header.

[test/run.sh](test/run.sh) goes further than emulation can: it plays a looping
tune on the emulated chip and **reads all fourteen registers back off the YM2149
after every frame**, folding them into a checksum the host computed from the YM
data alone, and past the end so the loop is crossed. It then replays the tune
and reports the cost:

```text
SUM=OK wraps=1 sum=2941391492
CALIB 12 241
T 1700 93
```

93 ticks of the 200 Hz clock for 1700 frames, with the calibration loop's
7,864,630 cycles measured at 241 ticks, works out at about **1,790 cycles per
frame** — roughly 1.1% of a 50 Hz frame on an 8 MHz ST, including the
harness's own loop, the sound chip's bus wait states and the script finding
nothing to do (the harness tune's effect codes are deliberately inert, so
the checksum stays deterministic — and it is the same checksum the v1
interpreter produced, which is the point). The v1 player measured 96 ticks
on the same tune: replaying compiled decisions is cheaper than making them. Measure your own tune before budgeting: the
byte limit is not a time limit, and how hard a chunk is to decode depends on
the data.

Most of what is left is the decoder itself: at `C=24` a refill decodes 24 bytes
on nineteen frames out of every twenty-four. Raising `C` amortises the per-call
cost over more bytes at the price of a refill frame that costs proportionally
more, which is the wrong trade if your frame budget is tight.

The harness masks interrupts while it verifies, and so should anything that
reads the chip back: selecting a register and reading it are two bus cycles, and
TOS's own handlers use the sound chip between them.
