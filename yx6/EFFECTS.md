# YX6 effects: the design

How the YM6 special effects — SID-Voice, DigiDrum, Sync-Buzzer, Sinus-SID —
become part of the streaming player. Streams first, then the file format, the
timer engine, and what it costs. Every fact below is source-verified: the
effect semantics against ST-Sound (the format author's reference player,
github.com/arnaud-carre/StSound @ d1876bc), the 68000 practice against gwEm's
shipping maxYMiser replayer, Hatari's MFP/PSG models, EmuTOS and the ST
hardware register docs, and the numbers against a survey of 516 real YM files.

## The model these four share

[doc/terminology.md](../doc/terminology.md) holds the full vocabulary, the
table that separates the four effects and a two-way mapping to the YM
names; this is the orientation, and no more.

Sound arrives at three rates: the **frame clock**, one call to the player,
usually 50 a second; the **timer clocks**, one MFP timer interrupt each
and up to four at once, 48 to 25,600 a second; and the YM2149's own 2 MHz,
which software never reaches. A series of values arriving at one register
is a **stream**, so the fourteen register streams are **frame streams**
and an effect is a **timer stream**.

Three of the four effects are one idea: **a volume stream sends a series
of volume values to one voice, one per tick**, which is all a DigiDrum, a
SID-Voice or a Sinus-SID is. They differ in the series, in whether it
repeats, in whether the voice's own generators are left running
underneath, and in where the rate comes from — a drum's answers to nothing
(there is no note; the voice is disconnected) while a SID's is derived
from the note it chops and must stay in ratio with it, which is why
**retune** exists in section 4. terminology.md carries that table and the
two reasons the format keeps a two-value stream apart from a general one;
the cost half of it is why section 5's tick handlers are separate blocks
rather than one.

Sync-Buzzer is the odd one. It writes the envelope shape register, the
same shape every tick, and the values say nothing — what matters is the
side effect, since writing that register sends the envelope generator
back to the start of its shape. It also reaches a voice indirectly: there
is one envelope generator, and a voice takes its loudness from it only
while following the envelope, so a buzzer is the one effect not tied to a
single voice.

A stream's **phase** is its place in its own cycle and its **phase
policy** is what becomes of that place across a gap — terminology.md
defines both. Which policy a pack uses is the packer's `-sidresume` flag,
and the reasoning behind it is in
[the phase-semantics experiment](../doc/experiments/2026-08-20-sid-phase-semantics.md)
and its companion case study.

## 0. Where the compiled script moved the machinery

Two numberings run through this document: **v1** and **v2** name player
generations — the interpreter and the script replayer — while every bare
**v4** to **v10** names the file format, which is at 10. Section 3's **v3**
is a format too, the header v4 extends — the repo numbers players past v2
elsewhere, so a bare v3 here is still the file.

Format v5 replaced the E/T streams and the player's interpreting effect
stage with the **compiled effect script**: `EffectScript.java` replays every
rule in this document over the whole timeline at pack time and emits
streams of prepared actions — M (what acts this frame; zero almost always),
X (the operand an action byte has no room for) and, per timer channel, an
A/P pair (its action byte and timer count). Format v7 made that four
channels and added T, the stream that says which MFP timer each channel
runs on; a YM frame starts at most two effects, so a YM tune uses two and
the others' streams pack to nothing. The player's
handlers copy prepared values into the timers and the tick handlers'
operands and compare nothing; the voice disconnection of section 4
arrives baked into the R7 stream, the ring is never edited (the
borrow/restore of section 4 has no counterpart in the v2 player), and a
drum's gate reopens at the frame boundary after its computed end instead of
mid-frame at the marker. A loop whose
wrap state differs from its first arrival has its split rotated until the
two match, so action streams replay correctly every time round.

Two later revisions widened what the script can say, both for the .ymr front
end. Format **v9** carries the shape a retrigger stream restarts in X, so the
player looks for it nowhere (section 2 below has the corpus this settles).
Format **v10** adds a loop word to each sample table entry: the PCM tick that
meets the end marker moves the loop address into its own operand instead of
stopping the timer, `$FFFF` meaning one that stops. Neither costs a front end
more than an array it fills — `Tune.shapes` and `Tune.sampleLoops` — and both
front ends fill them unconditionally.

