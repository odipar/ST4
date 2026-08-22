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

| Piece | What it is |
|---|---|
| [`org.ym6.Yx6`](../src/main/java/org/ym6/Yx6.java) | the packer: YM5!/YM6! in, `.yx6` out - one tune or a whole set |
| [`org.ymr.Ymr`](../src/main/java/org/ymr/Ymr.java) | the second packer: RhYMe YMR! in, the same `.yx6` out - one tune or a whole set |
| [`Ym6Reader`](../src/main/java/org/ym6/Ym6Reader.java) + [`YmEffects`](../src/main/java/org/ym6/YmEffects.java) | one boundary: YM's frames and effect slots in, a `Tune` out |
| [`YmrReader`](../src/main/java/org/ymr/YmrReader.java) + [`YmrEffects`](../src/main/java/org/ymr/YmrEffects.java) | the other, a peer and not a client: .YMR's streams and pops in, the same `Tune` out |
| [`Tune`](../src/main/java/org/yx6/Tune.java) | what a front end hands over and the engine works on: frame streams, timer streams, samples, rate - no format anywhere in it |
| [YX6.S](YX6.S) | the player library, 2,002 bytes plus ST4_wrap's 292 |
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
its subtune name and the SNDH's composer is left absent rather than invented;
and the packer's per-stream table is left on the screen, which the `.ym` front
ends filter out - a build script on its way to an SNDH does not want a screen
of ratios per tune, and a test drive is the one moment you do. A set
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

The SNDH glue is the polite host the player's assumption 5 describes: INIT
(subtune in `d0.w`, 1-based) saves exactly what the player touches and
claims a timer per timer channel the tune names - two, for a YM tune -
EXIT quiesces and hands everything back, PLAY runs
one frame - every entry preserves `d0`-`a6`, nothing outside the blob is
used, the USP is never touched, and INIT called twice without an EXIT
cleans up after itself. The header carries `TC50` (play is driven at 50 Hz
from whatever the host hangs on it), `FLAG ~ady`, and a `FRMS` table -
looping tunes are marked endless, play-once tunes declare their frames -
and the subtune names, which is what a jukebox shows as its track list:
SNDH's subtunes ARE its multi-song format. Where the YM header carries
metadata, [ym_sndh.sh](ym_sndh.sh) carries it across: each tune's YM name
becomes its subtune name (the file stem when that field is "unknown"), a
shared YM author becomes the SNDH composer (COMM), the YM player rate
becomes the TC tag (one rate per file, validated), a lone tune's name
becomes the title and a set titles itself with its songs joined - unless
-t overrides it - and CONV records the provenance: converted from YM.
YEAR has no YM source and is honestly absent.
The file is raw and position independent; pack it with ICE 2.4 for the
archive if you like, players unpack that themselves.

One honest limitation, in the file's own header comment: a native host
that dispatches PLAY from its Timer C hook delays timer channel 2's ticks
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

`N` sets how much RAM the player needs (`25 × N` plus about 1.6 KB of
fixed state) and how far back the packer may reference, so it trades memory
for compression; it stops at 2520, because the player reads register `k`'s
ring through an assembled-in displacement of `k*N`. `C` must be at least
one refill slot per stream the tune **decodes** — 17 when it drives no
timer channel, 19, 21, 23 as it names more, 25 when it uses all four —
and must divide `N`, which is what lets the player use ST4_wrap rather
than the bigger general ring decoder. So the default 960/24 covers
everything up to three channels; a tune on all four needs 25 slots and a
ring that divides by them, 1000/25 say. The packer
enforces both, packs every stream with `-mN` so no back-reference reaches
outside the ring, and with `-l65535` so no operation outruns the 68000
decoder's word counters.

On a synthetic 1500-frame tune, the twenty-five streams pack from 37500 bytes to
about 2900 — the streams for registers that barely change cost a few bytes each,
and a timer channel the tune never uses costs 28.
On a real one they do far better: Wings of Death's level 6, digidrums and all,
packs 188784 register bytes into 5064 (2.7%).

## Playing it

