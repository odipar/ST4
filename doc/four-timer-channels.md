# Four timer channels, assigned by the file

> **Shipped, in format v7.** What this note designs is the format as
> built, and the format has gone on since: v8, v9 and v10 all sit on top
> of it. See [../yx6/FORMAT.md](../yx6/FORMAT.md) for the container as it
> stands. The note stays as the record of the decision, and says what was
> known when the four channels were settled.

Format v6 has three timer channels and a fixed map: channel 1 on Timer A,
2 on Timer D, 3 on Timer B, written into the player. v7 makes it four
channels, numbered 0 to 3, and moves the map into the file - a stream of
its own says which of the MFP's four timers each channel runs on.

## What changes

| | v6 | v7 |
|---|---|---|
| channels | 3, numbered 1-3 | **4, numbered 0-3** |
| channel -> timer | fixed in the player | **a stream in the file** |
| timers reachable | A, D, B | **A, B, C, D** |
| streams | 22 | **25**: R0-R13, M, X, T, then four A/P pairs |
| header flags | bit 1+c, three channels | bit 1+c, four channels |
| M byte | bits 0-2 channels, 3 gate flag, 6-4 mask | bits 0-3 channels, 4 gate flag, 7-5 mask |

## The assignment stream

**T**, one byte per frame, two bits per channel:

    bits 1-0   channel 0's timer
    bits 3-2   channel 1's timer
    bits 5-4   channel 2's timer
    bits 7-6   channel 3's timer

The value in each field: 0 = Timer A, 1 = B, 2 = C, 3 = D.

One byte covers all four. A tune that never re-assigns emits the same
byte every frame, which the event optimizer packs to nothing - the cost
of the whole mechanism is one ring and about 28 bytes in the file.

The player reads T every frame and acts only when it changes, because a
stream is the natural place for something the packer may want to vary and
the comparison is two instructions. **Two channels may not name the same
timer in the same frame.** The packer enforces it; the player trusts it.

Re-assigning a running stream restarts its phase: the new timer starts
counting from its own reload. That is a real edit to the sound, not a
free move. No source re-assigns mid-tune yet - each front end picks one
map and the packer fills T with it for the whole tune. The rule is what a
packer that did vary it would have to obey: move a channel only between
streams.

## Why the handler blocks move to the timers

A tick handler bakes its timer into the code: the end-of-interrupt value
and register (five sites), the control register (one), and the vector the
toggle halves write to each other (two). With the map fixed, the
YX6_TICKS macro filled those in per channel.

Make the map data and there are two ways to keep the handlers correct:

1. **Patch the block when an assignment changes** - eight sites per
   block, self-modified. The player already self-modifies, but this is
   eight more places to get wrong, and it happens while a timer may be
   live.
2. **Give the blocks to the timers instead of the channels.** Four
   blocks, one per MFP timer, each with its own EOI, control register
   and vector baked in exactly as now. A channel's descriptor takes a
   copy of the row of the timer it was assigned - eighteen bytes, the
   handler block's address among them. Assignment is a copy, never a
   patch.

v7 takes the second. Nothing in the interrupt path changes, no patching
happens at assignment time, and the cost is one extra handler block:
four instead of three, 134 bytes.

It also settles the naming. Blocks are named after their timer -
`yx6_pcm_a`, `yx6_toggle_d_on` - and channels are numbers, which is what
the format speaks. The two never collide.

## Timer C is not free

The other three timers cost the host something a demo can plan around.
Timer C is different: it is the operating system's 200 Hz clock. A tune
that names Timer C:

- stops the system clock while it plays, so `$4BA` and anything TOS
  schedules off the 200 Hz tick stand still;
- **cannot be hosted from a Timer C hook**, because the player has taken
  the interrupt the host would call from.

The player claims it when the file names it, on the same terms as the
others - assumption 5 puts machine state with the host. The SNDH wrapper
saves Timer A and Timer D state alone - both vectors, TACR, TCDCR's
Timer D nibble, TADR, TDDR and the four enable/mask bits - so a file
whose T stream names Timer B or C has nothing saved for it at INIT and
gets nothing back at EXIT. No YM tune claims Timer C: a YM frame starts
at most two effects, so the channel the default map puts there is never
named.

## The programming difference C brings

The four timers are not programmed alike. Timers A and B have a control
register of their own, low nibble. Timers C and D **share** TCDCR at
$FFFFFA1D: C in the high nibble, D in the low. The player's idiom for
starting and stopping a timer is

    andi.b  #$F0,(ctrl)      ; stop, keeping the other timer's nibble
    or.b    d0,(ctrl)        ; run at the prescaler in d0

which is only right for a low nibble. Timer C needs `#$0F` and the
prescaler shifted up four. So a timer's row carries, besides its
registers, the **keep mask** and the **shift** its nibble needs, and
every place that stops or starts a timer takes its mask - and its shift,
where it needs one - from the row. The ISR's own stop is inside the
block, which is per timer, so it bakes its mask in for free.

## Cost

Measured after the build, not estimated:

| item | v6 | v7 |
|---|---|---|
| handler blocks | 3 x 134 | **4 x 134** |
| descriptors | 3 x 16 | 4 timer rows of 18, 4 channel rows of 20 |
| player total | 2,372 | **2,786** |
| streams | 22 | 25 |
| workspace at N=960 | 22,580 | **25,654** |
| harness, 1700 frames | 88 ticks | **93** - one more stream to decode on a tune that names no channel |
| smallest usable C | 22 | 25 for a four-channel tune, **21 for a YM tune** |

That last row looked like the one with teeth: 25 streams would need
C >= 25, and the default 960/24 shape does not survive it - 960 is not a
multiple of 25.

It does survive, because the rule was stated too strongly. What the
round-robin needs is that every stream the player **decodes** is refilled
once per C frames, and since v6 the player decodes only up to the last
channel a tune names. A tune with no effects decodes 17 streams, a YM
tune 21, a four-channel tune 25. So the packer's check becomes C >= the
live count rather than C >= 25, and 960/24 keeps working for every tune
that does not use all four channels. A tune that does use all four needs
C >= 25 and a ring to match - 1000/25, say.

## What the build turned up

**An indexed displacement is a signed byte.** `YH_LOOP_TABLE` is
`34 + 4 x S`, which at 22 streams was 122 and at 25 is 134 - and
`move.l YH_LOOP_TABLE(a0,d0.w),d0` encodes that displacement in eight
signed bits. 134 became -122, and the player read the header 122 bytes
*below* the file. rmac said nothing. The loop restart now does the
arithmetic on the base register instead, and the lesson generalises: any
`d8(An,Dn)` whose displacement is derived from the stream count is one
format bump away from silently reading somewhere else.

**Two names that had to move.** `yx6_desc_1..3` became `yx6_desc_0..3`,
and the numbering shift meant every reference had to be re-checked, not
renamed - `YX6_stop` walked from `yx6_desc_1`, which after the shift was
the *second* channel, so a stop released three of four claims and left
one timer enabled. The rig caught it.

## What stays

The claim is still per channel, and a channel a tune does not name costs
nothing: no timer taken, and its two streams past the live end of the
refill list. `YX6_init` is still idempotent. The host still quiets the
machine; the player still claims only what the file names.
