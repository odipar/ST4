# The `.yx6` format — the container and the frame

What a `.yx6` file holds and what the player does with it, once a frame.
[README.md](README.md) is how to make one and play it,
[CONVERSION.md](CONVERSION.md) what a source loses on the way in, and
[EFFECTS.md](EFFECTS.md) why the design is this shape rather than another.

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
map names those takes them, and what a Timer C tune costs its host is in
[doc/four-timer-channels.md](../doc/four-timer-channels.md#timer-c-is-not-free).
No YM tune packed with the default map names either, since a YM frame can
start at most two effects and so only ever uses timer channels 0 and 1 —
`-timers` is what it takes to move them, and why that option's help warns
about Timer C.

A timer the player does not claim is left exactly as the host had it,
which includes **still running**: TOS leaves Timer D counting as its
RS232 baud generator. That is audible even though the player's chip
writes are unchanged, so a host that takes the machine over should quiet
it first. [YX6_player.S](YX6_player.S) does: it saves and stops all four
MFP timers at takeover and restores them at exit. The story is in
[doc/experiments/2026-08-21-the-timers-left-running.md](../doc/experiments/2026-08-21-the-timers-left-running.md). Include both `YX6.S` and `ST4_wrap.S`, with `ST4_UNIT`
defined first and set to the unit the tune was packed at — 2 for the packer's
default; `mkprg.sh` reads it out of the file's first section for you.

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

The channels sit last on purpose. A tune that uses channels 0 and 1 never
has to decode the other two pairs, and a tune with no effects at all stops
after T: the player refills up to the last channel the header names, and
the rest of the streams are in the file but never touched. That is also
why `C` is measured against what a tune decodes rather than against the
format's twenty-five.

Every stream is therefore one full group ahead of what is being read, and the
work per frame is flat, and ordered so the chip writes never jitter: the
burst gates — which voices this frame's register writes must leave alone —
then the fourteen register writes, at a fixed offset from the call whatever
the frame's effects cost, then the script's actions, then
one 24-byte decoder call.
The player counts the calls itself and wraps a ring's write pointer when it
lands on the ring end, which is exactly ST4_wrap's contract — there is no DONE
state to poll and no bound check inside the decoder.

### Looping

A packed stream can only be restarted from its beginning, so a looping tune's
streams are packed as two **sections** split at the loop frame `L`: an
intro covering frames `[0, L)` and a loop covering `[L, O)`. When a register's
intro section runs out mid-refill the player starts its loop section over — a
fresh decoder writing on into the same ring — so the rings hold one continuous
sequence and the read side is unaffected by it. Nothing requires `L` to fall
on a group boundary; a refill that straddles the split just decodes two pieces.
A tune that loops from frame 0 has no intro to split off: it gets one loop
section per stream, and the intro half of the offset table is zero.

`YX6_play` returns 1 on the frame that ends the tune (the next one is `L`), so
a caller can count passes. A tune packed with `-o` has no loop section: after
its last frame `YX6_play` returns -1 and writes nothing.

The loop frame comes from the YM header by default. The split costs
compression, since the loop section cannot reference the intro section —
nothing at all for a tune that loops from frame 0, and about 37% on the
synthetic test tune when it loops from the middle, where 2912 packed bytes
become 3998.

### Writing the chip

The fourteen writes are unrolled through a `YX6_WRITE` macro, one invocation
per register. The register number is an assembled-in immediate that sits in
`d1`'s high byte, the ring byte lands in its low byte, and one `movep.w` sends
both. Nothing is tested and no pointer is stepped: register k's ring byte sits
`k*N` above the cursor, and N is known at init, so init patches each write's
displacement into the instruction once.

```
        move.w  #\number<<8,d1          ; the select byte, in the high half
        move.b  $7FFF(a1),d1            ; the value - k*N, patched at init
        movep.w d1,0(a2)                ; select and write in ONE instruction
```

That is also why `N` stops at 2520: `13*N` must fit the signed 16-bit
displacement on that ring read. `movep` as a SPEED trick - the `.l` pairing,
streams pre-formatted for it, streams of ready-to-run 68000 code - was
measured and declined; the numbers are in
[doc/experiments](../doc/experiments/README.md), next to the register
clustering experiment. `movep.w` came back a few paragraphs down for a
different reason.

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

## The `.yx6` container

Big-endian, fixed header, then the packed sections in register order. The
words below follow [doc/terminology.md](../doc/terminology.md): a
**stream** is a series of values arriving at one register, the fourteen
register streams are **frame streams** (one value per player call), and
what the YM format calls an "effect" is a **timer stream**, driven by an
MFP timer rather than by the frame. A **section** is this format's own
word: one ST4 container holding a stream's intro or its loop.

| offset | size | field |
|---:|---:|---|
| 0 | 4 | `'YX6!'` |
| 4 | 2 | format version (10) |
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
| 34 | 4·S | byte offset of each intro section, covering frames `[0, L)` |
| 134 | 4·S | byte offset of each loop section, covering frames `[L, O)` |
| 234 | … | the packed sections, then the sample table |

Packed sizes are not stored: ST4 counts output units, not input bytes, so the
player never needs them.

Streams 14–24 are **script data** rather than frame streams: they are
packed the same way, but their bytes never reach a chip register. They
carry the compiled effect script — the packer replays the reference
player's decisions over the whole timeline and writes down the outcomes.

* **M** records what acts this frame: bits 0–3, one per timer channel, plus
  bit 4 and bits 7–5, the burst-gate state — which voices' volume registers
  the frame write must leave alone.
* **T** carries the channel-to-timer map, two bits each.
* **A** names the channel's action: a verb in three bits, a voice in two, and
  a prescaler or a set of hold flags in the rest. The eight verbs are resume,
  hold, release, a **toggle stream** start, retune, a retrigger stream start,
  a **PCM stream** start, and a PCM start that preempts a toggle stream on
  the same voice. There are three voices in a two-bit field, so voice 3 names
  none, and RETUNE addressed to it is the live rate change: control register
  then data register, the timer never stopped.
* **P** carries the timer count, the other half of the **rate**.
* **X** carries the operands an action byte has no room for: bits 7–4 the
  envelope shape a retrigger stream restarts — one per frame, not one per
  channel, since the chip has one envelope generator — and bits 3–0 the timer
  channels a preempting sample stops, one bit each.

`O` and `L` count PLAYED frames: when the effect state at the wrap
differs from its first arrival, the split rotates forward until the two
match, and the file carries those frames twice, compiled differently.

The sample table holds what a PCM stream plays out: eight-byte `{offset,
length, loop}` entries — a long and two words — pointing at PSG-ready volume
bytes, each sample closed by a
byte with bit 7 set. `loop` is where the tick goes back to when it meets
that byte, as a position in the sample, or `$FFFF` for one that stops
there — 0 is a real loop point, the sample that repeats whole. YM calls
these digidrums, they never loop, and their numbering is the YM file's.
