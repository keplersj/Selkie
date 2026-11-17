#!/usr/bin/env bash
# Build proot for Selkie.
#
#   third_party/proot/build.sh <outdir> <abi>      abi: arm64-v8a | x86_64 | host
#
# Outputs <outdir>/libproot.so and <outdir>/libproot-loader.so (stripped) plus
# <outdir>/proot-src-<version>.tar.gz, the corresponding source (GPL-2).
# "host" builds a glibc binary with gcc for the machine running the script
# (used for experiments only; outputs proot and proot-loader instead).
#
# Everything is downloaded at build time: termux/proot at a pinned commit,
# talloc, and the proot patches/headers from DroidDeck's tools/proot/ at a
# pinned commit. See README.md. Environment overrides:
#   NDK          Android NDK root (default /opt/android-ndk)
#   PROOT_API    Android API level (default 26)
#   PROOT_NO_PATCH=1  build plain termux/proot (no DroidDeck patches)
set -euo pipefail

usage() { echo "usage: $0 <outdir> <arm64-v8a|x86_64|host>" >&2; exit 2; }
[[ $# -eq 2 ]] || usage
OUTDIR=$(mkdir -p "$1" && cd "$1" && pwd)
ABI=$2
HERE=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=source.env
. "$HERE/source.env"
API=${PROOT_API:-26}
NDK=${NDK:-${ANDROID_NDK_ROOT:-/opt/android-ndk}}
JOBS=$(getconf _NPROCESSORS_ONLN)

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

sha_check() { printf '%s  %s\n' "$2" "$1" | sha256sum -c - >/dev/null; }

# --- toolchain -------------------------------------------------------------
case $ABI in
  arm64-v8a|x86_64)
    [[ -d $NDK/toolchains/llvm/prebuilt ]] || { echo "NDK not found at $NDK" >&2; exit 1; }
    TC=$(echo "$NDK"/toolchains/llvm/prebuilt/linux-x86_64/bin)
    [[ -d $TC ]] || { echo "need a linux-x86_64 NDK toolchain" >&2; exit 1; }
    case $ABI in arm64-v8a) TRIPLE=aarch64 ;; x86_64) TRIPLE=x86_64 ;; esac
    CC="$TC/${TRIPLE}-linux-android${API}-clang"
    AR="$TC/llvm-ar"; STRIP="$TC/llvm-strip"; OBJCOPY="$TC/llvm-objcopy"
    OBJDUMP="$TC/llvm-objdump"; READELF="$TC/llvm-readelf"
    [[ -x $CC ]] || { echo "missing $CC" >&2; exit 1; }
    EXTRA_CPP="-DARG_MAX=131072 -Wno-error=implicit-function-declaration"
    TALLOC_EXTRA=""
    OUT_PROOT=libproot.so; OUT_LOADER=libproot-loader.so
    ;;
  host)
    # the loader link uses --rosegment, which GNU ld lacks: wrap gcc to use lld
    mkdir -p "$WORK/bin"
    printf '#!/bin/sh\nexec gcc -fuse-ld=lld "$@"\n' > "$WORK/bin/gcc-lld"; chmod +x "$WORK/bin/gcc-lld"
    CC="$WORK/bin/gcc-lld"; AR=ar
    STRIP=strip; OBJCOPY=objcopy; OBJDUMP=objdump; READELF=readelf
    EXTRA_CPP="-Wno-error=implicit-function-declaration"
    TALLOC_EXTRA="-include limits.h -include stdint.h"  # config.h is tuned for bionic
    # glibc has no <linux/ashmem.h>; provide the two ioctl numbers proot needs.
    mkdir -p "$WORK/shim/linux"
    cat > "$WORK/shim/linux/ashmem.h" <<'SHIM'
#include <linux/ioctl.h>
#include <stddef.h>
#define ASHMEM_SET_SIZE _IOW(0x77, 3, size_t)
#define ASHMEM_GET_SIZE _IO(0x77, 4)
SHIM
    EXTRA_CPP="$EXTRA_CPP -I$WORK/shim"
    OUT_PROOT=proot; OUT_LOADER=proot-loader
    ;;
  *) usage ;;
esac

# --- downloads -------------------------------------------------------------
curl_get() { # url dest
  curl -fsSL --connect-timeout 15 --max-time 300 --retry 3 --retry-all-errors -o "$2" "$1"
}

