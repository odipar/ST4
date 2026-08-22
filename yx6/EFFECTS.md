# YX6 effects: the design

How the YM6 special effects — SID-Voice, DigiDrum, Sync-Buzzer, Sinus-SID —
become part of the streaming player. Streams first, then the file format, the
timer engine, and what it costs. Every fact below is source-verified: the
effect semantics against ST-Sound (the format author's reference player,
github.com/arnaud-carre/StSound @ d1876bc), the 68000 practice against gwEm's
shipping maxYMiser replayer, Hatari's MFP/PSG models, EmuTOS and the ST
hardware register docs, and the numbers against a survey of 516 real YM files.

## The model these four share

Read `doc/terminology.md` for the full vocabulary and a two-way mapping to
the YM names; this is the short of it, because the rest of this document
reads better once the four effects stop looking like four unrelated
tricks.

A **signal** is a series of values with a rate, and every sound is one.
The generators produce signals without any help. Software produces them
by writing registers, which is the only thing the player ever does. Only
the rate changes, and that gives three clocks — the
**frame clock**, one call to the player, usually 50 a second; the two
**tick clocks**, one MFP timer interrupt each, 48 to 25,600 a second; and
the YM2149's own 2 MHz, which software never reaches. A series of values
arriving at one register is a **stream**: the fourteen register streams
are **frame streams**, and an effect is a **timer stream**.

Three of the four effects are the same thing. **A volume stream sends a
series of volume values to one voice, one per tick** — which is all a
DigiDrum is, all a SID-Voice is, and all a Sinus-SID would be. They
differ in the series, in whether it repeats, and in whether the voice's
own generators are left running underneath:

| YM6 | series | repeats | the voice | rate |
|---|---|---|---|---|
| DigiDrum | a stored sample, any length | no, plays once and ends | disconnected (section 4's mixer forcing), so no generator signal reaches the voice and the values themselves are the sound | **independent** — the sample's playback pitch |
| SID-Voice | two values, loud and off | yes, until stopped | left connected, so the values chop its signal | **derived** from the note playing |
| Sinus-SID | a smooth curve | yes, until stopped | left connected, so the values shape its signal | **derived** from the note playing |

So a SID-Voice is a DigiDrum whose sample is two values long and repeats,
and the format keeps them apart for two reasons, neither of them about
sound. The first is cost: a general sample stream holds a pointer, reads,
steps and tests for the end on every tick, while a two-value stream flips
between two numbers it already holds — at 25 kHz on an 8 MHz 68000 that
gap is most of the machine, and it is why the tick handlers of section 5
are written as separate blocks rather than one. The second is that a
drum's rate answers to nothing (there is no note; the voice is
disconnected) while a SID's rate is derived from the note it chops, so
the two must stay in ratio. That ratio is why **retune** exists in
section 4: a melody moves, a derived rate moves with it, and restarting
the square on every note would be audible.

Sync-Buzzer is the odd one. It writes the envelope shape register, the
same shape every tick, and the values say nothing — what matters is the
side effect, since writing that register sends the envelope generator
back to the start of its shape. It also reaches a voice indirectly: there
is one envelope generator, and a voice takes its loudness from it only
while following the envelope, so a buzzer is the one effect not tied to a
single voice.

Two more terms this document uses without naming: a stream's **phase** is
its place in its own cycle, and its **phase policy** is what becomes of
that place across a gap — free-running, or restarting from zero. Which
policy a pack uses is the packer's `-sidresume` flag, and the reasoning
behind both is in `doc/experiments/2026-08-20-sid-phase-semantics.md`
and its companion case study.

## 0. Where v2 moved the machinery

