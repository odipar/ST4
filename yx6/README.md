# YX6 — streaming YM chiptunes on a plain 68000

YX6 is a MinYMiser-style player, now ST4-native: a YM tune packed as twenty-five ST4
streams — fourteen carrying the sound registers, eleven carrying the compiled
effect script — each decoded through its own small ring by
[ST4_wrap.S](../68k/ST4_wrap.S). The music never
exists in memory as a whole — only rings of a few hundred bytes, refilled one
stream per frame.

It grew inside the ST1 project and moved here with it; it plays the fourteen
standard YM2149 registers, loops, and plays the YM6 special effects: SID
voice, digidrum and sync-buzzer run on timer channels exactly as the
original replay routines ran them, compiled at pack time out of either YM
dialect — or, through a second front end, out of RhYMe's own `.YMR` register
dump, which is the same idea with different bookkeeping. The format has four
timer channels and the file says which MFP timer each runs on, one stream
carrying the map; the player claims only the channels a tune names — a YM
tune names two and a converted `.ymr` three, so the timers left over stay
the host's. [EFFECTS.md](EFFECTS.md) is the design; sinus-SID
is the one effect deliberately left unplayed, in the good company of every
other player including the format author's.

This file is how to pack a tune and play it. The rest is split off so each
piece can be read on its own:

| | |
|---|---|
| [FORMAT.md](FORMAT.md) | what a `.yx6` holds and what the player does with it once a frame |
| [CONVERSION.md](CONVERSION.md) | what a YM file or a `.YMR` loses on the way in, and what each front end reports |
| [EFFECTS.md](EFFECTS.md) | the design: why the effect script is compiled at pack time rather than interpreted |
| [../doc/terminology.md](../doc/terminology.md) | the vocabulary all four use |

| Piece | What it is |
|---|---|
| [`org.ym6.Yx6`](../src/main/java/org/ym6/Yx6.java) | the packer: YM5!/YM6! in, `.yx6` out - one tune or a whole set |
| [`org.ymr.Ymr`](../src/main/java/org/ymr/Ymr.java) | the second packer: RhYMe YMR! in, the same `.yx6` out - one tune or a whole set |
| [`Ym6Reader`](../src/main/java/org/ym6/Ym6Reader.java) + [`YmEffects`](../src/main/java/org/ym6/YmEffects.java) | one boundary: YM's frames and effect slots in, a `Tune` out |
| [`YmrReader`](../src/main/java/org/ymr/YmrReader.java) + [`YmrEffects`](../src/main/java/org/ymr/YmrEffects.java) | the other, a peer and not a client: .YMR's streams and pops in, the same `Tune` out |
| [`Tune`](../src/main/java/org/yx6/Tune.java) | what a front end hands over and the engine works on: frame streams, timer streams, samples, rate - no format anywhere in it |
| [YX6.S](YX6.S) | the player library, 3,170 bytes plus ST4_wrap's 292 |
| [YX6_sndh.S](YX6_sndh.S) + [`MkSndh`](../src/main/java/org/yx6/MkSndh.java) | the canonical container: an SNDH v2.2 file, subtunes included |
| [`YmSndh`](../src/main/java/org/ym6/YmSndh.java) | `.ym` dumps straight to one SNDH, packer flags and all |
| [YX6_player.S](YX6_player.S) + [`MkPrg`](../src/main/java/org/yx6/MkPrg.java) | a thin TOS shell around those same SNDH bytes |
| [`Play`](../src/main/java/org/ym6/Play.java) | one command: pack a `.ym`, build it, play it under Hatari |
| [`RhymePlay`](../src/main/java/org/ymr/RhymePlay.java) + [rhyme.sh](rhyme.sh) | one command: pack a `.ymr`, build it, play it under Hatari |

