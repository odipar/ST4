#!/bin/sh
# MANIFEST.txt for a release directory: what the release contains, by name,
# size and sha256, so a caller can tell one release's file from another's
# without opening it.
#
#   release/manifest.sh VERSION DIR
#   COMMIT=e39c110 release/manifest.sh VERSION DIR
set -e
VERSION=$1
DIR=$2
if [ -z "$VERSION" ] || [ -z "$DIR" ]; then
    echo "usage: release/manifest.sh VERSION DIR" >&2
    exit 1
fi
COMMIT=${COMMIT:-$(git rev-parse --short HEAD 2>/dev/null || echo unknown)}

sha() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

{
    echo "ST4 tools - release $VERSION"
    echo "source commit $COMMIT"
    echo "The format is 7, which the tools print; this version is the tools'"
    echo "and the Go module's. doc/SPEC.md is the format, doc/research.md what"
    echo "was measured."
    echo
    echo "the zips"
    echo "name  bytes  sha256  contents"
    for zip in "$DIR"/st4-tools-*.zip; do
        [ -e "$zip" ] || continue
        name=$(basename "$zip")
        platform=$(echo "$name" | sed "s/^st4-tools-//; s/-v$VERSION\.zip$//")
        echo "$name  $(wc -c < "$zip" | tr -d ' ')  $(sha "$zip")  st4 and dst4 for $platform"
    done
} > "$DIR/MANIFEST.txt"
echo "$DIR/MANIFEST.txt: $(wc -l < "$DIR/MANIFEST.txt" | tr -d ' ') lines"
