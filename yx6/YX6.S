; -----------------------------------------------------------------------------
; YX6.S - streaming YM chiptune player for the plain 68000 (Atari ST)
;
; By Robbert van Dalen. Plays a .yx6 file: twenty-five streams - fourteen
; frame streams, one per sound register, and eleven carrying the compiled
; effect script - every one packed as its own embedded ST4 container and decoded
; through its own small ring by ST4_wrap.S. Written by Claude (Anthropic's
; Claude Code) under Robbert's direction, based on ZX1 v1.5 by Einar Saukas
; and inspired by Steve Clarets' MinYMiser. Use it freely, including
; commercially, as long as your documentation says you used ZX1 through
; ST4 (see LICENSE).
;
; It plays the fourteen standard sound registers, loops, and plays what the
; YM format calls its special effects - digidrums, SID voices, the
; sync-buzzer - on THREE TIMER CHANNELS. A timer channel is the format's
; idea; WHICH of the MFP's timers serves it is the FILE's: stream T maps
; the four channels onto the MFP's four timers, two bits each, and
; yx6_timers holds one row per timer for the map to point at. A timer is
; claimed only when a tune says it uses that channel. In this player's
; own words a channel's streams are
; TIMER STREAMS: series of values written to one register between frames,
; timer-driven, as against the FRAME STREAMS that carry the music itself.
; Sinus-SID, which no player has ever implemented, is dropped at pack time.
; EFFECTS.md holds the design and doc/terminology.md the vocabulary.
;
; REQUIRES ST4_wrap.S, built for the SAME unit size the tune was packed with
; (yx6 -kK; byte units unless the packer was told otherwise). Include both, in
; either order, with the unit size defined first:
;
;       ST4_UNIT    equ     1
;               include "YX6.S"
;               include "ST4_wrap.S"
;
; YX6 calls ST4_init and ST4_resume by label, and YX6_init checks every
; section's ST4 signature against this build's unit size - a tune packed for
; a different one is rejected, not garbled.
;
; -----------------------------------------------------------------------------
; THE DESIGN, in one paragraph. A tune is O frames long. Each of the
; twenty-five streams gets its own ring of N bytes and its own saved decoder
; state, and is decoded C values at a time (C = 24 by default). On every VBL the player
; uses one value from each of the rings and refills exactly one
; stream - stream k on the VBL where the frame number modulo C is k. With
; C at least the number of streams a tune DECODES - seventeen when it names
; no channel, twenty-one for a YM tune - every one is refilled once per C
; frames, one refill per VBL, so
; the work per frame is flat, and ordered for the chip's benefit: the burst
; gates, then the fourteen register writes - the FRAME WRITE, leaving the
; VBL at a FIXED offset whatever the frame's timer streams cost - then the
; script's actions, then one ST4_resume of C bytes. Since C divides N and a
; call never reaches the ring end, ST4_wrap.S is the decoder that fits: the
; player counts the calls itself and wraps the write pointer when it lands
; on the ring end.
;
; THE SCRIPT. The last five rings are not frame streams: their bytes never
; reach a register. They carry the COMPILED EFFECT SCRIPT, in which the
; packer replayed the reference player's whole decision logic over the
; timeline - held or new, retune or restart, who stops whose timer, when a
; sample's gate reopens - and wrote the outcomes down as prepared actions.
; Ring M says what acts this frame (almost always: nothing, one test); each
; channel's A/P pair carries its action byte and timer count, and ring X
; the operand an action byte has no room for.
; The handlers copy prepared values into the claimed timers and this blob's
; tick handlers - digidrum, SID square, sync-buzzer - and compare NOTHING
; at run time. A sample's voice is DISCONNECTED by mixing baked into the
; R7 stream, so its sample is heard alone, and no ring byte is ever edited:
; what v1 discovered per frame, the file simply says.
;
; LOOPING. A packed stream can only be restarted from its beginning, so the packer
; splits every register at the loop frame L: an intro stream for frames [0, L)
; and a loop stream for [L, O). When a register's section runs out mid-refill
; the player starts its loop stream over - a fresh decoder writing on into the
; same ring - so the rings always hold one continuous sequence of values and
; the read side never learns that anything happened. Nothing requires L to fall
; on a group boundary: a refill that straddles the split simply decodes two
; pieces. A tune that loops from frame 0 has no intro streams at all.
; The script adds one twist: when the effect state arriving from the intro
; differs from the state arriving from the wrap, the packer ROTATES the
; split forward to a frame where the two agree - the file then carries a
; few frames twice, compiled differently, and O and L in the header count
; this played timeline. The player never knows; the rotation is inaudible.
;
; -----------------------------------------------------------------------------
; USING IT
;
;       lea     song,a0                 ; the .yx6 file, loaded anywhere
;       lea     workspace,a1            ; even address, YX6_SIZE bytes
;       bsr     YX6_init                ; d0 = 0 when the file was accepted
;       tst.l   d0
;       bne     bad_file
;   vbl:                                ; once per frame, in supervisor mode
;       lea     workspace,a0
;       bsr     YX6_play                ; d0 = 0 played, 1 wrapped, -1 ended
;       ...
;       lea     workspace,a0
;       bsr     YX6_stop                ; chip quiet, timers stopped
;
; The workspace holds the player's own fields, one decoder state per stream
; and one ring per stream, so its size depends on the ring size in the file.
; It is always sized for all twenty-five, whatever the tune uses:
;
;   YX6_SIZE = YX6_FIXED + 25 * N       ; 1654 + 25 * N bytes
;
; For the default 960-byte rings that is 25654 bytes. A program that knows N
; at build time can reserve it with
;
;       ds.b    YX6_FIXED+(YX6_STREAMS*960)
;
; where the parentheses matter: rmac evaluates expressions left to right.
;
; CALLS AND REGISTERS
;
;   YX6_init   in   a0 = .yx6 file, at an even address, a1 = workspace
;              out  d0 = 0 accepted, -1 not a v7 yx6 file with 25 streams
;                        packed at this build's unit size
;              Claims a timer for each timer channel the header names:
;              stops it, parks its vector, drops stale pendings, enables
;              and unmasks it. It saves NOTHING - the machine state is the
;              host's, see assumption 5. WHICH timer a channel gets comes
;              from stream T, not from this file: a YM tune names channels
;              0 and 1 and maps them to Timers A and D, leaving B for
;              rasters and C for the system, but a tune may name any of
;              the four. Pair init with a stop.
;              A channel the tune does not name is left ALONE, which means
;              a timer the host had running keeps running: claiming used
;              to quiet the machine as a side effect and no longer does.
;              A host that wants a quiet machine stops the timers itself
;              before init - YX6_player.S is the worked example
;   YX6_play   in   a0 = workspace
;              out  d0 = 0 played a frame
;                   d0 = 1 played a frame, and that frame ended the tune: the
;                          next one is the loop frame L
;                   d0 = -1 the tune is over and nothing was written, which
;                          only a tune packed with -o can report
;   YX6_stop   in   a0 = workspace
;              Quiesces: stops the claimed timers, disables their interrupts,
;              drops pendings, silences the three voices. Restores nothing
;   CLOBBERED  d0-d5, a0-a5. d6, d7 and a6 survive, as with ST4 - whose
;              decoder state spans a4 and a5, which is why they no longer do.
;
; YX6_play writes to the sound chip at $FFFF8800, so it must run in supervisor
; mode - which a VBL handler already does. Each of the fourteen writes is
; a single movep.w, which a 68000 cannot split, so the burst needs no
; interrupt mask to be correct - it takes one anyway by default, and
; YX6_MASK_BURST equ 0 (the tools' -nomask) drops it so ticks interleave
; with the frame write instead of waiting behind it. Its
; effect stage programs the timers YX6_init claimed, vectors included.
; Beyond the chip, those timers and the workspace, it writes only ITSELF: it is
; self-modifying code. The channel descriptors, the prepared tables and the
; tick handlers' operands live in this blob, init patches the burst's ring
; displacements and the run-time addresses into it, and the script's
; handlers keep patching as effects come and go. Two consequences: the
; player must run from RAM, and
; one loaded copy is one player - two workspaces cannot share it. Every
; self-reference is pc-relative or patched at run time, so the blob loads
; at any even address.
;
; ASSUMPTIONS (unchecked beyond the magic, version and stream count):
;
;   1. the file comes from the yx6 packer, which enforces C >= the streams
;      a tune decodes, N mod C = 0,
;      N >= 2C, N <= 2520 - the burst reads each register's ring through an
;      assembled-in 16-bit displacement of k*N - offsets that stay inside the
;      ring, and - at ST4_UNIT above 1 - a tune length, loop frame and C that
;      are whole units, so every budget this player passes to the decoder
;      divides cleanly
;   2. the workspace is at an even address and large enough for the file's N
;   3. YX6_play is called once per frame and never after it returns -1.
;      Calling it again is harmless - it returns -1 and writes nothing - which
;      is what keeps ST4_wrap's "never call the decoder again" rule intact
;   4. a looping tune plays for as long as you keep calling: d0 = 1 marks each
;      time round, and YX6_init is only needed to start again from frame 0
;   5. the HOST owns the machine state - the player saves and restores none
;      of it. A host that wants the machine back afterwards saves, around
;      init..stop, exactly what the player touches: the vectors $134 and
;      $110, TACR, TADR, TDDR, TCDCR's low (Timer D) nibble, bit 5 of IERA
;      and IMRA, bit 4 of IERB and IMRB - and, built with YX6_SUPER_HOST,
;      the USP, which the PCM tick parks a0 in. A demo that has already
;      taken the machine over saves nothing at all. YX6_player.S is the
;      worked example of the polite version
; -----------------------------------------------------------------------------

; A host that never runs user-mode code while the player is live - a demo
; under its own takeover, say - may define YX6_SUPER_HOST equ 1 before
; including this file: the PCM tick of the channel on Timer D then parks a0
; in the then-unused USP (4 cycles each way instead of 12 through the
; stack). Every other channel's always uses the stack, because Timer A and
; Timer B both nest into Timer D's parked windows and must never overwrite
; one - Timer D is last in MFP priority, so nothing nests into it. The park restores a0
; but not the USP: a host that ever returns to user mode must save its USP
; before YX6_init and restore it after YX6_stop, with the rest of the
; machine state assumption 5 lists. Default off, because a program that
; hooks the VBL from user mode has a live USP at every tick.
        .if ^^defined YX6_SUPER_HOST
        .else
YX6_SUPER_HOST  equ     0
        .endif

; YX6_PERF equ 1 turns on the raster monitor: the frame's work paints the
; background red for exactly as long as it runs (one scanline = 512 cycles),
; each tick handler paints its own sliver - Timer A green, Timer D blue -
; wherever the beam happens to be, and because slivers are hard to read, the
; handlers also accumulate an estimated cost that the next frame burns off
; as a solid yellow bar AFTER the red one: the timers' total, in scanlines,
; burned once the frame's own work - and above all the register burst - is
; done, so the monitor never moves the chip writes. The frame step waits
; for the display to start before it paints anything - the VBL fires far
; above the visible screen, so an unsynced monitor draws its bands into
; the top border where nothing can be seen - and the wait is a constant,
; so nothing it measures is distorted. An estimate - the quanta are
; ~10-cycle loop turns - and pure debug: with the flag off (the default)
; not one cycle or byte remains.
        .if ^^defined YX6_PERF
        .else
YX6_PERF        equ     0
        .endif

; YX6_MASK_BURST masks interrupts around the frame write, and is ON by
; default. It is not needed for correctness - every write is one movep.w,
; which no interrupt can split - so setting it to 0 is safe, and what it
; buys is tick timing: with the mask on, a tick landing inside the burst
; waits about 500 cycles, which at the top of the tick range is longer
; than a whole tick period. With it off, ticks interleave with the frame
; write and wait one instruction. Everything else is identical either way,
; so an A/B on this flag isolates the mask and nothing else - the tools
; spell it -nomask.
        .if ^^defined YX6_MASK_BURST
        .else
YX6_MASK_BURST  equ     1
        .endif
PERF_PAD        equ     YX6_PERF*10     ; the color prologue's bytes, sitting
                                        ; ahead of every fixed patch offset
PERF_SYNC       equ     8000            ; the sync loop's ceiling: a whole
                                        ; frame's worth of turns, so a host
                                        ; that calls from anywhere still runs

YX6_CHANNELS    equ     4               ; timer channels, numbered 0..3. Which
                                        ; of the MFP's timers runs each one
                                        ; is the file's business now: stream
                                        ; T carries the map, two bits each
YX6_TIMERS      equ     4               ; and this is what it may name
YX6_STREAMS     equ     25              ; R0..R13, then the compiled effect
                                        ; script: M, X, T, then each channel's
                                        ; action and count bytes - decoded
                                        ; like any register
YX6_CHAN0       equ     17              ; stream index of channel 0's action
                                        ; byte: R0..R13, then M, X and T
YX6_WRITE_READ  equ     6               ; inside a YX6_WRITE: the ring read's
                                        ; displacement, patched at init
YX6_WRITE_MOVEP equ     8               ; and the movep, which the burst gate
                                        ; swaps for two nops
YX6_WRITE_SIZE  equ     12              ; the whole write
YX6_STATE_SIZE  equ     64              ; per stream, so the index is a shift
                                        ; both ways: *64 in, /16 back to *4

; Workspace layout. Counters that a frame touches are kept in the form the
; frame wants them in: a pointer to walk, and a countdown to test against zero.
YX6_CURSOR      equ     0               ; long  this frame's byte in ring 0
YX6_LEFTFR      equ     4               ; long  frames before the tune ends
YX6_NEXT        equ     8               ; long  values decoded so far
YX6_RINGEND     equ     12              ; long  one past ring 0, where it wraps
YX6_RINGS       equ     16              ; long  base of the ring area
YX6_FILE        equ     20              ; long  the .yx6 file
YX6_TOTAL       equ     24              ; long  O, the tune length in frames
YX6_LOOPLEN     equ     28              ; long  O - L, zero when it does not loop
YX6_LOOP        equ     32              ; long  L, read at init to split sections
YX6_N           equ     36              ; word  ring size N
YX6_C           equ     38              ; word  values per call C
YX6_SLOT        equ     40              ; word  refill turn, 0..C-1
YX6_INDEX       equ     42              ; word  stream index, during init only
YX6_LOOPS       equ     44              ; word  nonzero when the tune loops
YX6_EFXOFF      equ     46              ; long  14*N: the cursor's ring to M's
YX6_TMAP        equ     50              ; word  the channel-to-timer map in
                                        ; force, so stream T's byte only
                                        ; costs a compare per frame
YX6_LIVE        equ     52              ; word  streams still worth decoding:
                                        ; the channels sit last, so a tune
                                        ; that names two leaves the others'
                                        ; pair off the end
YX6_STATE       equ     54              ; the decoder states, from an even base

; A timer channel is the format's idea; which of the MFP's four serves it
; is this file's. One
; descriptor per channel says which timer implements it, and every channel
; carries the same fields, so the code that claims, programs and hands
; timers back is one loop over the table. No remembered state beyond the
; claim - the script compiled every comparison at pack time. The hardware
; addresses are words: movea.w sign-extends $FAxx to $FFFFFAxx.
;
; The MFP's four interrupt registers march in step - enable, pending,
; in-service, mask, four bytes apart in both banks - so one address covers
; all four: IER+4 is pending, +8 in-service, +12 mask.
CH_CTRL         equ     0               ; word  timer control register
CH_DATA         equ     2               ; word  timer data register
CH_VEC          equ     4               ; word  timer vector
CH_IER          equ     6               ; word  the timer's enable register
CH_IERBIT       equ     8               ; word  and its bit: a toggle release
                                        ; only MASKS the interrupt, so the
                                        ; counter keeps counting and a
                                        ; resume is phase-continuous
CH_KEEP         equ     10              ; word  what a stop must KEEP of the
                                        ; control byte: $F0 for a timer in
                                        ; the low nibble, $0F for Timer C,
                                        ; which sits in the high one
CH_SHIFT        equ     12              ; word  and how far the prescaler
                                        ; shifts up to meet it: 0 or 4
CH_ISR          equ     14              ; long  the timer's tick handlers
CH_ROW          equ     18              ; the bytes above are the timer's, and
                                        ; an assignment copies exactly them
CH_CLAIMED      equ     18              ; word  nonzero once YX6_init took
                                        ; this channel's timer, which it
                                        ; does only for a channel the
                                        ; header names. The channel's own,
                                        ; so it survives a re-assignment
CH_SIZE         equ     20              ; the table is indexed, so descriptors
                                        ; are a fixed stride apart
; rmac evaluates left to right, so the parentheses here are load-bearing:
; without them this reads as (YX6_STATE+YX6_STREAMS)*YX6_STATE_SIZE.
YX6_FIXED       equ     YX6_STATE+(YX6_STREAMS*YX6_STATE_SIZE)

; One saved ST4_wrap state, plus the ring bounds the wrap check needs. The
; first four longs are in movem order - d1, d2, then a0, a1 - so that much of
; the decoder state loads and stores in one instruction; the three extra
; stream pointers ST4 carries (literals, byte offsets, word offsets) follow.
YS_D1           equ     0               ; long  d1, -ring.low : remaining
YS_D2           equ     4               ; long  d2, N : signed offset
YS_INPUT        equ     8               ; long  a0, stream A position
YS_WRITE        equ     12              ; long  a1, write pointer
YS_LIT          equ     16              ; long  a2, stream B position
YS_BOFF         equ     20              ; long  a4, stream C position
YS_WOFF         equ     24              ; long  a5, stream D position
YS_QUEUE        equ     28              ; word  d0, bit queue
YS_START        equ     32              ; long  this stream's ring
YS_END          equ     36              ; long  one past its last byte
YS_LEFT         equ     40              ; long  values left in the current section
; The movem block starts at the top of the state, so the two movems address it
; as plain (a2) - see the note where they are used.

; The .yx6 header, as written by the packer.
YH_MAGIC        equ     0
YH_VERSION      equ     4
YH_FLAGS        equ     6
YH_FRAMES       equ     8
YH_PLAYER_HZ    equ     12
YH_STREAM_COUNT equ     14
YH_RING         equ     16
YH_CHUNK        equ     18
YH_LOOP         equ     20
YH_CLOCK        equ     24
YH_SAMPLES        equ     28              ; long: the sample table, 0 when none
YH_SAMPLECOUNT    equ     32              ; word
YH_INTRO_TABLE  equ     34              ; one offset per stream: the frames
                                        ; before the loop
YH_LOOP_TABLE   equ     YH_INTRO_TABLE+(YX6_STREAMS*4)

YX6_MAGIC       equ     $59583621       ; 'YX6!'
YX6_VERSION     equ     7               ; 7: four channels, and the file says
                                        ; which timer each one runs on
YX6_FLAG_LOOPS  equ     1               ; header flag: the tune loops
YX6_FLAG_CHAN   equ     2               ; and one per timer channel, from bit
                                        ; 1 up: set when the tune uses it,
                                        ; which is when a timer is claimed
YX6_PSG         equ     $FFFF8800       ; select/read at +0, write at +2
YX6_NO_ENV      equ     $FF             ; R13 value meaning "leave it alone"
YX6_PORTS       equ     $C0             ; R7 bits the ST needs as outputs
YX6_MIXER       equ     7
YX6_ENVELOPE    equ     13

; -----------------------------------------------------------------------------
; YX6_WRITE: send one register's value straight from its ring to the sound
; chip, then step a1 on to the next register's ring.
;
; Two moves and an add, with the register number assembled in as an immediate:
; nothing is tested, nothing is counted, and the value never passes through a
; data register. Register k's ring byte sits k*N above the cursor, and N is
; known at init: the displacement is patched into the instruction there,
; once, so the burst never steps a pointer. The $7FFF placeholder is
; load-bearing - a zero displacement would let rmac's +o3 fold the extension
; word away and shift every patch site. Registers 7 and 13 need their values
; touched, so they are written out of line rather than through this macro.
;
; Skipping a register whose value has not changed was measured and dropped: a
; sound-chip access on an ST costs about 1.5 cycles more than a RAM one, so the
; compare that would save the write costs about as much as the write.
; -----------------------------------------------------------------------------
        .macro YX6_WRITE number
        move.w  #\number<<8,d1          ; the select byte, in the high half
        move.b  $7FFF(a1),d1            ; the value - SMC: k*N, set at init
        movep.w d1,0(a2)                ; select and write in ONE instruction,
        .endm                           ; so no tick can land between them

; Jump table: bra.w keeps 4-byte slots for a program that loads YX6 as a blob.
                bra.w   YX6_init
                bra.w   YX6_play
                bra.w   YX6_stop

; -----------------------------------------------------------------------------
; YX6_init: a0 = .yx6 file, a1 = workspace. Sets up every decoder, decodes
; each stream's first group so frame 0 can be played immediately, patches the
; blob's run-time addresses and the burst's ring displacements, and claims
; a timer for each timer channel the header names - saving nothing, per
; assumption 5.
; -----------------------------------------------------------------------------
YX6_init:
        movea.l a1,a3                   ; a3 is the workspace throughout
        move.l  a0,YX6_FILE(a3)
        cmp.l   #YX6_MAGIC,YH_MAGIC(a0)
        bne     .rejected
        cmp.w   #YX6_VERSION,YH_VERSION(a0)
        bne     .rejected
        cmp.w   #YX6_STREAMS,YH_STREAM_COUNT(a0)
        bne     .rejected

        move.l  YH_FRAMES(a0),YX6_TOTAL(a3)
        move.l  YH_LOOP(a0),YX6_LOOP(a3)
        move.w  YH_RING(a0),YX6_N(a3)
        move.w  YH_CHUNK(a0),YX6_C(a3)
        move.w  YH_FLAGS(a0),d0
        and.w   #YX6_FLAG_LOOPS,d0
        move.w  d0,YX6_LOOPS(a3)
        move.l  YH_FRAMES(a0),YX6_LEFTFR(a3)
        clr.w   YX6_SLOT(a3)
        lea     YX6_FIXED(a3),a1        ; the rings follow the decoder states
        move.l  a1,YX6_RINGS(a3)
        move.l  a1,YX6_CURSOR(a3)       ; frame 0 is the first byte of ring 0
        moveq   #0,d0
        move.w  YH_RING(a0),d0
        add.l   a1,d0
        move.l  d0,YX6_RINGEND(a3)      ; where the cursor goes back to the top

        move.l  YX6_TOTAL(a3),d0        ; the loop section is O - L frames long,
        sub.l   YX6_LOOP(a3),d0         ; and empty when the tune plays once
        tst.w   YX6_LOOPS(a3)
        bne.s   .loop_length
        moveq   #0,d0
.loop_length:
        move.l  d0,YX6_LOOPLEN(a3)

        moveq   #0,d1                   ; the first group is C values, or the
        move.w  YX6_C(a3),d1            ; whole tune when that is shorter and
        tst.w   YX6_LOOPS(a3)           ; there is no loop to carry on into
        bne.s   .first_group
        move.l  YX6_TOTAL(a3),d0
        cmp.l   d1,d0
        bcc.s   .first_group
        move.l  d0,d1
.first_group:
        move.l  d1,YX6_NEXT(a3)         ; values decoded once init is done

        clr.w   YX6_INDEX(a3)           ; the loop counter lives here: every
.stream:                                ; register is state during the calls
        move.w  YX6_INDEX(a3),d0
        lsl.w   #6,d0                   ; * YX6_STATE_SIZE
        lea     YX6_STATE(a3,d0.w),a2
        moveq   #0,d3
        move.w  YX6_N(a3),d3
        move.w  YX6_INDEX(a3),d0
        mulu.w  d3,d0                   ; this stream's ring is the k'th
        movea.l YX6_RINGS(a3),a1
        adda.l  d0,a1
        move.l  a1,YS_START(a2)
        move.l  a1,YS_WRITE(a2)
        move.l  a1,d0
        add.l   d3,d0
        move.l  d0,YS_END(a2)

; A tune that loops from frame 0 has no intro streams, so its registers start
; on the loop section - which is then the whole tune. The load of L comes
; last because it is the branch condition: a move to a data register writes
; the flags, a lesson the original version of this choice taught by always
; picking the intro table and leaning on the section-exhausted path to
; recover. The signature check below ended that arrangement.
        move.w  #YH_INTRO_TABLE,d4
        move.l  YX6_LOOP(a3),d0
        bne.s   .section                ; L != 0: the intro covers [0, L)
        move.w  #YH_LOOP_TABLE,d4
        move.l  YX6_LOOPLEN(a3),d0
.section:
        move.l  d0,YS_LEFT(a2)          ; values before this stream runs out

        move.w  YX6_INDEX(a3),d0
        lsl.w   #2,d0                   ; the tables hold long offsets
        add.w   d4,d0
        movea.l YX6_FILE(a3),a0
        move.l  0(a0,d0.w),d0
        adda.l  d0,a0                   ; the section's ST4 container
        cmp.l   #ST4_SIGNATURE,(a0)     ; magic, version and unit in one compare:
        bne     .rejected               ; a tune packed for another unit size

; Open the container. The state block goes to the stack; ST4_init clobbers
; a3, so the workspace pointer does too.
        move.l  a2,-(sp)
        move.l  a3,-(sp)
        bsr     yx6_open
        movea.l (sp)+,a3

        movea.l (sp)+,a1                ; the state block; a1 is recomputed
        move.l  a0,YS_INPUT(a1)         ; every time round this loop
        move.l  a2,YS_LIT(a1)
        move.l  a4,YS_BOFF(a1)
        move.l  a5,YS_WOFF(a1)
        move.w  d0,YS_QUEUE(a1)
        move.l  d1,YS_D1(a1)
        move.l  d2,YS_D2(a1)

        move.w  YX6_INDEX(a3),d0        ; and its first group
        lsl.w   #6,d0
        move.w  YX6_NEXT+2(a3),d3       ; C, or fewer: both fit a word
        bsr     YX6_fill
        addq.w  #1,YX6_INDEX(a3)
        cmp.w   #YX6_STREAMS,YX6_INDEX(a3)
        bne     .stream

; The cursor reaches the script rings by fixed hops: 14*N to M, computed
; once, and the three parameter offsets (8+voice)*N the handlers read the
; the toggle volume, retrigger shape and sample number through.
        move.w  YX6_N(a3),d0
        mulu.w  #14,d0
        move.l  d0,YX6_EFXOFF(a3)
        moveq   #0,d0
        move.w  YX6_N(a3),d0
        move.l  d0,d1
        lsl.l   #3,d1                   ; 8*N
        lea     yx6_parmoff(pc),a1
        move.l  d1,(a1)+                ; voice A
        add.l   d0,d1
        move.l  d1,(a1)+                ; voice B
        add.l   d0,d1
        move.l  d1,(a1)                 ; voice C

; The sample table becomes a table of absolute addresses, so a trigger
; is one indexed load instead of an entry walk: the DRUM action's byte is
; the sample number, pre-scaled to index these longs.
        movea.l YX6_FILE(a3),a0
        move.l  YH_SAMPLES(a0),d1
        beq.s   .no_drums
        movea.l a0,a2
        adda.l  d1,a2                   ; the entries: offset.l, length.w
        lea     yx6_samples(pc),a1
        move.w  YH_SAMPLECOUNT(a0),d0
        subq.w  #1,d0
        bmi.s   .no_drums
.drum_addr:
        move.l  (a2),d1
        add.l   a0,d1                   ; file base + entry offset
        move.l  d1,(a1)+
        addq.l  #6,a2
        dbra    d0,.drum_addr
.no_drums:

; The burst reads register k's ring byte through an assembled-in k*N
; displacement - patch all fourteen now that N is known. The packer keeps
; N at 2520 or less, so 13*N fits the signed word. The displacement sits
; at YX6_WRITE_READ inside each write; R7 and R13 read out of line.
        move.w  YX6_N(a3),d2
        moveq   #0,d1                   ; k*N, accumulated
        lea     yx6_wA+YX6_WRITE_READ(pc),a0
        moveq   #6,d0                   ; R0..R6
.wpatchA:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     YX6_WRITE_SIZE(a0),a0
        dbra    d0,.wpatchA
        lea     yx6_w7+2(pc),a0         ; R7 reads through d1
        move.w  d1,(a0)
        add.w   d2,d1
        lea     yx6_wB+YX6_WRITE_READ(pc),a0
        moveq   #4,d0                   ; R8..R12
.wpatchB:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     YX6_WRITE_SIZE(a0),a0
        dbra    d0,.wpatchB
        lea     yx6_w13+2(pc),a0
        move.w  d1,(a0)
        bsr     yx6_link                ; gates open, handlers linked

; Read the map before claiming anything: stream T's first byte says which
; timer each channel runs on, and the rings are primed by now, so it is
; simply the byte under the cursor.
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_EFXOFF(a3),a0       ; ring M
        moveq   #0,d1
        move.w  YX6_N(a3),d1
        adda.l  d1,a0                   ; ring X
        adda.l  d1,a0                   ; ring T
        moveq   #0,d0
        move.b  (a0),d0
        move.w  d0,YX6_TMAP(a3)         ; remembered, so a change shows up
        bsr     yx6_assign

; Claim a timer for each timer channel the header names: park our handler
; on its vector and leave the timer stopped but enabled and unmasked. A
; channel the tune does not use is left entirely alone, timer and all, so
; a tune that names two hands the other timers to the host untouched.
; Nothing is saved - the machine state is the host's, see assumption 5 in
; the header. bset/bclr only: Timer C's bits, Timer D's and the ACIA's all
; live in registers they share with something else.
        movea.l YX6_FILE(a3),a0         ; yx6_link had a0
        move.w  YH_FLAGS(a0),d0
        lsr.w   #1,d0                   ; flag bit 1+c: channel c is used
        lea     yx6_desc_0(pc),a5
        moveq   #YX6_CHANNELS-1,d2
        move.w  #YX6_CHAN0,YX6_LIVE(a3) ; no channel: no A or P worth decoding
        move.w  sr,-(sp)
        ori.w   #$0700,sr
.claim:
        bsr     yx6_handback            ; an earlier init's claim on this
                                        ; channel goes back first, whether
                                        ; or not this tune wants it again:
                                        ; init twice without a stop and no
                                        ; timer of ours is left running
        moveq   #0,d1
        lsr.w   #1,d0
        bcc.s   .idle
        moveq   #1,d1
.idle:
        move.w  d1,CH_CLAIMED(a5)       ; what YX6_stop hands back
        beq.s   .next_channel
        move.w  #YX6_CHANNELS,d1        ; and the last claimed channel's pair
        sub.w   d2,d1                   ; is the last stream worth decoding
        add.w   d1,d1
        add.w   #YX6_CHAN0,d1
        move.w  d1,YX6_LIVE(a3)
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; stopped; the other nibble kept
        movea.l CH_ISR(a5),a2
        lea     ISR_PARK(a2),a2         ; the park entry, until an effect
        movea.w CH_VEC(a5),a1           ; aims the vector somewhere
        move.l  a2,(a1)
        move.w  CH_IERBIT(a5),d1
        movea.w CH_IER(a5),a1
        bclr    d1,4(a1)                ; drop a stale pending
        bset    d1,(a1)                 ; enabled
        bset    d1,12(a1)               ; and unmasked
.next_channel:
        lea     CH_SIZE(a5),a5
        dbra    d2,.claim
        move.w  (sp)+,sr

        moveq   #0,d0
        rts
.rejected:
        moveq   #-1,d0
        rts

; -----------------------------------------------------------------------------
; YX6_fill: d0.w = stream index * YX6_STATE_SIZE, d3.w = values wanted.
; Produces exactly that many values for one register, crossing into the loop
; section - and around it again - as often as it takes. Straddling matters even
; for one group: nothing says the loop frame falls on a group boundary.
; -----------------------------------------------------------------------------
YX6_fill:
        movem.l d6-d7/a6,-(sp)          ; three registers that survive a decoder
        lea     YX6_STATE(a3,d0.w),a2   ; call, which its own state needs
        movea.l a3,a6                   ; ST4 clobbers a3: a6 keeps the workspace
        move.l  a2,d6
        moveq   #0,d7
        move.w  d3,d7                   ; values still wanted
.piece:
        move.l  YS_LEFT(a2),d0
        bne.s   .section
        bsr     YX6_start_loop          ; this section ran out: go round again
        move.l  YS_LEFT(a2),d0
        beq.s   .done                   ; nothing to go round to; do not hang
.section:
        cmp.l   d7,d0
        bcs.s   .piece_size
        move.l  d7,d0                   ; the section covers all that is wanted
.piece_size:
        sub.l   d0,YS_LEFT(a2)
        sub.l   d0,d7
        move.w  d0,d3                   ; a piece is at most C, so it fits a word
; The decoder's budget counts units of ST4_UNIT bytes. The packer enforces
; that every piece boundary - C, the loop frame, the tune length - is a whole
; number of units, so this shift never drops a remainder.
        .if ST4_UNIT=2
        lsr.w   #1,d3
        .endif
        .if ST4_UNIT=4
        lsr.w   #2,d3
        .endif

; (a2) written out rather than YS_D1(a2): with +o3 rmac folds a zero
; displacement away on movem but keeps the addressing mode, and emits the
; instruction without its extension word. The next word becomes the
; displacement and the code runs off into nothing.
        movem.l (a2),d1-d2/a0-a1
        movea.l YS_BOFF(a2),a4
        movea.l YS_WOFF(a2),a5
        move.w  YS_QUEUE(a2),d0
        movea.l YS_LIT(a2),a2           ; loaded last: a2 was the base
        bsr     ST4_resume
        movea.l a6,a3                   ; the decoder clobbered the workspace
        exg     a2,d6                   ; a2 = the block again, d6 = stream B
        cmpa.l  YS_END(a2),a1           ; YX6_save, inlined: this is the hot path
        bne.s   .stored
        movea.l YS_START(a2),a1         ; the call landed on the ring end
.stored:
        movem.l d1-d2/a0-a1,(a2)
        move.l  d6,YS_LIT(a2)
        move.l  a4,YS_BOFF(a2)
        move.l  a5,YS_WOFF(a2)
        move.w  d0,YS_QUEUE(a2)
        move.l  a2,d6                   ; the block pointer, back where fill keeps it
        tst.l   d7
        bne.s   .piece
.done:
        movem.l (sp)+,d6-d7/a6
        rts

; -----------------------------------------------------------------------------
; YX6_start_loop: a2 = state block. Starts this register's loop stream over -
; a fresh decoder, reading from the top of the loop section, still writing
; where the last one stopped. ST4_init wants the ring start so that its packed
; metadata comes out right; the write pointer is restored from the block, which
; is why it is stored rather than held in a register.
; -----------------------------------------------------------------------------
; It runs inside YX6_fill, which guarantees d6 = the state block and a6 = the
; workspace - the two anchors that survive the decoder call.
YX6_start_loop:
        move.l  a2,d0
        sub.l   a3,d0
        sub.w   #YX6_STATE,d0
        lsr.w   #4,d0                   ; k * 64 becomes k * 4, a table index
        movea.l YX6_FILE(a3),a0
        lea     YH_LOOP_TABLE(a0),a0    ; the loop table passed 127 bytes into
        move.l  0(a0,d0.w),d0           ; the header at 25 streams, and an
                                        ; indexed displacement is a SIGNED
                                        ; BYTE - so the base moves instead
        suba.w  #YH_LOOP_TABLE,a0       ; a0 is the file again below
        beq.s   .no_loop                ; this tune has no loop section
        adda.l  d0,a0                   ; the loop section's ST4 container
        movea.l YS_START(a2),a1         ; read before a2 becomes stream B
        bsr.s   yx6_open
        movea.l a6,a3                   ; the decoder clobbered the workspace
        exg     a2,d6                   ; a2 = the block again, d6 = stream B
        move.l  a0,YS_INPUT(a2)
        move.l  d6,YS_LIT(a2)
        move.l  a4,YS_BOFF(a2)
        move.l  a5,YS_WOFF(a2)
        move.w  d0,YS_QUEUE(a2)
        move.l  d1,YS_D1(a2)
        move.l  d2,YS_D2(a2)
        move.l  YX6_LOOPLEN(a3),YS_LEFT(a2)
        move.l  a2,d6                   ; the block pointer, as fill keeps it
.no_loop:
        rts

; -----------------------------------------------------------------------------
; yx6_open: a0 = an ST4 container. Points the four stream registers at its
; streams - A begins where the header ends, B, C and D sit at the recorded
; header-relative offsets, exactly as ST4.S documents - and tail-calls
; ST4_init: its rts returns to yx6_open's caller with the decoder registers
; as ST4_init leaves them. ST4_init clobbers a3; callers restore it
; themselves, init from the stack and the loop restart from a6.
; -----------------------------------------------------------------------------
yx6_open:
        movea.l a0,a2
        adda.l  8(a0),a2                ; stream B, the literals
        movea.l a0,a4
        adda.l  12(a0),a4               ; stream C, the byte offsets
        movea.l a0,a5
        adda.l  16(a0),a5               ; stream D, the word offsets
        lea     20(a0),a0               ; stream A, the bits
        moveq   #0,d3
        move.w  YX6_N(a3),d3            ; N, ST4_init's argument
        bra     ST4_init

; -----------------------------------------------------------------------------
; YX6_play: a0 = workspace. One frame: run the effect stage, write fourteen
; registers, undo the stage's ring edits, refill one stream.
; -----------------------------------------------------------------------------
YX6_play:
        movea.l a0,a3
        tst.l   YX6_LEFTFR(a3)
        bne.s   .frame
        moveq   #-1,d0                  ; past the last frame of a tune that
        rts                             ; does not loop: write nothing

.frame:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)       ; the raster monitor. The background
        move.b  $FFFF8209.w,d0          ; goes on the stack first, before any
        move.w  #PERF_SYNC,d1           ; tick can paint over it; then wait
