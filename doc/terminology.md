# YM5/YM6 -> YX6

YM files hold Atari ST music: a row of sound-chip settings for every
fiftieth of a second, plus a few tricks the scene named long ago. YM5
files carry two of those tricks, digidrum and SID voice. YM6 adds sync
buzzer and sinus SID.

YX6 keeps those names in the code that reads YM files, and uses plain
digital names everywhere else, so the engine can be read without knowing
the scene. This file maps one set to the other.

A word in **bold** is a term with a precise meaning here, defined where
it first appears. Words in quotes, like "digidrum" and "effect", belong
to the YM format rather than to YX6. The new names come from digital
systems: counters, streams, rates, phases. None come from analogue
synths. There are no carriers here, no modulators and no LFOs, because
the YM2149 has none of those.

## The sound chip

The sound chip is a **YM2149**, Yamaha's version of the AY-3-8910,
running at 2 MHz on an Atari ST.

A **register** is one byte of YM2149 state, a setting the chip reads
while it works. There are sixteen. Fourteen steer the sound. The other
two are wiring the ST borrowed for its joystick and printer ports.
Writing a register is the only way software changes anything, and each
register holds its value until written again.

Those registers steer five **generators**, the parts that actually make
signal:

- three **tone generators**. Each is a counter that flips its output
  every time it runs out, making a square wave.
- one **noise generator**, a shift register producing a random-sounding
  bit pattern.
- one **envelope generator**, a counter that walks through a shape. You
  pick one of sixteen. Normally the shape is a note's rise and fall, but
  run it fast and the same sweep is heard as a pitch.

How long a generator takes per cycle is its **period**. Bigger period,
lower pitch:

    tone frequency     = 2,000,000 / (16 x tone period)
    envelope frequency = 2,000,000 / (256 x envelope period)

A tone period of 284 gives about 440 Hz. An envelope period of 18 gives
about 434 Hz, the same note from the envelope generator instead.

The YM2149 has three **voices**, A, B and C. Each has a **volume** (0 to
15, or a flag meaning "follow the envelope") and a **routing** setting,
which decides what reaches it: tone, noise, both or neither.

One register does more than hold a value, and it matters later. Writing
the envelope shape also sends the envelope generator back to the start
of that shape. Writing the same shape twice is therefore not a wasted
write. It is a restart.

## Streams

A **frame** is one call to the player. A tune is a list of frames, and
the player works through them, usually fifty a second.

A **stream** is a series of values arriving at one register, one after
another, at a steady speed. Three things make one: a **target**, the
register the values land in; a **source**, where the values come from;
and a **rate**, how often one arrives.

Sources differ. Some streams read from the packed tune, and those are
the music. One reads a stored sample. Others make values up as they go,
flipping between two numbers or walking a curve. The YM2149 cannot tell
the difference. Values arrive at a register, and it does not care who
chose them.

Not everything here is a stream. A generator runs by itself and nothing
feeds it. A register is where a stream ends. A stored sample is a source
a stream reads from, not the delivery. And the per-frame instruction
data YX6 packs beside the music, saying which effects start when, is
stored like a stream but never reaches a register.

Streams come in two kinds, and only their speed differs:

- a **frame stream** sends one value per frame. This is the music. There
  are fourteen, one for each register that steers the sound.
- a **tick stream** sends many values per frame, one per **tick**. A
  tick is one interrupt from a hardware **timer**, which the next
  section builds. This is what the YM format calls an "effect".

## Three clocks

A chiptune player only ever does one thing: **write a value to a
register**. Only the speed changes.