```
        lea     song,a0                 ; the .yx6 file, loaded anywhere
        lea     workspace,a1            ; even address, YX6_FIXED+(25*N) bytes
        bsr     YX6_init                ; d0 = 0 when the file was accepted
   vbl:                                 ; once per frame, in supervisor mode
        lea     workspace,a0
        bsr     YX6_play                ; d0 = 0 played, 1 wrapped, -1 ended
        lea     workspace,a0
        bsr     YX6_stop                ; chip quiet, timers stopped
```

`YX6_play` clobbers `d0`–`d5` and `a0`–`a5`, and leaves `d6`, `d7` and
`a6` alone, the same guarantee ST4 gives - its decoder state spans `a4` and
`a5`. `YX6_init` claims an MFP timer for each timer channel the file names, and
`YX6_stop` quiesces them again; neither saves nor restores any machine
state - that is the host's, and the player's header lists exactly what a
polite host must keep (vectors, timer registers, the four enable/mask bits,
the USP under `YX6_SUPER_HOST`), with [YX6_player.S](YX6_player.S) as the
worked example. Which timers a tune takes is its own map's business: the
packer's default puts channels 0 and 1 on Timers A and D, so Timer B stays
free for rasters and Timer C stays the system's 200 Hz clock. A tune whose
map names those takes them — and a Timer C tune stops the system clock and
cannot be hosted from a Timer C hook. No YM tune names either, since a YM
frame can start at most two effects.

A timer the player does not claim is left exactly as the host had it,
which includes **still running**: TOS leaves Timer D counting as its
RS232 baud generator. That is audible even though the player's chip
writes are unchanged, so a host that takes the machine over should quiet
it first. [YX6_player.S](YX6_player.S) does: it saves and stops all four
MFP timers at takeover and restores them at exit. The story is in
[doc/experiments/2026-08-21-the-timers-left-running.md](../doc/experiments/2026-08-21-the-timers-left-running.md). Include both `YX6.S` and `ST4_wrap.S`, with `ST4_UNIT equ 1`
first.

### The schedule

A tune is `O` frames long. Each stream owns a ring of `N` bytes and a saved
decoder state. On every VBL the player reads one value from each ring and
refills exactly one stream — stream `k` on the frame where `frame mod C`
is `k`:

```text
VBL  0: use value  0 from every stream; refill R0
VBL  1: use value  1 from every stream; refill R1
...
VBL 13: use value 13 from every stream; refill R13
VBL 14: use value 14 from every stream; refill M
VBL 15: use value 15 from every stream; refill X
VBL 16: use value 16 from every stream; refill T
VBL 17: use value 17 from every stream; refill A0
VBL 18: use value 18 from every stream; refill P0
VBL 19: use value 19 from every stream; refill A1
VBL 20: use value 20 from every stream; refill P1
VBL 21: use value 21 from every stream; refill A2 - if channel 2 is used
VBL 22: use value 22 from every stream; refill P2 - likewise
VBL 23: use value 23 from every stream; refill A3 - if channel 3 is used
```

The channels sit last on purpose. A tune that uses two of them never has
to decode the other two pairs, and a tune with no effects at all stops
after T: the player refills up to the last channel the header names, and
the rest of the streams are in the file but never touched. That is also
why `C` is measured against what a tune decodes rather than against the
format's twenty-five - the default 960/24 shape still fits every tune
that leaves a channel idle.

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
side is unaffected by it. Nothing requires `L` to fall on a
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
of ready-to-run 68000 code - were measured and declined; the numbers are in
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

That burst of writes is what [doc/terminology.md](../doc/terminology.md)
calls the **frame write**. Selecting a register and writing it are two bus
cycles, and an interrupt between them would send the value to whatever
register the interrupt selected — so each write is a single `movep.w`,
which a 68000 cannot split. That makes the burst's interrupt mask
optional rather than necessary: it is on by default, and `-nomask` drops
it, which lets ticks interleave with the burst instead of waiting behind
it for about 500 cycles — longer than a tick period at the top of the
range. [The note](../doc/experiments/2026-08-21-the-unmasked-burst.md)
has the numbers on both.

## What the conversion is

