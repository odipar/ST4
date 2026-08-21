# YM5/YM6 -> YX6

YM files hold **chiptunes**: a row of sound-chip settings for every
frame, plus a few tricks the scene invented long ago. A chiptune is
music made by steering a sound chip directly, register by register,
rather than by playing back recorded audio - which is what separates
these files from a tracker module or a sample disk.

The format was written for the Atari ST and its YM2149, and that is what
this file describes, but YM files exist for other machines with the same
chip family, the ZX Spectrum 128 and Amstrad CPC among them.

It grew by versions. The early ones - YM2, YM3, YM3b - are bare register
dumps: fourteen bytes a frame and nothing else, no title, no loop, no
effects. **YM5** added a header (title, author, comment, loop frame, chip
clock, player rate) and the stored samples the digidrum needs, and widened
the frame to sixteen bytes so two of them could carry effect fields.
**YM6** kept that layout and spent the spare codes in those fields on two
more effects, sync buzzer and sinus SID. Both come from Arnaud Carré's
ST-Sound, the reference player, which is why the format's own names are
that player's names - and why a file carries `LeOnArD!` as its check
string. Distributed `.ym` files are usually LHA-compressed, so a reader
unpacks before it parses. YX6 reads YM5 and YM6; the bare dumps have no
effects to compile and are not supported.

YM5 files carry two tricks, digidrum and SID voice. YM6 adds sync buzzer
and sinus SID.

**YX6** is this repository's own format and player: YM6's ideas, packed
as streams and played out of a compressed container. The 6 is YM6's; the
X marks the departure. It keeps the YM names in the code that reads YM
files, and uses plain digital names everywhere else, so the engine can be
read without knowing the scene. This file maps one set to the other.

A word in **bold** is a term with a precise meaning here, defined where
it first appears. Words in quotes, like "digidrum" and "effect", belong
to the YM format rather than to YX6. The new names come from digital
systems: counters, streams, rates, phases. None come from analogue
synths. There are no carriers here, no modulators and no LFOs, because
the YM2149 has none of those.

Throughout, a piece of music is a **tune**. "Song" is a tracker's word
for its own file and appears only in that sense.

## The sound chip

The sound chip is a **YM2149**, Yamaha's version of the AY-3-8910,
running at 2 MHz on an Atari ST.

A **register** is one byte of YM2149 state, a setting the chip reads
while it works. There are sixteen. Fourteen steer the sound. The other
two are peripheral I/O ports the ST borrowed for other duties - drive
select and the printer's data lines among them - and have nothing to do
with sound. Writing a register is the only way software changes
anything, and each register holds its value until written again.

| register | bits | what it holds |
|---|---|---|
| R0, R1 | 8 + 4 | voice A **tone period**, fine and coarse: one 12-bit number |
| R2, R3 | 8 + 4 | voice B tone period |
| R4, R5 | 8 + 4 | voice C tone period |
| R6 | 5 | **noise period** |
| R7 | 8 | the **mixer**: which generators reach which voice, plus the two I/O port directions |
| R8, R9, R10 | 5 | voice A, B, C **volume**: four bits of level, and bit 4 meaning "follow the envelope" |
| R11, R12 | 8 + 8 | **envelope period**, fine and coarse: one 16-bit number |
| R13 | 4 | **envelope shape** |
| R14, R15 | 8 | the two I/O ports. Not sound |

A **signal** is a series of values with a rate: a square wave, a run of
noise, a sample. Every sound the chip makes is a signal.

Five **generators** make signals, each of its own kind:

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
    noise clock        = 2,000,000 / (16 x noise period)
    envelope frequency = 2,000,000 / (256 x envelope period)

A tone period of 284 gives about 440 Hz. An envelope period of 18 gives
about 434 Hz, the same note from the envelope generator instead.

The **noise period** sets how fast the shift register is clocked, so it
sets how bright the noise is rather than how loud. Five bits, 1 to
31. At 1 it is a wide hiss, a cymbal or the top of a snare. Around 15 it
is a coarser rush that sits under a drum. At 31 it is slow enough to
take on a pitch of its own. There is one noise generator, so all three
the same noise reaches all three voices, and only its volume can differ
between them.

**Envelope shapes** are four bits, and only four of the sixteen repeat:
two sawtooths, ramping down and up, and two triangles, down-then-up and
up-then-down. The other twelve run once and hold a level, which makes
them fine for a note's decay and useless as an oscillator. Anything that
treats the envelope as a pitch uses one of the four.

**The envelope's pitch resolution is coarse, and it gets worse as the
pitch rises.** The divisor is 256, so neighbouring envelope periods are
far apart in frequency: period 18 is 434 Hz, period 17 is 460 Hz -
nearly a semitone between two adjacent settings. Low notes land close
enough, a lead does not, and that is the real limit on a buzzer part. A
sync buzzer escapes it, because its pitch comes from a timer rather than
from the envelope period, and the timer's steps stay fine where the
envelope's do not.

The YM2149 has three **voices**, A, B and C. Each has a **volume** and a
**mixing** setting - which generator signals reach it: tone, noise, both
or neither. The volume scales whatever arrives. "Follow the envelope" is
a real bit rather than a manner of speaking: bit 4 of the voice's volume
register. With it set, the four level bits are ignored and the envelope
generator supplies the level.

### What the volume actually is

The output stage is a **DAC** whose ladder of levels is close to
logarithmic, not linear: roughly 3 dB a step, so each step is about a
factor of 1.4 in amplitude. A volume register picks one of 16 steps; the
envelope generator walks 32. The bottom of the ladder is irregular,
which is why players carry a measured table rather than a formula -
`YmEffects.CURVE` is this one's.

That matters most for **samples**. A recording is a series of linear
amplitudes and the register takes an index into a logarithmic ladder, so
the conversion is not a shift. Anything that filters or resamples has to
work on the amplitudes and convert afterwards: filtering the indices
filters the logarithm of the signal, which is a different signal. Four
bits also means a sample arrives with about 24 dB of range and the
quantisation to match, so material prepared with its peaks near the top
of the ladder keeps the most detail.

### The write that does something

Writing the envelope shape sends the envelope generator back to the
start of that shape. Writing the same shape twice is therefore not a
wasted write. It is a restart - and that restart is the whole mechanism
behind the sync buzzer further down.

It also means a format needs a way to say "leave the shape alone" on
frames that must not restart it, since a row of register values would
otherwise rewrite it fifty times a second. YM stores 255 for that.

## Streams

A **frame** is one call to the player. A tune is a list of frames,
stepped at the tune's own rate.

A **stream** is a series of values arriving at one register, one after
another, at a steady speed. Three things make one: a **target**, the
register the values land in; a **source**, where the values come from;
and a **rate**, how often one arrives. A period is one number split over
two registers, so its stream targets that pair.

Sources differ - the packed tune, a stored sample, two numbers flipped
between, a waveform computed as it goes (which the format allows and no
corpus tune uses) - and none of it shows in the write. What arrives at a
register is a byte, and the sound is the same whatever produced it.

Not everything here is a stream. A generator has no input. A register is
where a stream ends. A stored sample is a source, not the delivery. And
the per-frame instruction data YX6 packs beside the music, naming which
effects start when, is stored like a stream but never reaches a
register.

A **tick** is one step of a stream's clock, and one register write.
Every stream is ticked; the two kinds differ in whose clock does it:

- a **frame stream** is ticked by the player's own call: one value per
  frame. This is the music - fourteen streams, one per sound register,
  all on that one clock.
- a **timer stream** is ticked by a timer claimed for it alone, many
  times per frame. This is what the YM format calls an "effect".

That is not software against hardware: a frame clock is often a timer
too, and at 200 a second always is. The difference is that the frame
clock steps the whole player at once, and a timer steps one stream.

A **timer channel** is one place a timer stream can run: a claimable
clock, and the stream on it. A tune names the channels it uses, and says
which timer each one runs on; the next section is where those timers
come from.

(Trackers use "effect" for the per-row commands a composer types, a
portamento or a volume slide. Where both readings are possible, this
file says **timer stream** and leaves "effect" in quotes.)