| clock | speed | what runs there |
|---|---|---|
| **frame clock** | usually 50 a second | frame streams. Also the **control rate**: how often the deciding code runs |
| **tick clocks** | 48 to 25,600 a second, two of them | tick streams. Also the **audio rate**: how often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second | the generators. Software cannot reach it |

    frame streams (50 a second)      tick streams (up to 25,600 a second)
                  \                          /
                   v                        v
              [ YM2149 registers  R0 - R13 ]
                            |
            +---------------+---------------+
            v               v               v
      tone counters    noise shift     envelope
         (three)         register       counter
            \               |              /
             +--------------+-------------+
                            |
                   routing (R7) picks what reaches each voice
                            |
                            v
                  voice A    voice B    voice C
                            |
             volume (R8-R10) scales it, or follows the envelope
                            |
                            v
                          output

### How fast is a frame?

Whatever the tune says. The rate is fixed for a whole tune, but tunes
differ:

- **50 a second** is usual, because the easiest place to call a player
  is the screen refresh, called the **VBL** or vertical blank, which
  comes once per drawn screen. YX6 is tested against a **corpus** of
  544 YM files, 543 of them readable, and every one runs at 50.
- **60 a second** on machines with 60 Hz screens.
- **200 a second** where a composer wants finer control: four times the
  detail in arpeggios, volume shapes and pitch slides. This one is not
  the screen but a timer, in the **MFP**. That is the ST's support chip,
  opened up in the next section. Usually it is the MFP's timer C, which
  the operating system already runs at 200.
- **Anything else** a composer chose. 25, 100 and 150 all exist. The
  rate belongs to the song rather than to the file carrying it: the
  music was written to be stepped at that speed, and stepping it at any
  other speed plays it wrong.

Faster frames cost processor time, since the whole round of writes comes
round again. Below, "per frame" always means "per player call", whatever
that speed is.

### Where a tick comes from

The timers live in the MFP, whose full name is MC68901. It has four,
named A to D. YX6 takes A and D, so two tick streams can run at once.
B and C belong to the system, driving video sync and the 200-a-second
clock the operating system counts on. A future YX6 format
allows three, and no more, because one timer has to stay with the
system.

One timer, plus whatever stream runs on it, is a **tick channel**. YX6
calls its two A and B.

The MFP has its own clock at 2,457,600 a second, separate from the
YM2149's 2 million and unrelated to it. A timer divides that twice: by a
**prescaler**, one of 4, 10, 16, 50, 64, 100 or 200, and then by a
**timer count**, 1 to 255.

    rate = 2,457,600 / (prescaler x timer count)

The timer counts down from the timer count at the divided speed, and
raises an interrupt at zero. That interrupt is the tick. (Both numbers
are just divisors. A generator's *counter*, above, is a different
thing.)

So the slowest rate is 2,457,600 / (200 x 255) = 48 a second, and the
fastest 2,457,600 / 4 = 614,400. YX6 refuses anything above 25,600: the
processor is a 68000 at 8 MHz, and at that speed the interrupt alone
eats a quarter of it. For scale, 69 tunes in the corpus play samples,
mostly between 5,000 and 6,100 a second. Only two ask for more than
25,600, and both are conversions from another machine.

### Where the two clocks meet

When the player is called, it takes the next value from each frame
stream and writes them to the YM2149 one after another. That burst of
writes is the **frame write**.

How much it writes is a choice. YX6 writes all fourteen registers every
frame, because a write to the YM2149 costs about what the comparison to
avoid it would cost, so checking what changed saves nothing. Two
registers are exceptions: the envelope shape is left alone on frames
that do not want a restart, and a voice's volume is skipped while a tick
stream owns it. Other players decide differently. A **tracker**, the
program a composer writes music in, plays its own song format and writes
only what changed, because it knows what changed. A YM file does not
carry that knowledge. It stores a full row of values every frame and
says nothing about which of them are new.

Most hard bugs live where the frame write meets a tick:

- **tearing**. A tick lands in the middle of the frame write and a
  value reaches the wrong register. Cured by turning interrupts off for
  the duration.
- **contention**. The frame write and a tick stream want the same
  register. Cured by giving the register an owner, and skipping it in
  the frame write.
- **quantisation**. Something happens between frames, but only the next
  frame can act on it. One extra frame of delay was an audible click.