The five build tools are Java; `mksndh.sh`, `ym_sndh.sh`, `mkprg.sh`,
`play.sh` and `rhyme.sh` are four-line wrappers that find the repository and
the compiled classes, so every command below is spelled the way it always was.
Reading a `.yx6` header, validating that a set shares one configuration,
generating the SNDH tags and driving rmac are all in
[`org.yx6`](../src/main/java/org/yx6) with the engine and the format,
because none of them can tell a `.yx6` packed from a `.ym` from one packed
from a `.ymr`. The `.ym` packer and everything that reads a YM header are in
[`org.ym6`](../src/main/java/org/ym6), the `.ymr` side in
[`org.ymr`](../src/main/java/org/ymr), and both call the shared tools in
process rather than through another JVM.

**Unit sizes.** `yx6 -kK` (and `play.sh -kK`) packs the register sections at
ST4 units of 1, 2 or 4 bytes: wider units hand the decoder half or a quarter
as many units per refill, at some ratio cost. The default is 2 - measured on
a real tune, 2 buys most of 4's speed for a fraction of its size cost. The
tune length, the loop frame and C must be whole units — a padded section
would decode one extra value into the ring, and it would be played — so a
tune with an odd length or loop frame is PADDED to the shape: the packer
duplicates a frame that neither writes R13 nor triggers a drum, which holds
the chip state one inaudible tick longer, and reports what it did. Only
when no safe frame exists near a boundary does it fall back to `-k1`. The
player is built for one unit size and checks every section's ST4
signature against it at init; `mkprg.sh` reads the unit out of the
file's first section automatically.

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
and the script closes the emulator behind it — nothing prompts you to
confirm anything. Point it at your own install with `HATARI=` and `TOS=`.
Everything it builds is kept next to the tune in
`<name>-n<ring>-c<chunk>/`, so you can compare two ring sizes by ear and
keep both. Hand it several tunes and they
become one program's subtunes — packed with one configuration, titled and
named from their own YM headers, switched with the number keys — in a
`<first name>+<n more>-n<ring>-c<chunk>/` directory of their own.

Or do the steps yourself:

```sh
mvn -q compile exec:exec@yx6 -Dargs="-f song.ym song.yx6"
yx6/mkprg.sh SONG.PRG song.yx6        # -> SONG.PRG, runnable on an ST
```

`mkprg.sh -m` builds the same program but has it drop a `YX6DONE.MRK` file as
it exits; that is how `play.sh` detects that the tune has stopped. A plain
build never touches the disk.

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
with a bounded loop, so a host that calls the player mid-screen stops
waiting instead of hanging, and the wait distorts nothing it measures. It
is an estimate (10-cycle quanta, fixed per-tick costs), and it is free
when off: the default build is byte-identical to one made before the
option existed.

## Playing a RhYMe .ymr

RhYMe's own register dump is a `.YMR`, and
[`org.ymr.Ymr`](../src/main/java/org/ymr/Ymr.java) packs one into the same
`.yx6` the YM packer writes. [rhyme.sh](rhyme.sh) test drives it the way
[play.sh](play.sh) test drives a `.ym`:

```sh
yx6/rhyme.sh song.ymr                 # 960-byte rings, 24 values per call
yx6/rhyme.sh -n2048 -c32 song.ymr     # longer calls: cheaper on average
yx6/rhyme.sh -l0 song.ymr             # loop from the start, header or no
yx6/rhyme.sh -o song.ymr              # play once and stop
yx6/rhyme.sh -min13 -sec52 song.ymr   # trim: start deep in a long tune
yx6/rhyme.sh -startframe41403 -frames1729 song.ymr
yx6/rhyme.sh -perf song.ymr           # the raster monitor
yx6/rhyme.sh -nomask song.ymr         # drop the frame write's interrupt mask
yx6/rhyme.sh one.ymr two.ymr          # a set: number keys pick the subtune
```

`rhyme.sh -h` lists the lot. The flags mean what they mean on `play.sh`, the
work directory is named the same `<name>-n<ring>-c<chunk>/` way, and SPACE in
the Hatari window still stops it — someone who has test driven a `.ym` has
nothing new to learn. Two things differ, and both come from the format rather
than from the tool. A `.YMR` carries no title, no author and no comment — it
stores streams and a command stream, not credits — so each file's own stem is
its subtune name and the SNDH's composer is left absent rather than invented.
The second is on the screen: the packer's per-stream table stays, where the
`.ym` front ends filter it out - a build script on its way to an SNDH does not
want a screen of ratios per tune, and a test drive is the one moment you do. A set
still has to share one frame rate, since one player build is called at one
rate, and `rhyme.sh` checks before it packs anything - which for a `.YMR`
costs a full read of each dump, there being no way into one but from the
start.