A `.YMR` and a `.yx6` are the same idea with different bookkeeping. Both
stream a YM2149 register dump past a 68000 through small rings, refilled a
byte or two per frame, so the music never exists in memory as a whole; both
give every stream a ring that is the whole of its memory; both are packed
against that ring so no back-reference can reach outside it. The lineage is
literally shared — a .YMR's streams are ZX1, which is what ST4 grew out of,
which is why [`org.ymr.Zx1`](../src/main/java/org/ymr/Zx1.java) reads them
through the [vendored jx1 decoder](../src/main/java/org/jx1/README.md) rather
than a second implementation of a format that already has one.

What differs is what a frame costs. A .YMR stores one entry per CHANGE and a
command stream saying which streams each frame POPS, so a held note costs
nothing after the frame it arrives on — and no frame can be reached except by
replaying every frame before it. A `.yx6` stores one value per frame per
stream and lets ST4 find the repetition, which is why a frame is a read from
each ring and no bookkeeping at all. So the conversion's shape is:
[`YmrReader`](../src/main/java/org/ymr/YmrReader.java) replays the command
stream once, from the start, and hands on the flat per-frame view. The pops
become frames.

The effect vocabularies then line up one to one, and not by coincidence: both
formats hang the same three tricks off an MFP timer, and each pair is the same
effect for the same reason.

* **A RhYMe PWM is a toggle stream** — what YM calls a SID voice. Both write
  one voice's volume register from a timer interrupt at audio rate,
  alternating a level with zero, and neither touches the mixer for it, so the
  values chop whatever the voice's own generators are doing rather than
  replacing it. Both take the loud level from what the song last set on that
  voice: RhYMe's handler toggles between the shadow volume and zero, and the
  toggle tick reads its level out of `R(8+voice)`. Same effect, same
  parameter, same place — which is why the converter writes nothing at all
  for a PWM.
* **A RhYMe Sample is a PCM stream** — a digidrum. Both walk a block of 4-bit
  levels into the voice's volume register, one byte a tick, at whatever rate
  the timer is programmed to, and both hand the register back to the song when
  the block runs out. RhYMe's exporter has already folded its samples down to
  the levels the PSG's volume register takes, which is exactly what a
  yx6 sample table holds, so the bytes cross unchanged: they need a table
  entry and an end marker and nothing else. A YM digidrum arrives 8-bit and
  has to be folded; this is the one thing a .YMR hands over that needs no work
  at all.
* **A RhYMe RTE is a retrigger stream** — a sync-buzzer. Both rewrite R13
  from a timer interrupt, and the values say nothing: writing R13 sends the
  envelope generator back to the start of its shape, so the envelope becomes
  the waveform and the timer's rate becomes its pitch. The one difference is
  where the shape comes from — RhYMe's handler keeps the player's own copy of
  it, the retrigger tick reads it out of the voice's volume register — which
  is why this is the single effect the converter has to write something for.

Two more correspondences are worth naming, because between them they are why
the register vector needs so little done to it: the first is why nothing has
to be translated, the second is why the two parameters that must be written
can be.

**R13 and the `$FF` marker.** A .YMR frame that does not pop
`envelope_shape` must not write R13: the pop IS the retrigger, so writing the
last shape again would restart the envelope on every frame of a held note. No
shape value can mean "nothing", so the reader marks such a frame with `$FF` —
and `$FF` is precisely what **Writing the chip** above means by it, the value
on which the player skips the register entirely. Two formats reached one
convention from one constraint, so the register vector is handed straight on.

**The shadow volume and the burst gate.** The .YMR spec suppresses the frame
write to a volume register owned by a running PWM or Sample — the value goes
to the player's shadow and never to the chip, so the frame write cannot fight
the effect's own timer-rate writes — and says nothing of the sort about an
RTE, which writes R13 and leaves the voice's volume to the song. That is the
`.yx6` burst gate exactly: M's gate mask, one bit per voice, which a toggle
arm and a PCM arm set and a retrigger arm does not. It is also what decides
which of a stream's parameters can ride in a register that already means
something. A PCM stream's sample number can sit in the volume byte because
the gate is shut over it — `yx6_gates` has overwritten that write with two
`nop`s, so it does not reach the chip at all. A retrigger stream's shape
cannot: an RTE leaves the voice's volume to the song, so that byte is
delivered, and a shape hidden in its low nibble would cost the voice its
level on any frame not already following the envelope. Format v9 is the
answer to that — see **Where a retrigger stream's shape comes from** — and
a `.ymr` sets its flag, so the only parameter this conversion writes is a
PCM stream's sample number.

