#!/bin/sh
# play.sh - test drive a YM tune: pack it, build a player, run it under Hatari.
#
#   yx6/play.sh song.ym                  # 1024-byte rings, 16 values per call
#   yx6/play.sh -n256 song.ym            # smaller rings: less RAM, worse ratio
#   yx6/play.sh -n2048 -c32 song.ym      # longer calls: cheaper on average
#   yx6/play.sh -l0 song.ym              # loop from the start, whatever the
#                                        # YM header says
#   yx6/play.sh -o song.ym               # play once and stop
#   yx6/play.sh -min13 -sec52 song.ym    # trim: start deep in a long tune
#   yx6/play.sh -startframe41403 -frames1729 song.ym
#   yx6/play.sh one.ym two.ym            # a set: subtunes, number keys pick
#   yx6/play.sh -perf song.ym            # the raster monitor: the frame step
#                                        # works in red, timer ticks in green
#                                        # (A) and blue (D), and a yellow bar
#                                        # estimates the ticks' scanlines
#
# Press SPACE in the Hatari window to stop; the program asks Hatari to quit on
# its way out, so the script returns. Everything it builds lands in a work
# directory next to the first tune, so you can keep the .yx6 and the .PRG.
#
# Several tunes become one program's subtunes, packed with one configuration
# and switched with the number keys. The trim flags apply to a single tune.
#
# Needs rmac, hatari with a TOS image, and a compiled Java tree (it runs
# `mvn compile` for you if target/classes is missing).
#
#   HATARI=/path/to/hatari TOS=/path/to/tos.img yx6/play.sh song.ym
set -e

YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)

HATARI=${HATARI:-hatari}
TOS=${TOS:-$HOME/hatari-2.6.1_macos/tos-2.06.rom}
RING=960
CHUNK=24
UNIT=""
LOOP=""
EXTRA=""
PERF=""

while [ $# -gt 0 ]; do
    case $1 in
        -perf) PERF=-perf ;;
        -n*)  RING=${1#-n} ;;
        -c*)  CHUNK=${1#-c} ;;
        -k*)  UNIT=${1#-k} ;;
        -l*)  LOOP="-l${1#-l}" ;;
        -o)   LOOP="-o" ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        -*)   EXTRA="$EXTRA $1" ;;      # everything else is the packer's:
                                        # trim, -drumhz, whatever comes next
        *)    break ;;
    esac
    shift
done

if [ $# -lt 1 ]; then
    echo "usage: play.sh [-perf] [-nRING] [-cCHUNK] [-kUNIT] [-lFRAME|-o] song.ym..." >&2
    exit 1
fi
for tune in "$@"; do
    if [ ! -f "$tune" ]; then
        echo "play.sh: no such file: $tune" >&2
        exit 1
    fi
done
if [ ! -f "$TOS" ]; then
    echo "play.sh: no TOS image at $TOS - set TOS=/path/to/tos.img" >&2
    exit 1
fi

# Everything for this run goes in one directory, named after the first tune, so
# a second run with a different ring size does not overwrite the first. A set
# says how many more it carries, so its directory cannot collide with a run of
# the first tune alone.
stem=$(basename "$1" | sed 's/\.[Yy][Mm]$//')
work=$(cd "$(dirname "$1")" && pwd)/$stem$([ $# -eq 1 ] || echo "+$(($# - 1))")
work=$work-n$RING-c$CHUNK${UNIT:+-k$UNIT}
mkdir -p "$work"

if [ ! -d "$REPO/target/classes" ]; then
    echo "play.sh: building the packer"
    (cd "$REPO" && mvn -q compile)
fi

# The YM metadata rides into the embedded SNDH, as ym_sndh.sh carries it: each
# tune's name becomes its subtune name, a shared author becomes the composer,
# and a set titles itself with its songs joined.
composer=""
authors_agree=1
: > "$work/names.txt"
for tune in "$@"; do
    meta=$(java -cp "$REPO/target/classes" org.yx6.Yx6 -meta "$tune")
    name=$(echo "$meta" | sed -n 1p)
    author=$(echo "$meta" | sed -n 2p)
    case $(echo "$name" | tr 'A-Z' 'a-z') in
        ''|unknown|untitled|'<unknown>')
            name=$(basename "$tune" | sed 's/\.[Yy][Mm]$//') ;;
    esac
    echo "$name" >> "$work/names.txt"
    if [ -z "$composer" ] && [ -n "$author" ]; then
        composer=$author
    elif [ "$author" != "$composer" ]; then
        authors_agree=0
    fi
done
[ "$authors_agree" -eq 1 ] || composer=""
title=$(paste -sd '|' "$work/names.txt" | sed 's/|/ \/ /g')

echo "play.sh: packing $*"
if [ $# -eq 1 ]; then
    # one tune: the single-file form, so the trim flags work too
    java -ea -cp "$REPO/target/classes" org.yx6.Yx6 -f \
         "-n$RING" "-c$CHUNK" ${UNIT:+-k$UNIT} $LOOP $EXTRA "$1" "$work/$stem.yx6" \
        | grep -vE '^  [RTE]' || true               # the register table is noise here
else
    # a set: the trailing directory packs them all with one configuration,
    # which is what lets them share the player's rings
    java -ea -cp "$REPO/target/classes" org.yx6.Yx6 -f \
         "-n$RING" "-c$CHUNK" ${UNIT:+-k$UNIT} $LOOP $EXTRA "$@" "$work" \
        | grep -vE '^  [RTE]' || true
fi

# Swap the .ym arguments for the .yx6 files they packed into - appending and
# shifting rather than splitting a string, so names with spaces survive.
count=$#
for tune in "$@"; do
    set -- "$@" "$work/$(basename "$tune" | sed 's/\.[Yy][Mm]$//').yx6"
done
shift $count

"$YX6_DIR/mkprg.sh" -m $PERF -t"$title" ${composer:+-c"$composer"} \
    -N"$work/names.txt" "$work/PLAY.PRG" "$@"

# -m built a program that drops this file as it exits, which is how the script
# below knows to close the emulator. Clear any leftover from an earlier run.
marker=$work/YX6DONE.MRK
rm -f "$marker"

echo "play.sh: starting Hatari - press SPACE in its window to stop"
# Sound on, real speed, a window: this is a listening test, not a measurement.
# --confirm-quit off so that closing it never asks anything.
"$HATARI" --tos "$TOS" --machine st --cpuclock 8 --memsize 4 \
    --sound 44100 --ym-mixing model \
    --window --zoom 2 --confirm-quit off --log-level fatal \
    "$work/PLAY.PRG" &
hatari=$!

# Wait for whichever comes first: the program dropping its marker, or the
# emulator being closed by hand. Nothing here needs an answer from the user.
while kill -0 "$hatari" 2>/dev/null; do
    if [ -f "$marker" ]; then
        kill "$hatari" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$hatari" 2>/dev/null || break
            sleep 0.3
        done
        kill -9 "$hatari" 2>/dev/null || true
        break
    fi
    sleep 0.2
done
wait "$hatari" 2>/dev/null || true
rm -f "$marker"
echo "play.sh: stopped. The tune and the program are in $work"
