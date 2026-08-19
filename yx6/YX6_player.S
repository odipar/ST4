; -----------------------------------------------------------------------------
; YX6_player.S - a complete TOS program that plays one .yx6 file.
;
; This is what yx6/mkprg.sh assembles around a song: it installs YX6 on the
; VBL, plays the tune - round its loop for as long as it has one - and quits on
; SPACE or when a play-once tune ends.
; It is also the smallest worked example of the library's calling convention.
;
; It is not a standalone assembly: the includer supplies the three things
; that depend on the actual song, then includes this file.
;
;       RING_SIZE   equ     960             ; the ring size in the file header
;       YX6_EXIT_MARKER equ 0               ; 1 to drop a file when it stops
;       ST4_UNIT    equ     1               ; the unit the tune was packed at
;               include "YX6_player.S"
;       song:   incbin  "song.yx6"          ; in .data, at an even address
;
; and a `workspace` label is reserved here from RING_SIZE. yx6/mkprg.sh writes
; exactly that wrapper and runs:
;
;       rmac -m68000 -p +o3 -iyx6 -i68k -o SONG.PRG wrapper.S
; -----------------------------------------------------------------------------

GEMDOS      equ     1
Fcreate     equ     $3C
Fclose      equ     $3E
Cconws      equ     9
Mshrink     equ     $4A
XBIOS       equ     14
Supexec     equ     38
VBL_VECTOR  equ     $70
ACIA_CTRL   equ     $FFFFFC00       ; the keyboard ACIA, polled directly
ACIA_DATA   equ     $FFFFFC02
SPACE_MAKE  equ     $39             ; the space bar's make code

        .text

start:
        move.l  4(sp),a5                        ; the basepage TOS gave us
        lea     stack_top,sp                    ; our own stack, inside our bss
        move.l  12(a5),d0                       ; give back everything past
        add.l   20(a5),d0                       ; text + data + bss
        add.l   28(a5),d0
        add.l   #$100,d0                        ; plus the basepage itself
        move.l  d0,-(sp)
        move.l  a5,-(sp)
        clr.w   -(sp)
        move.w  #Mshrink,-(sp)
        trap    #GEMDOS
        lea     12(sp),sp

        lea     m_banner,a0
        bsr     puts
        lea     m_playing,a0                    ; printed while TOS still owns
        bsr     puts                            ; the machine - see below

; From here to remove, the machine is ours alone. install silences every
; interrupt TOS runs - the 200 Hz tick, the keyboard, the lot - takes the VBL,
; and only then initialises the player, whose YX6_init needs supervisor mode
; anyway for the MFP timers it claims. TOS never touches the sound chip or a
; timer while the tune plays, and space is read straight off the keyboard
; ACIA. play holds the whole session; d0 comes back -1 for a bad file.
        move.l  #play,-(sp)
        move.w  #Supexec,-(sp)
        trap    #XBIOS
        addq.l  #6,sp
        tst.l   d0
        beq.s   .quit
        lea     m_bad,a0
        bsr     puts

.quit:
        lea     m_bye,a0
        bsr     puts
        .if YX6_EXIT_MARKER
; A scripted run - yx6/play.sh - watches for this file so it can close the
; emulator when the program stops, instead of leaving a window to be quit by
; hand. It is the only thing the player ever writes to disk, and only when the
; wrapper asks for it.
        clr.w   -(sp)                           ; ordinary attributes
        pea     m_marker
        move.w  #Fcreate,-(sp)
        trap    #GEMDOS
        lea     8(sp),sp
        tst.w   d0
        bmi.s   .no_marker
        move.w  d0,-(sp)
        move.w  #Fclose,-(sp)
        trap    #GEMDOS
        addq.l  #4,sp
.no_marker:
        .endif
        clr.w   -(sp)                           ; Pterm0
        trap    #GEMDOS

