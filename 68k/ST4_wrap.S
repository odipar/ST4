; -----------------------------------------------------------------------------
; ST4_wrap.S - ST4: counted-wrap ring decompressor for the Atari ST (68000)
;
; By Robbert van Dalen, based on ZX1 v1.5 by Einar Saukas and on ST1_wrap.S,
; which this follows instruction for instruction wherever the format allows.
; Written by Claude (Anthropic's Claude Code) under Robbert's direction. Use it
; freely, including commercially, as long as your documentation says you used
; ZX1 through ST4 (see LICENSE).
;
; ST4_wrap is ST1_wrap with ST4's three streams and units: the literal payload
; comes from stream B in a2 and the offsets from stream C in a4 (bytes) or D in
; a5 (words), lengths and budgets count units of ST4_UNIT bytes, and the copy
; moves a unit at a time. With the offsets gone, stream A holds nothing but
; bits, so the queue refills a word at a time. Set the unit size before
; including:
;
;       ST4_UNIT    equ     4
;               include "ST4_wrap.S"
;
; !!! LIKE ST1_wrap, THIS HAS NO DONE STATE !!!
; The caller must know the exact output size O in units, make exactly
; T = ceil(O/C) decode calls, and never call again.
;
; The ring is N BYTES and must be a whole number of units; the caller resets a1
; to the ring start after every F = N/(C*UNIT) calls. Budgets and remaining
; counts are units; offsets, positions and N are bytes - which is what keeps
; the wrap arithmetic identical to ST1_wrap's.
;
; The container is ST4.S's: twenty bytes of header - signature, O, and where
; streams B, C and D begin - then A, B, C and D in order. See ST4.S for the
; eight instructions that open one.
;
; THE STATE IS YOUR REGISTERS:
;
;   a0.l  position in stream A          a1.l  write pointer
;   a2.l  position in stream B          a4.l  stream C, the byte offsets
;   a5.l  stream D, the word offsets    d0.w  bit queue ($8000 empty)
;   d1.l  -ring_start.low : units remaining
;   d2.l  N : signed byte offset (+lastOffset*UNIT literals, - during a match)
;
;   resume in    d3.w   this call's budget in UNITS
;   CLOBBERED    d3.w d4.l d5.l a3.l
;   untouched    d6 d7 a6
;
; ASSUMPTIONS (unchecked; violating them is undefined behaviour):
;
;   1. THE RING SIZE N IS A MULTIPLE OF ST4_UNIT, and the ring starts on a unit
;      boundary - as does the destination. A wide move would fault on an odd
;      address, and the caller's wrap has to land on a unit
;   2. N mod (C * ST4_UNIT) = 0, so F = N/(C*UNIT) calls fill the ring exactly
;   3. no offset exceeds 32512 BYTES - the packer clamps -m to that
;   4. no operation is longer than 65535 units - compress with -l65535
;   5. the caller knows O and makes exactly T = ceil(O/C) calls, as ST1_wrap
;   6. streams A and D start on even addresses, and A holds an even number of
;      bytes: the bit queue is refilled with a move.w, and so is a word offset
;
; Compress with st4 -k<UNIT> -m<ring/UNIT> -l65535.
; -----------------------------------------------------------------------------

; The asset's signature for this build: 'S4', format version 4, and the unit
; size this decoder was assembled for. One cmp.l against a file's first long
; proves all three at once.
ST4_SIGNATURE   equ     $53340400+ST4_UNIT

; One unit copied, at the width this build was made for.
        .macro ST4_MOVE
        .if ST4_UNIT=4
        move.l  (a3)+,(a1)+
        .endif
        .if ST4_UNIT=2
        move.w  (a3)+,(a1)+
        .endif
        .if ST4_UNIT=1
        move.b  (a3)+,(a1)+
        .endif
        .endm

; Jump table: bra.w keeps 4-byte slots (base+0/4). A counted ring has no
; one-shot entry; the caller drains it through ST4_resume.
                bra.w   ST4_init
                bra.w   ST4_resume

; ST4_init: a0 = A, a1 = first ring byte, a2 = B, a4 = C, a5 = D,
; d3.w = N.
; Packs -ring_start.low/N above the common state; writes no memory.
; Java: the Decompressor constructor / reset().
ST4_init:
        moveq   #0,d1
        sub.w   a1,d1                   ; -ring_start.low, exact modulo 65536
        swap    d1                      ; pack it high; remaining = 0
; The initial offset has to land in the low word after the swap, so it starts
; in the high half. moveq would put -1 there whatever the unit size, which is
; a byte short at k=2 and three at k=4 - and only shows up on a stream whose
; first match reuses INITIAL_OFFSET.
        move.l  #(-ST4_UNIT)<<16,d2
        move.w  d3,d2                   ; N lives below it for now
        swap    d2                      ; N above, -INITIAL_OFFSET*UNIT below
        move.w  #$8000,d0               ; an empty bit queue: $8000 is the sentinel
        rts

source_wrapped:
        move.l  d2,d5
        clr.w   d5
        swap    d5                      ; zero-extend N from d2.high
        adda.l  d5,a3                   ; raw source + N, back into the ring
        bra.s   copy

; ST4_resume: a0/a1/d0/d1/d2 = state and d3.w = this call's budget.
; Continuing calls emit d3 bytes; the final call may be shorter. The caller
; knows the final span from O; [old a1 .. new a1) is fresh output.
ST4_resume:
        tst.w   d1                      ; only the first legal call has zero
        beq.s   begin_literals
        move.w  d3,d4                   ; seed n from the nonzero budget
segment:                                ; d1.w remaining >= 1, d3 budget >= 1
        cmp.w   d1,d4                   ; n = min(budget, remaining)
        bls.s   budget_fits
        move.w  d1,d4
budget_fits:
        tst.w   d2                      ; positive lastOffset means LITERALS
        bpl.s   literal_source
; MATCH: derive the source and split a segment that crosses the ring end.
        movea.l a1,a3                   ; raw source = dst + -lastOffset*UNIT
        adda.w  d2,a3                   ; which is why the offset is kept scaled
        move.l  d1,d5
        swap    d5                      ; d5.w = -ring_start.low
        add.w   a1,d5                   ; d5.w = destination position
        add.w   d2,d5                   ; add -offset; carry means unwrapped
        bcs.s   copy                    ; unwrapped source cannot cross before dst
        neg.w   d5                      ; wrapped: room = offset - position
; The room is bytes and the segment is units, so bring them to the same scale.
; Both are whole units - offsets and positions are - so the shift is exact.
        .if ST4_UNIT=2
        lsr.w   #1,d5
        .endif
        .if ST4_UNIT=4
        lsr.w   #2,d5              ; one shift, not two: 10 cycles, not 16
        .endif
        cmp.w   d4,d5                   ; source units before the ring end
        bcc.s   source_wrapped
        move.w  d5,d4                   ; stop this segment at the source wrap
        bra.s   source_wrapped
literal_source:
        movea.l a2,a3                   ; literals copy from stream B
copy:                                   ; Java: writeByte(...) - n moves total:
        sub.w   d4,d1                   ; consume n from the operation
        sub.w   d4,d3                   ; and from this call's budget
        moveq   #15,d5                  ; d5 = n&15, partial-pass dispatch
        and.w   d4,d5
        lsr.w   #4,d4                   ; d4 = n>>4 full passes
        add.w   d5,d5
        neg.w   d5                      ; index = -2*(n&15)
        jmp     ladder_end(pc,d5.w)     ; pc-relative dispatch, no base register
ladder:
        ST4_MOVE                        ; entry at ladder_end-2k copies k units
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
        ST4_MOVE
ladder_end:
        dbf     d4,ladder               ; full 16-unit passes
        tst.w   d2
        bmi.s   match_copied             ; matches consume no input here
        movea.l a3,a2                   ; literals do: stream B advanced
        tst.w   d1
        bne.s   resume_return            ; literals stop only on the budget

literals_transition:
        add.w   d0,d0                   ; Java: next(), case LITERALS (bare read)
        bcs.s   new_offset              ; 1: new offset
        neg.w   d2                      ; from-last reuses the negated offset
        bra.s   gamma_seed0             ; Java: beginMatchFromLastOffset()

match_copied:
        tst.w   d1
        beq.s   match_transition
resume_fresh:                           ; gamma or a split match may continue
        move.w  d3,d4                   ; test budget and seed the next segment
        bne.s   segment                 ; budget remains: start/continue a segment

resume_return:
        rts

begin_literals:                         ; Java: beginLiterals()
        neg.w   d2                      ; positive lastOffset = State.LITERALS
gamma_seed0:
        moveq   #0,d4                   ; remaining = gamma + 0; fall through

; get_gamma: interlaced Elias gamma in d1.w; d4.w is the 0/1 length seed.
; Gammas are odd-length and each is followed by one flag bit, so a gamma always
; starts at an even position and the queue - which empties every 16 bits, also
; even - can only run out on a continuation bit. Data and transition reads need
; no refill check; 16 is even like 8, so the word queue inherits the argument.
get_gamma:
        addq.w  #1,d1
        add.w   d0,d0                   ; first continuation bit
        beq.s   gamma_refill
        bcc.s   gamma_done
gamma_loop:
        add.w   d0,d0                   ; data bit: never a refill
        addx.w  d1,d1                   ; value = value*2 + bit
        add.w   d0,d0                   ; continuation bit
        beq.s   gamma_refill
        bcs.s   gamma_loop
gamma_done:
        add.w   d4,d1                   ; +1 only for a new-offset match
        bra.s   resume_fresh
gamma_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bcs.s   gamma_loop
        bra.s   gamma_done

match_transition:                       ; remaining == 0: parse after a match
        add.w   d0,d0
        bcc.s   begin_literals

new_offset:                             ; Java: beginMatchFromNewOffset/readOffset
; Two class bits: the first says byte or word, the second picks a byte offset's
; 256-unit bank or, for a word, marks the end of the stream. That first bit is
; the only read here that can need a refill. Every operation is an even number
; of bits, so flags and gamma data bits all land on odd positions and the queue
; - which empties every 16 bits - can never run out on one; the bit immediately
; after a flag is the one exception, and it is checked.
        add.w   d0,d0                   ; class bit 1: set means a byte offset
        beq.s   class_refill
class_read:
        bcs.s   byte_offset
        add.w   d0,d0                   ; class bit 2: set means end of stream
        bcs.s   resume_return
        move.w  (a5)+,d2                ; -lastOffset*UNIT, stored ready to use
have_offset:
        moveq   #1,d4                   ; new-offset match: remaining = gamma+1
        bra.s   get_gamma

class_refill:
        move.w  (a0)+,d0
        addx.w  d0,d0
        bra.s   class_read

byte_offset:
; The ladder's DBF left d4.w = $ffff, so one move.b makes $ff00 + (256-offset),
; which already IS -offset as a word. The bank bit moves it 256 units further.
        move.b  (a4)+,d4
        add.w   d0,d0                   ; class bit 2: the 256-unit bank
        bcc.s   scale_offset
        sub.w   #256,d4                 ; the far bank: 257..512 units
scale_offset:
; The offset arrives in units and is kept in bytes, so the match source is one
; adda.w away for the rest of the operation.
        .if ST4_UNIT>1
        add.w   d4,d4
        .endif
        .if ST4_UNIT>2
        add.w   d4,d4
        .endif
        move.w  d4,d2                   ; install -lastOffset*UNIT, keep d2.high
        bra.s   have_offset