v10 also gives RETUNE a live form. The action byte's voice field holds three
voices in two bits, so 3 names none, and RETUNE addressed to it writes the
control register and then the data register with the timer left running. That
is what RhYMe's own player does for a rate pop, so a prescaler slide keeps both
the square's phase and its place inside the period in flight. Naming no
voice, it repatches no parameter, so the compiler emits it only where the
parameter stood still and falls back to the ordinary retune where it moved —
and it is the one thing here a source has to claim, through a flag in
`EffectScript.Semantics`.

Sections 1–4 are the YM source's semantics — the set
`EffectScript.Semantics.YM` names — and they are now the specification of the
pack-time simulator rather than of a run-time interpreter. The compiler
applies whichever set the front end hands it: a source that triggers by
event, leaves R7 to the song, ends its own samples or retunes live takes the
other branches. Their frame contract is what `EffectScriptTest`, the rig's
directed effect test and the corpus sweep's independent Python model all
assert. Section 5's implementation notes describe the v1 interpreter where
they touch the frame side; the timer register map, the tick handlers and the
budgets still read true.

## 1. The stream concept

> The E1/T1/E2/T2 streams below — section 1's stream table, and the rules of
> sections 3 and 4 — are the **v4** shape, where each slot had a control
> stream and a timer-count stream. They stay because those rules are still
> the specification the pack-time simulator implements. Section 0 says what
> the file carries instead, and [FORMAT.md](FORMAT.md) has the v10 table.
> Where these sections say "the player decides", read "the packer resolved
> it, and wrote the answer down".


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
C = 16 to at least 18, still dividing N (C = 20 with N = 1000, or C = 24 with
N = 960 — an ST4 container set of 24 streams is already measured at 2,340
cycles/frame at k = 2, so the budget is known to hold).

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

One nibble for the whole frame is what the chip has, and one is all the
format carries — section 2 has the corpus that settled that arity.

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
  25.6 kHz outright; the packer drops SIDs and buzzers above it, but rescues
  drums by resampling them to the highest MFP rate under it and scaling every
  trigger's divisor to match (`-drumhz` moves the drum ceiling).
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
  between them. R13's own stream and the voice's nibble DISAGREE on a real
  tune: on 621 of `jamblv1`'s 972 buzzer frames the two resolutions part
  company, and the nibble is the one ST-Sound arrives at. The other two agree
  with themselves throughout and settle nothing, which is worth saying — two
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

## 3. The file: yx6 format v4 (superseded; the container is [v10](FORMAT.md))

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
  no such drum, or a SID or buzzer rate above 25.6 kHz → E written as 0
  (with a pack-time warning). A drum above the ceiling (`-drumhz`, 25,600 by
  default) is rescued instead: the sample is resampled to the highest
  MFP-representable rate under the ceiling and every trigger's divisor scaled
  by the same exact ratio, so only a scaled divisor that no prescaler/count
  pair represents still goes to 0. The player never receives a code it cannot
  run. This also sidesteps
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
  slot-2 events); out of scope for the first release. YM3/YM3b have no
  effects; YM4 is dead ("No more YM4! support" - ST-Sound).

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
                                stop + reload + start - except v10's live
                                retune, section 0, which moves the prescaler
                                under a running timer without letting the
                                timer's nibble pass through zero)
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
the hard way - a rig test packed a pattern that a later match copied across
a sanitized byte's position, and failed if the byte was not returned; the
trap survives, repurposed, now that nothing edits the ring.)
Idle cost: with the stage inlined
into YX6_play and each slot gated on E | E-last, an idle frame pays two ring
pops and two zero tests, ~140 cycles measured from the tables; a running
drum adds the r7 borrow. One channel machine serves them all, aimed by a
descriptor - timer registers, the claim, the channel's tick-handler block -
so the timers' only remaining difference is data. The tick
handlers sign off with an immediate byte write to the in-service register
(it ignores written ones), and a host that never runs user-mode code can
build with YX6_SUPER_HOST=1 to park a0 in the USP for the PCM tick of the
channel on Timer D - the lowest-priority timer, so nothing nests into its
parked window - 16 cycles under the stack. Every other channel's tick still
uses the stack, and the park is one-way for the USP itself, so a host
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
programmed read-modify-write. The TP value goes into the timer's own
nibble of the control register — shifted up four for Timer C, the other
nibble kept, and masked to three bits, so a timer always runs in delay
mode — and T to the data register.

