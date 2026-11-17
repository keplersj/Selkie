# proot for Selkie

Selkie runs bootc images under [proot](https://proot-me.github.io/) without root.
This directory builds the proot binary the Android app ships.

## License: GPL-2.0-or-later

proot is licensed under the **GNU General Public License, version 2 or (at your
option) any later version**; see [`COPYING`](COPYING) (copied from termux/proot).
The proot binary is a separate program from the Selkie app: the app starts it as
a child process (exec from the native library directory) and does not link it.
talloc, linked statically into proot, is LGPL-3.0-or-later (Samba project).

Selkie itself is not part of the proot binary, and no Selkie code is combined
with proot. The patched proot is distributed as its own GPL-2 work.

## What is built

| Output (Android)      | Output (`host`) | Notes |
|-----------------------|-----------------|-------|
| `libproot.so`         | `proot`         | the proot tracer, statically linked talloc |
| `libproot-loader.so`  | `proot-loader`  | the tiny static loader proot injects; pass its path via `PROOT_LOADER` |

`build.sh <outdir> <arm64-v8a|x86_64|host>` (NDK r27c, API 26 for Android ABIs;
`host` is a gcc/glibc build used for experiments, see `docs/systemd-user-spike.md`).
The Android binaries are named `lib*.so` so the app can find them in
`nativeLibraryDir`, which Android allows executing; they are executables, not
libraries. NEEDED libs are checked to be only `libc`, `libdl`, `libm`, and the
build fails unless seccomp filtering and `process_vm` support were enabled.

## Provenance (pinned in `source.env`)

| Input | Source | Pin |
|-------|--------|-----|
| proot | `https://github.com/termux/proot` (Termux fork) | commit `4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f` (Termux package 5.1.107-70); tarball `https://github.com/termux/proot/archive/<commit>.tar.gz` sha256 `de94f648547d1b62431073b82dc99d56ef0ddcef6da022e0dfffe2bf661cc34e` |
| patches | `https://github.com/Droid-Deck/DroidDeck`, directory `tools/proot/` (28 patches to proot, `statx_mnt_id.h`, `profile.h`, `talloc-config.h`) | commit `6fe7b4ec1109169c71c10b77c648eb649ee97423`; tarball `https://codeload.github.com/Droid-Deck/DroidDeck/tar.gz/<commit>` (sha256 not yet pinned, see below) |
| talloc | `https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz` | sha256 `dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd` (fallback: Samba git tag `talloc-2.4.3`, commit `77229f73c20af69ab0f3c96efbb229ff64a9dfe4`) |

The DroidDeck repository is GPL-3.0 as a whole. Only its proot patches (which
are derivative works of GPL-2 proot) and the small headers they need are used,
and they are **downloaded at build time**, never copied into this repository.
`build.sh` is Selkie's own script.

Download integrity: the script tries the tarball and verifies the pinned sha256;
if the tarball cannot be fetched (or no hash is pinned, as for DroidDeck so far)
it fetches the exact pinned commit with `git`, where the commit id is the
integrity check. To pin DroidDeck's tarball hash, run
`curl -L https://codeload.github.com/Droid-Deck/DroidDeck/tar.gz/6fe7b4ec1109169c71c10b77c648eb649ee97423 | sha256sum`
and put it in `DD_SHA256`.

Set `PROOT_NO_PATCH=1` to build plain termux/proot at the same commit.

## Corresponding source

Every build also writes `proot-src-<version>.tar.gz` next to the binaries. It
contains the proot source with all patches applied (`proot/`), the unmodified
patch files (`patches/`), talloc (`talloc/`), and `build.sh`, `source.env`,
`COPYING` and this README: everything needed to rebuild the binaries.

How the source is offered: the Selkie CI uploads this tarball in the
`proot-android` artifact, and Selkie releases that bundle the proot binaries
attach the same tarball as a release asset (GPL-2 section 3a). The app's
licenses screen should point at the release asset and at this directory.