Format v5 replaced the E/T streams and the player's interpreting effect
stage with the **compiled effect script**: `EffectScript.java` replays every
rule in this document over the whole timeline at pack time and emits
streams of prepared actions — M (what acts this frame; zero almost always),
X (the operand an action byte has no room for), T (which MFP timer each
channel runs on) and, per timer channel, an A/P pair (its action byte and
timer count). Format v7 made that four channels and moved the
channel-to-timer map into T; a YM frame starts at most two effects, so a
YM tune uses two and the others' streams pack to nothing. The player's
handlers copy prepared values into the timers and the tick handlers'
operands and compare nothing; the voice disconnection of section 4
arrives baked into the R7 stream, the ring is never edited (the
borrow/restore of section 4 has no v2 counterpart), and a drum's gate
reopens at the frame boundary after its computed end instead of
mid-frame at the marker. A loop whose
wrap state differs from its first arrival has its split rotated until the
two match, so action streams replay correctly every time round.

Two later revisions widened what the script can say, both for the .ymr front
end and both available to any source that tells the compiler its semantics.
Format **v9** carries the shape a retrigger stream restarts in X, so the
player looks for it nowhere (section 2 below has the corpus this settles).
Format **v10** adds a loop word to each sample table entry — the PCM tick
that meets the end marker moves the loop address into its own operand
instead of stopping the timer, `$FFFF` meaning one that stops — and gives
RETUNE a live form: the action byte's voice field holds three voices in two
bits, so 3 names none, and RETUNE addressed to it writes the control
register and then the data register with the timer left running. That is
what RhYMe's own player does for a rate pop, so a prescaler slide keeps both
the square's phase and its place inside the period in flight. Naming no
voice, it repatches no parameter, so the compiler emits it only where the
parameter stood still and falls back to the ordinary retune where it moved.

Sections 1–4 remain the semantic truth — they are now the specification of
the pack-time simulator rather than of a run-time interpreter — and their
frame contract is what `EffectScriptTest`, the rig's directed effect test
and the corpus sweep's independent Python model all assert. Section 5's
implementation notes describe the v1 interpreter where they touch the frame
side; the tick handlers, the timer programming order, the burst-gate
mechanism and the budgets still read true.

## 1. The stream concept

> Sections 1 to 6 describe the **v4** shape, where each slot had a control
> stream and a timer-count stream, E1/T1 and E2/T2. They stay because the
> rules are still the specification the pack-time simulator implements.
> The **v7** file replaces those four streams with eleven of script data,
> M X T and four A/P pairs, as section 0 describes and as the container table in
> [README.md](README.md) lays out. Where these sections say "the player
> decides", read "the packer resolved it, and wrote the answer down".


A YM6 frame can start up to two effects. Each slot is three fields smeared
across the frame's spare register bits, plus a parameter hidden in a volume
register:

```
slot 1:  code = r1 bits 4-7    TP = r6 bits 5-7    TC = r14
slot 2:  code = r3 bits 4-7    TP = r8 bits 5-7    TC = r15

code bits 4-5:  voice + 1 (00 = slot idle this frame)
code bits 6-7:  00 SID   01 DigiDrum   10 Sinus-SID   11 Sync-Buzzer
parameter:      in the voice's volume register r8+v - SID max volume (&15),
                drum sample number (&31), sync-buzzer envelope shape (&15)
timer rate:     2457600 / prediv[TP] / TC   (prediv: -,4,10,16,50,64,100,200)
```

The packer already strips the spare bits off the register streams — the
fourteen streams hold exactly what the chip should see. The design keeps that
invariant and gives the effects their own streams, repacked at pack time into
the shape the player uses:

```
stream 14  E1  slot-1 control: code bits 7-4, TP bits 2-0. Zero = idle
stream 15  T1  slot-1 timer count TC
stream 16  E2  slot-2 control, same layout
stream 17  T2  slot-2 timer count
```

**Eighteen streams, one byte per frame each, same machinery.** Every effect
stream is an ST4 container like the register streams: same rings, same refill
round-robin, same loop split at L, same unit-size rules. The chunk grows from
C = 16 to a multiple of 18 (C = 20 with N = 1000, or C = 24 with N = 960 —
the 24-stream shape is already measured at 2,340 cycles/frame at k = 2, so
the budget is known to hold).