- **phase**. A stream's place in its own cycle: which sample it is on,
  or which of two values. What becomes of that place when a stream stops
  and starts again is its **phase policy**. Either the cycle keeps
  running while the stream is off, which is **free-running**, or every
  start begins from the beginning, which is **zero-restart**. The
  difference is audible.

## What the old names mean

**digidrum**. Short for digital drum. A recorded sound played out through a
voice's volume register, one value per tick. Drums were the first use,
but it works the same for any sound.

**SID voice**. Named after the Commodore 64's sound chip, whose sound
it resembles. It works nothing like it: it switches a voice's volume
between loud and off very fast, chopping the tone.

**buzzer**. The envelope generator used to make a note rather than a
volume shape. A repeating shape and a short period run fast enough to be
heard as a pitch, and the result sounds buzzy. It needs no timer: two
frame streams, and a voice set to follow the envelope. A bass part made
this way is a "buzz bass".

**sync buzzer**. A buzzer whose envelope a timer keeps restarting. The
pitch then comes from the timer, and the tone colour from how far the
envelope gets before it restarts. Restarting one generator from another
is called hard sync in synths, which is where the name comes from.

**sinus SID**. A SID voice using a calculated sine instead of two
values. It is in the format, but no player has implemented it and no
tune in the corpus uses it.

## The tick streams

Three of the four are one idea under three names.

**A volume stream sends a series of volume values to one voice, one per
tick.** That is all a digidrum is, and all a SID voice is, and all a
sinus SID is. Three differences separate them:

| YM5/YM6 | YX6 | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| digidrum | **PCM stream** | a stored sample, any length | **no**, plays once and ends | **disconnected**: routed to no generator, so only the values are heard | **independent**: the pitch you want the sample at |
| SID voice | **toggle stream** | two values, loud and off | **yes**, until stopped | **left connected**: the values chop the tone | **derived** from the note playing |
| sinus SID | **curve stream** | a smooth shape, many values | **yes**, until stopped | **left connected**: the values shape the tone | **derived** from the note playing |

Read the last two rows as special cases of the first. A toggle stream is
a PCM stream whose sample is two values long and repeats. A curve stream
is one whose sample is a smooth shape and repeats. Where the values are
kept makes no difference. A curve can be worked out as it goes or read
from a table, and the sound is the same.

**Why three codes, then, and not one?** Two reasons, neither of them
about sound.

The first is cost. A general PCM stream works on every tick: hold a
pointer, read a byte, step the pointer, test for the end. A two-value
stream does none of that. It flips between two numbers it already has.
At 25,000 ticks a second on an 8 MHz 68000 that gap is most of the
machine. The format keeps them apart because the hardware must.

The second is musical. A digidrum's rate is **independent**. It is the
pitch you want a recording played at, and owes nothing to any note. Just
as well, since the voice is disconnected and there is no note.

A SID voice's rate is **derived**. Its values chop a square the voice is
still making, so the two must stay in ratio, or the tone changes with
every note. One is a sound in itself. The other is a treatment applied
to a note.

Those same words say when a rate may change. An independent rate is
**set once**, fixed at the start, since moving it would bend the sample.
A derived rate is **control-rate**: renewed by the code that runs each
frame, which is what following a melody needs.

**The fourth is not a volume stream.** It writes the envelope shape
instead, and writes the same shape every tick. Since the shape never
changes, the values say nothing. The point is the side effect from the
first section: each write sends the envelope counter back to the start.
Do that fast enough and the envelope never finishes its shape. The sweep
then repeats at the tick rate, and is heard as a pitch.

It reaches a voice indirectly. The chip has one envelope generator, and
a voice takes its loudness from it only while that voice is following
the envelope rather than holding a fixed volume. Put one voice on the
envelope and the retrigger stream sounds through it. Put two there and
both play the same note. This is the one tick stream not tied to a
single voice.

