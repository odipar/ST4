; -----------------------------------------------------------------------------
; YX6.S - streaming YM chiptune player for the plain 68000 (Atari ST)
;
; By Robbert van Dalen. Plays a .yx6 file: nineteen streams - fourteen frame
; streams, one per sound register, and five carrying the compiled effect
; script - every one packed as its own embedded ST4 container and decoded
; through its own small ring by ST4_wrap.S. Written by Claude (Anthropic's
; Claude Code) under Robbert's direction, based on ZX1 v1.5 by Einar Saukas
; and inspired by Steve Clarets' MinYMiser. Use it freely, including
; commercially, as long as your documentation says you used ZX1 through
; ST4 (see LICENSE).
;
; It plays the fourteen standard sound registers, loops, and plays what the
; YM format calls its special effects - digidrums, SID voices, the
; sync-buzzer - on MFP Timers A and D. In this player's own words those are
; TICK STREAMS: series of values written to one register between frames,
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
; THE DESIGN, in one paragraph. A tune is O frames long. Each of the nineteen
; streams gets its own ring of N bytes and its own saved decoder state, and
; is decoded C values at a time (C = 24 by default). On every VBL the player
; uses one value from each of the nineteen rings and refills exactly one
; stream - stream k on the VBL where the frame number modulo C is k. With
; C >= 19 every stream is refilled once per C frames, one refill per VBL, so
; the work per frame is flat, and ordered for the chip's benefit: the burst
; gates, then the fourteen register writes - the FRAME WRITE, leaving the
; VBL at a FIXED offset whatever the frame's tick streams cost - then the
; script's actions, then one ST4_resume of C bytes. Since C divides N and a
; call never reaches the ring end, ST4_wrap.S is the decoder that fits: the
; player counts the calls itself and wraps the write pointer when it lands
; on the ring end.
;
; THE SCRIPT. The last five rings are not frame streams: their bytes never
; reach a register. They carry the COMPILED EFFECT SCRIPT, in which the
; packer replayed the reference player's whole decision logic over the
; timeline - held or new, retune or restart, who stops whose timer, when a
; drum's gate reopens - and wrote the outcomes down as prepared actions.
; Ring M says what acts this frame (almost always: nothing, one test);
; A1/P1 and A2/P2 carry each tick channel's action byte and timer count.
; The handlers copy prepared values into Timers A and D and this blob's
; tick handlers - digidrum, SID square, sync-buzzer - and compare NOTHING
; at run time. The drummed voice is DISCONNECTED by routing baked into the
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
; The workspace holds the player's own fields, the nineteen decoder states
; and the nineteen rings, so its size depends on the ring size in the file:
;
;   YX6_SIZE = YX6_FIXED + 19 * N       ; 1266 + 19 * N bytes
;
; For the default 960-byte rings that is 19506 bytes. A program that knows N
; at build time can reserve it with
;
;       ds.b    YX6_FIXED+(YX6_STREAMS*960)
;
; where the parentheses matter: rmac evaluates expressions left to right.
;
; CALLS AND REGISTERS
;
;   YX6_init   in   a0 = .yx6 file, at an even address, a1 = workspace
;              out  d0 = 0 accepted, -1 not a v5 yx6 file with 19 streams
;                        packed at this build's unit size
;              Claims MFP Timers A and D for the effect slots: stops them,
;              parks their vectors, drops stale pendings, enables and
;              unmasks both. It saves NOTHING - the machine state is the
;              host's, see assumption 5. Timer B stays free for rasters,
;              Timer C stays the system's. Pair every init with a stop
;   YX6_play   in   a0 = workspace
;              out  d0 = 0 played a frame
;                   d0 = 1 played a frame, and that frame ended the tune: the
;                          next one is the loop frame L
;                   d0 = -1 the tune is over and nothing was written, which
;                          only a tune packed with -o can report
;   YX6_stop   in   a0 = workspace
;              Quiesces: stops both timers, disables their interrupts,
;              drops pendings, silences the three voices. Restores nothing
;   CLOBBERED  d0-d5, a0-a5. d6, d7 and a6 survive, as with ST4 - whose
;              decoder state spans a4 and a5, which is why they no longer do.
;
; YX6_play writes to the sound chip at $FFFF8800, so it must run in supervisor
; mode - which a VBL handler already does. It masks interrupts around the
; fourteen writes, because selecting a register and writing it are two bus
; cycles and TOS's own handlers use the chip too. Its effect stage programs
; the two timers YX6_init claimed, vectors included. Beyond the chip, those
; timers and the workspace, the player writes only ITSELF: it is
; self-modifying code. The slot descriptors, the prepared tables and the
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
;   1. the file comes from the yx6 packer, which enforces C >= 19, N mod C = 0,
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
;      the USP, which the drum tick parks a0 in. A demo that has already
;      taken the machine over saves nothing at all. YX6_player.S is the
;      worked example of the polite version
; -----------------------------------------------------------------------------