.perf_sync:                             ; for the beam. The VBL fires in the
        cmp.b   $FFFF8209.w,d0          ; blanking, dozens of lines above the
        dbne    d1,.perf_sync           ; screen, and only the video counter
                                        ; knows when the display starts: it
                                        ; moves when, and only when, the chip
                                        ; is fetching pixels. Bounded, so a
                                        ; host that plays mid-screen - or a
                                        ; machine whose counter never moves -
                                        ; stops rather than hanging.
        move.w  #$700,$FFFF8240.w       ; this frame's work, red - first,
        .endif                          ; so the monitor never delays the
                                        ; burst; the timers' bar follows

; One channel's turn in the frame's action stage: \bit of the master byte
; says whether it acts, \rings is how far its action stream sits above M
; (channel c's pair is 3+2c and 4+2c streams up), \here names the block
; and \next the one after it, so the channels chain without a loop's
; bookkeeping in the common case of nothing acting at all.
        .macro YX6_ACT chan, bit, rings, here, next
\here:
        move.b  (a0),d0                 ; a0 survives yx6_act
        btst    #\bit,d0
        beq.s   \next
        lea     yx6_desc\{chan}(pc),a5
        movea.l a0,a4
        .rept \rings
        adda.l  d3,a4                   ; its ring sits \rings above M
        .endr
        bsr     yx6_act
        .endm

; The gates first, then the burst, then everything else: the fourteen chip
; writes leave the VBL at a FIXED offset, whatever the frame's effects cost.
; Only the gate state must precede the burst (a trigger frame's voice must
; be muted before the burst would write its sample number); the actions -
; timer programs, handler patches - happen after, where their varying cost
; cannot jitter the writes. Gate changes are rare, so the burst's offset
; moves only on those frames, and by a fixed amount.
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_EFXOFF(a3),a0       ; ring M, at this frame's offset
        move.b  (a0),d0                 ; a0 survives everything below
        btst    #4,d0                   ; the gate mask changed?
        beq.s   .burst                  ; bits 7-5 carried the new state
        lsr.b   #5,d0
        bsr     yx6_gates
.burst:
        movea.l YX6_CURSOR(a3),a1       ; the cursor: every register's ring
        lea     YX6_PSG,a2              ; byte is a patched k*N above it

; Selecting a register and writing it are two bus cycles, and an interrupt
; between them would send the value to whatever register the interrupt
; selected. The burst used to mask interrupts for its whole run to prevent
; that, which delayed every tick that fell inside it - at 25,000 ticks a
; second the mask was longer than a tick period, so a sample could lose
; its place or a tick could be coalesced away.
;
; movep.w writes both bytes from one register in ONE instruction. A 68000
; takes interrupts between instructions, so nothing can land inside a
; write and the mask below is optional: with YX6_MASK_BURST at 0 the burst
; runs unmasked and ticks interleave with it, delayed by one instruction
; at worst instead of ~500 cycles. The tick handlers are atomic the same
; way - move.l #imm,$FFFF8800.w - so the interleaving is safe in both
; directions, and a tick that lands between two register writes is
; followed by a write that selects again.
        .if YX6_MASK_BURST
        move.w  sr,-(sp)                ; YX6_MASK_BURST: the old behaviour,
        ori.w   #$0700,sr               ; for hearing what the mask costs
        .endif
yx6_wA:
        YX6_WRITE 0                     ; channel A period, fine and coarse
        YX6_WRITE 1
        YX6_WRITE 2                     ; channel B
        YX6_WRITE 3
        YX6_WRITE 4                     ; channel C
        YX6_WRITE 5
        YX6_WRITE 6                     ; noise period

; R7 sits HERE ON PURPOSE, between the periods and the volumes, and moving
; it to either end of the run - which would let R0..R6 and R8..R12 fold
; into one unbroken block, for two source lines and four bytes - widens an
; audible window. Routing and volume disagree for as long as the writes
; between them take: on the frame a sample ends, R7 reconnects the voice
; while its volume register still holds the sample's parked tail value.
; That gap is one write, about 4 us, where it is. First in the run it
; becomes seven writes, 28 us; last, five writes on the other side, 20 us -
; and the same class of artefact cost a day and a half on Trapped in China.
; Nothing automated would catch it: the harness reads registers at the end
; of a frame, and the rig compares each frame's writes as a set.
        move.w  #YX6_MIXER<<8,d1        ; R7 also carries the ST's I/O port
yx6_w7:
        move.b  $7FFF(a1),d1            ; directions, which port A needs kept
        or.b    #YX6_PORTS,d1           ; as outputs for the floppy select
        movep.w d1,0(a2)                ; one instruction, like every write

yx6_wB:
        YX6_WRITE 8                     ; the three volumes
        YX6_WRITE 9
        YX6_WRITE 10
        YX6_WRITE 11                    ; envelope period
        YX6_WRITE 12

        move.w  #YX6_ENVELOPE<<8,d1     ; the select, above the shape byte
yx6_w13:
        move.b  $7FFF(a1),d1            ; R13 restarts the envelope whenever it
        cmp.b   #YX6_NO_ENV,d1          ; is written, so $FF means leave it be
        beq.s   .envelope_held
        movep.w d1,0(a2)
.envelope_held:
        .if YX6_MASK_BURST
        move.w  (sp)+,sr
        .endif

; The actions, now that the chip has its frame: replay the compiled script.
; One master byte says what acts - almost always nothing. The action bytes
; were decided at pack time; the handlers copy prepared values to prepared
; places and compare nothing. a0 still points at ring M.
; The map first, if it moved. Stream T is one byte a frame and almost
; always the same one, so a tune that never re-assigns pays a compare.
        moveq   #0,d3
        move.w  YX6_N(a3),d3            ; d3 survives yx6_act; d0 does not
        movea.l a0,a1
        adda.l  d3,a1
        adda.l  d3,a1                   ; ring T = M + 2N
        moveq   #0,d0
        move.b  (a1),d0
        cmp.w   YX6_TMAP(a3),d0
        beq.s   .map_ready
        move.w  d0,YX6_TMAP(a3)
        bsr     yx6_assign              ; leaves a0 and d3 alone
.map_ready:
        move.b  (a0),d0
        andi.b  #(1<<YX6_CHANNELS)-1,d0 ; the channels' bits; gates are done
        beq.s   .effects_done
        YX6_ACT _0,0,3,.channel0,.channel1
        YX6_ACT _1,1,5,.channel1,.channel2
        YX6_ACT _2,2,7,.channel2,.channel3
        YX6_ACT _3,3,9,.channel3,.effects_done
.effects_done:

; Refill the stream whose turn it is. Slots past the live streams exist
; when C is larger than that count; they simply have nothing to do - which
; is also how an unused timer channel costs no time, its pair of streams
; sitting past the end.
        move.w  YX6_SLOT(a3),d0
        cmp.w   YX6_LIVE(a3),d0
        bcc.s   .advance
        moveq   #0,d3
        move.w  YX6_C(a3),d3            ; a whole group
        tst.w   YX6_LOOPS(a3)
        bne.s   .refill                 ; a looping tune never runs out
        move.l  YX6_TOTAL(a3),d1
        sub.l   YX6_NEXT(a3),d1         ; values still packed
        ble.s   .advance                ; all decoded: never call ST4 again
        cmp.l   d3,d1
        bcc.s   .refill
        move.l  d1,d3                   ; the last group is a short one
.refill:
        lsl.w   #6,d0
        bsr     YX6_fill

.advance:
        addq.l  #1,YX6_CURSOR(a3)       ; one byte along every ring
        move.l  YX6_CURSOR(a3),d0
        cmp.l   YX6_RINGEND(a3),d0
        bcs.s   .cursor_ready
        move.l  YX6_RINGS(a3),YX6_CURSOR(a3)
.cursor_ready:
        move.w  YX6_SLOT(a3),d0
        addq.w  #1,d0
        cmp.w   YX6_C(a3),d0
        bcs.s   .slot_ready
        moveq   #0,d0                   ; a cycle ended: the groups move on
        moveq   #0,d1
        move.w  YX6_C(a3),d1
        add.l   d1,YX6_NEXT(a3)
.slot_ready:
        move.w  d0,YX6_SLOT(a3)

; The rings hold the tune as one continuous sequence, so reaching the end of
; the last frame simply means the next values in them are the loop section's.
; The countdown follows: it reloads with the loop section's length.
        subq.l  #1,YX6_LEFTFR(a3)
        bne.s   .played
        tst.w   YX6_LOOPS(a3)
        beq.s   .played                 ; zero and no loop: the next call ends
        move.l  YX6_LOOPLEN(a3),YX6_LEFTFR(a3)
        .if YX6_PERF
        move.w  #$770,$FFFF8240.w       ; then the timers' bar: what the
        lea     yx6_perf_acc(pc),a0     ; ticks cost since last frame,
        move.w  (a0),d0                 ; burned AFTER the frame's work
        clr.w   (a0)
.perf_bar_w:
        dbra    d0,.perf_bar_w
        move.w  (sp)+,$FFFF8240.w
        .endif
        moveq   #1,d0                   ; played, and wrapped to the loop point
        rts
.played:
        .if YX6_PERF
        move.w  #$770,$FFFF8240.w
        lea     yx6_perf_acc(pc),a0
        move.w  (a0),d0
        clr.w   (a0)
.perf_bar_p:
        dbra    d0,.perf_bar_p
        move.w  (sp)+,$FFFF8240.w
        .endif
        moveq   #0,d0
        rts

; -----------------------------------------------------------------------------
; YX6_stop: a0 = workspace. Quiesces what YX6_init claimed - every claimed
; channel's timer stopped, its interrupt disabled and masked, its pending
; dropped - then silences the three voices. A timer the tune never asked
; for is not touched here either. It restores NOTHING: the host that saved the
; machine state hands it back itself, after this returns. Tone and envelope
; settings stay, and a fresh YX6_init starts the tune over.
; -----------------------------------------------------------------------------
YX6_stop:
        movea.l a0,a3
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        lea     yx6_desc_0(pc),a5
        moveq   #YX6_CHANNELS-1,d2
.channel:
        bsr.s   yx6_handback
        lea     CH_SIZE(a5),a5
        dbra    d2,.channel
        moveq   #0,d0                   ; every burst gate open again
        bsr     yx6_gates
; The silence stays inside the mask: selecting a register and writing it are
; two bus cycles, and a polite host's system interrupts - about to get their
; state back - use the chip too.
        lea     YX6_PSG,a0
        moveq   #8,d0                   ; R8, R9, R10 are the voice volumes
.silence:
        move.b  d0,(a0)
        clr.b   2(a0)
        addq.b  #1,d0
        cmp.b   #11,d0
        bne.s   .silence
        move.w  (sp)+,sr
        rts

; One channel's timer given back: stopped, disabled, masked, no pending
; tick of ours - and the claim cleared, so a second call does nothing. The
; vector is left where it points, at this blob's park entry: it cannot
; fire, and restoring vectors is the host's job (assumption 5).
; in: a5 = the descriptor; clobbers d1/a1.
yx6_handback:
        tst.w   CH_CLAIMED(a5)          ; only what YX6_init took
        beq.s   .not_ours
        clr.w   CH_CLAIMED(a5)
        movea.w CH_CTRL(a5),a1
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; the timer stopped,
        move.w  CH_IERBIT(a5),d1
        movea.w CH_IER(a5),a1
        bclr    d1,(a1)                 ; disabled and masked - the claim
        bclr    d1,12(a1)               ; handed back bit by bit
        bclr    d1,4(a1)                ; and no pending tick of ours
.not_ours:
        rts

; -----------------------------------------------------------------------------
; The effect machinery. The per-frame stage lives inlined in YX6_play; what
; follows is everything it drives: the channel descriptors, the tick
; handlers, and the one machine that serves every channel.
; -----------------------------------------------------------------------------

; The timers, and the channels that borrow them.
;
; A timer's row holds everything that tells one timer from another: its
; registers, its vector, its interrupt bit, what a stop must keep of the
; shared control byte, how far its prescaler shifts to get there, and the
; tick-handler block that belongs to it. The handlers belong to the TIMER,
; not the channel - each bakes in its own end-of-interrupt and control
; register - so assigning a channel to a timer is a copy of this row and
; never a patch of live interrupt code.
;
; Timers C and D share TCDCR at $FA1D: C in the high nibble, D in the low.
; That is the whole reason for KEEP and SHIFT. Timer C is also the
; operating system's 200 Hz clock, so a tune that names it stops the
; system clock and cannot be hosted from a Timer C hook - the file's
; choice, made at pack time, and the host's problem to know about.
        even
yx6_timers:
yx6_timer_a:
        dc.w    $FA19,$FA1F,$0134       ; control, data, vector
        dc.w    $FA07,5                 ; the enable register and its bit
        dc.w    $00F0,0                 ; keep, shift: the low nibble
        dc.l    0                       ; the block: yx6_link fills it in
yx6_timer_b:
        dc.w    $FA1B,$FA21,$0120
        dc.w    $FA07,0
        dc.w    $00F0,0
        dc.l    0
yx6_timer_c:
        dc.w    $FA1D,$FA23,$0114
        dc.w    $FA09,5
        dc.w    $000F,4                 ; the HIGH nibble of the shared byte
        dc.l    0
yx6_timer_d:
        dc.w    $FA1D,$FA25,$0110
        dc.w    $FA09,4
        dc.w    $00F0,0
        dc.l    0

; The channels: four rows of the same shape, each filled from a timer's
; row whenever stream T says so, plus the claim that is the channel's own.
        even
yx6_desc_0:
        dcb.b   CH_SIZE,0
yx6_desc_1:
        dcb.b   CH_SIZE,0
yx6_desc_2:
        dcb.b   CH_SIZE,0
yx6_desc_3:
        dcb.b   CH_SIZE,0

; The two prepared tables the script indexes into: the samples' absolute
; sample addresses (YX6_init resolves the file's table once, so a trigger
; is one indexed load) and the three parameter-ring offsets (8+voice)*N.
yx6_samples:
        ds.l    32
yx6_parmoff:
        ds.l    3

; -----------------------------------------------------------------------------
; The tick handlers, in maxYMiser's shipping forms: the PSG select and write
; in as few instructions as the bus allows, patched immediates instead of
; state, software end-of-interrupt as TOS runs the MFP - written as an
; immediate byte, since the in-service registers ignore written ones. All
; MFP interrupts arrive at 68000 level 6, so these never nest, and every
; patched operand is addressed pc-relatively so the player stays loadable
; anywhere - which also means it must run from RAM, where a demo lives.
;
; The channels' handlers are the same text three times, and MUST stay
; byte-congruent: the action handlers reach every patched operand through
; ISR_ offsets measured on channel 1's block, and yx6_link walks the blocks
; at a fixed stride. The rig checks both from the symbol table. The park entries - where YX6_init aims the vectors
; while no effect runs - are the handlers' own tails.
;
; The timers STACK: each handler drops the IPL to 5 as soon as its PSG
; select/data pair is complete, so an MFP channel of higher priority can
; nest instead of waiting a whole tick. The MFP serves its A bank before
; its B bank, which puts the player's timers in the order A, B, D. The
; nesting is strictly one-way: a higher-priority timer interrupts a lower
; one's handler, never the reverse (SEI mode admits only higher MFP
; priorities while an in-service bit is set), and nothing of the player's
; sits above Timer A. That direction is why the USP park belongs to
; Timer D's PCM tick alone - it is the one nothing else waits behind, so
; nothing can nest into its parked window. Every other channel's tick
; uses the stack, so a parked USP survives. The drop must never come before
; the handler's LAST chip write: a nested tick re-selects the PSG.
;
; The patch offsets are load-bearing (all fixed ones shift by PERF_PAD when
; the raster monitor's color prologue sits at each block's top):
;   pcm:      select immediate at +3, sample address at +8
;   toggle:   select at +2 and volume at +4 inside the imm long; the partner's
;          whole vector at +14, past the IPL drop, patched by yx6_link
;   retrigger: the shape at +4
; -----------------------------------------------------------------------------

