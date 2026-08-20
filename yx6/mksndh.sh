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
# The work is org.yx6.MkSndh's; this only finds the repo and the classes.
# Needs rmac on PATH.
set -e
YX6_DIR=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$YX6_DIR/.." && pwd)
[ -d "$REPO/target/classes" ] || (cd "$REPO" && mvn -q compile)
exec java -ea -Dyx6.repo="$REPO" -cp "$REPO/target/classes" org.yx6.MkSndh "$@"