; A host that never runs user-mode code while the player is live - a demo
; under its own takeover, say - may define YX6_SUPER_HOST equ 1 before
; including this file: the slot-2 drum tick then parks a0 in the then-unused
; USP (4 cycles each way instead of 12 through the stack; slot 1's drum
; always uses the stack, because Timer A nests into Timer D's parked
; windows and must never overwrite one). The park restores a0
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
PERF_PAD        equ     YX6_PERF*10     ; the color prologue's bytes, sitting
                                        ; ahead of every fixed patch offset
PERF_SYNC       equ     8000            ; the sync loop's ceiling: a whole
                                        ; frame's worth of turns, so a host
                                        ; that calls from anywhere still runs

YX6_STREAMS     equ     19              ; R0..R13, then the compiled effect
                                        ; script: M, then each slot's action
                                        ; and count bytes - decoded like any
                                        ; register
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
YX6_SLOT        equ     40              ; word  refill slot, 0..C-1
YX6_INDEX       equ     42              ; word  stream index, during init only
YX6_LOOPS       equ     44              ; word  nonzero when the tune loops
YX6_EFXOFF      equ     46              ; long  14*N: the cursor's ring to M's
YX6_STATE       equ     50              ; 19 decoder states, from an even base

; Each effect slot's descriptor holds only what tells the two slots apart:
; its timer's registers, its vector, its partner's control register (the
; DRUM_ARB action stops that timer first), and its tick-handler block. No
; remembered state - the script compiled every comparison at pack time. The
; hardware addresses are words: movea.w sign-extends $FAxx to $FFFFFAxx.
SD_CTRL         equ     0               ; word  timer control register
SD_DATA         equ     2               ; word  timer data register
SD_VEC          equ     4               ; word  timer vector
SD_OTHER_CTRL   equ     6               ; word  the partner slot's control
SD_IER          equ     8               ; word  the timer's enable register
SD_IERBIT       equ     10              ; word  and its bit: a SID release
                                        ; only MASKS the interrupt, so the
                                        ; counter keeps counting and a
                                        ; resume is phase-continuous
SD_ISR          equ     12              ; long  the slot's tick handlers
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
YH_DRUMS        equ     28              ; long: the drum table, 0 when none
YH_DRUMCOUNT    equ     32              ; word
YH_INTRO_TABLE  equ     34              ; 19 offsets: the frames before the loop
YH_LOOP_TABLE   equ     YH_INTRO_TABLE+(YX6_STREAMS*4)

YX6_MAGIC       equ     $59583621       ; 'YX6!'
YX6_VERSION     equ     5               ; 5: the compiled effect script
YX6_FLAG_LOOPS  equ     1               ; header flag: the tune loops
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
        move.b  #\number,(a2)           ; select
        move.b  $7FFF(a1),2(a2)         ; and write - SMC: k*N, set at init
        .endm

; Jump table: bra.w keeps 4-byte slots for a program that loads YX6 as a blob.
                bra.w   YX6_init
                bra.w   YX6_play
                bra.w   YX6_stop

; -----------------------------------------------------------------------------
; YX6_init: a0 = .yx6 file, a1 = workspace. Sets up eighteen decoders, decodes
; each stream's first group so frame 0 can be played immediately, patches the
; blob's run-time addresses and the burst's ring displacements, and claims
; MFP Timers A and D for the effect slots - saving nothing, per assumption 5.
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
; SID volume, buzzer shape and drum number through.
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