; -----------------------------------------------------------------------------
; play, under Supexec: the takeover, the whole playing session, and the
; handback. Every MFP interrupt is disabled and its enable state saved - the
; system tick, the keyboard, the disk, the serial port - so nothing but this
; player touches the sound chip or a timer; YX6_init then enables exactly the
; two timers the effects own. The VBL is ours alone, no chaining: TOS's VBL
; work (the floppy deselect among it, a sound-chip access) stays silenced
; with the rest. The space bar is read straight off the keyboard ACIA.
; -----------------------------------------------------------------------------
play:
        move.w  sr,-(sp)
        move.w  #$2700,sr
        move.b  $FFFFFA07.w,old_iera
        move.b  $FFFFFA09.w,old_ierb
        move.b  $FFFFFA13.w,old_imra
        move.b  $FFFFFA15.w,old_imrb
        clr.b   $FFFFFA07.w                     ; every MFP interrupt dead
        clr.b   $FFFFFA09.w
        clr.b   $FFFFFA13.w
        clr.b   $FFFFFA15.w

        lea     song,a0                         ; the packed tune; init claims
        lea     workspace,a1                    ; Timers A and D on top of the
        bsr     YX6_init                        ; silence just made
        tst.l   d0
        beq.s   .accepted
        move.b  old_iera,$FFFFFA07.w            ; rejected: hand it all back
        move.b  old_ierb,$FFFFFA09.w
        move.b  old_imra,$FFFFFA13.w
        move.b  old_imrb,$FFFFFA15.w
        move.w  (sp)+,sr
        moveq   #-1,d0
        rts
.accepted:
        move.l  VBL_VECTOR,old_vbl
        move.l  #handler,VBL_VECTOR
        move.w  #$2300,sr                       ; VBL and our timers, only

        clr.w   mouse_skip
.wait:
        tst.w   finished                        ; the handler reports the end
        bne.s   .stop
        btst    #0,ACIA_CTRL.w                  ; a byte from the keyboard?
        beq.s   .wait
        move.b  ACIA_DATA.w,d0
        tst.w   mouse_skip                      ; inside a mouse packet: its
        beq.s   .fresh                          ; delta bytes are not keys
        subq.w  #1,mouse_skip
        bra.s   .wait
.fresh:
        cmp.b   #$F8,d0                         ; $F8-$FB open a relative
        bcs.s   .key                            ; mouse packet of two deltas
        cmp.b   #$FC,d0
        bcc.s   .key
        move.w  #2,mouse_skip
        bra.s   .wait
.key:
        cmp.b   #SPACE_MAKE,d0                  ; space stops; anything else
        bne.s   .wait                           ; keeps it playing

.stop:
        move.w  #$2700,sr
        lea     workspace,a0
        bsr     YX6_stop                        ; chip quiet, timers handed back
        move.l  old_vbl,VBL_VECTOR
        move.b  old_iera,$FFFFFA07.w            ; and TOS's interrupts revived
        move.b  old_ierb,$FFFFFA09.w
        move.b  old_imra,$FFFFFA13.w
        move.b  old_imrb,$FFFFFA15.w
        move.w  (sp)+,sr
        moveq   #0,d0
        rts

; The VBL handler runs in supervisor mode at 50 Hz, which is exactly what
; YX6_play wants: one call per frame, in order, and never after it reports
; the end. A looping tune never reports one - it returns 1 each time round
; and plays until the space bar stops it.
handler:
        movem.l d0-d7/a0-a6,-(sp)
        tst.w   finished
        bne.s   .done
        lea     workspace,a0
        bsr     YX6_play
        tst.l   d0
        bpl.s   .done                           ; 0 played, 1 went round again
        move.w  #1,finished                     ; -1: a play-once tune ran out
        lea     workspace,a0
        bsr     YX6_stop
.done:
        movem.l (sp)+,d0-d7/a0-a6
        rte                                     ; ours alone: no TOS behind us

puts:
        move.l  a0,-(sp)
        move.w  #Cconws,-(sp)
        trap    #GEMDOS
        addq.l  #6,sp
        rts

; ------------------------------------------------------------------ the library
        include "YX6.S"
        include "ST4_wrap.S"

        .data
m_banner:   dc.b    "YX6 player v0.5 - ZX1 streaming through ST4",13,10,0
m_playing:  dc.b    "Playing. Press SPACE to stop.",13,10,0
        .if YX6_EXIT_MARKER
m_marker:   dc.b    "YX6DONE.MRK",0
        .endif
m_bad:      dc.b    "Not a v4 yx6 file for this build.",13,10,0
m_bye:      dc.b    "Done.",13,10,0
        even

        .bss
        even
old_vbl:    ds.l    1
finished:   ds.w    1
old_iera:   ds.b    1
old_ierb:   ds.b    1
old_imra:   ds.b    1
old_imrb:   ds.b    1
        even
mouse_skip: ds.w    1
        even
; The parentheses matter: rmac evaluates expressions strictly left to right.
workspace:  ds.b    YX6_FIXED+(YX6_STREAMS*RING_SIZE)
        even
            ds.b    $1000
stack_top:
