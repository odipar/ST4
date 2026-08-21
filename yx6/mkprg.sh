#!/bin/sh
# mkprg.sh - a runnable TOS program around one or more packed tunes.
#
#   yx6/mkprg.sh [-m] [-perf] [-nomask] [-tTitle] [-cComposer] [-Nnamesfile] output.prg tunes...
#   yx6/mkprg.sh [-m] tune.yx6 output.prg        # the old order still works
#
# -t, -c and -N flow into the embedded SNDH's tags (mksndh.sh has the
# details); without them the title is the output's stem.
#
# The tunes are built into an SNDH container first (yx6/mksndh.sh - the
# canonical form of the player) and the PRG is a thin shell around those
# same bytes: takeover, one play call per VBL, SPACE to quit, number keys
# to switch subtunes. -m makes the program drop YX6DONE.MRK as it exits,
# which is how yx6/play.sh knows to close the emulator.
#
# The work is org.yx6.MkPrg's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dyx6.repo="$REPO" -cp "$REPO/target/classes" org.yx6.MkPrg "$@"