; The channels differ only in which timer runs them, so the whole set of
; handlers is written once as a macro and invoked once per channel. \chan
; is the channel's label suffix - rmac renders a bare digit as hex, so the
; number wears a leading underscore - and the timer arrives
; as arguments: \eoi and \eoir the end-of-interrupt value and register,
; \tctrl the timer's control register, \vec its vector. \colour is the
; monitor's band and \park is 1 on the channel allowed to park a0 in the
; USP. rmac's \~ gives each expansion its own local labels.
        .macro YX6_TICKS chan, colour, eoi, eoir, tctrl, keep, vec, park

; One PCM tick: the sample byte goes straight to the voice's volume register.
; The byte after the last has bit 7 set, so the same move answers "done": park
; the volume mid-scale against the click, stop the timer, drop the flag.
yx6_pcm\{chan}:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)       ; this channel's band
        move.w  #\colour,$FFFF8240.w
        .endif
        move.b  #8,$FFFF8800.w          ; SMC PERF_PAD+3: select the volume
        move.b  $12345678,$FFFF8802.w   ; SMC PERF_PAD+8: the next sample
        bmi.s   .ended\~
        move.w  #$2500,sr               ; pair done: the other timer may stack
        .if \park*YX6_SUPER_HOST        ; only the channel that cannot nest
        move    a0,usp                  ; into a parked window may use the USP
        .else
        move.l  a0,-(sp)
        .endif
        lea     yx6_pcm\{chan}+8+PERF_PAD(pc),a0
        addq.l  #1,(a0)                 ; step the operand itself
        .if YX6_PERF
        lea     yx6_perf_acc(pc),a0
        add.w   #21,(a0)                ; ~a PCM tick, in 10-cycle quanta
        .endif
        .if \park*YX6_SUPER_HOST
        move    usp,a0
        .else
        movea.l (sp)+,a0
        .endif
        .if YX6_PERF
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
.ended\~:
        move.b  #$D,$FFFF8802.w         ; middle volume: no click
        andi.b  #\keep,\tctrl.w         ; the sample ran out: stop the timer -
        .if YX6_PERF                    ; the script already knew this frame
        move.l  a0,-(sp)                ; was coming: gate and mixer follow
        lea     yx6_perf_acc(pc),a0     ; at the next frame boundary
        add.w   #23,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
