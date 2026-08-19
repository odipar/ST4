#!/bin/sh
# mkprg.sh - a runnable TOS program around one or more packed tunes.
#
#   yx6/mkprg.sh [-m] output.prg tune1.yx6 [tune2.yx6 ...]
#   yx6/mkprg.sh [-m] tune.yx6 output.prg        # the old order still works
#
# The tunes are built into an SNDH container first (yx6/mksndh.sh - the
# canonical form of the player) and the PRG is a thin shell around those
# same bytes: takeover, one play call per VBL, SPACE to quit, number keys
# to switch subtunes. -m makes the program drop YX6DONE.MRK as it exits,
# which is how yx6/play.sh knows to close the emulator.
#
# Needs rmac on PATH.
set -e

YX6_DIR=$(cd "$(dirname "$0")" && pwd)

MARKER=0
case $1 in
    -m) MARKER=1; shift ;;
esac

# both argument orders: the .prg names the output wherever it stands
case $1 in
    *.prg|*.PRG) output=$1; shift ;;
    *) output=""; ;;
esac
if [ -z "$output" ]; then
    tune=$1; output=$2
    [ -n "$output" ] || { echo "usage: mkprg.sh [-m] output.prg tunes..." >&2; exit 1; }
    set -- "$tune"
fi

work=$(dirname "$output")/.prg_work
mkdir -p "$work"

title=$(basename "$output" | sed 's/\.[Pp][Rr][Gg]$//')
sh "$YX6_DIR/mksndh.sh" -t"$title" "$work/tune.sndh" "$@"

# subtune count and the first tune's frame count (0 when it loops), for the
# shell's key handling and play-once end detection
tunes=$#
field() {
    od -A n -t u1 -j "$2" -N "$3" "$1" | tr -s ' ' '\n' | grep . | {
        v=0
        while read b; do v=$(( (v << 8) + b )); done
        echo $v
    }
}
flags=$(field "$1" 6 2)
frames=0
if [ $((flags & 1)) -eq 0 ] && [ "$tunes" -eq 1 ]; then
    frames=$(field "$1" 8 4)
fi

cat > "$work/wrapper.S" <<WRAP
YX6_TUNES       equ     $tunes
YX6_FRAMES      equ     $frames
YX6_EXIT_MARKER equ     $MARKER
        include "YX6_player.S"
        .data
        even
sndh:   incbin  "tune.sndh"
        even
WRAP

out=$(cd "$(dirname "$output")" && pwd)/$(basename "$output")
(cd "$work" && rmac -m68000 -p +o3 -i"$YX6_DIR" -o "$out" wrapper.S)
size=$(wc -c < "$output" | tr -d ' ')
echo "$output: $size bytes, $tunes subtune$([ $tunes -ne 1 ] && echo s)"