## Three clocks

Three time domains, and everything in the system belongs to one of them:

| clock | speed | what runs there |
|---|---|---|
| **frame clock** | the tune's own rate, below | frame streams. Also the **control rate**: how often the deciding code runs |
| **timer clocks** | 48 to 25,600 a second | timer streams. Also the **audio rate**: how often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second | the generators. Software cannot reach it |

    frame streams (frame clock)     timer streams (timer clocks)
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
                    mixing (R7) picks what reaches each voice
                            |
                            v
                  voice A    voice B    voice C
                            |
            volume (R8-R10) scales that signal, or follows the envelope
                            |
                            v
                          output

### How fast is a frame?

It is a property of the tune, fixed for the whole of it, and tunes
differ:

- **50 a second** is usual, because the easiest place to call a player
  is the screen refresh, called the **VBL** or vertical blank, which
  comes once per drawn screen. That is the PAL and SECAM rate. YX6 is
  tested against a **corpus** of 544 YM files, 543 of them readable, and
  every one of those runs at 50.
- **60 a second** on NTSC machines, whose screens refresh that often.
- **200 a second** for finer control: four times the detail in
  arpeggios, volume shapes and pitch slides. This one is not the screen
  but a timer, in the **MFP**. That is the ST's support chip, opened up
  in the next section. Usually it is the MFP's timer C, which the
  operating system already runs at 200.
- **Anything else** a composer set. 25, 100 and 150 all exist. The rate
  belongs to the tune rather than to the file carrying it: the music was
  written to be stepped at that speed, and stepping it at any other
  speed plays it wrong.

Faster frames cost processor time, since the whole round of writes comes
round again. Below, "per frame" always means "per player call", whatever
that speed is.

Note what is *not* here: a tempo in beats. Tempo lives in the tracker,
which turns it into rows and rows into frames. What reaches a YM file,
and a player, is the frame rate and a row of register values per frame.

### Where a timer tick comes from

The timers are in the MFP, whose full name is MC68901. It has four,
named A to D. Timer C belongs to the operating system, which runs it at
200 a second and reads it as the system clock, so it stays where it is.

YX6 has four timer channels, numbered 0 to 3, and **the file says which
timer each one runs on** - a stream of its own carries the map, two bits
per channel. A player claims a timer only for a channel a tune names, so
a tune that uses two leaves the other two timers alone.

All four MFP timers are reachable, Timer C included, and that one costs
more than the others: it is the operating system's 200 Hz clock, so a
tune that names it stops the system clock while it plays and cannot be
hosted from a Timer C hook. A YM file never names it, or any third
channel, because a YM frame can start at most two effects.

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
fastest 2,457,600 / 4 = 614,400. YX6 rejects anything above 25,600: the
processor is a 68000 at 8 MHz, and at that speed the interrupt alone
occupies a quarter of it. For scale, 69 tunes in the corpus play
samples, mostly between 5,000 and 6,100 a second.

This is also where the sync buzzer gets its accuracy. At 440 Hz a timer
lands within a few cents of the note, where the envelope period is most
of a semitone out: the timer divides a faster clock by a wider choice of
numbers, so its steps stay fine exactly where the envelope's stop being
so.

### Conflicts between the two clocks

When the player is called, it takes the next value from each frame
stream and writes them to the YM2149 one after another. That burst of
writes is the **frame write**.

How much it writes is a choice. YX6 writes all fourteen registers every
frame, because a write to the YM2149 costs about what the comparison to
avoid it would cost, so checking what changed saves nothing. Two
registers are exceptions: the envelope shape is left alone on frames
where a restart would be wrong, and a voice's volume is skipped while a
timer stream holds it. Other players are built differently. A **tracker**,
the program a composer writes music in, plays its own format and writes
only the registers that changed, because that format records which they
are. A YM file carries no such record. It stores a full row of values
every frame, with nothing to mark which of them are new.

Most hard bugs come from a tick landing during a frame write, or from
both writing the same register:

- **tearing**. A tick lands in the middle of the frame write and a
  value reaches the wrong register. Prevented by turning interrupts off
  for the duration.
