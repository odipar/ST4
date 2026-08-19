#!/bin/sh
# mksndh.sh - build an SNDH container around one or more .yx6 files.
#
#   yx6/mksndh.sh [-perf] [-t"Title"] [-c"Composer"] [-Nnamesfile] out.sndh tunes...
#
# -c fills the COMM (composer) tag; -N names the subtunes from a file, one
# per line in tune order, instead of the file stems. -perf builds the raster
# monitor in (YX6.S has the colors). Flags come first.
#
# The tunes become subtunes 1..N (SNDH '##' tag) and must be packed with one
# configuration - same ring, chunk and unit - which `yx6 ... directory/` does
# in one call. The result is a raw (unpacked) SNDH v2.2 file: position
# independent, loadable anywhere, playable by any SNDH host; pack it with
# ICE 2.4 for the archive if you like, players unpack that themselves.
#
# Needs rmac on PATH.
set -e

YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)

TITLE=""
COMPOSER=""
NAMES=""
PERF=0
while true; do
    case $1 in
        -perf) PERF=1; shift ;;
        -t*) TITLE=${1#-t}; shift ;;
        -c*) COMPOSER=${1#-c}; shift ;;
        -N*) NAMES=${1#-N}; shift ;;
        *)   break ;;
    esac
done

output=$1
shift
if [ -z "$output" ] || [ $# -lt 1 ]; then
    echo "usage: mksndh.sh [-tTitle] output.sndh tune1.yx6 [tune2.yx6 ...]" >&2
    exit 1
fi
if [ $# -gt 99 ]; then
    echo "mksndh.sh: SNDH's '##' tag caps a file at 99 subtunes" >&2
    exit 1
fi
[ -n "$TITLE" ] || TITLE=$(basename "$output" | sed 's/\.[Ss][Nn][Dd][Hh]$//')

# One byte or word out of a .yx6 header, big-endian.
field() {
    od -A n -t u1 -j "$2" -N "$3" "$1" | tr -s ' ' '\n' | grep . | {
        v=0
        while read b; do v=$(( (v << 8) + b )); done
        echo $v
    }
}

work=$(dirname "$output")/.sndh_work
mkdir -p "$work"

ring=""; chunk=""; unit=""; hz=""
frms=""
n=0
: > "$work/sndh_tunes.inc.tmp"
for tune in "$@"; do
    [ -f "$tune" ] || { echo "mksndh.sh: no such file: $tune" >&2; exit 1; }
    t_ring=$(field "$tune" 16 2)
    t_chunk=$(field "$tune" 18 2)
    t_hz=$(field "$tune" 12 2)
    t_flags=$(field "$tune" 6 2)
    t_frames=$(field "$tune" 8 4)
    t_intro=$(field "$tune" 34 4)
    t_loop0=$(field "$tune" 106 4)
    section=$t_intro
    [ "$section" -eq 0 ] && section=$t_loop0
    t_unit=$(field "$tune" $((section + 3)) 1)
    if [ -z "$ring" ]; then
        ring=$t_ring; chunk=$t_chunk; unit=$t_unit; hz=$t_hz
    elif [ "$t_hz" != "$hz" ]; then
        echo "mksndh.sh: $tune plays at $t_hz Hz, the set at $hz - one SNDH" \
             "declares one rate" >&2
        exit 1
    elif [ "$t_ring" != "$ring" ] || [ "$t_chunk" != "$chunk" ] || [ "$t_unit" != "$unit" ]; then
        echo "mksndh.sh: $tune is packed n$t_ring c$t_chunk k$t_unit, the set" \
             "started n$ring c$chunk k$unit - one player build needs one" \
             "configuration (pack the set in one yx6 call)" >&2
        exit 1
    fi
    n=$((n + 1))
    # a looping tune is endless: FRMS 0; a play-once tune declares its frames
    if [ $((t_flags & 1)) -eq 1 ]; then frms="$frms,0"; else frms="$frms,$t_frames"; fi
    cp "$tune" "$work/tune$n.yx6"
    if [ -n "$NAMES" ]; then
        sed -n "${n}p" "$NAMES" >> "$work/names.tmp"
    else
        basename "$tune" | sed 's/\.[Yy][Xx]6$//' >> "$work/names.tmp"
    fi
    printf '        dc.l    sndh_tune%d-sndh_start\n' "$n" >> "$work/sndh_tunes.inc.tmp"
done
{
    cat "$work/sndh_tunes.inc.tmp"
    i=1
    while [ $i -le $n ]; do
        printf 'sndh_tune%d:\n        incbin  "tune%d.yx6"\n        even\n' "$i" "$i"
        i=$((i + 1))
    done
} > "$work/sndh_tunes.inc"
rm -f "$work/sndh_tunes.inc.tmp"

nn=$(printf '%02d' "$n")
{
    printf 'ST4_UNIT    equ     %d\n' "$unit"
    printf 'RING_SIZE   equ     %d\n' "$ring"
    printf 'YX6_TUNES   equ     %d\n' "$n"
    printf 'YX6_PERF    equ     %d\n' "$PERF"
    clean=$(echo "$TITLE" | tr -d '"' | tr -cd '[:print:]')
    printf '        dc.b    %s\n' "'TITL',\"$clean\",0"
    if [ -n "$COMPOSER" ]; then
        clean=$(echo "$COMPOSER" | tr -d '"' | tr -cd '[:print:]')
        printf '        dc.b    %s\n' "'COMM',\"$clean\",0"
    fi
    printf "        dc.b    'CONV','Converted from YM by YX6 (ZX1 through ST4)',0\n"
    printf "        dc.b    '##%s',0\n" "$nn"
    printf "        dc.b    'TC%d',0\n" "$hz"
    printf "        dc.b    'FLAG','~','ady',0\n"
    printf '        even\n'
    printf "        dc.b    'FRMS'\n"
    printf '        dc.l    %s\n' "$(echo "$frms" | sed 's/^,//')"
    # the subtune names: SNDH's own multi-song track list. The offsets are
    # words relative to the tag start; the reference parsers agree on the
    # '!#SN' spelling (the spec's own text wavers between the two).
    printf '        even\n'
    printf 'sndh_sn:\n'
    printf "        dc.b    '!#SN'\n"
    i=1
    while [ $i -le $n ]; do
        printf '        dc.w    sndh_sn%d-sndh_sn\n' "$i"
        i=$((i + 1))
    done
    i=1
    while read name; do
        clean=$(echo "$name" | tr -d '"' | tr -cd '[:print:]')
        printf 'sndh_sn%d:\n        dc.b    "%s",0\n' "$i" "$clean"
        i=$((i + 1))
    done < "$work/names.tmp"
} > "$work/sndh_tags.inc"
rm -f "$work/names.tmp"

# rmac trips over this file's include shape and over long include paths
# (crashes, not errors), so the two generated includes are substituted into
# a build copy, and rmac runs inside the work directory on short names.
awk -v tags="$work/sndh_tags.inc" -v tunes="$work/sndh_tunes.inc" '
    /include "sndh_tags.inc"/  { while ((getline l < tags) > 0) print l; next }
    /include "sndh_tunes.inc"/ { while ((getline l < tunes) > 0) print l; next }
    { print }
' "$YX6_DIR/YX6_sndh.S" > "$work/sndh_build.S"
out=$(cd "$(dirname "$output")" && pwd)/$(basename "$output")
(cd "$work" && rmac -m68000 -fr +o3 -i"$YX6_DIR" -i"$REPO/68k" \
     -o "$out" sndh_build.S)
size=$(wc -c < "$output" | tr -d ' ')
echo "$output: $size bytes, $n subtune$([ $n -ne 1 ] && echo s), n$ring c$chunk k$unit"