The packer's default map puts channel 0 on Timer A and channel 1 on
Timer D — maxYMiser's proven allocation, and what v6 wired in — so a YM
tune sounds as it always did and leaves B and C alone. **Timer B stays
free for raster code** unless a tune names it, and **Timer C stops TOS's
200 Hz clock** if a tune names that, which also rules out a host that
calls PLAY from a Timer C hook. Timer D's only casualty is RS232 baud.

Facts the engine leans on, all verified:

- The whole MFP interrupts at 68000 level 6, so effect ISRs never nest and
  a PSG select/write pair inside an ISR is atomic against the other timers
  by construction. (The player then drops to level 5 after the pair, on
  purpose, so a higher-priority timer's tick need not wait a whole tick —
  the MFP serves its A bank first, which orders the four: Timer A, Timer B,
  Timer C, Timer D.) The frame burst runs at $2700 by default
  (`YX6_MASK_BURST`, which the tools' `-nomask` turns off), though the mask
  is not what keeps it safe — every burst write is a single `movep.w`, and a
  tick's own PSG pair cannot be split because it runs at level 6 — and
  EmuTOS guards its own PSG
  port-A access the same way; no new races exist anywhere.
- TOS runs the MFP in software end-of-interrupt mode (VR = $48): every ISR
  signs off with one immediate byte written to its in-service register — a
  mask with a zero only in that timer's bit, $DF/$FE/$DF/$EF, since the
  in-service registers ignore written ones — 16 cycles. maxYMiser
  instead flips to AEI and parks a dummy handler on the spurious-interrupt
  vector $60 to save those 16 cycles per interrupt — a documented option,
  not the default.
- Init: install dummy vectors; enable + unmask with `bset` (never
  whole-byte writes, which would clear Timer C's bits); all under $2700.
  YX6_stop quiesces the claim — timers stopped, bits disabled — and
  restores nothing: the machine state is the HOST's to save and hand back
  (YX6.S assumption 5; YX6_player.S is the worked example). A demo that
  already owns the machine saves nothing at all.

The ISRs follow maxYMiser's shipping forms — the PSG select+write in ONE
instruction, `move.l #$rr00vv00,$FFFF8800.w` (byte lanes: select at 8800,
data at 8802), operands patched at effect start - bar the toggle's
partner vector, which init writes once:

```
SID:     move.l #$0800vv00,$FFFF8800.w   toggle between volume and 0 by
         writing the partner half's WHOLE vector - a move.l into $134/$120/
         $114/$110 - so the blob may straddle a 64K boundary; ~120 cycles
         per interrupt all-in, tone frequency = timer rate / 2
drum:    select volume reg, move.b (sample)+ to $FFFF8802 - the sample's
         end marker has bit 7 set, so the move itself answers "done" via
         bmi: from v10 the tick takes the entry's loop address when there is
         one, and otherwise parks the volume at $D (the anti-click idiom)
         and stops the timer; ~160 cycles/interrupt
buzzer:  move.l #$0D00ss00,$FFFF8800.w - write shape ss to r13, which
         resets the envelope: that IS the effect; ~120 cycles/interrupt
```

All-in costs include the 56-cycle ST interrupt entry, rte (20), and the
SEI's 16-cycle in-service write. Self-modifying operands are fine on a plain
68000 (no cache); the non-SMC variants exist in the references if a 68020
target ever appears.

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
worst realistic pairing (SID + 8 kHz drum) is ~20% CPU on top of the ~1.1%
the player frame measures on the Hatari harness — comfortable for a player,
worth documenting for demo hosts.

## 6. Where this is verified

The Java packer's tests, the Unicorn rig and the Hatari harness divide the
work. Unicorn has no MFP, so the rig checks the frame engine — the
timer-register writes, the vector installs and the gate and mixer state,
frame by frame against the compiled script — and drives each tick handler by
direct invocation; the ring is only checked for carrying its bytes through
unedited, since nothing edits it any more. Hatari has the real MFP, and
reads all fourteen registers back off the emulated chip.
[README.md](README.md)'s Tests section says what each one covers and how to
run it.