- **contention**. The frame write and a timer stream target the same
  register. Prevented by giving the register an owner, and skipping it
  in the frame write.
- **quantisation**. Something happens between frames, but only the next
  frame can act on it.
- **phase**. A stream's place in its own cycle: which sample it is on,
  or which of two values. What becomes of that place when a stream stops
  and starts again is its **phase policy**. Either the cycle keeps
  running while the stream is off, which is **free-running**, or every
  start begins from the beginning, which is **zero-restart**. The
  difference is audible.

## What the old names mean

A YM file starts an effect by putting an **effect code** in a register
on that frame: a few bits naming the kind of effect and the voice it
acts on. **An effect code is a trigger, not a change.** The same code on
two frames in a row starts the stream *twice*; it does not mean "still
running".

**digidrum**. Short for digital drum. A recorded sound played out
through a voice's volume register, one value per tick. The name fits the
practice: these are nearly always unpitched sounds - drums, percussion
hits - played at one rate and not transposed. Nothing in the mechanism
requires that; a source is free to play a sample at any rate it likes.

**SID voice**. Named after the Commodore 64's SID chip, not for how it
works but for one sound that chip is known for: pulse-width modulation,
a square wave whose duty cycle moves. Here that character comes from
switching a voice's volume between a set level and off, very fast,
chopping the signal already coming out of the voice.

**buzzer**. The envelope generator used to make a note rather than a
volume shape. A repeating shape and a short period run fast enough to be
heard as a pitch, and the result sounds buzzy. It needs no timer: two
frame streams, and a voice set to follow the envelope. A bass part made
this way is a "hard bass". Its weakness is the envelope's coarse pitch
resolution, which is why hard bass is a bass and rarely anything higher.

**sync buzzer**. A buzzer whose envelope a timer keeps restarting. The
pitch then comes from the timer, and the tone colour from how far the
envelope gets before it restarts. Because the pitch is the timer's, it
holds its tuning where a plain buzzer drifts. Restarting one generator
from another is called hard sync in synths, which is where the name
comes from.

**sinus SID**. A SID voice using a calculated sine instead of two
values. It is in the format; no tune in the corpus uses it, and YX6
drops it at pack time.

## The timer streams

Three of the four kinds are the same idea with different sources.

**A volume stream sends a series of volume values to one voice, one per
tick.** That is all a digidrum is, and all a SID voice is, and all a
sinus SID is. Three things separate them:

| YM5/YM6 | YX6 | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| digidrum | **PCM stream** | a stored sample, any length | **no**, plays once and ends | **disconnected**: no generator signal reaches it, so the stream's own values are all that is left | **independent** by default: the sample's own playback pitch |
| SID voice | **toggle stream** | two values, a set level and off | **yes**, until stopped | **left connected**: the values chop its signal | **derived** from the note playing |
| sinus SID | **wave stream** | a stored or computed waveform | **yes**, until stopped | **left connected**: the values shape its signal | **derived** from the note playing |

Read the last two rows as special cases of the first. A toggle stream is
a PCM stream whose sample is two values long and repeats. A wave stream
is one whose sample is a waveform and repeats. Where the values are kept
makes no difference: a waveform can be computed as it goes or read from
a table, and the sound is the same.

**Why three codes, then, and not one?** Two reasons, neither about
sound.

**Cost.** A general PCM stream does four things every tick: hold a
pointer, read a byte, step the pointer, test for the end. A two-value
stream flips between two numbers it already has - one instruction
against roughly four on a 68000. At 25,000 ticks a second that
difference is tens of percent of the whole machine, not a rounding.

**Rate.** A digidrum's is **independent**: the pitch a recording plays
back at, unrelated to any note - and there is no note anyway, because
the voice is disconnected. A SID voice's is **derived**: its values
scale the signal the voice still makes, so the two must stay in ratio or
the tone changes with every note. One is a sound in itself. The other is
a treatment applied to a note.

That difference is a default, not a rule. YM6 stores one rate per
trigger, so a digidrum in a YM file plays at a fixed rate; nothing in
this model forbids a source that moves a sample's rate under a melody,
which is how a sampler plays a tune. The same freedom runs the other
way: a derived rate is the usual choice for a chopped voice, not a
requirement of one.