One engine reads both dialects, and three flags say which. A held PCM code
does not retrigger, because a .YMR's trigger is a pop and not the code's
continued presence — that is what stops a sustained sample being chopped into
frame-long pieces, where a YM dump's held drum code fires again every frame.
A voice playing a sample keeps its mixer bits, because RhYMe's player never
touches R7 for an effect: the mixer is the song's, and a song that wants its
sample clean has already disconnected the voice itself, where a YM drum's
voice is forced off the mixer for it. And a channel's own commands end the
sample running on it — an effect pop of 0 stops the timer, an effect pop of
anything else reprograms the one timer the sample was ticking on — where in a
YM dump nothing ends a sample but its own marker tick. The one thing that
needed inventing is the other half of "a pop is an event": the script acts
where a code byte CHANGES, so bit 3 of the code is flipped on every sample
trigger, and two pops of one sample at one rate become two different codes
and two starts.

`signals-grouped.ymr` is 9,984 frames at 50 Hz with a PWM on voice A, a
sync-buzzer on voice B and a PWM on voice C — three effects at once, which two
fixed channels could not have carried, and which is the case the four-channel
generalisation and the T stream were built for. Packed with the default shape,

```sh
java -ea -cp target/classes org.ymr.Ymr -f signals-grouped.ymr doc.yx6
```

reports 249,600 bytes of register and script data packed into 12,432 (5.0%)
in a 13,840-byte file, 25 rings of 960 bytes, decoding 23 of the 25 streams so
that one of the default `C`=24's slots is idle; the tune loops from its start,
and the encoder rotated the split forward 96 frames so the effect state at the
wrap matches its first arrival. 5,312 of those 12,432 packed bytes are the
eleven script streams, which the `.YMR` — 10,488 bytes — does not carry at
all: RhYMe's player reconciles its three timers every frame from what popped,
and this one replays decisions taken at pack time. That is the bookkeeping
difference paid in bytes, and what it buys is the flat frame.

### Where a retrigger stream's shape comes from

A sync-buzzer restarts the hardware envelope at audio rate, so what a
retrigger stream needs to know is which shape to restart. The two formats
file it in different places, and neither of them is where the player looks.

A toggle stream's volume and a PCM stream's sample number are read off the
voice's own register ring, and that is right, because both belong to the
voice the effect took over: the level a SID chops is the level the tune put
in that register. A shape belongs to nothing of the kind. There is one
envelope generator and any number of voices may follow it, so the shape is
not a voice's data — YM6 keeps it in a voice's nibble because the parameter
field sits at one place for all three kinds and a buzzer's voice, following
the envelope, leaves that nibble spare. RhYMe keeps it where the chip does,
in its own copy of R13.

So from **v9** the player does not look for it at all: the shape is CARRIED,
in X's high nibble, one value per frame. Whichever front end read the file
knows where its format filed it and simply writes the number down — the same
way the other three source differences already reach the player, as different
values rather than as a mode. `yx6_shape` is five instructions with no branch,
there is no header flag, no shadow, and no priming: a tune that arms a buzzer
before it has set a shape carries whatever its format assumes, `$08` for a
`.ymr` and `0` for a YM dump, because that is the front end's fact to know.

One nibble for the whole frame is not a budget compromise. Two retrigger
streams cannot restart different shapes — there is one generator — and
ST-Sound agrees: its `envShape` is a single variable, written by an R13 write
and then by each buzzer in slot order, last one winning. Before v9 each
channel patched its own tick from its own voice, which on a tune running two
buzzers at once gave the generator two shapes at two rates. `jamblv1` does
exactly that on 462 of its 972 buzzer frames, and the old arrangement
restarted the wrong shape on 15 of them.

### What a .ymr gives up

Everything not on this list is exact, and
[test/ymr_sweep.py](test/ymr_sweep.py) is what says so: it replays a converted
tune on the real player and compares every write `YX6_play` makes to the sound
chip, plus which MFP timers it claimed, against its own decoder and replay of
the .YMR image. It walks 1,200 frames of a long tune, and the whole of one —
the rotation frames and the wrap included — with `YMR_FRAME_CAP` raised;
`signals-grouped.ymr` passes both. Two things it does not establish: it packs
at `-k1`, so the played frames are the .YMR's own and the padding the default
`-k2` may insert is never walked, and it does not compare what a timer was
PROGRAMMED to — that is the directed effect test's, and it is the dimension
the two rate rows below are about.