# fetch_github <name> <owner/repo> <commit> <sha256|""> <destdir>
# Tries the codeload tarball (sha256-verified when a hash is pinned) and falls
# back to fetching the exact commit with git (the commit id verifies content).
fetch_github() {
  local name=$1 repo=$2 commit=$3 sha=$4 dest=$5 tgz="$WORK/$1.tar.gz"
  mkdir -p "$dest"
  if [[ -n $sha ]] &&
     curl_get "https://codeload.github.com/$repo/tar.gz/$commit" "$tgz" 2>/dev/null &&
     sha_check "$tgz" "$sha"; then
    tar -xzf "$tgz" -C "$dest" --strip-components=1
    echo "fetched $name from codeload.github.com (sha256 $sha)"
    return 0
  fi
  echo "codeload tarball for $name unavailable or unpinned; fetching $commit with git" >&2
  git init -q "$WORK/$name.git"
  git -C "$WORK/$name.git" fetch -q --depth 1 "https://github.com/$repo" "$commit"
  [[ $(git -C "$WORK/$name.git" rev-parse FETCH_HEAD) == "$commit" ]] || { echo "commit mismatch for $name" >&2; exit 1; }
  git -C "$WORK/$name.git" archive FETCH_HEAD | tar -x -C "$dest"
}

fetch_talloc() {
  local dest=$1 tgz="$WORK/talloc.tar.gz"
  mkdir -p "$dest"
  if curl_get "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" "$tgz" 2>/dev/null &&
     sha_check "$tgz" "$TALLOC_SHA256"; then
    tar -xzf "$tgz" -C "$dest" --strip-components=1
    echo "fetched talloc from samba.org (sha256 $TALLOC_SHA256)"
    return 0
  fi
  echo "samba.org tarball unavailable; fetching talloc-${TALLOC_VERSION} tag from Samba's git tree" >&2
  local g="$WORK/samba.git"
  git clone -q --depth 1 --branch "talloc-${TALLOC_VERSION}" --filter=blob:none --sparse \
      https://github.com/samba-team/samba "$g"
  git -C "$g" sparse-checkout set lib/talloc lib/replace
  [[ $(git -C "$g" rev-parse HEAD) == "$TALLOC_GIT_COMMIT" ]] || { echo "talloc commit mismatch" >&2; exit 1; }
  # same layout as the release tarball: talloc.c at the top, lib/replace below
  cp -a "$g"/lib/talloc/. "$dest"/
  mkdir -p "$dest/lib"
  cp -a "$g"/lib/replace "$dest/lib/replace"
}

PROOT_DIR=$WORK/proot-src/proot
TALLOC_DIR=$WORK/proot-src/talloc
DD_DIR=$WORK/dd
mkdir -p "$WORK/proot-src"
fetch_github proot termux/proot "$PROOT_COMMIT" "$PROOT_SHA256" "$PROOT_DIR"
fetch_talloc "$TALLOC_DIR"

# --- patches ---------------------------------------------------------------
if [[ ${PROOT_NO_PATCH:-0} == 1 ]]; then
  PATCHED=no
  VERSION="${PROOT_COMMIT:0:8}"