There is no `ymr.sh`: the packer is a plain class, and everything downstream
of a packed file is format-blind, so the containers are built by the same
tools as any other `.yx6`.

```sh
java -ea -cp target/classes org.ymr.Ymr -f song.ymr song.yx6
yx6/mkprg.sh SONG.PRG song.yx6                      # -> SONG.PRG, as ever
java -ea -cp target/classes org.ymr.Ymr -f one.ymr two.ymr build/  # a set
yx6/mksndh.sh -t"My Set" myset.sndh build/*.yx6     # -> subtunes 1..2
```

`ymr` takes the packer flags `yx6` takes — `-f -o -nN -cC -kK -lF` and the
trim window — and drops the three that are YM arguments. There is no
`-timers`, because the .YMR spec binds each timer to a voice and a flag that
let a caller break it would only produce a file that plays the wrong voices;
no `-drumhz`, because a YM digidrum carries the rate it was sampled at and can
be resampled to fit a ceiling, while a .YMR sample is a stream of levels whose
rate is whatever its timer is programmed to on the frame it plays, so there is
nothing to resample it against; and no `-sidresume`, because the phase model
it selects is a YM argument — RhYMe's PWM restarts at its loud half whenever
the effect is configured, which is the default model already. `-script` dumps
the compiled effect script instead of packing, one line per frame anything
acts on, which is the quickest way to see that a channel started the effect it
should.

## The SNDH container

The canonical build of the player is an **SNDH v2.2 file** - the Atari ST's
standard music container - and the `.PRG` is a thin shell around those same
bytes. [mksndh.sh](mksndh.sh) assembles [YX6_sndh.S](YX6_sndh.S) around one
or more `.yx6` files:

```sh
java ... org.ym6.Yx6 -f one.ym two.ym three.ym build/   # a set, one config
yx6/mksndh.sh -t"My Set" myset.sndh build/*.yx6         # -> subtunes 1..3
yx6/mkprg.sh MYSET.PRG build/*.yx6                      # the same, runnable
yx6/ym_sndh.sh -t"My Set" myset.sndh one.ym two.ym      # both steps in one
```

The SNDH glue is the polite host the player's assumption 5 describes. INIT
takes the subtune in `d0.w`, 1-based, saves what a YM tune's player touches —
both timer vectors, TACR, TCDCR's Timer D nibble, TADR, TDDR and the four
enable and mask bits, Timers A and D being the two a YM tune uses —
and claims a timer per timer channel the tune names - two, for a YM tune.
EXIT quiesces and hands everything back, and PLAY runs one frame. Every entry
preserves `d0`-`a6`, nothing outside the blob is used, the USP is never
touched, and INIT called twice without an EXIT cleans up after itself. The
header carries `TC50` (play is driven at 50 Hz from whatever the host hangs
on it), `FLAG ~ady`, and a `FRMS` table - looping tunes are marked endless,
play-once tunes declare their frames - and the subtune names, which is what a
jukebox shows as its track list: SNDH's subtunes ARE its multi-song format.

Where the YM header carries metadata, [ym_sndh.sh](ym_sndh.sh) carries it
across:

| YM field | SNDH tag |
|---|---|
| the tune's name | its subtune name - the file stem where that field is "unknown" |
| the author, where a set shares one | `COMM`, the composer |
| the player rate | the `TC` tag - one rate per file, validated |
| a lone tune's name, or a set's songs joined | `TITL`, unless `-t` overrides it |
| nothing | `CONV`, which records the provenance: converted from YM |

`YEAR` has no YM source and is honestly absent. The file is raw and position
independent; pack it with ICE 2.4 for the archive if you like, players unpack
that themselves.

