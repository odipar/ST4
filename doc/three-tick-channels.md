# A third timer channel: the design space

> **Superseded.** What this note designed shipped in format v6 - Design 3
> with the operand stream - and was then widened: v7 has four channels and
> the file carries the channel-to-timer map itself. See
> [four-timer-channels.md](four-timer-channels.md). The note stays because
> the four designs it weighs, and the measurements under them, are what
> the later one builds on. The numbers in the middle of it are the
> pre-build estimates, kept as written; the last section records what was
> actually built.

Two timer channels ran on MFP timers A and D. This is what a third cost,
and four ways of arranging it.

## What it is for

No YM file needs it. A YM6 frame can start at most two effects, so at
most two timer streams ever run at once, and every tune in the 544-file
corpus fits in two channels with room to spare. A third channel is for
sources the packer does not read yet: a tracker format with three
independent effects, or a tune traced from an original replay that used
more timers than YM6 can express.

That matters for the design. The cost of a third channel must fall on
files that use it, not on every tune.

## The four hard constraints

**There is only one timer left, and it is not free.** The MFP has four.
YX6 takes A and D. Timer C is the operating system's 200 Hz clock, and
SNDH hosts commonly call PLAY from it, so taking C breaks the host that
is running the player. That leaves **Timer B**, which YX6 deliberately
left alone: it is the one timer a demo needs for raster work, and
EFFECTS.md records leaving it free as a choice, not an accident. A
three-channel tune therefore cannot coexist with raster code.

**The verb space is full.** Verbs are three bits and all eight are
used: RESUME, HOLD, RELEASE, START_TOGGLE, RETUNE, START_RETRIGGER,
START_PCM, START_PCM_PREEMPT. There is no free encoding for a "bind this
channel to that timer" command without restructuring the action byte.

**The action byte is full too.** Three bits of verb, two of voice, three
of prescaler. Nothing in it can name a timer or a victim.

**Preemption assumes exactly two channels.** `CH_OTHER_CTRL` in each
descriptor is "the partner channel's control register", and
START_PCM_PREEMPT stops that timer before starting its own sample. With
two channels the partner is implied. With three it is a choice, and one
bit has to say which. This is the real format cost, larger than the
streams or the player bytes.

The M byte, by contrast, has room. Today bits 0 and 1 say which channel
acts, bit 2 flags a gate change and bits 5 to 3 carry the mask. Three
channels re-lay it as bits 0 to 2 for the channels, bit 3 for the flag
and bits 6 to 4 for the mask, with bit 7 still spare.

## What it costs, measured

| item | now | with a third channel |
|---|---|---|
| tick handler block | 134 bytes each, twice | three times: **+134 bytes** |
| channel descriptor | 16 bytes each, twice | **+16 bytes** |
| player total | 2,418 bytes | about 2,570, **+6%** |
| streams in the file | 19 | 21, or 22 if preemption needs an operand stream |
| workspace at N=960 | 18,240 bytes of ring | 20,160, **+1,920** |
| smallest usable C | 19 | 21, so C=24 still works and C=20 stops working |

The 2,418 is the estimate's own base, and no build of the v5 player
reproduces it: `rmac +o3` assembles that player to 2,126 bytes as it
ships and 2,430 with the raster monitor on. The outcome table below
measures against 2,126, so read the row as +150 bytes rather than as a
size.

The two extra streams are nearly free in the file itself. They carry one
byte per frame and are almost always the same byte, which the event
optimizer packs to nothing. The ring RAM is the real per-tune cost, and
it is paid whether the channel is used or not.

## Four designs

### 1. Three channels, always

Channels 0, 1, 2 map to timers A, D, B. One format, one player, no
choices at run time.

Simple, and wrong for the reason above: every tune pays 1.9 KB of ring
and every host loses Timer B, to support something no YM file uses.

### 2. Three channels, conditional assembly

