ST4_UNIT    equ     2
RING_SIZE   equ     960
YX6_TUNES   equ     2
        dc.b    'TITL','Rig',0
        dc.b    'CONV','YX6 (ZX1 through ST4)',0
        dc.b    '##02',0
        dc.b    'TC50',0
        dc.b    'FLAG','~','ady',0
        even
        dc.b    'FRMS'
        dc.l    0,0
        even
sndh_sn:
        dc.b    '!#SN'
        dc.w    sndh_sn1-sndh_sn
        dc.w    sndh_sn2-sndh_sn
sndh_sn1:
        dc.b    'sndh_tune1',0
sndh_sn2:
        dc.b    'sndh_tune2',0
