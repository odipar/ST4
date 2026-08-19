        dc.l    sndh_tune1-sndh_start
        dc.l    sndh_tune2-sndh_start
sndh_tune1:
        incbin  "tune1.yx6"
        even
sndh_tune2:
        incbin  "tune2.yx6"
        even