; The drum table becomes a table of absolute sample addresses, so a trigger
; is one indexed load instead of an entry walk: the DRUM action's byte is
; the sample number, pre-scaled to index these longs.
        movea.l YX6_FILE(a3),a0
        move.l  YH_DRUMS(a0),d1
        beq.s   .no_drums
        movea.l a0,a2
        adda.l  d1,a2                   ; the entries: offset.l, length.w
        lea     yx6_samples(pc),a1
        move.w  YH_DRUMCOUNT(a0),d0
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
; N at 2520 or less, so 13*N fits the signed word. Each YX6_WRITE is ten
; bytes with its displacement at +6; R7 and R13 read out of line.
        move.w  YX6_N(a3),d2
        moveq   #0,d1                   ; k*N, accumulated
        lea     yx6_wA+6(pc),a0
        moveq   #6,d0                   ; R0..R6
.wpatchA:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     10(a0),a0
        dbra    d0,.wpatchA
        lea     yx6_w7+2(pc),a0         ; R7 reads through d1
        move.w  d1,(a0)
        add.w   d2,d1
        lea     yx6_wB+6(pc),a0
        moveq   #4,d0                   ; R8..R12
.wpatchB:
        move.w  d1,(a0)
        add.w   d2,d1
        lea     10(a0),a0
        dbra    d0,.wpatchB
        lea     yx6_w13+2(pc),a0
        move.w  d1,(a0)
        bsr     yx6_link                ; gates open, handlers linked

; Claim Timers A and D: park our handlers on the vectors and leave both
; timers stopped but enabled and unmasked. Nothing is saved - the machine
; state is the host's, see assumption 5 in the header. bset/bclr only:
; Timer C's bits and the ACIA's live in the same registers.
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        clr.b   $FFFFFA19.w             ; Timer A stopped
        andi.b  #$F0,$FFFFFA1D.w        ; Timer D stopped; Timer C untouched
        lea     yx6_parkA(pc),a0
        move.l  a0,$134.w
        lea     yx6_parkD(pc),a0
        move.l  a0,$110.w
        bclr    #5,$FFFFFA0B.w          ; drop stale pendings
        bclr    #4,$FFFFFA0D.w
        bset    #5,$FFFFFA07.w          ; Timer A enabled and unmasked
        bset    #5,$FFFFFA13.w
        bset    #4,$FFFFFA09.w          ; Timer D likewise
        bset    #4,$FFFFFA15.w
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
        move.l  YH_LOOP_TABLE(a0,d0.w),d0
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
                                        ; gives up rather than hangs.
        move.w  #$700,$FFFF8240.w       ; this frame's work, red - first,
        .endif                          ; so the monitor never delays the
                                        ; burst; the timers' bar follows

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
        btst    #2,d0                   ; the gate mask changed?
        beq.s   .burst                  ; bits 5-3 carried the new state
        lsr.b   #3,d0
        bsr     yx6_gates
.burst:
        movea.l YX6_CURSOR(a3),a1       ; the cursor: every register's ring
        lea     YX6_PSG,a2              ; byte is a patched k*N above it

; Selecting a register and writing it are two bus cycles. An interrupt that
; uses the sound chip in between - TOS has several, its floppy motor timeout
; among them - would leave this write going to whatever register it selected,
; so the burst runs masked. Cheap: about 24 cycles for the whole frame.
        move.w  sr,-(sp)
        ori.w   #$0700,sr

yx6_wA:
        YX6_WRITE 0                     ; channel A period, fine and coarse
        YX6_WRITE 1
        YX6_WRITE 2                     ; channel B
        YX6_WRITE 3
        YX6_WRITE 4                     ; channel C
        YX6_WRITE 5
        YX6_WRITE 6                     ; noise period

        move.b  #YX6_MIXER,(a2)         ; R7 also carries the ST's I/O port