`YX6_CHANNELS equ 2` builds exactly today's player, byte for byte;
`equ 3` adds the third. The format's stream count already lives in the
header, and the player already rejects a file whose count does not match
its build.

Costs nothing when off, which is the appeal. But it splits the format in
two: a `.yx6` file is now either a 19-stream or a 21-stream thing, and
the two players cannot read each other's files. Every tool that reads a
header has to care.

### 3. Three channels in the player, claimed per tune

One player, always carrying three descriptors and three handler blocks
(+150 bytes). One format, always 21 streams. The header says how many
channels the tune actually uses, and `YX6_init` claims only those
timers, in order: a two-channel tune claims A and D and leaves Timer B
to the host, exactly as today.

The ring cost is paid by every tune. Sizing the workspace from the
header's channel count is not free. `YX6_STREAMS` is a compile-time
constant, and the loop table's header offset, the workspace layout and
the refill loop's bound all come from it - three constants that would
become init-time arithmetic. It is a later saving, not part of this.

This is one format, one player, and the host keeps Timer B whenever the
tune does not need it. The cost is 150 bytes of player carried by
everyone.

### 4. The script names the timer

Each action carries the timer it wants, so channels are logical and the
binding is per frame.

This does not survive contact with the constraints. There are no bits
for it in the action byte, no free verb to add a bind command, and the
mapping cannot usefully change while a stream runs anyway, because
moving a stream to another timer restarts its phase. Rejected, but worth
writing down. The packer sees the whole timeline, so it can decide the
mapping when it compiles the script - and deciding it while the tune
plays buys nothing.

## Preemption, whichever design wins

Three channels need one bit to say which timer a preempting sample
stops. Three ways:

- **An operand stream.** A twenty-second stream carrying a byte per
  frame, read only by verbs that need one. Nearly free in the file,
  general enough for the next thing that needs an operand, and it costs
  one more ring.
- **Split the verb.** Make START_PCM_PREEMPT two verbs, one per victim,
  which needs a ninth verb the encoding does not have.
- **Order the channels.** The player processes channels in index order,
  so a preemptor placed at a higher index than its victim can rely on
  the victim's own RELEASE being processed first, and the preempt verb
  disappears. The packer cannot always arrange that, since a running
  stream already sits on a fixed channel, so this works only as an
  optimization on top of one of the others.

The operand stream is the one to build.

## Recommendation

**Design 3, with an operand stream**, and the third channel bound to
Timer B.

Design 2 is the fallback if 150 bytes ever matters more than format
unity, and it can be reached from Design 3 later without changing the
file.

## Decided

**Timer B is claimed, not requested.** The player already takes timers A
and D without asking, and assumption 5 puts every machine-state decision
with the host. A tune whose header names three channels claims Timer
B on the same terms. What changes is that this is now worth saying out
loud: a three-channel tune cannot run under a host that wants raster
code.

**There is no negotiation, because the player has no way to hear an
answer.** A build that does not support three channels rejects the file
through the stream-count check it already performs. A host that cannot
spare Timer B must not play a three-channel tune, and nothing in the
format can enforce that.

**The workspace is 22 rings, always.** The variable-count saving above is
a later change, and it can be made without touching the file.

## What was built, and what it measured

**A timer channel is the format's concept; which of the machine's timers
serves it is the player's.** The file never names a timer. It names the
channels a tune uses, one flag bit each, and `YX6_init` claims a timer for
each named channel from a table of three descriptors - channel 1 on Timer
A, 2 on Timer D, 3 on Timer B. Every channel is described the same way,
claimed by the same loop, handed back by the same loop, and served by
handler blocks generated from one macro invoked three times. Nothing
outside `yx6_desc_1` to `yx6_desc_3` knows which timer is which.

Stating the claim per channel rather than "the third one is optional"
turned out to pay twice: a tune with no effects at all names no channel,
and the player then claims no timer and skips six streams.