Why dedicated streams beat streaming all sixteen raw registers (the
alternative, which needs only r14/r15 added):

- **Ratio.** In a raw r1 stream the byte changes when the fine period *or*
  the effect code changes. Separated, the register streams pack exactly as
  today and the control streams are long runs of one value — the survey
  shows
  effect activity is a few percent of frames on most tunes — which the event
  optimizer packs to almost nothing.
- **One byte, one decision.** The raw layout spreads code and TP over two
  registers; repacked, the player tests one ring byte: zero means idle.
- **Dialect-proof.** YM5 encodes effects entirely differently (section 3).
  The packer normalizes every dialect into the same E/T streams; the player
  speaks one language.

The effect *parameters* need no streams of their own: SID volume, drum
number and buzzer shape are all held in the voice's volume register, which the
masked register streams already carry. The player reads them from the ring —
and sanitizes what must not reach the chip (section 4).

That filing is YM6's, and for a YM6 file it is exactly right, because that is
where its bytes are. It sits oddly with what this document says two sections
earlier, though: a buzzer "is the one effect not tied to a single voice",
there being one envelope generator and any number of voices following it. Its
shape is per-generator data kept per-voice, because YM6 had a spare nibble
there and nowhere better — free for a YM6 file, since a voice following the
envelope makes the chip ignore that nibble anyway.

It is not free for a source that files the shape with the envelope and puts
the voice on it a frame later; there the nibble is still a level, and the
buzzer and the voice cannot both have it. From format v9 the player stops
looking: a retrigger stream's shape is carried in stream X's high nibble, one
value a frame, and whichever front end read the file resolves where its own
format kept it. A SID voice's volume and a digidrum's number stay in the
voice's register, because they belong to the voice the effect took over and
are free where they are; only the buzzer's parameter moved, because only the
buzzer's parameter was never a voice's to begin with.

One nibble for the whole frame is what the chip has. Two retrigger streams
cannot restart different shapes - there is one envelope generator - and
ST-Sound's `envShape` is a single variable, written by an R13 write and then
by each buzzer in slot order, the last winning. A per-channel shape could
encode a state the machine cannot hold, and did: `jamblv1` runs two buzzers
at once on 462 of its 972 buzzer frames, and reading each channel's own voice
restarted the wrong shape on 15 of them.

## 2. What the corpus shows

Survey of 516 local YM files plus the research corpus:

- 111 tunes use effects or carry drums. SID dominates: 81k effect frames
  (one tune holds it on 86% of its frames). DigiDrum second (~8k frames,
  overwhelmingly voice C). Sync-Buzzer is rare and slow (measured 83-110 Hz
  restart rates). Sinus-SID: **zero occurrences** among the 33 effect tunes
  examined closely, and ST-Sound's own `sidSinStart()` body is literally
  `// TODO` — the canonical player has never implemented it.
- Measured timer rates: SID interrupts 110 Hz - 3.2 kHz; drums almost always
  TP=1 with TC 122..24 → 5-7 kHz typical, and two known outliers at
  23.6-25.6 kHz (Jambala 11, Megaman). maxYMiser rejects rates above
  25.6 kHz outright; so will the packer.
- Drum inventories: ≤ 6 drums per tune seen in the wild; largest sample
  3,004 bytes, largest per-tune table 4,532 bytes (local library worst:
  7.9 KB). The number field is 5 bits, so 32 is the format ceiling.