yx6_w7:
        move.b  $7FFF(a1),d1            ; directions, which port A needs kept
        or.b    #YX6_PORTS,d1           ; as outputs for the floppy select
        move.b  d1,2(a2)

yx6_wB:
        YX6_WRITE 8                     ; the three volumes
        YX6_WRITE 9
        YX6_WRITE 10
        YX6_WRITE 11                    ; envelope period
        YX6_WRITE 12

yx6_w13:
        move.b  $7FFF(a1),d1            ; R13 restarts the envelope whenever it
        cmp.b   #YX6_NO_ENV,d1          ; is written, so $FF means leave it be
        beq.s   .envelope_held
        move.b  #YX6_ENVELOPE,(a2)
        move.b  d1,2(a2)
.envelope_held:
        move.w  (sp)+,sr

; The actions, now that the chip has its frame: replay the compiled script.
; One master byte says what acts - almost always nothing. The action bytes
; were decided at pack time; the handlers copy prepared values to prepared
; places and compare nothing. a0 still points at ring M.
        move.b  (a0),d0
        andi.b  #3,d0                   ; the slots' bits; gates are done
        beq.s   .effects_done
        moveq   #0,d3
        move.w  YX6_N(a3),d3
        lsr.b   #1,d0                   ; bit 0: slot 1 acts
        bcc.s   .slot2
        lea     yx6_desc1(pc),a5
        movea.l a0,a4
        adda.l  d3,a4                   ; ring A1 = M + N
        bsr     yx6_act
.slot2:
        move.b  (a0),d0                 ; a0 survives yx6_act; d0 does not
        btst    #1,d0                   ; bit 1: slot 2 acts
        beq.s   .effects_done
        lea     yx6_desc2(pc),a5
        movea.l a0,a4
        adda.l  d3,a4
        adda.l  d3,a4
        adda.l  d3,a4                   ; ring A2 = M + 3N
        bsr     yx6_act
.effects_done:

; Refill the stream whose turn it is. Slots 19..C-1 exist when C is larger
; than the stream count; they simply have nothing to do.
        move.w  YX6_SLOT(a3),d0
        cmp.w   #YX6_STREAMS,d0
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
; YX6_stop: a0 = workspace. Quiesces what YX6_init claimed - both timers
; stopped, their interrupts disabled and masked, pendings dropped - then
; silences the three voices. It restores NOTHING: the host that saved the
; machine state hands it back itself, after this returns. Tone and envelope
; settings stay, and a fresh YX6_init starts the tune over.
; -----------------------------------------------------------------------------
YX6_stop:
        movea.l a0,a3
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        clr.b   $FFFFFA19.w             ; both timers stopped,
        andi.b  #$F0,$FFFFFA1D.w
        bclr    #5,$FFFFFA07.w          ; disabled and masked - the claim
        bclr    #5,$FFFFFA13.w          ; handed back bit by bit
        bclr    #4,$FFFFFA09.w
        bclr    #4,$FFFFFA15.w
        bclr    #5,$FFFFFA0B.w          ; and no pending ticks of ours
        bclr    #4,$FFFFFA0D.w
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

; -----------------------------------------------------------------------------
; The effect machinery. The per-frame stage lives inlined in YX6_play; what
; follows is everything it drives: the two slot descriptors, the tick
; handlers, and the one slot machine that serves both timers.
; -----------------------------------------------------------------------------

; The slot descriptors - see the SD_ equates. Slot 1 owns Timer A, slot 2
; Timer D, whose control nibble is shared with Timer C and only ever changed
; read-modify-write; the slot machine uses that form for both, which on
; Timer A writes the same values a clear would.
        even
yx6_desc1:
        dc.w    $FA19,$FA1F,$0134       ; control, data, vector
        dc.w    $FA1D                   ; the partner's control register
        dc.w    $FA07,5                 ; the enable register and its bit
        dc.l    0                       ; ISR block: yx6_link
yx6_desc2:
        dc.w    $FA1D,$FA25,$0110
        dc.w    $FA19
        dc.w    $FA09,4
        dc.l    0