yx6_park\{chan}:
        move.b  #\eoi,\eoir.w
        rte

; The toggle tick: each tick writes one of two values - the volume or
; silence - and installs the other half as the next tick, one word into the
; vector. Select and data land in one move.l: the immediate's bytes hit
; $8800 (select) and $8802 (data) by bus layout.
yx6_toggle\{chan}_on:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$08000F00,$FFFF8800.w  ; SMC PERF_PAD+2 select, +4 volume
        move.w  #$2500,sr
        move.l  #0,\vec.w                ; SMC PERF_PAD+14: the quiet half is
        .if YX6_PERF                    ; next - the whole vector, so the
        move.l  a0,-(sp)                ; blob may straddle a 64K boundary
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)                ; ~a toggle tick, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
yx6_toggle\{chan}_off:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$08000000,$FFFF8800.w  ; SMC PERF_PAD+2 select; silence
        move.w  #$2500,sr
        move.l  #0,\vec.w                ; SMC PERF_PAD+14: the loud half next
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte

; The retrigger tick: writing the shape to R13 restarts the envelope, and
; that restart at the timer's rate is the whole effect.
yx6_retrigger\{chan}:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #\colour,$FFFF8240.w
        .endif
        move.l  #$0D000000,$FFFF8800.w  ; SMC PERF_PAD+4: the shape
        move.w  #$2500,sr
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #12,(a0)                ; ~a retrigger tick, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #\eoi,\eoir.w
        rte
        .endm