The split does say when a rate is *allowed* to change in a given format.
Independent means **set once**, fixed at the start. Derived means
**control-rate**, renewed each frame, which is what following a melody
needs.

**The fourth kind is not a volume stream.** It writes the envelope shape
instead, and writes the same shape every tick. Since the shape never
changes, the values say nothing. The point is the side effect from the
first section: each write sends the envelope counter back to the start.
Do that fast enough and the envelope never finishes its shape. The sweep
then repeats at the tick rate, and is heard as a pitch.

It reaches a voice indirectly. The chip has one envelope generator, and
a voice takes its loudness from it only while that voice is following
the envelope rather than holding a fixed volume. Put one voice on the
envelope and the retrigger stream sounds through it. Put two there and
both carry it - which real tunes do: 15 of the corpus's 543 tunes have
more than one voice on the envelope at some point. This is the one tick
stream not tied to a single voice.

| YM5/YM6 | YX6 | the series | repeats? | the voice | rate |
|---|---|---|---|---|---|
| sync buzzer | **retrigger stream** | one shape, written again and again | **yes**, until stopped | not written directly. A voice following the envelope sounds it, and more than one can | **derived**: the rate is the pitch |

"Sync stream" would have matched YM6's own word. But this system
already has three clocks, and "sync" would read as clock syncing.

### What each rate is coupled to

**Coupling** is "derived" said exactly: what a rate is set against. It is
always something else on the same voice, and the ratio between them is
what you hear. Nothing in the hardware enforces it. It is how the music
was written, and what the **packer** - the tool that turns a YM file into
a YX6 file - has to preserve.

| stream | coupled to | why |
|---|---|---|
| **PCM stream** | nothing, in a YM tune | its voice is disconnected, so there is nothing on that voice to be in ratio with. A source that transposes its samples couples it to the note, and then it is derived like the rest |
| **toggle stream** | the voice's **tone period** | two square signals multiply, and their ratio is the tone colour. Hold the ratio and the tone colour is unchanged from note to note, so the rate must move with every note |
| **wave stream** | the voice's **tone period** | the waveform shapes the signal instead of chopping it, but the ratio still sets the result |
| **retrigger stream** | the **envelope period** | the tick rate sets the pitch; the ratio fixes how far into the shape the counter gets, which is the tone colour |

Coupling is why **retune** exists. A melody moves, so a derived rate
moves with it, many times a second. If every move restarted the stream,
the phase would jump on every note and you would hear it. So a rate
change on its own is a retune: new rate, same place in the cycle.

The same rate can be built from different pairs of divisors - 4 x 100
and 16 x 25 divide alike - and a melody's rate eventually leaves one
prescaler's range into the next. That is the retune that is easiest to
get wrong: re-arming the stream instead of retuning it loses its place
in the cycle, at exactly the moment a listener is following a line.

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

## Common techniques

Most of what a composer does in a tracker needs no timer stream at all:

| technique | what it is here |
|---|---|
| digidrum kick or snare | **PCM stream**: rate set once, stops by itself, voice disconnected while it plays |
| SID lead | **toggle stream** over the tone generator's square wave |
| SID bass | the same, slow enough that the rate needs a different prescaler: the retune case |
| hard bass | no timer stream: **envelope period** and **shape** frame streams, voice following the envelope |
| sync buzzer | **retrigger stream** on the shape register, with the voice following the envelope. Envelope period sets the colour, tick rate the pitch |
| arpeggio | **tone period** frame stream, a new note each frame |
| vibrato, portamento | **tone period** frame stream, small steps |
| noise drums, hi-hat | **mixing** frame stream with noise on, plus a **volume** frame stream |
| a SID and a drum together | two timer channels. On one voice the PCM stream preempts and the toggle stream is suppressed until the drum ends |

A tracker effect that seems missing from the YM format is usually a
frame stream nobody named.

## The rest of the mapping

The YM format names bytes and fields; YX6 names what those bytes do. The
registers hold bytes, and the stream is the reading this engine puts on a
series of writes:

| YM5/YM6 | YX6 |
|---|---|
| R0-R5, R6, R7 | **tone period**, **noise period**, **mixing** streams |
| R8-R10, R11-R12, R13 | **volume**, **envelope period**, **envelope shape** streams |
| effect; effect slot 1 and 2 | **timer stream**; **timer channels** 0 and 1 (four exist) |
| TP and TC, the prescaler and timer count fields | the two halves of a **rate** |
| vmax | the **toggle stream**'s set level |
| drum number, drum table | which stored sample a **PCM stream** plays, and where the samples are kept |
| player frequency | the **frame clock** |

Names from the hardware, which belong to neither format:

| Atari ST | YX6 |
|---|---|
| VBL, the vertical blank | the usual source of the **frame clock** |
| MFP timers A, B, C and D | what the file's map puts behind **timer channels** 0 to 3 |
| the MFP's prescaler and data register | **prescaler** and **timer count** |

## The names in the code

The engine's identifiers use these words, so a term here can be grepped
for:

| term | in the code |
|---|---|
| the four kinds | `KIND_PCM`, `KIND_TOGGLE`, `KIND_RETRIGGER`, `KIND_CURVE` - the **wave stream**, still under its earlier name |
| the actions a stream can be given | `VERB_START_PCM`, `VERB_START_TOGGLE`, `VERB_START_RETRIGGER`, `VERB_START_PCM_PREEMPT`, `VERB_HOLD`, `VERB_RETUNE`, `VERB_RELEASE`, `VERB_RESUME` |
| the player's tick handlers | `yx6_pcm_a`, `yx6_toggle_a_on`, `yx6_toggle_a_off`, `yx6_retrigger_a`, and the same per timer: they belong to the timer, not the channel |
| the timers, and the map onto them | `yx6_timer_a` to `yx6_timer_d`, `yx6_desc_0` to `yx6_desc_3`, `yx6_assign` |
| the actions the script runs | `yx6_pcm`, `yx6_pcm_preempt`, `yx6_toggle_start`, `yx6_retrigger_start`, `yx6_retune`, `yx6_resume`, `yx6_hold`, `yx6_release` |
| the frame write | `yx6_wA`, `yx6_w7`, `yx6_wB` |
| the mixer | `YX6_MIXER` |

`Ym6Reader` and `YmEffects` keep the YM format's names on their input
side, for the reason given above: those are the names of the bytes.

## If you know these ideas from elsewhere

| here | known elsewhere as |
|---|---|
| **frame clock**, **timer clocks** | **control rate** and **audio rate**; k-rate and a-rate in Csound and SuperCollider |
| **PCM stream** | **sample playback**, as a sampler does it |
| **toggle** and **wave streams** | **wavetable** oscillators: one short cycle repeated. A toggle stream is the smallest wavetable there is, two entries wide |
| **derived** rate | **key follow**, or key tracking |
| **coupling** | the **harmonicity ratio** of FM synthesis, where a ratio sets the tone and the absolute pitches do not |
| **retrigger stream** | **hard sync** |
| the noise generator | an **LFSR**, a linear-feedback shift register |

The one idea without a settled name elsewhere is **phase policy**. Synths
have free-running and retriggered LFOs, which is the same question.
Chiptune players are rarely explicit about it, though each is consistent
in what it does - which is why two players can disagree audibly on the
same file and both be self-consistent.

## Where this is going

The model above covers what YM5 and YM6 can express. It is deliberately
wider than that in two places, because the next formats will want the
room:

- **a PCM stream whose rate moves**, so a sample can follow a melody
  instead of playing at one pitch.
- **a PCM stream that loops**, so a sample can be an instrument's
  sustain rather than a one-shot. Together with a moving rate that is a
  wavetable in the ordinary sense of the word. It needs a tick handler
  that can loop and scale a volume - most likely with the scaling
  precomputed, since a multiply per tick is not affordable at audio
  rates on a 68000.

Neither needs anything in this file to change. Both are a source and a
rate policy, which is what the stream model is made of.

## Quick reference

**The sound chip**