; The two prepared tables the script indexes into: the drums' absolute
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
; The two slots' handlers are the same text twice, and MUST stay
; byte-congruent: the action handlers reach every patched operand through
; ISR_ offsets measured on the A block. The rig checks the congruence from
; the symbol table. The park entries - where YX6_init aims the vectors
; while no effect runs - are the handlers' own tails.
;
; The timers STACK: each handler drops the IPL to 5 as soon as its PSG
; select/data pair is complete, so the MFP's higher-priority channel -
; Timer A over Timer D - can nest instead of waiting a whole tick. The
; nesting is strictly one-way: Timer A interrupts Timer D's handlers,
; never the reverse (SEI mode admits only higher MFP priorities while an
; in-service bit is set), and nothing of the player's sits above Timer A.
; That direction is why the USP park belongs to the D-side drum alone: the
; A-side drum - the one that can nest into a parked window - always uses
; the stack, so a parked USP survives. The drop must never come before
; the handler's LAST chip write: a nested tick re-selects the PSG.
;
; The patch offsets are load-bearing (all fixed ones shift by PERF_PAD when
; the raster monitor's color prologue sits at each block's top):
;   drum:  select immediate at +3, sample address at +8
;   sid:   select at +2 and volume at +4 inside the imm long; the partner's
;          whole vector at +14, past the IPL drop, patched by yx6_link
;   buzz:  the shape at +4
; -----------------------------------------------------------------------------

; One drum tick: the sample byte goes straight to the voice's volume register.
; The byte after the last has bit 7 set, so the same move answers "done": park
; the volume mid-scale against the click, stop the timer, drop the flag.
yx6_drumA:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)       ; Timer A works in green
        move.w  #$070,$FFFF8240.w
        .endif
        move.b  #8,$FFFF8800.w          ; SMC PERF_PAD+3: select the volume
        move.b  $12345678,$FFFF8802.w   ; SMC PERF_PAD+8: the next sample
        bmi.s   .ended
        move.w  #$2500,sr               ; pair done: the other timer may stack
        move.l  a0,-(sp)                ; the stack, never the USP: this side
        lea     yx6_drumA+8+PERF_PAD(pc),a0 ; nests into parked windows
        addq.l  #1,(a0)                 ; step the operand itself
        .if YX6_PERF
        lea     yx6_perf_acc(pc),a0
        add.w   #21,(a0)                ; ~a drum tick, in 10-cycle quanta
        .endif
        movea.l (sp)+,a0
        .if YX6_PERF
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$DF,$FFFFFA0F.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w         ; middle volume: no click
        andi.b  #$F0,$FFFFFA19.w        ; the sample ran out: stop Timer A -
        .if YX6_PERF                    ; the script already knew this frame
        move.l  a0,-(sp)                ; was coming: gate and mixer follow
        lea     yx6_perf_acc(pc),a0     ; at the next frame boundary
        add.w   #23,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
yx6_parkA:
        move.b  #$DF,$FFFFFA0F.w
        rte

; The SID square: each tick writes one half of the square - the volume or
; silence - and installs the other half as the next tick, one word into the
; vector. Select and data land in one move.l: the immediate's bytes hit
; $8800 (select) and $8802 (data) by bus layout.
yx6_sidA_on:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$070,$FFFF8240.w
        .endif
        move.l  #$08000F00,$FFFF8800.w  ; SMC PERF_PAD+2 select, +4 volume
        move.w  #$2500,sr
        move.l  #0,$134.w               ; SMC PERF_PAD+14: the quiet half is
        .if YX6_PERF                    ; next - the whole vector, so the
        move.l  a0,-(sp)                ; blob may straddle a 64K boundary
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)                ; ~a SID half, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$DF,$FFFFFA0F.w
        rte
yx6_sidA_off:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$070,$FFFF8240.w
        .endif
        move.l  #$08000000,$FFFF8800.w  ; SMC PERF_PAD+2 select; silence
        move.w  #$2500,sr
        move.l  #0,$134.w               ; SMC PERF_PAD+14: the loud half next
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$DF,$FFFFFA0F.w
        rte