One honest limitation, in the file's own header comment: a native host
that dispatches PLAY from its Timer C hook delays timer channel 1's ticks
(Timer D, lower MFP priority) by up to the length of the call - pending,
never lost. VBL
and emulator hosts have no such window.

The packer's parameters are the ring size and the chunk size:

```
yx6 [-f] [-o] [-nN] [-cC] [-kK] [-lF] input.ym [output.yx6]
  -f    overwrite the output file if it exists
  -nN   ring size per stream, in bytes (default 960)
  -cC   values decoded per call, and the round-robin group size (default 24)
  -kK   ST4 unit size: 1, 2 or 4 (default: 2 when the shape allows, else 1)
  -timersT   which MFP timer each channel runs on, one letter per
        channel from 0 up: -timersBC puts channel 0 on Timer B and
        channel 1 on Timer C. The default is AD. Timer C is the
        system's 200 Hz clock, so a tune that takes it stops that
        clock and cannot be hosted from a Timer C interrupt
  -lF   loop from frame F, overriding the YM header
  -o    play once: pack no loop section
  -minM -secS   trim: drop everything before M:S, so a moment deep in a
        long tune plays immediately
  -startframeF -endframeF -framesN   the same window in frames
  -drumhzH  the drum rate ceiling (default 25600): a drum encoded at a
        faster timer is resampled to the highest MFP rate under the
        ceiling (windowed-sinc, pitch and duration exact), with a warning
  -sidresume  the maxYMiser SID gap model: a released SID's timer keeps
        counting (interrupt masked) and a re-arrival resumes its phase.
        The default is the ym2149-rs model: every re-arrival restarts the
        square at phase zero. Both are ordinary stream verbs - the player
        always carries both, the packer selects per tune
```

### Ring size and chunk

`N` sets how much RAM the player needs (`25 × N` plus about 1.6 KB of
fixed state) and how far back the packer may reference, so it trades memory
for compression; it stops at 2520, because the player reads register `k`'s
ring through an assembled-in displacement of `k*N`. `C` must be at least
one refill slot per stream the tune **decodes** — 17 when it drives no
timer channel, then 19, 21, 23, 25 by the HIGHEST channel it names, since
the player stops at the last channel rather than counting them —
and must divide `N`, which is what lets the player use ST4_wrap rather
than the bigger general ring decoder. So the default 960/24 covers every
tune that leaves channel 3 idle; one that names channel 3 needs 25 slots
and a ring that divides by them, 1000/25 say. The packer
enforces both, packs every stream with `-mN` so no back-reference reaches
outside the ring, and with `-l65535` so no operation outruns the 68000
decoder's word counters.

### What it packs to

On a synthetic 1500-frame tune packed at `-k1` with no loop split, the
twenty-five streams pack from 37500 bytes to about 2900 — the streams for
registers that barely change cost a few bytes each, and a timer channel the
tune never uses costs 56, 28 each for its action stream and its count stream.
At the packer's own defaults — split at the loop, `-k2` — the same tune costs
5464. On a real one they do far better: Wings of Death's level 6, digidrums
and all, packs 262250 register bytes into 6180 (2.4%), a 10476-byte file.

## Tests

```sh
mvn test                                  # the packers: formats, effects, shapes
python3 yx6/test/emu/test_yx6.py          # the player, against the YM data
python3 yx6/test/sweep.py songs/*.ym      # a whole collection, differentially
python3 yx6/test/ymr_sweep.py song.ymr    # the same, against the .YMR data
HATARI=... TOS=... yx6/test/run.sh        # the player, on emulated hardware
```