| term | meaning |
|---|---|
| **YM2149** | the sound chip: Yamaha's AY-3-8910, at 2 MHz on an ST |
| **MFP** | the MC68901 support chip, which holds the timers |
| **register** | one byte of YM2149 state. Sixteen exist; fourteen steer the sound |
| **generator** | a part of the YM2149 that makes a signal by itself |
| **tone generator** | a counter flipping its output, making a square wave. Three of them |
| **noise generator** | a shift register making a random-sounding bit pattern. One, shared by all three voices |
| **envelope generator** | a counter walking one of sixteen shapes, four of which repeat. Run fast, heard as a pitch |
| **voice** | one of the three outputs, A, B and C |
| **volume** | a voice's level: four bits, or bit 4 set meaning "follow the envelope" |
| **mixing** | which generators reach a voice: tone, noise, both or neither |
| **period** | how long a generator takes per cycle. Bigger period, lower pitch |
| **DAC** | the output ladder: about 3 dB a step, 16 steps from a volume register, 32 from the envelope |
| **timer** | MFP hardware counting down to an interrupt. Four exist, and a tune may name any of them, one per channel it uses |
| **prescaler, timer count** | the two divisors that set a tick rate |

**The model**

| term | meaning |
|---|---|
| **frame** | one call to the player |
| **VBL** | the screen refresh, the usual thing a player is called from |
| **signal** | a series of values with a rate |
| **stream** | values arriving at one register at a steady speed. Has a target, a source and a rate |
| **target, source** | the register values land in; where they come from |
| **frame stream** | ticked by the player's own call, one value per frame. The music |
| **timer stream** | ticked by a timer of its own, many values per frame. The YM format's "effect" |
| **effect code** | the YM file's way of starting one: a trigger on that frame, never "still running" |
| **tick** | one step of a stream's clock, and one register write. Every stream is ticked |
| **rate** | how often a timer stream writes. Stored as prescaler and timer count |
| **set once** | the rate is fixed when the stream starts |
| **control-rate** | the rate is renewed each frame |
| **independent rate** | set by nothing else: a sample's playback pitch |
| **derived rate** | set against the note playing, so it moves with the melody |
| **coupling** | what exactly a derived rate is set against. The ratio is what you hear |
| **frame clock, control rate** | the rate the tune is stepped at. How often the deciding code runs |
| **timer clock, audio rate** | 48 to 25,600 a second. How often a sound-shaping write lands |
| **YM2149 clock** | 2,000,000 a second. Runs the generators; software has no access to it |
| **timer channel** | one place a timer stream can run, numbered 0 to 3. The file says which of the machine's timers each gets |
| **volume stream** | a timer stream writing a voice's volume. Three of the four kinds are one |
| **phase** | where a stream is inside its own cycle |
| **phase policy** | what happens to phase across a stop: free-running, or zero-restart |
| **disconnect** | mix no generator into a voice, leaving only its volume writes |
| **frame write** | the once-a-frame round of register writes |
| **packer** | the tool that turns a YM file into a YX6 file |
| **tracker** | the program a composer writes music in, with its own file format |
| **corpus** | the 544 YM files YX6 is tested against; 543 readable |
| **script data** | per-frame instructions saying which streams start when. Stored like a stream, never written to a register |

**The timer streams**

| term | meaning |
|---|---|
| **PCM stream** | a stored sample, played once, voice disconnected, rate independent. Was: digidrum |
| **toggle stream** | a PCM stream of two values, repeating, voice connected. Cheap to run. Was: SID voice |
| **wave stream** | a PCM stream of a waveform, repeating, voice connected. Was: sinus SID |
| **retrigger stream** | not a volume stream: one shape written over and over, each write restarting the envelope. Was: sync buzzer |

**What a stream can do**

start, hold, retune, release, resume, expire, preempt, suppress. The
section of that name above defines each one.

**Conflicts between the two clocks**

| term | meaning |
|---|---|
| **tearing** | a tick interrupts the frame write; a value lands in the wrong register |
| **contention** | frame write and timer stream target the same register |
| **quantisation** | something happens between frames; only the next frame can act |