**Preemption lost its partner.** `CH_OTHER_CTRL` - "the other channel's
control register" - was the last place where a channel was defined by
reference to another. It is gone. The operand stream X carries a mask of
the channels a preempting sample must stop, and the handler walks the
descriptor table stopping them. That is the recommendation of the section
above, built as written.

**The streams are ordered so an unused channel is free.** M, X, then each
channel's A and P pair, channels last. The player decodes up to the last
channel the header names and stops: sixteen streams for a tune with no
effects, twenty for a YM tune, twenty-two for a three-channel one. The
file always carries all twenty-two - the two unused ones pack to 28 bytes
each - and the workspace is always sized for twenty-two rings, as decided.

| item | v5 | v6 | note |
|---|---|---|---|
| player bytes | 2,126 | 2,372 | +246, not the +150 estimated: the claim, hand-back and link loops cost another 96 bytes, about two-thirds of a handler block again |
| streams in the file | 19 | 22 | M, X, and three A/P pairs |
| streams decoded, YM tune | 19 | 20 | the third channel's pair is never touched |
| streams decoded, no effects | 19 | 16 | new: an idle channel costs nothing |
| harness tune, 1700 frames | 94 ticks | 88 ticks | that tune's script is inert, so it names no channel |
| workspace at N=960 | 19,506 | 22,580 | +3,074, paid by every tune |
| smallest usable C | 19 | 22 | C=24 still works |

The harness number is not a like-for-like comparison and should not be
read as a speedup for real tunes: it fell because that tune stopped
decoding four streams it never used. A tune that uses two timer channels
decodes one stream more than v5 did, about 2% more refill work.

### What the corpus actually needs

Compiling the script for all 543 packable tunes in the jatari collection
and counting the channels each one drives:

| channels used | tunes | streams the player decodes |
|---:|---:|---:|
| 0 | 460 | 16 |
| 1 | 12 | 18 |
| 2 | 71 | 20 |
| 3 | 0 | - |

So the ordering pays for itself on 85% of the corpus, which uses no timer
channel at all and now decodes three streams fewer than v5 did. Nothing
in the corpus reaches the third channel, as expected: a YM frame starts
at most two effects.

The same census counted **zero START_PCM_PREEMPT verbs in the whole
corpus** - and the pre-v6 packer emits zero as well, so this is not a
regression but a fact about the material: a drum landing on a voice
another channel is holding is a synthetic-test scene, not a real one.
The operand stream X is therefore untested by the corpus and covered
only by the rig's directed effect test, which is worth remembering if it
ever grows a second reader.

### The differential that made the change safe

The compiled script for every one of the 543 tunes was digested twice -
once by this build, once by the previous release built in a git worktree
- with the new M byte normalised back to the v5 layout. **Every tune's
digest matches**: the channel bits, both channels' action and count
streams, the R7 mixer force, the frame count and the loop split are
byte-identical. v6 moves the layout and adds to it; it changes no
decision the packer makes about a YM tune.

### Two sharp edges, found and closed

Claiming per channel means an unclaimed channel is left alone - and on a
second `YX6_init` without a stop, "left alone" would have included a
timer the *first* init claimed and nobody released. It would have kept
ticking with the player's vector on it. `YX6_init` now hands every
channel back before it claims, sharing one `yx6_handback` routine with
`YX6_stop`, so init is idempotent. The rig grew a test that inits a
two-channel tune, plays into it, then inits an effect-free tune into the
same blob and checks that no timer is left running; it fails without the
fix.

The second edge was audible. Taking Timers A and D unconditionally had
been **quieting the machine** as a side effect; claiming per channel ends
that, and a tune that names no channel now leaves TOS's own timers
running. The fix belongs to the host, not the player - `YX6_player.S`
saves and stops all four MFP timers at takeover and restores them at
exit - and the story is in
[experiments/2026-08-21-the-timers-left-running.md](experiments/2026-08-21-the-timers-left-running.md).
