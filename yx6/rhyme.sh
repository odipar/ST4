#!/bin/sh
# rhyme.sh - test drive a RhYMe tune: pack it, build a player, run it under Hatari.
#
#   yx6/rhyme.sh song.ymr                # 960-byte rings, 24 values per call
#   yx6/rhyme.sh -n2048 -c32 song.ymr    # longer calls: cheaper on average
#   yx6/rhyme.sh -o song.ymr             # play once and stop
#   yx6/rhyme.sh -min13 -sec52 song.ymr  # trim: start deep in a long tune
#   yx6/rhyme.sh one.ymr two.ymr         # a set: subtunes, number keys pick
#   yx6/rhyme.sh -perf song.ymr          # the raster monitor
#   yx6/rhyme.sh -nomask song.ymr        # drop the frame write's interrupt mask
#
# yx6/rhyme.sh -h lists the lot. Press SPACE in the Hatari window to stop; the
# program asks Hatari to quit on its way out, so the script returns.
#
# The work is org.ymr.RhymePlay's; this only finds the repo and the classes.
# Needs rmac, hatari with a TOS image, and a compiled Java tree.
#
#   HATARI=/path/to/hatari TOS=/path/to/tos.img yx6/rhyme.sh song.ymr
set -e
YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dyx6.repo="$REPO" -cp "$REPO/target/classes" org.ymr.RhymePlay "$@"