| YM5/YM6 | YX6 | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| sync buzzer | **retrigger stream** | one shape, written again and again | **yes**, until stopped | not written directly. A voice following the envelope sounds it, and more than one can | **derived**: the rate is the pitch |

"Sync stream" would have matched YM6's own word. But this system
already has three clocks, and "sync" would read as clock syncing.

### What each rate is coupled to

**Coupling** is "derived" said exactly: what a rate is set against. It is
always something else on the same voice, and the ratio between them is
what you hear. Nothing in the hardware enforces it. It is how the music
was written, and what the packer must preserve.

| stream | coupled to | why |
|---|---|---|
| **PCM stream** | nothing | its voice is disconnected, so there is nothing to be in ratio with |
| **toggle stream** | the voice's **tone period** | two square waves multiply, and their ratio is the tone colour. Hold the ratio and the sound survives a change of note, so the rate must move with every note |
| **curve stream** | the voice's **tone period** | the curve shapes the square instead of chopping it, but the ratio still decides the result |
| **retrigger stream** | the **envelope period** | the tick rate sets the pitch; the ratio decides how far into the shape the counter gets, which is the tone colour |

Coupling is why **retune** exists. A melody moves, so a derived rate
moves with it, many times a second. If every move restarted the stream,
the phase would jump on every note and you would hear it. So a rate
change on its own is a retune: new rate, same place in the cycle.

## What a stream can do

| action | meaning |
|---|---|
| **start** | begin from the beginning |
| **hold** | carry on unchanged |
| **retune** | change the rate, keep the place in the cycle |
| **release** | stop writing |
| **resume** | write again, from where it was |
| **expire** | stop because the sample ran out. Only PCM streams do this |
| **preempt** | take a register from a stream that was using it |
| **suppress** | fail to start because the register is taken, and retry next frame |

## Things that surprise people

Five rules nobody guesses, each of which has cost somebody a day:

- **An effect code is a trigger, not a change.** A YM file starts an
  effect by putting an **effect code** in a register on that frame: a
  few bits naming the kind of effect and the voice it acts on. The same
  code on two frames in a row starts the stream *twice*. It does not
  mean "still running".
  Players that act only when the code changes drop half the drums in
  some tunes.
- **Writing the envelope shape always restarts the envelope.** Even
  with the value already there. A player therefore needs a way to say "leave
  it alone" on frames that want no restart. The format uses 255.
- **A timer count of 0 means 256.** An MFP quirk, and an easy
  off-by-a-lot.
- **The same rate can be built from different number pairs.** 4 x 100
  and 16 x 25 divide to the same speed. Swapping one for the other while
  a stream runs is where phase problems come from.
- **A voice's volume register has two possible writers.** The frame
  stream once a frame, and a tick stream many times a frame. When both
  want it, the frame write stands back. That is the contention above.

## Common techniques

Most of what a composer does in a tracker needs no tick stream at all:

| technique | what it is here |
|---|---|
| digidrum kick or snare | **PCM stream**: rate set once, stops by itself, voice disconnected while it plays |
| the same sample, higher or lower | **PCM stream** at another rate; started again each frame if it must repeat |
| SID lead | **toggle stream** over the tone generator's square wave |
| SID bass | the same, slow enough that the rate needs a different prescaler: the retune case |
| buzz bass | no tick stream: **envelope period** and **shape** frame streams, voice following the envelope |
| sync buzzer | **retrigger stream** on the shape register, with the voice following the envelope. Envelope period sets the colour, tick rate the pitch |
| arpeggio | **tone period** frame stream, a new note each frame |
| vibrato, portamento | **tone period** frame stream, small steps |
| fade in or out | **volume** frame stream |
| noise drums, hi-hat | **routing** frame stream with noise on, plus a **volume** frame stream |
| a SID and a drum together | two tick channels. On one voice the PCM stream preempts and the toggle stream is suppressed until the drum ends |

A tracker effect that seems missing from the YM format is usually a
frame stream nobody named.

## The rest of the mapping

Names the YM format itself uses:

| YM5/YM6 | YX6 |
|---|---|
| R0-R5, R6, R7 | **tone period**, **noise period**, **routing** streams |
| R8-R10, R11-R12, R13 | **volume**, **envelope period**, **envelope shape** streams |
| effect; effect slot 1 and 2 | **tick stream**; **tick channel** A and B (a third is allowed) |
| TP and TC, the prescaler and timer count fields | the two halves of a **rate** |
| vmax | the **toggle stream**'s loud value |
| drum number, drum table | which stored sample a **PCM stream** plays, and where the samples live |
| player frequency | the **frame clock** |

Names from the hardware, which belong to neither format:

| Atari ST | YX6 |
|---|---|
| VBL, the vertical blank | the usual source of the **frame clock** |
| MFP timer A and D | the clocks behind **tick channels** A and B |
| the MFP's prescaler and data register | **prescaler** and **timer count** |

Names YX6 itself used before this file, and one from elsewhere:

| where it comes from | YX6 |
|---|---|
| the burst (the player's own word, still its label in `YX6.S`) | the **frame write** |
| mixer forcing (this repo's earlier phrase) | **disconnecting** a voice |
| M, A1, P1, A2, P2 (YX6's stream letters) | control, command and parameter **script data** |
| the ym2149-rs and maxYMiser gap models | **zero-restart** and **free-running** **phase policy** |
| SNDH's TC50 and TC200 tags | the **frame clock** a tune asks for |

## The names in the code

The engine's identifiers use these words, so a term here can be grepped
for:

| term | in the code |
|---|---|
| the four kinds | `KIND_PCM`, `KIND_TOGGLE`, `KIND_RETRIGGER`, `KIND_CURVE` |
| what a stream is told to do | `VERB_START_PCM`, `VERB_START_TOGGLE`, `VERB_START_RETRIGGER`, `VERB_START_PCM_PREEMPT`, `VERB_HOLD`, `VERB_RETUNE`, `VERB_RELEASE`, `VERB_RESUME` |
| the player's tick handlers | `yx6_pcmA`, `yx6_toggleA_on`, `yx6_toggleA_off`, `yx6_retriggerA`, and their Timer D twins |
| the actions the script runs | `yx6_pcm_start`, `yx6_pcm_preempt`, `yx6_toggle_start`, `yx6_retrigger_start`, `yx6_retune`, `yx6_resume`, `yx6_hold`, `yx6_release` |

`Ym6Reader` and `YmEffects` keep the YM format's names on their input
side, for the reason given above: those are the names of the bytes.

The rename was verified as a change of words only. Every one of the 543
packable corpus tunes packs byte-for-byte identically, the player binary
is unchanged in both the plain and `-perf` builds, the SNDH container is
unchanged, the Java suite and the emulation rig pass, and the Hatari
hardware harness returns its documented checksum at the same tick count.

## If you know these ideas from elsewhere

Nothing here is new outside the Atari world:

| here | known elsewhere as |
|---|---|
| a register holding its value until written again | a **zero-order hold** |
| **frame clock**, **tick clocks** | **control rate** and **audio rate**; Csound and SuperCollider call them k-rate and a-rate, and add an init tier matching a **set once** rate |
| **PCM stream** | **sample playback**, as a sampler does it |
| **toggle** and **curve streams** | **wavetable** oscillators: one short cycle repeated. A toggle stream is the smallest wavetable there is, two entries wide |
| **derived** rate | **key follow**, or key tracking |
| **independent** rate | a parameter that ignores the note, like a sampler's playback pitch |
| **coupling** | the **harmonicity ratio** of FM synthesis, where a ratio decides the tone and the absolute pitches do not |
| **retrigger stream** | **hard sync** |
| **free-running** phase | the same words a synth uses for an LFO that never restarts |
| **tearing**, **contention** | **clock domain crossing** hazards |
| the noise generator | an **LFSR**, a linear-feedback shift register |

The one idea with no name elsewhere is **phase policy**. Synths have
free-running and retriggered LFOs, which is the same question. No
chiptune player writes down an answer.

## Quick reference

**The sound chip**

| term | meaning |
|---|---|
| **YM2149** | the sound chip: Yamaha's AY-3-8910, at 2 MHz on an ST |
| **MFP** | the MC68901 support chip, where the timers live |
| **register** | one byte of YM2149 state. Sixteen exist; fourteen steer the sound |
| **generator** | a part of the YM2149 that makes signal by itself |
| **tone generator** | a counter flipping its output, making a square wave. Three of them |
| **noise generator** | a shift register making a random-sounding bit pattern |
| **envelope generator** | a counter walking one of sixteen shapes. Run fast, heard as a pitch |
| **voice** | one of the three outputs, A, B and C |
| **volume** | a voice's loudness, 0 to 15, or a flag meaning "follow the envelope" |
| **routing** | which generators reach a voice: tone, noise, both or neither |
| **period** | how long a generator takes per cycle. Bigger period, lower pitch |
| **timer** | MFP hardware counting down to an interrupt. Four exist; YX6 uses two |
| **prescaler, timer count** | the two divisors that set a tick rate |

**The model**

| term | meaning |
|---|---|
| **frame** | one call to the player |
| **VBL** | the screen refresh, the usual thing a player is called from |
| **stream** | values arriving at one register at a steady speed. Has a target, a source and a rate |
| **target, source** | the register values land in; where they come from |
| **frame stream** | one value per frame. The music |
| **tick stream** | many values per frame, one per tick. The YM format's "effect" |
| **tick** | one timer interrupt, and one register write |
| **rate** | how often a tick stream writes. Stored as prescaler and timer count |
| **set once** | the rate is fixed when the stream starts |
| **control-rate** | the rate is renewed each frame |
| **independent rate** | answers to nothing else: a sample's playback pitch |
| **derived rate** | set against the note playing, so it moves with the melody |
| **coupling** | what exactly a derived rate is set against. The ratio is what you hear |
| **frame clock, control rate** | 50 a second usually. How often the deciding code runs |
| **tick clock, audio rate** | 48 to 25,600 a second. How often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second. Runs the generators; software never sees it |
| **tick channel** | one timer plus the stream on it. Two in use, A and B; three allowed |
| **volume stream** | a tick stream writing a voice's volume. Three of the four are one |
| **phase** | where a stream is inside its own cycle |
| **phase policy** | what happens to phase across a stop: free-running, or zero-restart |
| **disconnect** | route no generator to a voice, leaving only its volume writes |
| **frame write** | the once-a-frame round of register writes |
| **corpus** | the 544 YM files YX6 is tested against; 543 readable |
| **script data** | per-frame instructions saying which streams start when. Stored like a stream, never written to a register |

**The tick streams**

| term | meaning |
|---|---|
| **PCM stream** | a stored sample, played once, voice disconnected, rate independent. Was: digidrum |
| **toggle stream** | a PCM stream of two values, repeating, voice connected. Cheap to run. Was: SID voice |
| **curve stream** | a PCM stream of a smooth shape, repeating, voice connected. Was: sinus SID; never implemented |
| **retrigger stream** | not a volume stream: one shape written over and over, each write restarting the envelope. Was: sync buzzer |

**What a stream can do**

| term | meaning |
|---|---|
| **start** | begin from the beginning |
| **hold** | carry on unchanged |
| **retune** | change the rate, keep the place in the cycle |
| **release** | stop writing |
| **resume** | write again, from where it was |
| **expire** | stop because the sample ran out. Only PCM streams do this |
| **preempt** | take a register from a stream that was using it |
| **suppress** | fail to start because the register is taken, and retry next frame |

**When the two clocks collide**

| term | meaning |
|---|---|
| **tearing** | a tick interrupts the frame write; a value lands in the wrong register |
| **contention** | frame write and tick stream want the same register |
| **quantisation** | something happens between frames; only the next frame can act |