; One block per timer, in MFP priority order: the A bank is served before
; the B bank, so it runs Timer A, Timer B, Timer C, Timer D. Timer D is
; last, which makes it the one nothing can nest into and so the only one
; that may park a0 in the USP - whichever channel happens to be on it.
;                 timer colour  eoi  in-service  control    keep  vector park
        YX6_TICKS _a,    $070,   $DF, $FFFFFA0F, $FFFFFA19,  $F0,  $134,  0
        YX6_TICKS _b,    $700,   $FE, $FFFFFA0F, $FFFFFA1B,  $F0,  $120,  0
        YX6_TICKS _c,    $077,   $DF, $FFFFFA11, $FFFFFA1D,  $0F,  $114,  0
        YX6_TICKS _d,    $007,   $EF, $FFFFFA11, $FFFFFA1D,  $F0,  $110,  1

YX6_BLOCK       equ     yx6_pcm_b-yx6_pcm_a   ; the blocks are congruent, so
                                        ; one stride walks them

        .if YX6_PERF
yx6_perf_acc:
        dc.w    0                       ; the ticks' estimated cost since the
        .endif                          ; last frame, in 10-cycle quanta

; Where the patched operands sit, measured on channel 1's block; the other
; blocks are byte-congruent, so the same offsets serve all of them through
; CH_ISR.
ISR_PCM_SEL    equ     PERF_PAD+3
ISR_PCM_PTR    equ     PERF_PAD+8
ISR_TOGGLE_ON       equ     yx6_toggle_a_on-yx6_pcm_a
ISR_TOGGLE_ON_SEL   equ     ISR_TOGGLE_ON+PERF_PAD+2
ISR_TOGGLE_VOL     equ     ISR_TOGGLE_ON+PERF_PAD+4
ISR_TOGGLE_ON_NEXT  equ     ISR_TOGGLE_ON+PERF_PAD+14
ISR_TOGGLE_OFF      equ     yx6_toggle_a_off-yx6_pcm_a
ISR_TOGGLE_OFF_SEL  equ     ISR_TOGGLE_OFF+PERF_PAD+2
ISR_TOGGLE_OFF_NEXT equ     ISR_TOGGLE_OFF+PERF_PAD+14
ISR_RETRIGGER        equ     yx6_retrigger_a-yx6_pcm_a
ISR_PARK             equ     yx6_park_a-yx6_pcm_a
ISR_RETRIGGER_SHAPE  equ     ISR_RETRIGGER+PERF_PAD+4

