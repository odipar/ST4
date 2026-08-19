; -----------------------------------------------------------------------------
; YX6.S - streaming YM chiptune player for the plain 68000 (Atari ST)
;
; By Robbert van Dalen. Plays a .yx6 file: eighteen streams - the fourteen
; YM2149 registers plus each effect slot's control byte and timer count -
; every one packed as its own embedded ST4 container and decoded through its
; own small ring by ST4_wrap.S. Written by Claude (Anthropic's Claude Code)
; under Robbert's direction, based on ZX1 v1.5 by Einar Saukas and inspired
; by Steve Clarets' MinYMiser. Use it freely, including commercially, as long
; as your documentation says you used ZX1 through ST4 (see LICENSE).
;
; It plays the fourteen standard sound registers, loops, and plays the YM
; special effects - digidrums, SID voices, the sync-buzzer - on MFP Timers
; A and D. Sinus-SID, which no player has ever implemented, is dropped at
; pack time. EFFECTS.md, next to this file, holds the design.
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
; THE DESIGN, in one paragraph. A tune is O frames long. Each of the eighteen
; streams gets its own ring of N bytes and its own saved decoder state, and
; is decoded C values at a time (C = 24 by default). On every VBL the player
; uses one value from each of the eighteen rings and refills exactly one
; stream - stream k on the VBL where the frame number modulo C is k. With
; C >= 18 every stream is refilled once per C frames, one refill per VBL, so
; the work per frame is flat: the effect stage, fourteen register writes and
; one ST4_resume of C bytes. Since C divides N and a call never reaches the
; ring end, ST4_wrap.S is the decoder that fits: the player counts the calls
; itself and wraps the write pointer when it lands on the ring end.
;
; EFFECTS. The last four rings carry each slot's effect code and timer count,
; one value per frame like everything else. Before the burst the effect stage
; pops them and programs Timers A and D, whose tick handlers - digidrum, SID
; square, sync-buzzer - write the sound chip between frames. A drum start
; also edits this frame's ring bytes (the sample number, the mixer); every
; such edit is logged and undone right after the burst, because a ring is
; decode history that later matches copy from.
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
;       lea     workspace,a0
;       bsr     YX6_stop                ; chip quiet, timers stopped
;
; The workspace holds the player's own fields, the eighteen decoder states and
; the eighteen rings, so its size depends on the ring size in the file:
;
;   YX6_SIZE = YX6_FIXED + 18 * N       ; 1222 + 18 * N bytes
;
; For the default 960-byte rings that is 18502 bytes. A program that knows N
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
; self-modifying code. The slot descriptors and the tick handlers' operands
; live in this blob, init patches the burst's ring displacements and the
; run-time addresses into it, and the effect stage keeps patching as
; effects come and go. Three consequences: the player must run from RAM;
; one loaded copy is one player - two workspaces cannot share it; and it
; must sit inside one 64K segment, because the SID tick swaps only the low
; word of its vector. Every self-reference is pc-relative or patched at
; run time, so the blob still loads anywhere.
;
; ASSUMPTIONS (unchecked beyond the magic, version and stream count):
;
;   1. the file comes from the yx6 packer, which enforces C >= 18, N mod C = 0,
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
; The sanitize log. The effect stage edits ring bytes so the burst plays a
; sanitized frame - but the ring is decode history, and a later match may
; copy from any byte of it. So every edit is recorded here and undone right
; after the burst: the chip sees the edit, the ring never keeps it.
YX6_PATCHES     equ     54              ; byte  how many edits this frame, 0..3
YX6_PATCH_VAL   equ     55              ; 3 bytes: the originals
YX6_PATCH_ADDR  equ     58              ; 3 longs: where they go back
YX6_STATE       equ     70              ; 18 decoder states, from an even base

; Each effect slot's own state - its code and count from last frame, its
; parameter ring offset, its timer and its handlers - lives in a descriptor
; in the code blob (yx6_desc1/2 below), so ONE slot machine serves both
; slots. The hardware addresses are words: movea.w sign-extends $FAxx to
; $FFFFFAxx. The two run-time pointers are filled in by YX6_init.
SD_ELAST        equ     0               ; byte  the slot's code last frame
SD_TLAST        equ     1               ; byte  the count its timer last got:
                                        ; a held effect reloads its data
                                        ; register only when the count CHANGES,
                                        ; because a write that lands while the
                                        ; counter passes 01 loads an
                                        ; indeterminate value - pitch glitches
SD_CTRL         equ     2               ; word  timer control register
SD_DATA         equ     4               ; word  timer data register
SD_VEC          equ     6               ; word  timer vector
SD_PARM         equ     8               ; long  (8+voice)*N: the cursor's ring
                                        ;       to the slot's parameter byte
SD_ISR          equ     12              ; long  the slot's tick handlers
SD_OTHER        equ     16              ; long  the other slot's descriptor
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

; The cursor reaches the effect and mixer rings by fixed hops, computed once.
        move.w  YX6_N(a3),d0
        mulu.w  #7,d0
        move.l  d0,YX6_R7OFF(a3)
        add.l   d0,d0                   ; and 14*N, from the same mulu
        move.l  d0,YX6_EFXOFF(a3)

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
        clr.b   YX6_PATCHES(a3)
        lea     yx6_drums(pc),a0
        clr.b   (a0)
        bsr     yx6_link                ; descriptors: pointers and last codes

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
        moveq   #0,d3
        move.w  YX6_N(a3),d3            ; one ring to the next

; The effect stage, inlined: it runs BEFORE the burst so it may rewrite this
; frame's ring bytes. Each slot is gated on E | E-last: a slot that has, and
; had, nothing to do costs one test - the common frame. A slot machine call
; therefore always means something changed, holds, or just ended; yx6_slot's
; held path leans on that (E is never 0 there).
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_EFXOFF(a3),a0       ; ring E1, at this frame's offset
        lea     yx6_desc1(pc),a5
        move.b  (a0),d0                 ; E1 | its last code: zero is idle
        or.b    (a5),d0
        beq.s   .slot1_idle
        move.b  (a0),d0                 ; the real E1
        adda.l  d3,a0
        move.b  (a0),d1                 ; T1
        adda.l  d3,a0
        move.l  a0,-(sp)                ; ring E2's byte, for after slot 1
        bsr     yx6_slot
        movea.l (sp)+,a0
        bra.s   .slot2
.slot1_idle:
        adda.l  d3,a0
        adda.l  d3,a0
.slot2:
        lea     yx6_desc2(pc),a5
        move.b  (a0),d0                 ; E2, the same gate
        or.b    (a5),d0
        beq.s   .effects_done
        move.b  (a0),d0
        adda.l  d3,a0
        move.b  (a0),d1                 ; T2
        bsr     yx6_slot
.effects_done:

; While a drum runs its voice leaves the mixer, as the original replay
; routines had it: force the tone and noise bits in this frame's R7.
        move.b  yx6_drums(pc),d0
        beq.s   .mixer_ok
        move.b  d0,d1
        lsl.b   #3,d1
        or.b    d0,d1                   ; the voice's tone and noise disables
        movea.l YX6_CURSOR(a3),a0
        adda.l  YX6_R7OFF(a3),a0
        bsr     yx6_borrow              ; the ring gets this byte back
        or.b    d1,(a0)
.mixer_ok:
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

; Undo this frame's ring edits: the chip has heard the sanitized frame, and
; the ring must stay exact decode history for every match yet to come.
        move.b  YX6_PATCHES(a3),d0
        beq.s   .pristine
        clr.b   YX6_PATCHES(a3)         ; cleared only when it was used
        lea     YX6_PATCH_ADDR(a3),a0
        lea     YX6_PATCH_VAL(a3),a1
.restore:
        movea.l (a0)+,a2
        move.b  (a1)+,(a2)
        subq.b  #1,d0
        bne.s   .restore
.pristine:

; Refill the stream whose turn it is. Slots 18..C-1 exist when C is larger
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
        moveq   #1,d0                   ; played, and wrapped to the loop point
        rts
.played:
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
        lea     yx6_drums(pc),a0
        clr.b   (a0)
        moveq   #2,d2                   ; every burst gate open again
.gates: move.w  d2,-(sp)
        moveq   #2,d4
        bsr     yx6_burst_gate
        move.w  (sp)+,d2
        dbra    d2,.gates
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
        dc.b    0,0                     ; E last, T last
        dc.w    $FA19,$FA1F,$0134       ; control, data, vector
        dc.l    0                       ; PARM, set per effect
        dc.l    0,0                     ; ISR block, other slot: yx6_link
yx6_desc2:
        dc.b    0,0
        dc.w    $FA1D,$FA25,$0110
        dc.l    0
        dc.l    0,0

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
; byte-congruent: the slot machine and yx6_drum_start reach every patched
; operand through ISR_ offsets measured on the A block. The rig checks the
; congruence from the symbol table. The park entries - where YX6_init aims
; the vectors while no effect runs - are the handlers' own tails.
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
; The patch offsets are load-bearing:
;   drum:  select immediate at +3, sample address at +8, flag mask at
;          _clear+3, the burst gate's address at _gate+4
;   sid:   select at +2 and volume at +4 inside the imm long; the partner's
;          address low word at +14, past the IPL drop, patched by yx6_link
;   buzz:  the shape at +4
; The SID vector swap patches the vector's LOW word only, so the two halves
; of a block must share their address high word: keep the player inside one
; 64K segment - anywhere a program smaller than 64K naturally loads.
; -----------------------------------------------------------------------------

; One drum tick: the sample byte goes straight to the voice's volume register.
; The byte after the last has bit 7 set, so the same move answers "done": park
; the volume mid-scale against the click, stop the timer, drop the flag.
yx6_drumA:
        move.b  #8,$FFFF8800.w          ; SMC +3: select the drummed volume
        move.b  $12345678,$FFFF8802.w   ; SMC +8: the next sample byte
        bmi.s   .ended
        move.w  #$2500,sr               ; pair done: the other timer may stack
        move.l  a0,-(sp)                ; the stack, never the USP: this side
        lea     yx6_drumA+8(pc),a0      ; nests into parked windows
        addq.l  #1,(a0)                 ; step the operand itself
        movea.l (sp)+,a0
        move.b  #$DF,$FFFFFA0F.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w         ; middle volume: no click
        andi.b  #$F0,$FFFFFA19.w        ; the sample ran out: stop Timer A
        move.w  #$2500,sr
        move.l  a0,-(sp)
        lea     yx6_drums(pc),a0
yx6_drumA_clear:
        andi.b  #$FF,(a0)               ; SMC +3: drop the voice's drum flag
yx6_drumA_gate:
        move.w  #2,$12345678            ; SMC +4: the voice's burst write
        movea.l (sp)+,a0                ; comes back
yx6_parkA:
        move.b  #$DF,$FFFFFA0F.w
        rte

; The SID square: each tick writes one half of the square - the volume or
; silence - and installs the other half as the next tick, one word into the
; vector. Select and data land in one move.l: the immediate's bytes hit
; $8800 (select) and $8802 (data) by bus layout.
yx6_sidA_on:
        move.l  #$08000F00,$FFFF8800.w  ; SMC +2 select, +4 volume
        move.w  #$2500,sr
        move.w  #0,$136.w               ; SMC +14: the quiet half is next
        move.b  #$DF,$FFFFFA0F.w
        rte
yx6_sidA_off:
        move.l  #$08000000,$FFFF8800.w  ; SMC +2 select; silence
        move.w  #$2500,sr
        move.w  #0,$136.w               ; SMC +14: the loud half is next
        move.b  #$DF,$FFFFFA0F.w
        rte

; The sync-buzzer tick: writing the shape to R13 restarts the envelope, and
; that restart at the timer's rate is the whole effect.
yx6_buzzA:
        move.l  #$0D000000,$FFFF8800.w  ; SMC +4: the shape
        move.w  #$2500,sr
        move.b  #$DF,$FFFFFA0F.w
        rte

; Slot 2's instances: Timer D's stop, vector and in-service bit, otherwise
; the A block byte for byte.
yx6_drumD:
        move.b  #8,$FFFF8800.w
        move.b  $12345678,$FFFF8802.w
        bmi.s   .ended
        move.w  #$2500,sr               ; Timer A may nest from here on, and
        .if YX6_SUPER_HOST              ; never parks the USP itself - so
        move    a0,usp                  ; this side may
        .else
        move.l  a0,-(sp)
        .endif
        lea     yx6_drumD+8(pc),a0
        addq.l  #1,(a0)
        .if YX6_SUPER_HOST
        move    usp,a0
        .else
        movea.l (sp)+,a0
        .endif
        move.b  #$EF,$FFFFFA11.w
        rte
.ended:
        move.b  #$D,$FFFF8802.w
        andi.b  #$F0,$FFFFFA1D.w
        move.w  #$2500,sr
        .if YX6_SUPER_HOST
        move    a0,usp
        .else
        move.l  a0,-(sp)
        .endif
        lea     yx6_drums(pc),a0
yx6_drumD_clear:
        andi.b  #$FF,(a0)
yx6_drumD_gate:
        move.w  #2,$12345678
        .if YX6_SUPER_HOST
        move    usp,a0
        .else
        movea.l (sp)+,a0
        .endif
yx6_parkD:
        move.b  #$EF,$FFFFFA11.w
        rte

yx6_sidD_on:
        move.l  #$08000F00,$FFFF8800.w
        move.w  #$2500,sr
        move.w  #0,$112.w
        move.b  #$EF,$FFFFFA11.w
        rte
yx6_sidD_off:
        move.l  #$08000000,$FFFF8800.w
        move.w  #$2500,sr
        move.w  #0,$112.w
        move.b  #$EF,$FFFFFA11.w
        rte

yx6_buzzD:
        move.l  #$0D000000,$FFFF8800.w
        move.w  #$2500,sr
        move.b  #$EF,$FFFFFA11.w
        rte

yx6_drums:
        dc.b    0                       ; voice bits 0-2: a drum is running
        even

; Where the patched operands sit, measured on the A block; the D block is
; byte-congruent, so the same offsets serve both through SD_ISR.
; rmac evaluates left to right: the parentheses are load-bearing.
ISR_DRUM_SEL    equ     3
ISR_DRUM_PTR    equ     8
ISR_CLEAR       equ     (yx6_drumA_clear-yx6_drumA)+3
ISR_GATE        equ     (yx6_drumA_gate-yx6_drumA)+4
ISR_SIDON       equ     yx6_sidA_on-yx6_drumA
ISR_SIDON_SEL   equ     ISR_SIDON+2
ISR_SID_VOL     equ     ISR_SIDON+4
ISR_SIDON_NEXT  equ     ISR_SIDON+14
ISR_SIDOFF      equ     yx6_sidA_off-yx6_drumA
ISR_SIDOFF_SEL  equ     ISR_SIDOFF+2
ISR_SIDOFF_NEXT equ     ISR_SIDOFF+14
ISR_BUZZ        equ     yx6_buzzA-yx6_drumA
ISR_BUZZ_SHAPE  equ     ISR_BUZZ+4

; -----------------------------------------------------------------------------
; yx6_link, from YX6_init: the run-time addresses only a loaded player knows.
; Each descriptor gets its ISR block and its partner, both slots' last code
; and count are cleared, and the SID halves get each other's address low
; word - the vector's high word never changes, and neither do these, so
; they are patched once here rather than on every SID start.
; -----------------------------------------------------------------------------
yx6_link:
        moveq   #2,d2                   ; every burst gate open: a previous
.gates: move.w  d2,-(sp)                ; session's mute must not leak
        moveq   #2,d4
        bsr     yx6_burst_gate
        move.w  (sp)+,d2
        dbra    d2,.gates
        lea     yx6_desc1(pc),a0
        lea     yx6_desc2(pc),a1
        move.l  a1,SD_OTHER(a0)
        move.l  a0,SD_OTHER(a1)
        clr.w   (a0)                    ; E and T last, both slots
        clr.w   (a1)
        lea     yx6_drumA(pc),a2
        move.l  a2,SD_ISR(a0)
        bsr.s   .partners
        lea     yx6_drumD(pc),a2
        move.l  a2,SD_ISR(a1)
.partners:
        lea     ISR_SIDOFF(a2),a0       ; each half installs the other
        move.l  a0,d0
        move.w  d0,ISR_SIDON_NEXT(a2)
        lea     ISR_SIDON(a2),a0
        move.l  a0,d0
        move.w  d0,ISR_SIDOFF_NEXT(a2)
        rts

; -----------------------------------------------------------------------------
; yx6_slot: one slot of the effect stage - both slots, through a5.
; A changed code reprograms the slot's timer from scratch, a held one
; updates only the count (a running MFP timer loads it at its next reload),
; a dropped one stops the timer - unless the slot ran a drum, which
; finishes by itself. A held DRUM code is a trigger on EVERY frame it is
; present, as the reference player starts one on each coded frame: real
; dumps carry back-to-back codes whose ring bytes name different samples -
; an attack, then a body - and only a full restart plays the second one.
;
; in: d0.b = E (never 0 with E-last also 0: the caller's gate), d1.b = T,
;     a5 = the slot's descriptor, a3 = workspace, d3.l = N.
; preserves d3/a3/a5; clobbers d0-d2/d4-d5, a0-a2/a4.
; -----------------------------------------------------------------------------
yx6_slot:
        cmp.b   (a5),d0
        beq.w   .held
        move.b  (a5),d4                 ; what the slot was doing before
        move.b  d0,(a5)
        tst.b   d0
        beq.w   .released
; A new code: find the parameter register's ring - r8+voice carries the SID
; volume, the drum number or the buzzer shape - then let the type arm its
; handler; every type then (re)programs the timer the same way. The voice
; stays live in d5, the parameter byte in a1, for whoever needs them.
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5                   ; the voice, 0..2
        move.w  d5,d2
        addq.w  #8,d2
        mulu.w  YX6_N(a3),d2
        move.l  d2,SD_PARM(a5)
        movea.l YX6_CURSOR(a3),a1
        adda.l  d2,a1                   ; a1 = the slot's parameter byte
        move.b  d0,d2                   ; (d4 still holds the OLD code, for
        and.b   #$C0,d2                 ; the retune test below)
        beq.s   .sid
        cmp.b   #$40,d2
        beq.s   .drum
        move.b  (a1),d4                 ; $C0, the buzzer ($80 never packs):
        and.b   #15,d4                  ; the shape rides the tick's immediate
        movea.l SD_ISR(a5),a0
        move.b  d4,ISR_BUZZ_SHAPE(a0)
        lea     ISR_BUZZ(a0),a0
        bra.s   .armed
.sid:
; While a drum owns this voice, its volume register is the drum's alone: the
; SID stays un-armed and the cleared last-code makes every following frame
; retry, so it comes back by itself the frame after the drum ends. That is
; the reference player's priority, where the drum overrides the volume.
        moveq   #1,d2                   ; (d4 still holds the OLD code)
        lsl.b   d5,d2
        and.b   yx6_drums(pc),d2
        beq.s   .sid_free
        clr.b   (a5)
        bsr     yx6_burst_open_old      ; a replaced SID's gate must not leak
        rts
.sid_free:
; A code that differs from the old one ONLY in its prescaler is the same
; SID sliding across a prescaler boundary - real tunes wobble across one
; many times a second. The reference player free-runs the square's phase
; through frequency changes, so keep the installed half: retune the timer
; without touching the vector. A full restart here would reset the phase
; to the loud half every flip - an audible ticking.
        move.b  d0,d2
        eor.b   d4,d2
        and.b   #$F0,d2                 ; type and voice unchanged?
        beq.s   .sid_retune
        bsr     yx6_burst_open_old      ; a replaced SID's voice write resumes
        move.w  d5,d2                   ; ...and the new voice's is muted:
        moveq   #0,d4                   ; the square owns it now
        bsr     yx6_burst_gate
        move.w  d5,d4                   ; a fresh SID: the voice's volume
        addq.w  #8,d4                   ; register into both halves' selects
        movea.l SD_ISR(a5),a0
        move.b  d4,ISR_SIDON_SEL(a0)
        move.b  d4,ISR_SIDOFF_SEL(a0)
        move.b  (a1),d4
        and.b   #15,d4
        move.b  d4,ISR_SID_VOL(a0)      ; the loud half's volume
        lea     ISR_SIDON(a0),a0
        bra.s   .armed
.sid_retune:
        move.b  (a1),d4                 ; the volume still tracks the ring
        and.b   #15,d4
        movea.l SD_ISR(a5),a0
        move.b  d4,ISR_SID_VOL(a0)
        movea.w SD_VEC(a5),a0           ; keep whichever half is installed:
        movea.l (a0),a0                 ; .armed writes the same value back
        bra.s   .armed
.drum:
        bsr     yx6_drum_start
.armed:
        move.b  d1,SD_TLAST(a5)
        movea.w SD_CTRL(a5),a1
        movea.w SD_VEC(a5),a2
        move.w  sr,-(sp)
        ori.w   #$0700,sr
        andi.b  #$F0,(a1)               ; stop, then vector, count, prescaler
        move.l  a0,(a2)
        movea.w SD_DATA(a5),a2
        move.b  d1,(a2)
        move.b  d0,d5
        and.b   #7,d5
        or.b    d5,(a1)
        move.w  (sp)+,sr
        rts
.released:
        move.b  d4,d2                   ; the level dropped: stop the timer -
        and.b   #$C0,d2                 ; unless a drum is finishing by itself
        cmp.b   #$40,d2
        beq.s   .out
        movea.w SD_CTRL(a5),a1
        andi.b  #$F0,(a1)
        tst.b   d2                      ; an ended SID: the burst writes its
        bne.s   .out                    ; voice's volume again
        bsr     yx6_burst_open_old
.out:
        rts
.held:                                  ; E != 0 here: the caller's gate
        move.b  d0,d5
        and.b   #$C0,d5
        cmp.b   #$40,d5
        bne.s   .not_drum
        move.b  d0,d4                   ; a held drum code triggers again;
        bra.s   .drum                   ; old = new, nothing to clean up
.not_drum:
        cmp.b   SD_TLAST(a5),d1         ; reload only a CHANGED count: a write
        beq.s   .track                  ; meeting the counter at 01 is
        move.b  d1,SD_TLAST(a5)         ; indeterminate, so do not roll dice
        movea.w SD_DATA(a5),a2          ; fifty times a second
        move.b  d1,(a2)
.track:
; The level-held effects track their parameter every frame: the SID volume
; and the buzzer shape follow the voice's ring byte, as the reference player
; re-reads them.
        movea.l YX6_CURSOR(a3),a0
        adda.l  SD_PARM(a5),a0
        move.b  (a0),d4
        and.b   #15,d4
        movea.l SD_ISR(a5),a0
        tst.b   d5                      ; $00 SID, $C0 buzzer
        beq.s   .vol
        move.b  d4,ISR_BUZZ_SHAPE(a0)
        rts
.vol:
        move.b  d4,ISR_SID_VOL(a0)
        rts

; Opens the burst gate of the voice the OLD code (d4) named, when that code
; was a SID; anything else leaves the gates alone. Clobbers d2/a0, keeps d4.
yx6_burst_open_old:
        move.b  d4,d2
        and.b   #$C0,d2
        bne.s   .not_sid
        tst.b   d4
        beq.s   .not_sid
        move.b  d4,d2
        lsr.b   #4,d2
        and.w   #3,d2
        subq.w  #1,d2
        move.w  d4,-(sp)
        moveq   #2,d4
        bsr     yx6_burst_gate
        move.w  (sp)+,d4
.not_sid:
        rts

; -----------------------------------------------------------------------------
; yx6_drum_start: read the sample number from the drummed voice's ring byte
; and silence that byte - it is a number, not a volume - flag the voice for
; the mixer pass, then arm the drum routine: its select register, its sample
; address, and the flag-clearing mask, all patched into its own operands.
; If the other slot holds a SID on this same voice, the drum takes the
; volume register for itself, as the reference player's drum override does:
; stop that SID's timer and clear its last code, so the slot retries every
; frame - suppressed while the flag is up - and restarts the SID by itself
; the frame after the drum ends.
; Called from the new-code path AND the held retrigger, which has neither
; the voice nor the parameter pointer at hand - so it derives its own.
; in: d0.b = E, a5 = the descriptor, SD_PARM set; out: a0 = the handler;
; keeps d0/d1/d3/a5.
; -----------------------------------------------------------------------------
yx6_drum_start:
; The slot may be replacing something. An old SID's burst gate reopens (its
; timer is about to be re-aimed). An old drum on ANOTHER voice was cut off
; mid-sample: the marker that would have dropped its flag and reopened its
; gate will never run, so do both here.
        cmp.b   d0,d4
        beq.s   .no_old
        tst.b   d4
        beq.s   .no_old
        move.b  d4,d2
        and.b   #$C0,d2
        beq.s   .old_sid
        cmp.b   #$40,d2
        bne.s   .no_old                 ; a buzzer holds no register
        move.b  d4,d2                   ; an old drum: same voice keeps its
        eor.b   d0,d2                   ; flag and gate, another voice was
        and.b   #$30,d2                 ; orphaned
        beq.s   .no_old
        move.b  d4,d2
        lsr.b   #4,d2
        and.w   #3,d2
        subq.w  #1,d2
        moveq   #1,d4                   ; (the old code is spent now)
        lsl.b   d2,d4
        not.b   d4
        lea     yx6_drums(pc),a0
        and.b   d4,(a0)                 ; the orphan's flag drops
        moveq   #2,d4
        bsr     yx6_burst_gate          ; and its burst write returns
        bra.s   .no_old
.old_sid:
        bsr     yx6_burst_open_old
.no_old:
        movea.l YX6_CURSOR(a3),a0
        adda.l  SD_PARM(a5),a0
        bsr     yx6_borrow              ; the ring gets this byte back
        moveq   #0,d4
        move.b  (a0),d4                 ; the sample number, validated at pack
        clr.b   (a0)                    ; sanitize: silence, not a number
        move.b  d0,d5
        lsr.b   #4,d5
        and.w   #3,d5
        subq.w  #1,d5                   ; the voice, for flag and select
        moveq   #1,d2
        lsl.b   d5,d2
        lea     yx6_drums(pc),a0
        or.b    d2,(a0)                 ; the mixer pass forces this voice off
        not.b   d2
        movea.l SD_ISR(a5),a1
        move.b  d2,ISR_CLEAR(a1)        ; the end tick drops the same flag
        movea.l SD_OTHER(a5),a4         ; a SID on this voice in the other
        move.b  (a4),d2                 ; slot: the drum takes the register
        and.b   #$C0,d2
        bne.s   .keep
        move.b  (a4),d2
        eor.b   d0,d2
        and.b   #$30,d2
        bne.s   .keep
        movea.w SD_CTRL(a4),a0
        andi.b  #$F0,(a0)               ; stop the other slot's SID
        clr.b   (a4)                    ; and let it retry every frame
.keep:
        move.w  d5,d2
        addq.w  #8,d2                   ; the voice's volume register
        move.b  d2,ISR_DRUM_SEL(a1)     ; the tick's select immediate
        add.w   d4,d4                   ; a drum table entry is six bytes:
        move.w  d4,d2                   ; 6n = 2n + 4n
        add.w   d4,d4
        add.w   d2,d4
        movea.l YX6_FILE(a3),a2
        movea.l a2,a0
        adda.l  YH_DRUMS(a0),a0
        move.l  0(a0,d4.w),d2           ; the sample's offset in the file
        add.l   a2,d2
        move.l  d2,ISR_DRUM_PTR(a1)     ; the tick reads through its operand
; The drum owns the voice's volume register now, like a SID does: the burst
; write is gated off, and the END tick gives it back - the marker's reopen
; instruction gets the gate word's address patched in here.
        move.w  d5,d2
        moveq   #0,d4
        bsr     yx6_burst_gate
        move.w  d5,d2
        mulu.w  #10,d2
        lea     yx6_wB+8(pc),a0
        adda.w  d2,a0
        move.l  a0,ISR_GATE(a1)
        movea.l a1,a0                   ; the handler is the block base
        rts

; -----------------------------------------------------------------------------
; yx6_burst_gate: while a SID owns a voice, the burst must NOT write that
; voice's volume register - the write lands mid-phase and forces the loud
; half back for up to half a square period, an audible tick at bass rates.
; Both references agree: ST-Sound's per-sample SID overrides the register
; write, maxYMiser's frame code skips SID channels outright. The gate is
; one SMC word: the volume write's destination displacement, 2 for the PSG
; data register, 0 to land harmlessly on the select register instead - the
; next macro's select overrides it before anything is written.
; in: d2.w = the voice 0..2, d4.w = 2 to open or 0 to mute; clobbers d2/a0.
; -----------------------------------------------------------------------------
yx6_burst_gate:
        mulu.w  #10,d2                  ; a YX6_WRITE is ten bytes; R8's
        lea     yx6_wB+8(pc),a0         ; destination word sits at +8
        move.w  d4,0(a0,d2.w)
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