else
  fetch_github droiddeck Droid-Deck/DroidDeck "$DD_COMMIT" "$DD_SHA256" "$DD_DIR"
  DDP=$DD_DIR/tools/proot
  [[ -d $DDP/patches ]] || { echo "tools/proot/patches missing in DroidDeck $DD_COMMIT" >&2; exit 1; }
  cp "$DDP/statx_mnt_id.h" "$PROOT_DIR/src/tracee/statx_mnt_id.h"
  cp "$DDP/profile.h" "$PROOT_DIR/src/syscall/profile.h"
  mkdir -p "$WORK/proot-src/patches"
  for p in "$DDP"/patches/*.patch; do
    patch -d "$PROOT_DIR" -p1 --forward --quiet < "$p"
    cp "$p" "$WORK/proot-src/patches/"
  done
  cp "$DDP/talloc-config.h" "$WORK/proot-src/talloc-config.h"
  PATCHED=yes
  VERSION="${PROOT_COMMIT:0:8}-dd-${DD_COMMIT:0:8}"
fi
if [[ ! -f $WORK/proot-src/talloc-config.h ]]; then
  # plain build: reuse the config.h from the patch set if available, else error
  fetch_github droiddeck Droid-Deck/DroidDeck "$DD_COMMIT" "$DD_SHA256" "$DD_DIR"
  cp "$DD_DIR/tools/proot/talloc-config.h" "$WORK/proot-src/talloc-config.h"
fi

# --- talloc (static) -------------------------------------------------------
mkdir -p "$TALLOC_DIR/config"
cp "$WORK/proot-src/talloc-config.h" "$TALLOC_DIR/config/config.h"
"$CC" -c -O2 -fPIC -ffile-prefix-map="$WORK"=. -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H $TALLOC_EXTRA \
    -I"$TALLOC_DIR/config" -I"$TALLOC_DIR" -I"$TALLOC_DIR/lib/replace" \
    -o "$WORK/talloc.o" "$TALLOC_DIR/talloc.c"
"$AR" rcs "$WORK/libtalloc.a" "$WORK/talloc.o"

# --- proot -----------------------------------------------------------------
mkdir -p "$WORK/bin"
ln -s "$(command -v "$READELF" || echo "$READELF")" "$WORK/bin/readelf"
# loader-info.awk needs strtonum (gawk); mawk lacks it.
if command -v gawk >/dev/null; then
  ln -s "$(command -v gawk)" "$WORK/bin/awk"
elif ! awk 'BEGIN { exit !(strtonum("0x10") == 16) }' 2>/dev/null; then
  echo "gawk (or an awk with strtonum) is required to build the proot loader" >&2
  exit 1
fi
PATH="$WORK/bin:$PATH" make -C "$PROOT_DIR/src" -j"$JOBS" \
    CC="$CC" STRIP="$STRIP" OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$TALLOC_DIR $EXTRA_CPP" \
    LDFLAGS="$WORK/libtalloc.a -Wl,-z,noexecstack" \
    PROOT_UNBUNDLE_LOADER=/nonexistent proot loader

install -m755 "$PROOT_DIR/src/proot" "$OUTDIR/$OUT_PROOT"
install -m755 "$PROOT_DIR/src/loader/loader" "$OUTDIR/$OUT_LOADER"
"$STRIP" --strip-unneeded "$OUTDIR/$OUT_PROOT" "$OUTDIR/$OUT_LOADER"

# --- checks ----------------------------------------------------------------
grep -q '^#define HAVE_SECCOMP_FILTER' "$PROOT_DIR/src/build.h" || { echo "ERROR: built without seccomp acceleration" >&2; exit 1; }
grep -q '^#define HAVE_PROCESS_VM' "$PROOT_DIR/src/build.h" || { echo "ERROR: built without process_vm" >&2; exit 1; }
NEEDED=$("$READELF" -d "$OUTDIR/$OUT_PROOT" | sed -n 's/.*NEEDED.*\[\(.*\)\]/\1/p' | tr '\n' ' ')
echo "$OUT_PROOT NEEDED: $NEEDED"
for lib in $NEEDED; do
  case $ABI:$lib in
    host:libc.so.6|host:libm.so.6|host:libdl.so.2) ;;
    host:*) echo "ERROR: unexpected dependency $lib" >&2; exit 1 ;;
    *:libc.so|*:libdl.so|*:libm.so) ;;
    *) echo "ERROR: unexpected dependency $lib" >&2; exit 1 ;;
  esac
done

# --- corresponding source --------------------------------------------------
cp "$HERE/build.sh" "$HERE/source.env" "$HERE/COPYING" "$HERE/README.md" "$WORK/proot-src/"
SRCDIR=proot-src-$VERSION
rm -rf "$WORK/$SRCDIR" && mkdir "$WORK/$SRCDIR" && cp -a "$WORK"/proot-src/. "$WORK/$SRCDIR/"
# drop build products from the patched tree
make -C "$WORK/$SRCDIR/proot/src" clean >/dev/null 2>&1 || true
find "$WORK/$SRCDIR" \( -name '*.o' -o -name '*.d' \) -delete
rm -rf "$WORK/$SRCDIR/talloc/config"
tar --sort=name --mtime='2000-01-01 00:00Z' --owner=0 --group=0 --numeric-owner \
    -C "$WORK" -czf "$OUTDIR/$SRCDIR.tar.gz" "$SRCDIR"

echo "patched=$PATCHED version=$VERSION"
ls -l "$OUTDIR/$OUT_PROOT" "$OUTDIR/$OUT_LOADER" "$OUTDIR/$SRCDIR.tar.gz"