; The sync-buzzer tick: writing the shape to R13 restarts the envelope, and
; that restart at the timer's rate is the whole effect.
yx6_buzzA:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$070,$FFFF8240.w
        .endif
        move.l  #$0D000000,$FFFF8800.w  ; SMC PERF_PAD+4: the shape
        move.w  #$2500,sr
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #12,(a0)                ; ~a buzzer tick, in 10-cycle quanta
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$DF,$FFFFFA0F.w
        rte

; Slot 2's instances: Timer D's stop, vector and in-service bit, otherwise
; the A block byte for byte.
yx6_drumD:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)       ; Timer D works in blue
        move.w  #$007,$FFFF8240.w
        .endif
        move.b  #8,$FFFF8800.w
        move.b  $12345678,$FFFF8802.w
        bmi.s   .ended
        move.w  #$2500,sr               ; Timer A may nest from here on, and
        .if YX6_SUPER_HOST              ; never parks the USP itself - so
        move    a0,usp                  ; this side may
        .else
        move.l  a0,-(sp)
        .endif
        lea     yx6_drumD+8+PERF_PAD(pc),a0
        addq.l  #1,(a0)
        .if YX6_PERF
        lea     yx6_perf_acc(pc),a0
        add.w   #21,(a0)
        .endif
        .if YX6_SUPER_HOST
        move    usp,a0
        .else
        movea.l (sp)+,a0
        .endif
        .if YX6_PERF
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$EF,$FFFFFA11.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w
        andi.b  #$F0,$FFFFFA1D.w
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #23,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
yx6_parkD:
        move.b  #$EF,$FFFFFA11.w
        rte

yx6_sidD_on:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$007,$FFFF8240.w
        .endif
        move.l  #$08000F00,$FFFF8800.w
        move.w  #$2500,sr
        move.l  #0,$110.w
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$EF,$FFFFFA11.w
        rte
yx6_sidD_off:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$007,$FFFF8240.w
        .endif
        move.l  #$08000000,$FFFF8800.w
        move.w  #$2500,sr
        move.l  #0,$110.w
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #15,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$EF,$FFFFFA11.w
        rte

yx6_buzzD:
        .if YX6_PERF
        move.w  $FFFF8240.w,-(sp)
        move.w  #$007,$FFFF8240.w
        .endif
        move.l  #$0D000000,$FFFF8800.w
        move.w  #$2500,sr
        .if YX6_PERF
        move.l  a0,-(sp)
        lea     yx6_perf_acc(pc),a0
        add.w   #12,(a0)
        movea.l (sp)+,a0
        move.w  (sp)+,$FFFF8240.w
        .endif
        move.b  #$EF,$FFFFFA11.w
        rte

        .if YX6_PERF
yx6_perf_acc:
        dc.w    0                       ; the ticks' estimated cost since the
        .endif                          ; last frame, in 10-cycle quanta

; Where the patched operands sit, measured on the A block; the D block is
; byte-congruent, so the same offsets serve both through SD_ISR.
ISR_DRUM_SEL    equ     PERF_PAD+3
ISR_DRUM_PTR    equ     PERF_PAD+8
ISR_SIDON       equ     yx6_sidA_on-yx6_drumA
ISR_SIDON_SEL   equ     ISR_SIDON+PERF_PAD+2
ISR_SID_VOL     equ     ISR_SIDON+PERF_PAD+4
ISR_SIDON_NEXT  equ     ISR_SIDON+PERF_PAD+14
ISR_SIDOFF      equ     yx6_sidA_off-yx6_drumA
ISR_SIDOFF_SEL  equ     ISR_SIDOFF+PERF_PAD+2
ISR_SIDOFF_NEXT equ     ISR_SIDOFF+PERF_PAD+14
ISR_BUZZ        equ     yx6_buzzA-yx6_drumA
ISR_BUZZ_SHAPE  equ     ISR_BUZZ+PERF_PAD+4

