#!/bin/sh
# The standalone ST4 executables: one pair per platform, so a machine with
# neither this repository nor a runtime can pack and unpack.
#
#   release/publish.sh [version]      # the six platforms below
#   TARGETS="linux-x64" release/publish.sh
#   OUT=dir release/publish.sh
#
# They are built from go/, so there are six of them: go build cross-compiles
# to any target from any host with no toolchain installed for it.
#
# NO JAVA AND NO DOTNET RUN HERE. The three trees write the same bytes and
# the parity check reads them against one another; a release is built from
# one tree.
#
# The executables run with no wrapper. Go builds a real executable, so no tool
# has to find a runtime or a classpath before one runs. The Java tools read
# the wrappers under bin/ instead.
set -e
cd "$(dirname "$0")/.."
REPO=$(pwd)
OUT=${OUT:-dist}
# A relative OUT counts from the repository, and every use below is the
# resolved one: a build runs from go/, where a relative path counts from
# somewhere else.
case $OUT in /*) ;; *) OUT=$REPO/$OUT ;; esac
TARGETS=${TARGETS:-"win-x64 win-arm64 osx-x64 osx-arm64 linux-x64 linux-arm64"}
TOOLS="st4 dst4"

# The version names the zips. The pom is where it is recorded, and this
# reads the text rather than running anything. It is the version of the
# tools and of the Go module, which is not the format version the tools
# print: the format is 7, and a Go module reads semver from v0.
VERSION=${1:-$(sed -n 's/.*<version>\(.*\)<\/version>.*/\1/p' pom.xml | head -1)}
if [ -z "$VERSION" ]; then
    echo "publish: pom.xml does not name a version" >&2
    exit 1
fi

rm -rf "$OUT/release"
mkdir -p "$OUT/release"

for target in $TARGETS; do
    case "$target" in
        win-*)   os=windows; ext=.exe ;;
        osx-*)   os=darwin;  ext= ;;
        linux-*) os=linux;   ext= ;;
        *) echo "publish: $target is not a platform this builds" >&2; exit 1 ;;
    esac
    case "$target" in
        *-x64)   arch=amd64 ;;
        *-arm64) arch=arm64 ;;
        *) echo "publish: $target does not name an architecture" >&2; exit 1 ;;
    esac

    # The directory is where a built tool gets tried out, so the build
    # starts from an empty one.
    rm -rf "$OUT/$target"
    mkdir -p "$OUT/$target"
    for tool in $TOOLS; do
        # CGO off makes the binary static and the cross-build runs; -s -w
        # drop the symbol and debug tables, which no tool here reads.
        (cd go && CGO_ENABLED=0 GOOS=$os GOARCH=$arch \
            go build -ldflags="-s -w" -o "$OUT/$target/$tool$ext" ./cmd/"$tool")
    done
    zip="st4-tools-$target-v$VERSION.zip"
    (cd "$OUT/$target" && zip -q -X "../release/$zip" *)
    echo "$OUT/release/$zip: $(wc -c < "$OUT/release/$zip" | tr -d ' ') bytes"
done

# What the release contains, by name, size and hash: release/manifest.sh.
release/manifest.sh "$VERSION" "$OUT/release"

# The host's pair, tried as a caller would: from a directory that is not
# this repository, with no other file beside them and an empty environment.
# A file goes in on standard input and the same bytes come back out.
case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) host=osx-arm64 ;;
    Darwin-x86_64) host=osx-x64 ;;
    Linux-x86_64) host=linux-x64 ;;
    Linux-aarch64) host=linux-arm64 ;;
    *) host= ;;
esac
if [ -n "$host" ] && [ -d "$OUT/$host" ]; then
    try=$(mktemp -d)
    cp README.md "$try/in"
    (cd "$try" && env -i "$OUT/$host/st4" -k1 -silent < in > packed \
        && env -i "$OUT/$host/dst4" -silent < packed > out)
    if cmp -s "$try/in" "$try/out"; then
        echo "tried: README.md through both tools from $OUT/$host, outside the" \
             "repository, $(wc -c < "$try/packed" | tr -d ' ') bytes packed and back"
    else
        echo "publish: the host's pair did not round-trip" >&2
        exit 1
    fi
    rm -rf "$try"
fi

echo "$OUT/release is this release: the tools, one zip a platform."