| What changes | What it costs | Reported |
|---|---|---|
| A sample numbered past the 32nd | the sample is dropped, and so is every trigger of it | yes |
| A sample past 65535 bytes | everything after its 65535th, so the length fits a word | yes |
| A sample looped from past its own end | it is played once instead | yes |
| A looped sample | its loop region is unrolled towards 8 KB and stops there | yes |
| A sample byte above the 4-bit levels | masked, since bit 7 is what ends a PCM stream | yes |
| A rate pop that moves the prescaler | the timer period in flight, truncated | no — it is every such pop |
| …under a running sample | the sample restarts on that frame | yes |
| A sample the song stops early | one sample byte, held until the next frame | no — the window is sub-frame |
| A PWM or RTE re-configured with nothing changed | nothing is emitted at all | no — RhYMe's exporter cannot write one |

The six that are counted are named once per sample or once per channel
rather than reported a frame at a time, because a song 9,984 frames long can
break one rule on a thousand of them and still only be doing one thing wrong.
The three that are not are the three there is nothing to say about: one is
every prescaler move any song makes, one is shorter than the frame it happens
in, and the last cannot arise from a file RhYMe wrote.

A rate pop that moves only the COUNTER is on none of these lines, because it
costs nothing at all: it leaves the code byte where it was, so it compiles to
a HOLD carrying the reload flag, and `yx6_hold` writes the count to a timer it
never stops — the same live reload RhYMe does, verb for verb. That is worth
saying because it is the ordinary case rather than the lucky one: a pitch
slide is made of these. On `signals-grouped.ymr` the compiled script carries
3,827 live reloads against 325 verbs that stop a timer to reprogram it — and
313 of those 325 are prescaler moves under a running RTE, which is what a
verb census misses, since START_RETRIGGER is also what a fresh arm emits.

* **Three timers bound to voices, against four channels and a map.** A .YMR
  names Timer A, Timer B and Timer D, and the spec fixes which voice each one
  drives — A to A, B to B, D to C — so the binding is normative and not the
  converter's to choose. A `.yx6` has four timer channels and a stream saying
  which MFP timer each runs on, so the converter simply writes that binding
  into T: channels 0, 1 and 2 take Timers A, B and D, and the fourth channel,
  which no .YMR fills, takes the Timer C nobody asked for, which keeps the map
  a permutation and costs nothing — the header never flags an idle channel and
  the player claims no timer for it. So a `.ymr` tune leaves Timer C, the
  system's 200 Hz clock, alone, and does take Timer B wherever it runs an
  effect there, which the YM packer's default map keeps free for rasters.
  Three channels means the player decodes 23 streams, so `C` must be at least
  23: the default 24 clears it by one slot, and more buys headroom no `.ymr`
  can use.
* **Thirty-two samples, where a .YMR may carry 65535.** A yx6 sample number
  is the five bits the script reads out of a volume register, so everything
  past the cap is dropped and a trigger of a dropped one is reported. A yx6
  sample table entry holds its length in a word, too, so anything past 65535
  bytes is cut to fit. The .YMR spec caps a sample at 65536, so a file that
  keeps to it loses exactly the one byte; nothing in the reader enforces that
  ceiling, and a file that breaks it loses whatever it is over by.
* **A PCM tick has no loop.** It walks forward and stops on the first byte
  with bit 7 set, which is the whole of its end condition and the reason it
  costs no compare per tick. So a looped sample is unrolled instead — its
  loop region written out again as many whole times as fit under 8 KB, about
  ten seconds of an 800 Hz drum loop and about a fifth of a second of a 40 kHz
  one — and a song that holds the loop longer than the unrolled copy lasts
  hears it stop. Whole regions only, so a sample already at the ceiling, or
  one whose region will not fit under it, is unrolled no times at all and the
  note says so. The voice does not stick there: the script reopens its gate
  at the computed end and the frame write puts the song's own volume back,
  which is what a .YMR player does when a one-shot sample runs out.