; -----------------------------------------------------------------------------
; yx6_link, from YX6_init: the run-time addresses only a loaded player knows.
; Every burst gate opens (a previous session's mute must not leak), each
; descriptor gets its ISR block, and the SID halves get each other's whole
; vector - it never changes, so it is patched once here rather than on
; every SID start.
; -----------------------------------------------------------------------------
yx6_link:
        moveq   #0,d0
        bsr     yx6_gates
        lea     yx6_desc1(pc),a0
        lea     yx6_drumA(pc),a2
        move.l  a2,SD_ISR(a0)
        bsr.s   .partners
        lea     yx6_desc2(pc),a0
        lea     yx6_drumD(pc),a2
        move.l  a2,SD_ISR(a0)
.partners:
        lea     ISR_SIDOFF(a2),a0       ; each half installs the other,
        move.l  a0,ISR_SIDON_NEXT(a2)   ; as a whole vector
        lea     ISR_SIDON(a2),a0
        move.l  a0,ISR_SIDOFF_NEXT(a2)
        rts

; -----------------------------------------------------------------------------
; The action handlers: the script's verbs, one straight line each. The
; packer decided everything - which slot acts, which voice, restart or
; retune, who stops whose timer - so a handler only moves prepared bytes
; into prepared places and programs its timer in the contract's order.
;
; in: a4 = the slot's action ring byte (its count byte is one ring above),
;     a5 = the slot's descriptor, a3 = workspace, d3.l = N.
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
        dc.w    yx6_resume-yx6_verbs    ; verb 0: the masked SID comes back
        dc.w    yx6_held-yx6_verbs
        dc.w    yx6_halt-yx6_verbs
        dc.w    yx6_sid_start-yx6_verbs
        dc.w    yx6_sid_retune-yx6_verbs
        dc.w    yx6_buzz_start-yx6_verbs
        dc.w    yx6_drum-yx6_verbs
        dc.w    yx6_drum_arb-yx6_verbs

; The voice's parameter ring byte - the SID volume, the buzzer shape or the
; drum number ride the voice's own register stream, marked by the script.
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

yx6_halt:                               ; STOP: low bit clear = stop the
        btst    #0,d0                   ; timer (a buzzer release); set =
        bne.s   .mask                   ; MASK only - a SID release: the
        movea.w SD_CTRL(a5),a1          ; counter keeps counting and the
        andi.b  #$F0,(a1)               ; square's half stays frozen. RMW
        rts                             ; forms throughout: Timer C's nibble
.mask:                                  ; and the other enable bits belong
        movea.w SD_IER(a5),a1           ; to the machine
        move.w  SD_IERBIT(a5),d1
        bclr    d1,(a1)                 ; disabling drops any pending too
        rts

yx6_resume:                             ; unmask; reload the count if it
        btst    #0,d0                   ; moved, retrack the volume if it
        beq.s   .kept                   ; moved - the phase ran on through
        movea.l a4,a1                   ; the gap, exactly as a masked
        adda.l  d3,a1                   ; hardware timer does
        move.b  (a1),d1
        movea.w SD_DATA(a5),a2
        move.b  d1,(a2)                 ; a running timer loads a changed
.kept:                                  ; count at its next reload
        btst    #1,d0
        beq.s   .same
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l SD_ISR(a5),a2
        move.b  d1,ISR_SID_VOL(a2)
.same:
        movea.w SD_IER(a5),a1
        move.w  SD_IERBIT(a5),d1
        bset    d1,(a1)
        rts

yx6_held:                               ; flags: 1 = reload the count, 2 =
        btst    #0,d0                   ; track the SID volume, 4 = track
        beq.s   .tracks                 ; the buzzer shape - only on frames
        movea.l a4,a1                   ; where the value changed
        adda.l  d3,a1
        move.b  (a1),d1
        movea.w SD_DATA(a5),a2
        move.b  d1,(a2)                 ; a running timer loads a changed
.tracks:                                ; count at its next reload
        moveq   #6,d1
        and.b   d0,d1
        beq.s   .done
        bsr.s   yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l SD_ISR(a5),a2
        btst    #2,d0
        bne.s   .shape
        move.b  d1,ISR_SID_VOL(a2)
        rts
.shape:
        move.b  d1,ISR_BUZZ_SHAPE(a2)
.done:
        rts

yx6_sid_start:                          ; a fresh square restarts at phase
        bsr     yx6_voice               ; zero: the voice goes silent NOW,
        movea.l SD_ISR(a5),a2           ; and the first tick - one timer
        move.w  d5,d1                   ; period out - plays the loud half.
        lsr.w   #2,d1                   ; Retunes and resumes never come
        addq.w  #8,d1                   ; here: a held or masked square's
        move.b  d1,ISR_SIDON_SEL(a2)    ; phase free-runs
        move.b  d1,ISR_SIDOFF_SEL(a2)
        lea     YX6_PSG,a1
        move.w  sr,-(sp)                ; select and write are two bus
        ori.w   #$0700,sr               ; cycles: no tick in between
        move.b  d1,(a1)
        clr.b   2(a1)                   ; the silent first half
        move.w  (sp)+,sr
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        move.b  d1,ISR_SID_VOL(a2)
        lea     ISR_SIDON(a2),a1
        move.l  a1,d4
        bra.s   yx6_program

yx6_sid_retune:                         ; the vector is NEVER touched: the
        bsr     yx6_voice               ; square's phase lives through every
        move.b  (a1),d1                 ; retune, and through a resume after
        and.w   #15,d1                  ; a drum borrowed the voice
        movea.l SD_ISR(a5),a2
        move.b  d1,ISR_SID_VOL(a2)
        moveq   #0,d4
        bra.s   yx6_program

yx6_buzz_start:
        bsr     yx6_voice
        move.b  (a1),d1
        and.w   #15,d1
        movea.l SD_ISR(a5),a2
        move.b  d1,ISR_BUZZ_SHAPE(a2)
        lea     ISR_BUZZ(a2),a1
        move.l  a1,d4
        bra.s   yx6_program

yx6_drum_arb:                           ; arbitration, compiled: the loser's
        movea.w SD_OTHER_CTRL(a5),a1    ; timer stops FIRST, then the drum
        andi.b  #$F0,(a1)               ; arms its own - the reference order
yx6_drum:
        bsr     yx6_voice
        moveq   #0,d1
        move.b  (a1),d1                 ; the sample number, 0..31
        lsl.w   #2,d1
        lea     yx6_samples(pc),a2
        move.l  0(a2,d1.w),d2           ; its absolute address, from init
        movea.l SD_ISR(a5),a2
        move.l  d2,ISR_DRUM_PTR(a2)
        move.w  d5,d1
        lsr.w   #2,d1
        addq.w  #8,d1
        move.b  d1,ISR_DRUM_SEL(a2)
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
        movea.w SD_CTRL(a5),a1
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        andi.b  #$F0,(a1)               ; stop
        tst.l   d4
        beq.s   .keep
        movea.w SD_VEC(a5),a2
        move.l  d4,(a2)                 ; the vector; a retune keeps it
.keep:
        movea.w SD_DATA(a5),a2
        move.b  d2,(a2)                 ; count
        and.b   #7,d0
        or.b    d0,(a1)                 ; run
        move.w  (sp)+,sr
        movea.w SD_IER(a5),a1           ; and enabled, whatever a mask
        move.w  SD_IERBIT(a5),d1        ; left: every program ends live
        bset    d1,(a1)
        rts

; -----------------------------------------------------------------------------
; yx6_gates: apply an absolute burst-gate state. Bit v of d0 mutes voice v:
; the volume write's destination displacement flips to 0, so it lands on
; the select register, where the very next select overrides it - no branch
; ever enters the burst. The script computed every edge at pack time.
; in: d0 bits 2-0 = the state, voices A/B/C; clobbers d0-d2/a1.
; -----------------------------------------------------------------------------
yx6_gates:
        lea     yx6_wB+8(pc),a1         ; a YX6_WRITE is ten bytes; R8's
        moveq   #3-1,d1                 ; destination word sits at +8
.gate:
        moveq   #2,d2                   ; open: the write reaches the data
        lsr.b   #1,d0                   ; register
        bcc.s   .set
        moveq   #0,d2                   ; muted
.set:
        move.w  d2,(a1)
        lea     10(a1),a1
        dbra    d1,.gate
        rts