- Wild files require lenient parsing: Ninja Remix carries 151 drum codes with
  TP=TC=0 (all must be no-ops, per ST-Sound's `if (prediv*count)` guard),
  and files exist with effect codes set but zero drums in the file.
- The parameter register was measured too, because section 1 asserts where it
  is and v9 turned that assertion into work a front end does: the shape is
  resolved at pack time and carried, so being wrong about where YM6 keeps it
  is now silently wrong rather than loudly. Of 1,018 distinct tunes on hand,
  243 are YM6 and **three** carry a live sync-buzzer at all — `jamblv1`, and
  ST-Sound's own `ND-Loader` and `ND-Toxygene` — for 1,375 buzzer frames
  between them, which is why the effect earns one line above and no more.
  R13's own stream and the voice's nibble DISAGREE on a real tune: on 621 of
  `jamblv1`'s 972 buzzer frames the two resolutions part company, and the
  nibble is the one ST-Sound arrives at. The other two tunes agree with
  themselves throughout and settle nothing, which is worth saying — two
  thirds of the corpus evidence for this is inert.
- The same three tunes settled the shape's ARITY, which matters more than
  its source and was got wrong for longer. `jamblv1` runs **two** buzzers at
  once on 462 of those 972 frames. There is one envelope generator, so
  ST-Sound holds one `envShape`, written by R13 and then by each live buzzer
  in slot order with the last winning; a per-channel shape encodes a state
  the chip cannot hold, and did — reading each channel's own voice restarts
  the wrong shape on 15 of `jamblv1`'s frames. v9's single nibble in X is
  that arity, not a saving.
- The SID's loud half was measured the same way and is less marginal: 13,891
  SID frames across five YM6 tunes, 6,520 of them on the frame after the
  level moved. A packer that read the volume once at the arm rather than
  re-patching it wherever the nibble changes would be wrong on all 6,520.

## 3. The file: yx6 format v4 (superseded by v5, then v6)

```
header as v3 through the master clock, then:
  28  4   byte offset of the sample table; zero when there are none
  32  2   drum count
  34  72  intro table, now 18 long offsets
 106  72  loop table
 178  ..  the packed sections, then the sample table
```

The tables stay at fixed offsets - the player indexes them without parsing -
so the sample table goes last, found through its header pointer: count
entries of {long offset, word length}, each offset pointing at PSG-ready
volume bytes 0..15 closed by an end marker with bit 7 set (the maxYMiser
convention: the drum ISR's own move.b reads it as negative and stops). Drums
sit unpacked: the corpus worst case is under 8 KB and the ISR reads them a
byte at a time anyway.

Pack-time normalization — the packer holds all the dialect knowledge:

- **YM6**: E = (r1 & $F0) | (r6 >> 5), T = r14; slot 2 from r3/r8/r15.
- **YM5**: r1 bits 4-5 are a SID-only slot (bits 6-7 ignored, as ST-Sound),
  r3 bits 4-5 a DigiDrum-only slot with **TP always from r8** regardless of
  voice and TC from r15. Rewritten into YM6 codes. The version byte is
  load-bearing: the same r3 bits mean different effects in YM5 and YM6.
- **Invalid = idle**: TP=0 or TC=0 with voice bits set, a drum number with
  no such drum, or a rate above 25.6 kHz → E written as 0 (with a pack-time
  warning). The player never receives a code it cannot run. This also
  sidesteps
  the MFP's "data register 0 counts as 256" surprise.
- **Sinus-SID** is counted and dropped with a warning; the code point stays
  reserved. Three tunes by one musician, unplayed by the reference player.
- **Drum samples**: 8-bit unsigned by default → high nibble (`>>4`), the
  historically faithful map (ST-Sound's own real-hardware comment). Files
  with attribute bit 2 (A_DRUM4BITS) already hold 4-bit values — taken
  unmodified. Attribute bit 1 (A_DRUMSIGNED) is ignored, as ST-Sound does.
- **YM2** (Mad Max) triggers drums via r10 bit 7 from a 40-sample bank baked
  into the player, always voice C, rate (2457600/4)/r12. Supportable later
  purely in the converter (embed the used samples, rewrite triggers as
  slot-2 events); out of scope for v1. YM3/YM3b have no effects; YM4 is
  dead ("No more YM4! support" - ST-Sound).

## 4. The player: one frame

The reference player's frame contract, which the streams already obey:
SID and Sync-Buzzer are **level-triggered** — the file re-asserts the code
every frame the effect is held, absence means off. A DigiDrum code is a
**trigger on every frame it is present** — the reference starts a drum on
each coded frame, and real dumps depend on it: Wings of Death 8 codes every
hit on two back-to-back frames whose ring bytes name *different* samples
(an attack, then a body — 922 pairs), so a held code must restart, not be
ignored. Once its code goes away the drum runs to its end on its own.

The effect stage runs *before* the register burst, so the burst stays
fourteen dumb writes:

```
1. effect stage, per slot: pop E and T off their rings
   E = 0, slot ran SID/buzzer:  stop the slot's timer (the level dropped)
   E = 0, slot ran a drum:      nothing - the drum finishes by itself
   E = same drum code:          a fresh trigger - the full start below, with
                                the sample number the ring holds NOW
   E = same SID/buzzer code:    write T to the timer data register, and only
                                if T CHANGED - a write that lands while the
                                counter passes 01 loads an indeterminate
                                count, so a same-value refresh only rolls
                                dice (a running timer loads a changed count
                                at the next reload, glitch-free; changing
                                TP on the fly is NOT safe: any TP change is
                                stop + reload + start)
   E changed:                   point the slot's vector at the effect's ISR,
                                prime its state from the ring (SID volume,
                                drum sample, buzzer shape), write TP to the
                                control register and T to the data register
   drum start, additionally:    read the sample number from the drummed
                                voice's ring byte, then OVERWRITE that ring
                                byte with 0, and set the voice's drum-active
                                flag (the ISR clears it at sample end);
                                if the OTHER slot holds a SID on the same
                                voice, stop that SID's timer and clear its
                                last code - the drum owns the volume
                                register (the reference's drum override),
                                and the SID slot retries every frame,
                                suppressed while the flag is up, so it
                                restarts by itself after the drum ends
2. sanitize: while a voice's drum-active flag is set, OR its tone+noise
   bits into the ring's r7 byte - the reference player forces the drummed
   voice out of the mixer, and real dumps do not always carry that in r7
3. register burst: the fourteen writes, exactly as today
4. refill: one stream's ring, round-robin, exactly as today
```

The sanitize trick is the load-bearing idea: effects never add a branch to
the burst. But the ring is decode history - a later match may copy from any
byte of it - so the stage BORROWS bytes rather than taking them: every edit
is logged and undone right after the burst. The chip receives the sanitized
frame; the ring never keeps the edit. (Implementation note: this emerged
the hard way - a rig test now packs a pattern that a later match copies
across a sanitized byte's position, and fails if the byte is not returned.) Idle cost: with the stage inlined
into YX6_play and each slot gated on E | E-last, an idle frame pays two ring
pops and two zero tests, ~140 cycles measured from the tables; a running
drum adds the r7 borrow. One channel machine serves them all, aimed by a
descriptor - timer registers, the claim, the channel's tick-handler block -
so the timers' only remaining difference is data. The tick
handlers sign off with an immediate byte write to the in-service register
(it ignores written ones), and a host that never runs user-mode code can
build with YX6_SUPER_HOST=1 to park the PCM tick's a0 in the USP, 16
cycles under the stack; the park is one-way for the USP itself, so a host
that ever returns to user mode saves the USP with the rest of the machine
state - YX6_player.S does.

The per-frame volume write and a running SID do NOT cooperate — this
paragraph originally claimed they did, and that claim was wrong at bass
rates (the Wicked Polygons ticking;
[the full story](../doc/experiments/2026-08-19-sid-ticking.md)). The burst
write lands mid-phase and forces the loud half back for up to half a square
period: inaudible at kHz SID rates, a click train under a 100–1100 Hz
buzz-bass. Both references put the register under the ISR — ST-Sound's
per-sample SID overrides the frame write, maxYMiser's frame code skips SID
channels — so while a slot holds a SID **or a drum**, the burst's write of
that voice's volume register is gated off (self-modified: the write's
`movep.w` is replaced by two nops, and copied back from a template to
reopen). A SID's gate opens again on release, voice
change, drum takeover, init and stop; a drum's opens where the drum ends —
in the marker tick, through an address its start patched in — and a drum
cut off mid-sample by a voice change has its flag and gate cleaned up by
the drum that replaced it. SID phase deliberately free-runs across frames
(ST-Sound never resets `sidPos`), including across a prescaler boundary —
a code change that differs only in its prescaler retunes the timer without
touching the vector, keeping the installed half. The buzzer's per-frame
phase reset in ST-Sound is an emulator artifact — on hardware the timer
free-runs, which is the original sound.

## 5. Timers and interrupts

A **timer channel** is what the format names; which of the MFP's four
timers serves it is what stream T says. The player holds one row per
timer, `yx6_timer_a` to `yx6_timer_d`, and an assignment copies the named
row into the channel's descriptor - so the tick handlers belong to the
timers and are never patched when a channel moves:

**Timer A**: vector $134, control $FFFA19, data $FFFA1F, bit 5 of
IERA/IMRA/ISRA at $FFFA07/13/0F. **Timer B**: vector $120, control
$FFFA1B, data $FFFA21, bit 0 of the A registers. **Timer C**: vector
$114, the HIGH nibble of TCDCR $FFFA1D, data $FFFA23, bit 5 of the B
registers. **Timer D**: vector $110, the LOW nibble of TCDCR, data
$FFFA25, bit 4 of the B registers. C and D share that byte, so both are
programmed read-modify-write, and C's prescaler shifts up four to reach
its nibble.

The packer's default map puts channel 0 on Timer A and channel 1 on
Timer D — maxYMiser's proven allocation, and what v6 wired in — so a YM
tune sounds as it always did and leaves B and C alone. **Timer B stays
free for raster code** unless a tune names it, and **Timer C stops TOS's
200 Hz clock** if a tune names that, which also rules out a host that
calls PLAY from a Timer C hook. Timer D's only casualty is RS232 baud. The YM6 TP value is written verbatim to the
control register; T to the data register.

Facts the engine leans on, all verified:

- The whole MFP interrupts at 68000 level 6, so effect ISRs never nest and
  a PSG select/write pair inside an ISR is atomic against the other timers
  by construction. (The player then drops to level 5 after the pair, on
  purpose, so a higher-priority timer's tick need not wait a whole tick —
  the MFP serves its A bank first, which orders the three Timer A, Timer
  B, Timer D.) The frame burst already runs at $2700, and EmuTOS guards its
  own PSG port-A access the same way — no new races exist anywhere.
- TOS runs the MFP in software end-of-interrupt mode (VR = $48): every ISR
  ends with the one `bclr` of its in-service bit (20 cycles). maxYMiser
  instead flips to AEI and parks a dummy handler on the spurious-interrupt
  vector $60 to save those 20 cycles per interrupt — a documented option,
  not the default.
- Init: install dummy vectors; enable + unmask with `bset` (never
  whole-byte writes, which would clear Timer C's bits); all under $2700.
  YX6_stop quiesces the claim — timers stopped, bits disabled — and
  restores nothing: the machine state is the HOST's to save and hand back
  (YX6.S assumption 5; YX6_player.S is the worked example). A demo that
  already owns the machine saves nothing at all.

The ISRs follow maxYMiser's shipping forms — the PSG select+write in ONE
instruction, `move.l #$rr00vv00,$FFFF8800.w` (byte lanes: select at 8800,
data at 8802), operands patched at effect start:

```
SID:     move.l #$0800vv00,$FFFF8800.w   toggle between volume and 0 by
         stepping the vector's low word to the partner routine (the
         maxYMiser step-chain trick) - or a one-word EOR form; ~120 cycles
         per interrupt all-in, tone frequency = timer rate / 2
drum:    select volume reg, move.b (sample)+ to $FFFF8802 - the sample's
         end marker has bit 7 set, so the move itself answers "done" via
         bmi: park the volume at $D (the anti-click idiom) and stop the
         timer, clear the voice's drum-active flag; ~160 cycles/interrupt
buzzer:  move.l #$0D00ss00,$FFFF8800.w - write shape ss to r13, which
         resets the envelope: that IS the effect; ~120 cycles/interrupt
```

All-in costs include the 56-cycle ST interrupt entry, rte (20), and the SEI
`bclr`. Self-modifying operands are fine on a plain 68000 (no cache); the
non-SMC variants exist in the references if a 68020 target ever appears.

Budgets at 8 MHz (160k cycles per 50 Hz frame), from measured corpus rates:

| load | interrupts/s | CPU |
|---|---:|---:|
| SID tone 1 kHz (typical) | 2,000 | 3% |
| SID at the corpus top (3.2 kHz IRQ) | 3,200 | 5% |
| drum 6 kHz (the sweet spot) | 6,000 | 12% |
| drum 8 kHz | 8,000 | 17% |
| corpus-worst drum (25.6 kHz cap) | 25,600 | ~48% |
| sync-buzzer (83-110 Hz) | 110 | <1% |

The stream side of the player stays flat no matter what the effects do; the
worst realistic pairing (SID + 8 kHz drum) is ~20% CPU on top of the ~1.4%
player frame — comfortable for a player, worth documenting for demo hosts.

## 6. Packer changes (Java, mirrored in C#)

- `Ym6Reader`: keep the raw frames long enough to extract effect fields
  before masking; expose drums and attributes.
- `Yx6Encoder`: build E1/T1/E2/T2 with the dialect normalization and
  validity rules of section 3; pack them as streams 14-17; emit the drum
  table (converted, end-markered); bump version and stream count.
- The `-k` rules already cover the new streams (same length O, same
  divisibility); nothing changes for the user.

## 7. Verification

- **Java**: effect extraction against hand-built frames — every code, both
  slots, both dialects, the fixed-r8 YM5 drum TP, TP/TC=0 no-ops, missing
  drums, the rate cap, drum conversion both ways (8-bit and A_DRUM4BITS).
  Ninja Remix and Lightforce as regression corpora for the no-op rule.
- **Emulation rig**: Unicorn has no MFP, so the rig verifies the frame
  engine: per frame, the timer-register writes, vector installs and
  sanitized ring bytes must match a Java model of the effect stage. The
  ISRs are verified by direct invocation — call one N times, check the PSG
  write sequence, the self-stepping, the end-marker stop, the flag clear.
- **Hatari**: the real MFP. The frame engine's chip writes stay
  deterministic, so the existing checksum harness stays sound; ISR writes
  are bounded by expected tick counts from TP/TC spans rather than
  checksummed (timer phase against the VBL is genuinely asynchronous).
  Then the test that matters: Union Demo, Turrican 2 and a TAO buzzer tune
  from the corpus, on Hatari and on the real ST.

## 8. Plan

1. **Done.** Format v4 + packer + Java tests: effect streams exist, drums
   travel, dialects normalize.
2. **Done.** Player effect stage decoding E/T, timers programmed, handlers
   parked — rig-verified frame engine, no audible change.
3. **Done.** The tick handlers write the chip: the drum plays its sample
   into the voice's volume register and parks at $D, the toggle tick swaps
   its two halves through the vector, the buzzer rewrites R13; init/stop
   own and return the MFP. Verified by direct invocation in the rig, by
   the deterministic Hatari harness (an effect-inert tune), and by a real
   drum tune running whole under Hatari. The listening test on hardware
   is the one check only ears can run.
4. Measure, listen, and only then settle whether Sinus-SID (a sine table
   through the drum engine) ever earns its bytes.

Settled by the research: Timer A + Timer D for the two channels a YM tune
uses, Timer B for the third and only when a tune names it (so B stays for
rasters otherwise), SEI kept
(AEI as a host option), drums stored PSG-ready with bit-7 end markers, park
at $D, TP changes always stop+reload, invalid effects are dropped at
pack time.

Open for the adopting commit: C = 20 vs 24 (nothing vs headroom), whether
YX6_stop silences a mid-flight drum or lets it end, and whether the AEI
switch is worth a build flag from day one.