* **No verb moves a prescaler under a running timer.** RhYMe pops a rate on
  its own to slide a pitch: control register, then data register, the timer
  never stopped, so a running PWM keeps its phase and a running sample its
  place and only the rate moves. Half of that survives the conversion intact.
  A .YMR rate entry is a prescaler and a counter, only the prescaler is in the
  code byte, and a pop that moves the counter alone therefore leaves the code
  where it was: the script emits a HOLD carrying the reload flag, and
  `yx6_hold` writes the new count to a timer it never stops — RhYMe's own live
  reload, verb for verb. That is what a pitch slide is made of, and it costs
  nothing. A pop that moves the PRESCALER cannot be said that way: it changes
  the code byte, so it compiles to a program verb, and every verb that carries
  a rate goes through `yx6_program`, which stops the timer, loads the count and
  runs it again. The period in flight is truncated whichever verb it is, which
  is why a prescaler change under a running RTE compiles to a plain
  START_RETRIGGER and nothing gentler is invented for it. Under a running
  SAMPLE it costs more: the new code byte is one the script can only read as a
  new trigger, so the sample restarts there, and the conversion counts it. This
  is a gap in the `.yx6` ABI rather than anything the converter chose, and no
  gentler verb would close it, since they all truncate the same period.
* **A sample the song stops early is stopped a sliver late.** A .YMR can end
  a sample before its data runs out — an effect pop of 0, or a different
  effect arriving on the same timer, which is the same voice, since a .YMR
  binds each timer to one — and the conversion obeys it on the frame it is
  said. Where the frame hands the voice back, it hands it back at once: the
  timer is stopped on that frame — by a RELEASE where the song popped 0, by
  the arriving verb's own `yx6_program` where an RTE took the channel — the
  voice stops being the sample's, and its gate reopens. The player applies a
  frame's gate state BEFORE the
  register burst and the script's actions after it, so the frame write this
  reopens is that same frame's, and the voice's own volume is on the chip
  inside the 20 ms the song asked for it with no skew to correct. Where the
  frame hands the voice to a PWM instead, the gate stays shut, because the
  square wants it shut too, and the song's volume is not due back at all.
  What the ordering cannot cover is the sliver between the burst and the
  action: a tick landing in it writes one more sample byte over the volume
  just written, and that byte stands until the next frame. It is one wrong
  level for most of one frame, against the whole frames of a sample that
  should not be playing at all. No ordering of the verbs closes it either:
  the actions sit after the burst so their varying cost cannot jitter the
  register writes, which is a promise worth more than this sliver costs.
Everything else the conversion has to change, it counts and names rather than
reporting a frame at a time, because a song 9,984 frames long can break one
rule on a thousand of them and still only be doing one thing wrong: an effect
type in the 4-255 the spec reserves, dropped rather than guessed at, since
RhYMe's own player falls through to PWM for anything it does not recognise and
a wrong guess is a wrong sound; a timer configured with a prescaler or counter
of 0, the MFP's stopped state, which arms nothing; a sample index with no
block behind it; and a sample trigger landing on the loop frame with the code
the song's last frame already ends on, which the wrap swallows, since coming
round from the end the code has not changed and the script acts on codes that
change.

## What it does not do

* **Sinus-SID.** Never seen in a dump, and never implemented by any player -
  the packer warns and drops it.
* **YM2.** Mad Max's forty drum samples are held in the player, not the file;
  supporting them means embedding the bank in the converter. Not yet.
* **Trusted input.** Beyond the magic, version and stream count, the player
  checks nothing, like the ST4 decoders it is built on.

## The `.yx6` container

