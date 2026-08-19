#!/bin/sh
# ym_sndh.sh - from .ym dumps to one SNDH file, in one command.
#
#   yx6/ym_sndh.sh [-tTitle] [packer flags] output.sndh one.ym [two.ym ...]
#
# Runs the two steps this repo already has: the yx6 packer over every input
# with one configuration, then mksndh.sh around the results - the tunes
# become subtunes 1..N, named after their files. Every packer flag passes
# through (-nN -cC -kK -lF -o -drumhzH; the trim flags too, for a single
# tune). -tTitle names the SNDH; the default is the output's stem.
#
#   yx6/ym_sndh.sh -t"Mad Max" maxset.sndh stormlord3.ym lastv8.ym
#
# Needs rmac and a compiled Java tree (it runs `mvn compile` if needed).
set -e

YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)

TITLE=""
FLAGS=""
while true; do
    case $1 in
        -t*) TITLE=$1; shift ;;
        -*)  FLAGS="$FLAGS $1"; shift ;;
        *)   break ;;
    esac
done

output=$1
shift
if [ -z "$output" ] || [ $# -lt 1 ]; then
    echo "usage: ym_sndh.sh [-tTitle] [packer flags] output.sndh tunes.ym..." >&2
    exit 1
fi

if [ ! -d "$REPO/target/classes" ]; then
    echo "ym_sndh.sh: building the packer"
    (cd "$REPO" && mvn -q compile)
fi

work=$(dirname "$output")/.ym_work
rm -rf "$work"
mkdir -p "$work"

# The YM headers' metadata, where applicable: each tune's name becomes its
# subtune name (unless the dump says "unknown", when the file stem reads
# better), a shared author becomes the SNDH composer (COMM), and a lone
# tune's name becomes the title unless -t said otherwise.
COMPOSER=""
first_name=""
authors_agree=1
: > "$work/names.txt"
for ym in "$@"; do
    meta=$(java -cp "$REPO/target/classes" org.yx6.Yx6 -meta "$ym")
    name=$(echo "$meta" | sed -n 1p)
    author=$(echo "$meta" | sed -n 2p)
    stem=$(basename "$ym" | sed 's/\.[Yy][Mm]$//')
    case $(echo "$name" | tr 'A-Z' 'a-z') in
        ''|unknown|untitled|'<unknown>') name=$stem ;;
    esac
    echo "$name" >> "$work/names.txt"
    [ -n "$first_name" ] || first_name=$name
    if [ -z "$COMPOSER" ] && [ -n "$author" ]; then
        COMPOSER=$author
    elif [ "$author" != "$COMPOSER" ]; then
        authors_agree=0
    fi
done
[ "$authors_agree" -eq 1 ] || COMPOSER=""
if [ -z "$TITLE" ]; then
    if [ $# -eq 1 ]; then
        TITLE="-t$first_name"
    else
        # a set titles itself with its songs, joined
        TITLE="-t$(paste -sd '|' "$work/names.txt" | sed 's/|/ \/ /g')"
    fi
fi

if [ $# -eq 1 ]; then
    # one tune: the single-file form, so the trim flags work too
    stem=$(basename "$1" | sed 's/\.[Yy][Mm]$//')
    java -ea -cp "$REPO/target/classes" org.yx6.Yx6 -f $FLAGS \
         "$1" "$work/$stem.yx6" | grep -vE '^  [RTE]' || true
    set -- "$work/$stem.yx6"
else
    java -ea -cp "$REPO/target/classes" org.yx6.Yx6 -f $FLAGS \
         "$@" "$work" | grep -vE '^  [RTE]' || true
    tunes=""
    for ym in "$@"; do
        stem=$(basename "$ym" | sed 's/\.[Yy][Mm]$//')
        tunes="$tunes|$work/$stem.yx6"
    done
    oldIFS=$IFS; IFS='|'; set -- $tunes; IFS=$oldIFS
    shift        # the leading empty field
fi

sh "$YX6_DIR/mksndh.sh" "$TITLE" ${COMPOSER:+-c"$COMPOSER"} \
   -N"$work/names.txt" "$output" "$@"