; -----------------------------------------------------------------------------
; yx6_link, from YX6_init: the run-time addresses only a loaded player knows.
; Every burst gate opens (a previous session's mute must not leak), each
; descriptor gets its ISR block, and the toggle halves get each other's whole
; vector - it never changes, so it is patched once here rather than on
; every toggle start. One pass over the channel table does all of it.
; -----------------------------------------------------------------------------
yx6_link:
        moveq   #0,d0
        bsr     yx6_gates
        lea     yx6_timers(pc),a1
        lea     yx6_pcm_a(pc),a2
        moveq   #YX6_TIMERS-1,d1
.timer:
        move.l  a2,CH_ISR(a1)               ; the block that belongs to it
        lea     ISR_TOGGLE_OFF(a2),a0       ; each half installs the other,
        move.l  a0,ISR_TOGGLE_ON_NEXT(a2)   ; as a whole vector
        lea     ISR_TOGGLE_ON(a2),a0
        move.l  a0,ISR_TOGGLE_OFF_NEXT(a2)
        lea     CH_ROW(a1),a1
        lea     YX6_BLOCK(a2),a2
        dbra    d1,.timer
        rts

; -----------------------------------------------------------------------------
; yx6_assign: stream T's byte in d0, two bits per channel. Copies each
; named timer's row into that channel's descriptor - registers, vector,
; enable bit, keep mask, shift and handler block, everything that tells
; one timer from another. The claim stays where it is, because it belongs
; to the channel and not to the timer under it.
;
; A channel whose timer changes while it runs is not stopped here: the
; packer only moves a channel between streams, and the design note says
; why. Clobbers d0-d2, a0-a2.
; -----------------------------------------------------------------------------
yx6_assign:
        lea     yx6_desc_0(pc),a1
        moveq   #YX6_CHANNELS-1,d2
.channel:
        move.w  d0,d1
        and.w   #3,d1                   ; this channel's two bits
        mulu    #CH_ROW,d1              ; rare enough to pay for a multiply
        lea     yx6_timers(pc),a2
        adda.w  d1,a2
        move.l  (a2)+,d1                ; the row, eighteen bytes of it
        move.l  d1,(a1)+
        move.l  (a2)+,d1
        move.l  d1,(a1)+
        move.l  (a2)+,d1
        move.l  d1,(a1)+
        move.l  (a2)+,d1
        move.l  d1,(a1)+
        move.w  (a2),d1
        move.w  d1,(a1)+                ; a1 now stands on CH_CLAIMED, which
        lea     CH_SIZE-CH_ROW(a1),a1   ; is the channel's and stays put
        lsr.w   #2,d0                   ; the next channel's bits
        dbra    d2,.channel
        rts

; -----------------------------------------------------------------------------
; The action handlers: the script's verbs, one straight line each. The
; packer decided everything - which channel acts, which voice, restart or
; retune, who stops whose timer - so a handler only moves prepared bytes
; into prepared places and programs its timer in the contract's order.
;
; in: a4 = the channel's action ring byte (its count byte is one ring above),
;     a5 = the channel's descriptor, a3 = workspace, d3.l = N.
; preserves d3/a3/a0; clobbers d0-d2/d4-d5, a1-a2/a4-a5.
; -----------------------------------------------------------------------------
yx6_act:
        move.b  (a4),d0                 ; verb 7-5, voice 4-3, low bits 2-0
        move.w  d0,d1
        and.w   #$E0,d1
        lsr.w   #4,d1                   ; verb * 2
        move.w  yx6_verbs(pc,d1.w),d1
        jmp     yx6_verbs(pc,d1.w)
yx6_verbs:
        dc.w    yx6_resume-yx6_verbs    ; verb 0: a masked toggle returns
        dc.w    yx6_hold-yx6_verbs
        dc.w    yx6_release-yx6_verbs
        dc.w    yx6_toggle_start-yx6_verbs
        dc.w    yx6_retune-yx6_verbs
        dc.w    yx6_retrigger_start-yx6_verbs
        dc.w    yx6_pcm-yx6_verbs
        dc.w    yx6_pcm_preempt-yx6_verbs

; The voice's parameter ring byte - the toggle volume, the retrigger shape
; or the sample number ride the voice's own register stream, marked by the
; script.
; in: d0 = the action byte; out: a1 = the byte, d5 = voice*4.
yx6_voice:
        move.w  d0,d5
        lsr.w   #1,d5
        and.w   #$0C,d5                 ; voice * 4
        lea     yx6_parmoff(pc),a1
        move.l  0(a1,d5.w),d1
        movea.l YX6_CURSOR(a3),a1
        adda.l  d1,a1                   ; this frame's byte
        rts

yx6_release:                               ; STOP: low bit clear = stop the
        btst    #0,d0                   ; timer (a retrigger release); set =
        bne.s   .mask                   ; MASK only - a toggle release: the
        movea.w CH_CTRL(a5),a1          ; counter keeps counting and the
        move.w  CH_KEEP(a5),d1          ; square's half stays frozen. RMW
        and.b   d1,(a1)
        rts                             ; forms throughout: Timer C's nibble
.mask:                                  ; and the other enable bits belong
        movea.w CH_IER(a5),a1           ; to the machine
        move.w  CH_IERBIT(a5),d1
        bclr    d1,(a1)                 ; disabling drops any pending too
        rts

yx6_resume:                             ; unmask; reload the count if it
        btst    #0,d0                   ; moved, retrack the volume if it
        beq.s   .kept                   ; moved - the phase ran on through
        movea.l a4,a1                   ; the gap, exactly as a masked
        adda.l  d3,a1                   ; hardware timer does
        move.b  (a1),d1
        movea.w CH_DATA(a5),a2
        move.b  d1,(a2)                 ; a running timer loads a changed
.kept:                                  ; count at its next reload
        btst    #1,d0
        beq.s   .same
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l CH_ISR(a5),a2
        move.b  d1,ISR_TOGGLE_VOL(a2)
.same:
        movea.w CH_IER(a5),a1
        move.w  CH_IERBIT(a5),d1
        bset    d1,(a1)
        rts

yx6_hold:                               ; flags: 1 = reload the count, 2 =
        btst    #0,d0                   ; track the toggle volume, 4 =
        beq.s   .tracks                 ; track the retrigger shape - only
        movea.l a4,a1                   ; where the value changed
        adda.l  d3,a1
        move.b  (a1),d1
        movea.w CH_DATA(a5),a2
        move.b  d1,(a2)                 ; a running timer loads a changed
.tracks:                                ; count at its next reload
        moveq   #6,d1
        and.b   d0,d1
        beq.s   .done
        bsr     yx6_voice               ; the blob outgrew a short branch
        move.b  (a1),d1
        and.w   #15,d1
        movea.l CH_ISR(a5),a2
        btst    #2,d0
        bne.s   .shape
        move.b  d1,ISR_TOGGLE_VOL(a2)
        rts
.shape:
        move.b  d1,ISR_RETRIGGER_SHAPE(a2)
.done:
        rts

yx6_toggle_start:                          ; a fresh square restarts at phase
        bsr     yx6_voice               ; zero: the voice goes silent NOW,
        movea.l CH_ISR(a5),a2           ; and the first tick - one timer
        move.w  d5,d1                   ; period out - plays the loud half.
        lsr.w   #2,d1                   ; Retunes and resumes never come
        addq.w  #8,d1                   ; here: a held or masked square's
        move.b  d1,ISR_TOGGLE_ON_SEL(a2)    ; phase free-runs
        move.b  d1,ISR_TOGGLE_OFF_SEL(a2)
        lea     YX6_PSG,a1
        move.w  sr,-(sp)                ; select and write are two bus
        ori.w   #$0700,sr               ; cycles: no tick in between
        move.b  d1,(a1)
        clr.b   2(a1)                   ; the silent first half
        move.w  (sp)+,sr
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        move.b  d1,ISR_TOGGLE_VOL(a2)
        lea     ISR_TOGGLE_ON(a2),a1
        move.l  a1,d4
        bra.s   yx6_program

yx6_retune:                         ; the vector is NEVER touched: the
        bsr     yx6_voice               ; square's phase lives through every
        move.b  (a1),d1                 ; retune, and through a resume after
        and.w   #15,d1                  ; a PCM stream took the voice
        movea.l CH_ISR(a5),a2
        move.b  d1,ISR_TOGGLE_VOL(a2)
        moveq   #0,d4
        bra.s   yx6_program

yx6_retrigger_start:
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l CH_ISR(a5),a2
        move.b  d1,ISR_RETRIGGER_SHAPE(a2)
        lea     ISR_RETRIGGER(a2),a1
        move.l  a1,d4
        bra.s   yx6_program

yx6_pcm_preempt:                        ; arbitration, compiled: the losers'
        movea.l a0,a1                   ; timers stop FIRST, then the sample
        adda.l  d3,a1                   ; arms its own - the reference order.
        move.b  (a1),d2                 ; A channel has no fixed partner, so
                                        ; ring X - one stream above M - names
                                        ; the victims, a bit per timer channel
        lea     yx6_desc_0(pc),a1
        moveq   #YX6_CHANNELS-1,d1      ; the table bounds the walk: a bit
.victim:                                ; past the last channel names nobody
        lsr.b   #1,d2
        bcc.s   .spared
        movea.w CH_CTRL(a1),a2
        move.w  CH_KEEP(a1),d4
        and.b   d4,(a2)
.spared:
        lea     CH_SIZE(a1),a1
        dbra    d1,.victim
yx6_pcm:
        bsr     yx6_voice
        moveq   #0,d1
        move.b  (a1),d1                 ; the sample number, 0..31
        lsl.w   #2,d1
        lea     yx6_samples(pc),a2
        move.l  0(a2,d1.w),d2           ; its absolute address, from init
        movea.l CH_ISR(a5),a2
        move.l  d2,ISR_PCM_PTR(a2)
        move.w  d5,d1
        lsr.w   #2,d1
        addq.w  #8,d1
        move.b  d1,ISR_PCM_SEL(a2)
        move.l  a2,d4                   ; the tick handler is the block base
        ; falls through: a trigger and a retrigger program alike

; The timer program, in the order the reference demands: stop, vector,
; count, run - under the mask, since a tick between the stop and the run
; would fire into a half-programmed timer. A retune passes no vector.
; in: d0 = the action byte (prescaler in bits 2-0), d4 = the vector or 0,
;     a4 = the action ring byte (the count is one ring above).
yx6_program:
        movea.l a4,a1
        adda.l  d3,a1
        move.b  (a1),d2                 ; the count
        movea.w CH_CTRL(a5),a1
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        move.w  CH_KEEP(a5),d1
        and.b   d1,(a1)                 ; stop, keeping the other nibble
        tst.l   d4
        beq.s   .keep
        movea.w CH_VEC(a5),a2
        move.l  d4,(a2)                 ; the vector; a retune keeps it
.keep:
        movea.w CH_DATA(a5),a2
        move.b  d2,(a2)                 ; count
        and.b   #7,d0
        move.w  CH_SHIFT(a5),d1         ; Timer C's nibble is the high one
        lsl.b   d1,d0
        or.b    d0,(a1)                 ; run
        move.w  (sp)+,sr
        movea.w CH_IER(a5),a1           ; and enabled, whatever a mask
        move.w  CH_IERBIT(a5),d1        ; left: every program ends live
        bset    d1,(a1)
        rts

; -----------------------------------------------------------------------------
; yx6_gates: apply an absolute burst-gate state. Bit v of d0 mutes voice v:
; the write is overwritten with two nops, so the byte never reaches the chip
; and no branch ever enters the burst. Opening it again copies the movep
; back from a template. The script computed every edge at pack time.
; in: d0 bits 2-0 = the state, voices A/B/C; clobbers d0-d2/a1.
; -----------------------------------------------------------------------------
yx6_gates:
        lea     yx6_wB+YX6_WRITE_MOVEP(pc),a1   ; R8's movep, then R9's, R10's
        moveq   #3-1,d1
.gate:
        move.l  yx6_movep(pc),d2        ; open: the instruction itself, copied
        lsr.b   #1,d0                   ; from a template so no hand-encoded
        bcc.s   .set                    ; opcode can drift
        move.l  #$4E714E71,d2           ; muted: two nops - the displacement
.set:                                   ; word is part of the instruction
        move.l  d2,(a1)
        lea     YX6_WRITE_SIZE(a1),a1
        dbra    d1,.gate
        rts

; The template, never executed: yx6_gates copies these two words back over
; a muted write to open it again.
yx6_movep:
        movep.w d1,0(a2)