Big-endian, fixed header, then the packed streams in register order. The
words below follow [doc/terminology.md](../doc/terminology.md): a
**stream** is a series of values arriving at one register, the fourteen
register streams are **frame streams** (one value per player call), and
what the YM format calls an "effect" is a **timer stream**, driven by an
MFP timer rather than by the frame.

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YX6!'` |
| 4 | 2 | format version (9) |
| 6 | 2 | flags: bit 0 set when the tune loops; bits 1-4, one per timer channel, set when the tune uses it |
| 8 | 4 | `O`, the frame count |
| 12 | 2 | frame rate in Hz: how often the player is called |
| 14 | 2 | `S`, the stream count: 25, being fourteen frame streams R0..R13 and eleven of script data, M X T and four A/P pairs |
| 16 | 2 | `N`, the ring size |
| 18 | 2 | `C`, values per call |
| 20 | 4 | `L`, the loop frame; equal to `O` when the tune plays once |
| 24 | 4 | YM master clock (informational) |
| 28 | 4 | byte offset of the sample table; zero when there are none |
| 32 | 2 | sample count |
| 34 | 4·S | byte offset of each intro stream, covering frames `[0, L)` |
| 134 | 4·S | byte offset of each loop stream, covering frames `[L, O)` |
| 234 | … | the packed streams, then the sample table |

Packed sizes are not stored: ST4 counts output units, not input bytes, so the
player never needs them.

Streams 14–24 are **script data** rather than frame streams: they are
packed the same way, but their bytes never reach a chip register. They
carry the compiled effect script — the packer replays the reference
player's decisions over the whole timeline and writes down the outcomes.
**M** records what acts this frame (bits 0–3 = one per timer channel,
bit 4 + bits 7–5 = the burst-gate state, which registers the frame write
must leave alone). **T** carries the channel-to-timer map, two bits each. **A** names the channel's action: a verb, a voice and a
prescaler, the verbs being start, retune, stop, a **PCM stream** start,
a PCM start that preempts a **toggle stream** on the same voice, or a
hold that reloads or tracks. **P** carries the timer count, the other
half of the **rate**. **X** is the operand a verb reads when its action
byte has no room for one: today, which timer channels a preempting sample
stops, one bit each.

A timer channel is what the format names, and stream **T** says which MFP
timer each one runs on — so the file, not the player, decides. The
channels are the last streams for the same reason the flags name them: a
tune that uses two leaves the other two pairs unread at the end.

`O` and `L` count PLAYED frames: when the effect state at the wrap
differs from its first arrival, the split rotates forward until the two
match, and the file carries those frames twice, compiled differently.

The sample table holds what a PCM stream plays out: `{offset,
length}` entries pointing at PSG-ready volume bytes, each sample closed
by a byte with bit 7 set. YM calls these digidrums, and their numbering
is the YM file's.

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
the tightest legal 44/22 shape, tunes shorter than a ring, a group and a single
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

[test/run.sh](test/run.sh) goes further than emulation can: it plays a looping
tune on the emulated chip and **reads all fourteen registers back off the YM2149
after every frame**, folding them into a checksum the host computed from the YM
data alone, and past the end so the loop is crossed. It then replays the tune
and reports the cost:

```text
SUM=OK wraps=1 sum=2941391492
CALIB 12 242
T 1700 93
```

93 ticks of the 200 Hz clock for 1700 frames, with the calibration loop's
7,864,630 cycles measured at 242 ticks, works out at about **1,790 cycles per
frame** — roughly 1.1% of a 50 Hz frame on an 8 MHz ST, including the
harness's own loop, the sound chip's bus wait states and the script finding
nothing to do (the harness tune's effect codes are deliberately inert, so
the checksum stays deterministic — and it is the same checksum the v1
interpreter produced, which is the point — and, its script being inert, it
names no timer channel, so the player decodes seventeen streams and skips
the eight the four channels would need). The v1 player measured 96 ticks
on the same tune, v2 with two fixed channels 94, and v3 with three 88:
replaying compiled decisions is cheaper than making them, and decoding
only the streams a tune uses is cheaper still. A tune that uses two timer
channels decodes twenty-one streams. Measure your own tune before budgeting: the
byte limit is not a time limit, and how hard a chunk is to decode depends on
the data.

Most of what is left is the decoder itself: at `C=24` a refill decodes 24 bytes
on seventeen of every twenty-four frames for this tune, twenty-one for one
that uses two timer channels. Raising `C` amortises the per-call
cost over more bytes at the price of a refill frame that costs proportionally
more, which is the wrong trade if your frame budget is tight.

The harness masks interrupts while it verifies, and so should anything that
reads the chip back: selecting a register and reading it are two bus cycles, and
TOS's own handlers use the sound chip between them.