[test_yx6.py](test/emu/test_yx6.py) packs a synthetic tune with the real Java
tool, assembles YX6.S with ST4_wrap.S, runs the player under Unicorn as a plain
68000 and captures every write to `$FFFF8800`. What must match is the **chip's
contents** after each frame — computed from the YM data with no knowledge of the
packer or the player — plus the R13 writes themselves, since those restart the
envelope and are observable in their own right. It covers the default 960/24
shape, a 240-byte ring, the smallest ring that holds two groups, 64-value calls,
the tightest legal 34/17 shape, tunes shorter than a ring, a group and a single
frame, a loop point that is not on a group boundary, a loop section shorter than
one group, several passes round the loop, playing a `-o` tune past its end,
re-initialising for a second pass, and every unit size. Four named tests
follow: the SNDH container (subtunes, handback, re-init), v9's carried
retrigger shape from both sources, v10's sample loop in the plain and `-perf`
builds, and v10's live retune. A directed effect-stage
test then walks a tune frame by frame past the MFP: SID start, hold, retrigger
and release, a drum pair naming two samples on back-to-back frames, a drum
seizing a SID's voice, the sync-buzzer, the burst gate and the forced
mixer, and a drum number travelling through the ring unharmed - plus each tick
handler run to its `rte`.

[test/sweep.py](test/sweep.py) turns the same machinery on real tunes: it
packs each one at k=1 and replays it under Unicorn, comparing every chip
write against the YM data frame by frame - loop crossing included - one
status line per tune. All 543 readable tunes of the 544-file jatari collection
verify clean with it. The effect-owned volume registers are not excluded but
checked against an independent Python model of the script semantics — a gated
voice's must be absent from the frame's writes, an open one exact. The honest
limits are in its header: long tunes play their first 1200 frames, and the
tick handlers' own audio is not rendered. R7 is a limit the header does not
admit to — 7 is missing from the rig's strict list, so the mixer check never
runs.

[test/ymr_sweep.py](test/ymr_sweep.py) does the same for the `.ymr` front end,
and has to work harder to be worth anything: the truth side is an INDEPENDENT
model of the .YMR image written from the format spec - its own ZX1 decoder,
its own stream map walk, its own replay of the command stream - because the
Java reader and the 68000 player must not be able to cancel each other's bugs
out. It checks R13 as an EVENT rather than a value, since the .YMR writes it
on a frame that pops `envelope_shape` and on no other; it checks the volume
registers against the gate, a voice running a PWM or a Sample having to be
absent from that frame's writes and a voice running an RTE exact; and it
checks that the player claims exactly the timers the tune names - A, B and D,
never C. What it cannot see is what the tick handlers write, since it calls
`YX6_play` and nothing else; that side is the directed effect test's. Its
header names the rest.

[test/run.sh](test/run.sh) goes further than the Unicorn rig can: on a whole
emulated ST it plays a looping tune and **reads all fourteen registers back off
the YM2149 after every frame**, folding them into a checksum the host computed
from the YM data alone, and past the end so the loop is crossed. It then
replays the tune and reports the cost:

```text
SUM=OK wraps=1 sum=2941391492
CALIB 12 242
T 1700 93
```

93 ticks of the 200 Hz clock for 1700 frames, with the calibration loop's
7,864,630 cycles measured at 242 ticks, works out at about **1,790 cycles per
frame** — roughly 1.1% of a 50 Hz frame on an 8 MHz ST, including the
harness's own loop, the sound chip's bus wait states and the script finding
nothing to do. The harness tune's effect codes are deliberately inert, which
keeps the checksum deterministic — it is the same checksum the v1 interpreter
produced, which is the point — and leaves the player decoding seventeen
streams: an inert script names no timer channel, so the eight the four
channels would need are skipped. The v1 player measured 96 ticks
on the same tune, v2 with two fixed channels 94, and v3 with three 88:
replaying compiled decisions is cheaper than making them, and decoding
only the streams a tune uses is cheaper still. Measure your own tune before
budgeting: the byte limit is not a time limit, and how hard a chunk is to
decode depends on the data.

Most of what is left is the decoder itself: at `C=24` a refill decodes 24 bytes
on seventeen of every twenty-four frames for this tune, twenty-one for one
that names channels 0 and 1. Raising `C` amortises the per-call
cost over more bytes at the price of a refill frame that costs proportionally
more, which is the wrong trade if your frame budget is tight.

The harness masks interrupts while it verifies, and so should anything that
reads the chip back: selecting a register and reading it are two bus cycles, and
TOS's own handlers use the sound chip between them.