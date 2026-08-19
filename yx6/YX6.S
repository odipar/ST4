; -----------------------------------------------------------------------------
; YX6.S - streaming YM chiptune player for the plain 68000 (Atari ST)
;
; By Robbert van Dalen. Plays a .yx6 file: fourteen YM2149 register streams,
; each packed as its own embedded ST4 container and decoded through its own
; small ring by ST4_wrap.S. Written by Claude (Anthropic's Claude Code) under
; Robbert's direction, based on ZX1 v1.5 by Einar Saukas and inspired by Steve
; Clarets' MinYMiser. Use it freely, including commercially, as long as your
; documentation says you used ZX1 through ST4 (see LICENSE).
;
; It plays the fourteen standard sound registers, and loops. The YM6 special
; effects - SID voice, digidrum, sinus-SID, sync-buzzer - are not played, and
; the yx6 packer has already masked their bits out of the register values.
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
; THE DESIGN, in one paragraph. A tune is O frames long. Each of the fourteen
; registers gets its own ring of N bytes and its own saved decoder state, and
; is decoded C values at a time (C = 16 by default). On every VBL the player
; uses one value from each of the fourteen rings and refills exactly one
; register - register k on the VBL where the frame number modulo C is k. With
; C >= 14 every register is refilled once per C frames, one refill per VBL, so
; the work per frame is flat: fourteen register writes plus one ST4_resume of
; C bytes. Since C divides N and a call never reaches the ring end, ST4_wrap.S
; is the decoder that fits: the player counts the calls itself and wraps the
; write pointer when it lands on the ring end.
;
; LOOPING. A packed stream can only be restarted from its beginning, so the packer
; splits every register at the loop frame L: an intro stream for frames [0, L)
; and a loop stream for [L, O). When a register's section runs out mid-refill
; the player starts its loop stream over - a fresh decoder writing on into the
; same ring - so the rings always hold one continuous sequence of values and
; the read side never learns that anything happened. Nothing requires L to fall
; on a group boundary: a refill that straddles the split simply decodes two
; pieces. A tune that loops from frame 0 has no intro streams at all.
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
;       bsr     YX6_stop                ; silence the three voices
;
; The workspace holds the player's own fields, the eighteen decoder states and
; the eighteen rings, so its size depends on the ring size in the file:
;
;   YX6_SIZE = YX6_FIXED + 18 * N       ; 1248 + 18 * N bytes
;
; For the default 960-byte rings that is 18528 bytes. A program that knows N
; at build time can reserve it with
;
;       ds.b    YX6_FIXED+(YX6_STREAMS*960)
;
; where the parentheses matter: rmac evaluates expressions left to right.
;
; CALLS AND REGISTERS
;
;   YX6_init   in   a0 = .yx6 file, at an even address, a1 = workspace
;              out  d0 = 0 accepted, -1 not a v4 yx6 file with 18 streams
;                        packed at this build's unit size
;              Takes over MFP Timers A and D for the effect slots: vectors,
;              control and data registers, enable and mask bits - all saved
;              and restored by YX6_stop. Timer B stays free for rasters,
;              Timer C stays the system's. Pair every init with a stop
;   YX6_play   in   a0 = workspace
;              out  d0 = 0 played a frame
;                   d0 = 1 played a frame, and that frame ended the tune: the
;                          next one is the loop frame L
;                   d0 = -1 the tune is over and nothing was written, which
;                          only a tune packed with -o can report
;   YX6_stop   in   a0 = workspace: the MFP state to hand back
;   CLOBBERED  d0-d5, a0-a5. d6, d7 and a6 survive, as with ST4 - whose
;              decoder state spans a4 and a5, which is why they no longer do.
;
; YX6_play writes to the sound chip at $FFFF8800, so it must run in supervisor
; mode - which a VBL handler already does. It masks interrupts around the
; fourteen writes, because selecting a register and writing it are two bus
; cycles and TOS's own handlers use the chip too. Beyond that it performs no
; side effect: no interrupt setup, no memory allocation, nothing outside the
; workspace.
;
; ASSUMPTIONS (unchecked beyond the magic, version and stream count):
;
;   1. the file comes from the yx6 packer, which enforces C >= 14, N mod C = 0,
;      N >= 2C, offsets that stay inside the ring, and - at ST4_UNIT above 1 -
;      a tune length, loop frame and C that are whole units, so every budget
;      this player passes to the decoder divides cleanly
;   2. the workspace is at an even address and large enough for the file's N
;   3. YX6_play is called once per frame and never after it returns -1.
;      Calling it again is harmless - it returns -1 and writes nothing - which
;      is what keeps ST4_wrap's "never call the decoder again" rule intact
;   4. a looping tune plays for as long as you keep calling: d0 = 1 marks each
;      time round, and YX6_init is only needed to start again from frame 0
; -----------------------------------------------------------------------------

YX6_STREAMS     equ     18              ; R0..R13, then E1 T1 E2 T2: the two
                                        ; effect slots' control and timer-count
                                        ; streams, decoded like any register
YX6_REGISTERS   equ     14              ; the streams the burst writes
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
YX6_EFXOFF      equ     46              ; long  14*N: the cursor's ring to E1's
YX6_R7OFF       equ     50              ; long  7*N: the cursor's ring to R7's
YX6_E1LAST      equ     54              ; byte  slot 1's control byte last frame
YX6_E2LAST      equ     55              ; byte  slot 2's
YX6_T1LAST      equ     56              ; byte  the count each timer last got:
YX6_T2LAST      equ     57              ; a held effect reloads its data
                                        ; register only when the count CHANGES,
                                        ; because a write that lands while the
                                        ; counter passes 01 loads an
                                        ; indeterminate value - pitch glitches
; The MFP state YX6_init takes over and YX6_stop hands back: both timer
; vectors, both control registers, both data registers, and which of the four
; enable/mask bits were set (bit 0 IERA5, 1 IMRA5, 2 IERB4, 3 IMRB4).
YX6_OLDTA       equ     58              ; long  vector $134, Timer A
YX6_OLDTD       equ     62              ; long  vector $110, Timer D
YX6_OLDTACR     equ     66              ; byte
YX6_OLDTCDCR    equ     67              ; byte
YX6_OLDTADR     equ     68              ; byte
YX6_OLDTDDR     equ     69              ; byte
YX6_OLDMFP      equ     70              ; byte  the four enable/mask bits
                                        ; (71 pads the longs below even)
YX6_PARM1       equ     72              ; long  (8+voice)*N: the cursor's ring
YX6_PARM2       equ     76              ; long  to each slot's parameter byte
; The sanitize log. The effect stage edits ring bytes so the burst plays a
; sanitized frame - but the ring is decode history, and a later match may
; copy from any byte of it. So every edit is recorded here and undone right
; after the burst: the chip sees the edit, the ring never keeps it.
YX6_PATCHES     equ     80              ; byte  how many edits this frame, 0..3
YX6_PATCH_VAL   equ     81              ; 3 bytes: the originals
YX6_PATCH_ADDR  equ     84              ; 3 longs: where they go back
YX6_STATE       equ     96              ; 18 decoder states, from an even base
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
YH_INTRO_TABLE  equ     34              ; 18 offsets: the frames before the loop
YH_LOOP_TABLE   equ     106             ; 18 offsets: the frames from L on

YX6_MAGIC       equ     $59583621       ; 'YX6!'
YX6_VERSION     equ     4               ; 4: effect streams and the drum table
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
; data register. Registers 7 and 13 need their values touched, so they are
; written out of line rather than through this macro.
;
; Skipping a register whose value has not changed was measured and dropped: a
; sound-chip access on an ST costs about 1.5 cycles more than a RAM one, so the
; compare that would save the write costs about as much as the write.
; -----------------------------------------------------------------------------
        .macro YX6_WRITE number
        move.b  #\number,(a2)           ; select
        move.b  (a1),2(a2)              ; and write
        adda.l  d3,a1
        .endm

; Jump table: bra.w keeps 4-byte slots for a program that loads YX6 as a blob.
                bra.w   YX6_init
                bra.w   YX6_play
                bra.w   YX6_stop

; -----------------------------------------------------------------------------
; YX6_init: a0 = .yx6 file, a1 = workspace. Sets up fourteen decoders and
; decodes each register's first group, so frame 0 can be played immediately.
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

; Open the container: stream A begins where its header ends, and B, C and D
; sit at the header-relative offsets it records - ST4.S documents exactly this
; sequence. The state block goes to the stack; ST4_init clobbers a3, so the
; workspace pointer does too.
        move.l  a2,-(sp)
        movea.l a0,a2
        adda.l  8(a0),a2                ; stream B, the literals
        movea.l a0,a4
        adda.l  12(a0),a4               ; stream C, the byte offsets
        movea.l a0,a5
        adda.l  16(a0),a5               ; stream D, the word offsets
        lea     20(a0),a0               ; stream A, the bits
        moveq   #0,d3
        move.w  YX6_N(a3),d3            ; N, also ST4_init's argument
        move.l  a3,-(sp)
        bsr     ST4_init
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

; The cursor reaches the effect and mixer rings by fixed hops, computed once.
        move.w  YX6_N(a3),d0
        mulu.w  #14,d0
        move.l  d0,YX6_EFXOFF(a3)
        move.w  YX6_N(a3),d0
        mulu.w  #7,d0
        move.l  d0,YX6_R7OFF(a3)
        clr.b   YX6_E1LAST(a3)
        clr.b   YX6_E2LAST(a3)
        clr.b   YX6_T1LAST(a3)
        clr.b   YX6_T2LAST(a3)
        clr.b   YX6_PATCHES(a3)
        lea     yx6_drums(pc),a0
        clr.b   (a0)

; Take over Timers A and D: save everything YX6_stop must hand back, park our
; handlers on the vectors, leave both timers stopped but enabled and unmasked.
; bset/bclr only - Timer C's bits and the ACIA's live in the same registers.
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        move.l  $134.w,YX6_OLDTA(a3)
        move.l  $110.w,YX6_OLDTD(a3)
        move.b  $FFFFFA19.w,YX6_OLDTACR(a3)
        move.b  $FFFFFA1D.w,YX6_OLDTCDCR(a3)
        move.b  $FFFFFA1F.w,YX6_OLDTADR(a3)
        move.b  $FFFFFA25.w,YX6_OLDTDDR(a3)
        moveq   #0,d0
        btst    #5,$FFFFFA07.w          ; IERA bit 5, Timer A enabled?
        sne     d0
        and.w   #1,d0
        btst    #5,$FFFFFA13.w          ; IMRA bit 5
        beq.s   .imra_off
        addq.w  #2,d0
.imra_off:
        btst    #4,$FFFFFA09.w          ; IERB bit 4, Timer D
        beq.s   .ierb_off
        addq.w  #4,d0
.ierb_off:
        btst    #4,$FFFFFA15.w          ; IMRB bit 4
        beq.s   .imrb_off
        addq.w  #8,d0
.imrb_off:
        move.b  d0,YX6_OLDMFP(a3)
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
        movea.l a0,a2
        adda.l  8(a0),a2                ; stream B
        movea.l a0,a4
        adda.l  12(a0),a4               ; stream C
        movea.l a0,a5
        adda.l  16(a0),a5               ; stream D
        lea     20(a0),a0               ; stream A
        moveq   #0,d3
        move.w  YX6_N(a3),d3
        bsr     ST4_init
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
; YX6_play: a0 = workspace. One frame: write fourteen registers, refill one.
; -----------------------------------------------------------------------------
YX6_play:
        movea.l a0,a3
        tst.l   YX6_LEFTFR(a3)
        bne.s   .frame
        moveq   #-1,d0                  ; past the last frame of a tune that
        rts                             ; does not loop: write nothing

.frame:
        moveq   #0,d3
        move.w  YX6_N(a3),d3            ; one ring to the next
        bsr     YX6_effects             ; may rewrite this frame's ring bytes
        movea.l YX6_CURSOR(a3),a1       ; the same offset in all fourteen
        lea     YX6_PSG,a2              ; register rings

; Selecting a register and writing it are two bus cycles. An interrupt that
; uses the sound chip in between - TOS has several, its floppy motor timeout
; among them - would leave this write going to whatever register it selected,
; so the burst runs masked. Cheap: about 24 cycles for the whole frame.
        move.w  sr,-(sp)
        ori.w   #$0700,sr

        YX6_WRITE 0                     ; channel A period, fine and coarse
        YX6_WRITE 1
        YX6_WRITE 2                     ; channel B
        YX6_WRITE 3
        YX6_WRITE 4                     ; channel C
        YX6_WRITE 5
        YX6_WRITE 6                     ; noise period

        move.b  #YX6_MIXER,(a2)         ; R7 also carries the ST's I/O port
        move.b  (a1),d1                 ; directions, which port A needs kept
        or.b    #YX6_PORTS,d1           ; as outputs for the floppy select
        move.b  d1,2(a2)
        adda.l  d3,a1

        YX6_WRITE 8                     ; the three volumes
        YX6_WRITE 9
        YX6_WRITE 10
        YX6_WRITE 11                    ; envelope period
        YX6_WRITE 12

        move.b  (a1),d1                 ; R13 restarts the envelope whenever it
        cmp.b   #YX6_NO_ENV,d1          ; is written, so $FF means leave it be
        beq.s   .envelope_held
        move.b  #YX6_ENVELOPE,(a2)
        move.b  d1,2(a2)
.envelope_held:
        move.w  (sp)+,sr

; Undo this frame's ring edits: the chip has heard the sanitized frame, and
; the ring must stay exact decode history for every match yet to come.
        move.b  YX6_PATCHES(a3),d0
        beq.s   .pristine
        lea     YX6_PATCH_ADDR(a3),a0
        lea     YX6_PATCH_VAL(a3),a1
.restore:
        movea.l (a0)+,a2
        move.b  (a1)+,(a2)
        subq.b  #1,d0
        bne.s   .restore
.pristine:

; Refill the register whose turn it is. Slots 14..C-1 exist when C is larger
; than the register count; they simply have nothing to do.
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
        moveq   #1,d0                   ; played, and wrapped to the loop point
        rts
.played:
        moveq   #0,d0
        rts

; -----------------------------------------------------------------------------
; YX6_stop: a0 = workspace. Hands the MFP back exactly as YX6_init found it -
; timers quiesced first, vectors, data, control and the four enable/mask bits
; restored, pendings dropped - then silences the three voices. Tone and
; envelope settings stay, and a fresh YX6_init starts the tune over.
; -----------------------------------------------------------------------------
YX6_stop:
        movea.l a0,a3
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        clr.b   $FFFFFA19.w             ; quiesce before handing back
        andi.b  #$F0,$FFFFFA1D.w
        move.l  YX6_OLDTA(a3),$134.w
        move.l  YX6_OLDTD(a3),$110.w
        move.b  YX6_OLDTADR(a3),$FFFFFA1F.w
        move.b  YX6_OLDTDDR(a3),$FFFFFA25.w
        move.b  YX6_OLDTACR(a3),$FFFFFA19.w
        move.b  YX6_OLDTCDCR(a3),d0     ; only Timer D's nibble is ours
        and.b   #$0F,d0
        or.b    d0,$FFFFFA1D.w
        move.b  YX6_OLDMFP(a3),d0       ; re-clear what init found clear
        btst    #0,d0
        bne.s   .iera_keep
        bclr    #5,$FFFFFA07.w
.iera_keep:
        btst    #1,d0
        bne.s   .imra_keep
        bclr    #5,$FFFFFA13.w
.imra_keep:
        btst    #2,d0
        bne.s   .ierb_keep
        bclr    #4,$FFFFFA09.w
.ierb_keep:
        btst    #3,d0
        bne.s   .imrb_keep
        bclr    #4,$FFFFFA15.w
.imrb_keep:
        bclr    #5,$FFFFFA0B.w          ; and no pending ticks of ours
        bclr    #4,$FFFFFA0D.w
        move.w  (sp)+,sr
        lea     yx6_drums(pc),a0
        clr.b   (a0)
        lea     YX6_PSG,a0
        moveq   #8,d0                   ; R8, R9, R10 are the voice volumes
.silence:
        move.b  d0,(a0)
        clr.b   2(a0)
        addq.b  #1,d0
        cmp.b   #11,d0
        bne.s   .silence
        rts

; -----------------------------------------------------------------------------
; YX6_effects: the frame's effect stage. Runs BEFORE the register burst so it
; may sanitize the frame in the rings. Pops E and T from each slot's ring;
; a changed code reprograms the slot's timer from scratch, a held one updates
; only the count (a running MFP timer loads it at its next reload), a dropped
; one stops the timer - unless the slot ran a drum, which finishes by itself.
; Slot 1 owns Timer A, slot 2 Timer D, whose control nibble is shared with
; Timer C and only ever changed read-modify-write.
; in: d3.l = N; clobbers d0-d2/d4-d5, a0-a2.
; -----------------------------------------------------------------------------
YX6_effects:
        clr.b   YX6_PATCHES(a3)         ; a fresh frame, no edits yet
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_EFXOFF(a3),a0       ; ring E1, at this frame's offset
        move.b  (a0),d0                 ; E1
        adda.l  d3,a0
        move.b  (a0),d1                 ; T1
        adda.l  d3,a0
        move.l  a0,-(sp)                ; ring E2's byte, for after slot 1
        bsr.s   yx6_slot1
        movea.l (sp)+,a0
        move.b  (a0),d0                 ; E2
        adda.l  d3,a0
        move.b  (a0),d1                 ; T2
        bsr     yx6_slot2

; While a drum runs its voice leaves the mixer, as the original replay
; routines had it: force the tone and noise bits in this frame's R7.
        move.b  yx6_drums(pc),d0
        beq.s   .mixer_ok
        move.b  d0,d1
        lsl.b   #3,d1
        or.b    d0,d1                   ; the voice's tone and noise disables
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_R7OFF(a3),a0
        bsr.s   yx6_borrow              ; the ring gets this byte back
        or.b    d1,(a0)
.mixer_ok:
        rts

; Records a ring byte about to be edited, for the restore after the burst.
; in: a0 = the byte; clobbers d2.
yx6_borrow:
        moveq   #0,d2
        move.b  YX6_PATCHES(a3),d2
        addq.b  #1,YX6_PATCHES(a3)
        move.b  (a0),YX6_PATCH_VAL(a3,d2.w)
        add.w   d2,d2
        add.w   d2,d2
        move.l  a0,YX6_PATCH_ADDR(a3,d2.w)
        rts

; One slot: d0.b = E, d1.b = T. The two are the same machine aimed at
; different timers; slot 2 must respect Timer C's half of TCDCR.
yx6_slot1:
        cmp.b   YX6_E1LAST(a3),d0
        beq.w   .held
        move.b  YX6_E1LAST(a3),d4       ; what the slot was doing before
        move.b  d0,YX6_E1LAST(a3)
        tst.b   d0
        beq.s   .released
; A new code: find the parameter register's ring - r8+voice carries the SID
; volume, the drum number or the buzzer shape - then let the type arm its
; handler; every type then (re)programs the timer the same way.
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5                   ; the voice, 0..2
        move.w  d5,d4
        addq.w  #8,d4
        mulu.w  YX6_N(a3),d4
        move.l  d4,YX6_PARM1(a3)
        move.b  d0,d5
        and.b   #$C0,d5
        beq.s   .sid
        cmp.b   #$40,d5
        beq.s   .drum
        bsr     yx6_buzz_start1         ; $C0; the packer never emits $80
        bra.s   .armed
.sid:
; While a drum owns this voice, its volume register is the drum's alone: the
; SID stays un-armed and the cleared last-code makes every following frame
; retry, so it comes back by itself the frame after the drum ends. That is
; the reference player's priority, where the drum overrides the volume.
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5
        moveq   #1,d4
        lsl.b   d5,d4
        and.b   yx6_drums(pc),d4
        beq.s   .sid_free
        clr.b   YX6_E1LAST(a3)
        rts
.sid_free:
        bsr     yx6_sid_start1
        bra.s   .armed
.drum:
        bsr     yx6_drum_start1
.armed:
        move.b  d1,YX6_T1LAST(a3)
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        clr.b   $FFFFFA19.w             ; stop, then vector, count, prescaler
        move.l  a0,$134.w
        move.b  d1,$FFFFFA1F.w
        move.b  d0,d5
        and.b   #7,d5
        move.b  d5,$FFFFFA19.w
        move.w  (sp)+,sr
        rts
.released:
        and.b   #$C0,d4                 ; the level dropped: stop the timer -
        cmp.b   #$40,d4                 ; unless a drum is finishing by itself
        beq.s   .out
        clr.b   $FFFFFA19.w
.out:
        rts
.held:
        tst.b   d0
        beq.s   .out
; A drum code is a trigger on EVERY frame it is present, as the reference
; player starts one on each coded frame: real dumps carry back-to-back
; codes whose ring bytes name different samples - an attack, then a body -
; and only a full restart plays the second one.
        move.b  d0,d5
        and.b   #$C0,d5
        cmp.b   #$40,d5
        beq.w   .drum
        cmp.b   YX6_T1LAST(a3),d1       ; reload only a CHANGED count: a write
        beq.s   .track                  ; meeting the counter at 01 is
        move.b  d1,YX6_T1LAST(a3)       ; indeterminate, so do not roll dice
        move.b  d1,$FFFFFA1F.w          ; fifty times a second
.track:
; The level-held effects track their parameter every frame: the SID volume
; and the buzzer shape follow the voice's ring byte, as the reference player
; re-reads them.
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM1(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        tst.b   d5                      ; $00 SID, $C0 buzzer
        beq.s   .vol
        lea     yx6_buzzA+4(pc),a0      ; the shape rides the imm long
        move.b  d4,(a0)
        rts
.vol:
        lea     yx6_sidA_on+4(pc),a0    ; the square's loud half
        move.b  d4,(a0)
        rts

yx6_slot2:
        cmp.b   YX6_E2LAST(a3),d0
        beq.w   .held
        move.b  YX6_E2LAST(a3),d4
        move.b  d0,YX6_E2LAST(a3)
        tst.b   d0
        beq.s   .released
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5
        move.w  d5,d4
        addq.w  #8,d4
        mulu.w  YX6_N(a3),d4
        move.l  d4,YX6_PARM2(a3)
        move.b  d0,d5
        and.b   #$C0,d5
        beq.s   .sid
        cmp.b   #$40,d5
        beq.s   .drum
        bsr     yx6_buzz_start2
        bra.s   .armed
.sid:
        move.b  d0,d5                   ; a drum owns this voice: stay out of
        lsr.b   #4,d5                   ; its volume register and retry next
        and.w   #3,d5                   ; frame - see slot 1
        subq.w  #1,d5
        moveq   #1,d4
        lsl.b   d5,d4
        and.b   yx6_drums(pc),d4
        beq.s   .sid_free
        clr.b   YX6_E2LAST(a3)
        rts
.sid_free:
        bsr     yx6_sid_start2
        bra.s   .armed
.drum:
        bsr     yx6_drum_start2
.armed:
        move.b  d1,YX6_T2LAST(a3)
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        andi.b  #$F0,$FFFFFA1D.w
        move.l  a0,$110.w
        move.b  d1,$FFFFFA25.w
        move.b  d0,d5
        and.b   #7,d5
        or.b    d5,$FFFFFA1D.w
        move.w  (sp)+,sr
        rts
.released:
        and.b   #$C0,d4
        cmp.b   #$40,d4
        beq.s   .out
        andi.b  #$F0,$FFFFFA1D.w
.out:
        rts
.held:
        tst.b   d0
        beq.s   .out
        move.b  d0,d5                   ; a held drum code triggers again -
        and.b   #$C0,d5                 ; see slot 1
        cmp.b   #$40,d5
        beq.w   .drum
        cmp.b   YX6_T2LAST(a3),d1       ; reload only a CHANGED count -
        beq.s   .track                  ; see slot 1
        move.b  d1,YX6_T2LAST(a3)
        move.b  d1,$FFFFFA25.w
.track:
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM2(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        tst.b   d5
        beq.s   .vol
        lea     yx6_buzzD+4(pc),a0
        move.b  d4,(a0)
        rts
.vol:
        lea     yx6_sidD_on+4(pc),a0
        move.b  d4,(a0)
        rts

; A drum starts: read the sample number from the drummed voice's ring byte
; and silence that byte - it is a number, not a volume - flag the voice for
; the mixer pass, then arm the drum routine: its select register, its sample
; address, and the flag-clearing mask, all patched into its own operands.
; in: d0.b = E, the slot's PARM set; out: a0 = the handler; keeps d0/d1/d3.
yx6_drum_start1:
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM1(a3),a0
        bsr     yx6_borrow              ; the ring gets this byte back
        moveq   #0,d4
        move.b  (a0),d4                 ; the sample number, validated at pack
        clr.b   (a0)                    ; sanitize: silence, not a number
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5                   ; the voice again, for flag and select
        moveq   #1,d2
        lsl.b   d5,d2
        lea     yx6_drums(pc),a0
        or.b    d2,(a0)                 ; the mixer pass forces this voice off
        not.b   d2
        lea     yx6_drumA_clear+3(pc),a0
        move.b  d2,(a0)                 ; the end tick drops the same flag
; If the other slot holds a SID on this same voice, the drum takes the
; volume register for itself, as the reference player's drum override does:
; stop that SID's timer and clear its last code, so the slot retries every
; frame - suppressed while the flag is up - and restarts the SID by itself
; the frame after the drum ends.
        move.b  YX6_E2LAST(a3),d2
        and.b   #$C0,d2
        bne.s   .keep
        move.b  YX6_E2LAST(a3),d2
        eor.b   d0,d2
        and.b   #$30,d2
        bne.s   .keep
        andi.b  #$F0,$FFFFFA1D.w
        clr.b   YX6_E2LAST(a3)
.keep:
        move.w  d5,d2
        addq.w  #8,d2                   ; the voice's volume register
        lea     yx6_drumA+3(pc),a0
        move.b  d2,(a0)                 ; the tick's select immediate
        move.w  d4,d2                   ; a drum table entry is six bytes
        lsl.w   #2,d4
        add.w   d2,d2
        add.w   d2,d4
        movea.l YX6_FILE(a3),a0
        move.l  YH_DRUMS(a0),d2
        adda.l  d2,a0
        move.l  0(a0,d4.w),d2           ; the sample's offset in the file
        add.l   YX6_FILE(a3),d2
        lea     yx6_drumA+8(pc),a0      ; the tick reads through its own
        move.l  d2,(a0)                 ; operand
        lea     yx6_drumA(pc),a0
        rts

yx6_drum_start2:
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM2(a3),a0
        bsr     yx6_borrow              ; the ring gets this byte back
        moveq   #0,d4
        move.b  (a0),d4
        clr.b   (a0)
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5
        moveq   #1,d2
        lsl.b   d5,d2
        lea     yx6_drums(pc),a0
        or.b    d2,(a0)
        not.b   d2
        lea     yx6_drumD_clear+3(pc),a0
        move.b  d2,(a0)
        move.b  YX6_E1LAST(a3),d2       ; a SID on this voice: the drum takes
        and.b   #$C0,d2                 ; it - see yx6_drum_start1
        bne.s   .keep
        move.b  YX6_E1LAST(a3),d2
        eor.b   d0,d2
        and.b   #$30,d2
        bne.s   .keep
        clr.b   $FFFFFA19.w
        clr.b   YX6_E1LAST(a3)
.keep:
        move.w  d5,d2
        addq.w  #8,d2
        lea     yx6_drumD+3(pc),a0
        move.b  d2,(a0)
        move.w  d4,d2
        lsl.w   #2,d4
        add.w   d2,d2
        add.w   d2,d4
        movea.l YX6_FILE(a3),a0
        move.l  YH_DRUMS(a0),d2
        adda.l  d2,a0
        move.l  0(a0,d4.w),d2
        add.l   YX6_FILE(a3),d2
        lea     yx6_drumD+8(pc),a0
        move.l  d2,(a0)
        lea     yx6_drumD(pc),a0
        rts

; A SID starts: the two half-period routines get the voice's volume register
; as their select, the loud half gets the volume nibble, and each gets the
; other's address low word to install as the next tick - both live in this
; code, so the vector's high word never changes.
; in: d0.b = E, the slot's PARM set; out: a0 = the loud half; keeps d0/d1/d3.
yx6_sid_start1:
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5
        move.w  d5,d4
        addq.w  #8,d4                   ; the voice's volume register
        lea     yx6_sidA_on+2(pc),a0
        move.b  d4,(a0)
        lea     yx6_sidA_off+2(pc),a0
        move.b  d4,(a0)
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM1(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        lea     yx6_sidA_on+4(pc),a0    ; the loud half's volume
        move.b  d4,(a0)
        lea     yx6_sidA_off(pc),a0     ; each half installs the other
        move.l  a0,d4
        lea     yx6_sidA_on+10(pc),a1
        move.w  d4,(a1)
        lea     yx6_sidA_on(pc),a0
        move.l  a0,d4
        lea     yx6_sidA_off+10(pc),a1
        move.w  d4,(a1)
        rts

yx6_sid_start2:
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5
        move.w  d5,d4
        addq.w  #8,d4
        lea     yx6_sidD_on+2(pc),a0
        move.b  d4,(a0)
        lea     yx6_sidD_off+2(pc),a0
        move.b  d4,(a0)
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM2(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        lea     yx6_sidD_on+4(pc),a0
        move.b  d4,(a0)
        lea     yx6_sidD_off(pc),a0
        move.l  a0,d4
        lea     yx6_sidD_on+10(pc),a1
        move.w  d4,(a1)
        lea     yx6_sidD_on(pc),a0
        move.l  a0,d4
        lea     yx6_sidD_off+10(pc),a1
        move.w  d4,(a1)
        rts

; A buzzer starts: the shape nibble from the parameter byte goes into the
; tick's immediate; writing it to R13 every tick IS the effect.
yx6_buzz_start1:
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM1(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        lea     yx6_buzzA+4(pc),a0
        move.b  d4,(a0)
        lea     yx6_buzzA(pc),a0
        rts

yx6_buzz_start2:
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_PARM2(a3),a0
        move.b  (a0),d4
        and.b   #15,d4
        lea     yx6_buzzD+4(pc),a0
        move.b  d4,(a0)
        lea     yx6_buzzD(pc),a0
        rts

; -----------------------------------------------------------------------------
; The tick handlers, in maxYMiser's shipping forms: the PSG select and write
; in as few instructions as the bus allows, patched immediates instead of
; state, software end-of-interrupt as TOS runs the MFP. All MFP interrupts
; arrive at 68000 level 6, so these never nest, and every patched operand is
; addressed pc-relatively so the player stays loadable anywhere - which also
; means it must run from RAM, where a demo lives anyway.
;
; The patch offsets are load-bearing:
;   drum:  select immediate at +3, sample address at +8, flag mask at _clear+3
;   sid:   select at +2 and volume at +4 inside the imm long; the partner's
;          address low word at +10
;   buzz:  the shape at +4
; -----------------------------------------------------------------------------
yx6_parkA:
        bclr    #5,$FFFFFA0F.w
        rte
yx6_parkD:
        bclr    #4,$FFFFFA11.w
        rte

; One drum tick: the sample byte goes straight to the voice's volume register.
; The byte after the last has bit 7 set, so the same move answers "done": park
; the volume mid-scale against the click, stop the timer, drop the flag.
yx6_drumA:
        move.b  #8,$FFFF8800.w          ; SMC +3: select the drummed volume
        move.b  $12345678,$FFFF8802.w   ; SMC +8: the next sample byte
        bmi.s   .ended
        move.l  a0,-(sp)
        lea     yx6_drumA+8(pc),a0
        addq.l  #1,(a0)                 ; step the operand itself
        movea.l (sp)+,a0
        bclr    #5,$FFFFFA0F.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w         ; middle volume: no click
        clr.b   $FFFFFA19.w             ; the sample ran out: stop Timer A
        move.l  a0,-(sp)
        lea     yx6_drums(pc),a0
yx6_drumA_clear:
        andi.b  #$FF,(a0)               ; SMC +3: drop the voice's drum flag
        movea.l (sp)+,a0
        bclr    #5,$FFFFFA0F.w
        rte

yx6_drumD:
        move.b  #8,$FFFF8800.w
        move.b  $12345678,$FFFF8802.w
        bmi.s   .ended
        move.l  a0,-(sp)
        lea     yx6_drumD+8(pc),a0
        addq.l  #1,(a0)
        movea.l (sp)+,a0
        bclr    #4,$FFFFFA11.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w
        andi.b  #$F0,$FFFFFA1D.w
        move.l  a0,-(sp)
        lea     yx6_drums(pc),a0
yx6_drumD_clear:
        andi.b  #$FF,(a0)
        movea.l (sp)+,a0
        bclr    #4,$FFFFFA11.w
        rte

; The SID square: each tick writes one half of the square - the volume or
; silence - and installs the other half as the next tick, one word into the
; vector. Select and data land in one move.l: the immediate's bytes hit
; $8800 (select) and $8802 (data) by bus layout.
yx6_sidA_on:
        move.l  #$08000F00,$FFFF8800.w  ; SMC +2 select, +4 volume
        move.w  #0,$136.w               ; SMC +10: the quiet half is next
        bclr    #5,$FFFFFA0F.w
        rte
yx6_sidA_off:
        move.l  #$08000000,$FFFF8800.w  ; SMC +2 select; silence
        move.w  #0,$136.w               ; SMC +10: the loud half is next
        bclr    #5,$FFFFFA0F.w
        rte

yx6_sidD_on:
        move.l  #$08000F00,$FFFF8800.w
        move.w  #0,$112.w
        bclr    #4,$FFFFFA11.w
        rte
yx6_sidD_off:
        move.l  #$08000000,$FFFF8800.w
        move.w  #0,$112.w
        bclr    #4,$FFFFFA11.w
        rte

; The sync-buzzer tick: writing the shape to R13 restarts the envelope, and
; that restart at the timer's rate is the whole effect.
yx6_buzzA:
        move.l  #$0D000000,$FFFF8800.w  ; SMC +4: the shape
        bclr    #5,$FFFFFA0F.w
        rte
yx6_buzzD:
        move.l  #$0D000000,$FFFF8800.w
        bclr    #4,$FFFFFA11.w
        rte

yx6_drums:
        dc.b    0                       ; voice bits 0-2: a drum is running
        even
