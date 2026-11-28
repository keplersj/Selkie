# Selkie MVP status (v0.1.0-mvp.1)

Date: 2026-11-17, updated 2026-11-18 with real-hardware results. Components: Selkie (this repo, MIT) and droidc
([keplersj/droidc](https://github.com/keplersj/droidc), MIT), plus a patched proot (GPL-2,
separate binary).

**Run on two physical devices on 2026-11-17/18** (both arm64, Android 16): a Samsung Galaxy Tab S11
(SM-X730, kernel 6.6.102-android15-8) and a Motorola Razr Ultra 2025. Before that everything was
tested on Linux CI runners (x86_64 and arm64) and an x86_64 Android 14 (API 34) emulator in GitHub
Actions. See [Real-hardware results](#real-hardware-results) below. The device builds were made from
the same source as this release but not from the tagged APK itself; each fix was checked on the
device in a build made before it was merged.

## What works, and how it was tested

| Feature | Status | How tested |
| --- | --- | --- |
| Pull an OCI bootc image (ghcr.io, quay.io), pick the platform from the multi-arch index by kernel `uname` machine | Works | droidc unit tests; Linux CI on `ubuntu-latest` (x86_64) and `ubuntu-24.04-arm` (aarch64); emulator e2e |
| Keyless cosign verification (Fulcio + Rekor, offline, embedded Sigstore trusted root) against a trust policy | Works | 20 unit tests with real Bluefin LTS signatures; live CI check of `ghcr.io/projectbluefin/bluefin-lts:stable` (manifests + signature only); full Bluefin LTS arm64 switch on the arm64 runner |
| Unsigned images rejected unless `--insecure-allow-unsigned`; status shows `insecure: true` | Works | CI (CentOS bootc is unsigned); emulator e2e |
| Unpack: whiteouts, opaque dirs, drop `/boot`, modules, firmware, kernel/UKI; no chown; uid/gid/mode/xattrs in `metadata.jsonl.zst` | Works | unit tests; CI integration; emulator |
| Hard links on Android via a link2symlink-compatible layout | Works for reading and running binaries | emulator e2e under the bundled proot; Linux proot |
| Deployments: pristine `usr/etc`, 3-way `/etc` merge, `var/` and `home/` outside, atomic `current`, one `previous`, crash recovery, GC | Works | unit tests (all four interrupted states), CI integration (switch, second switch, rollback, gc) |
| Upgrade (no-op when the digest is unchanged), Rollback | Works | CI integration on both arches; emulator e2e (see below) |
| `droidc status --json` inside the guest | Works | emulator e2e (static musl guest build bound to `/usr/bin/droidc`) |
| Selkie app: Material 3 UI (image picker, progress, error cards, status, Upgrade/Rollback, terminal, About) | Works | Compose smoke test on the emulator; JVM unit tests; run by hand on the Tab S11 and the Razr (install flow, status, terminal) |
| In-app terminal (Termux `terminal-view`/`terminal-emulator` v0.118.3, Apache-2.0) | Works | used interactively on both devices: login shell, `id`, `busctl`, `curl`, `fastfetch`; the e2e also drives proot non-interactively with the same argv builder |
| Foreground service (`specialUse`) owning operations and terminal sessions | Partly exercised | an install kept running to completion on the phone while its screen was locked; closing the activity and checking a terminal session survives is untested |
| "Disable child process restrictions" detection and first-run explainer | Detection works on the emulator | e2e asserts the check returns OK (source `settings.global`) after `settings put global settings_enable_monitor_phantom_procs false`. On the Razr the first-run screen blocked until the Developer-options toggle was turned on, then moved on, so reading the property path works on a real device. `adb shell setprop` of that property is refused on that device |
| `droidc adb pair`: pair the guest's adb with Wireless debugging from a notification, as Shizuku does | Implemented | JVM tests for the host-link answers; droidc tests drive `droidc adb pair` against a fake host and adb; not yet run on a device |
| Error kinds network / signature / storage | Implemented | signature and not-found tested in CI; network and storage-space errors are unit-level only |

Emulator end-to-end (CI, `reactivecircus/android-emulator-runner`, API 34 google_apis x86_64,
`-cpu host`): installs the APK, checks the phantom-process state, switches to
`quay.io/centos-bootc/centos-bootc:stream10` with `--links link2symlink`, runs commands inside the
deployment through the bundled proot (`cat /etc/os-release`, `droidc --json status`, `ls`, `id`,
`id -u` under `-0`), upgrades, switches to a second stream10 digest, and rolls back.
Result: **passes** (CI run [44081405858](https://github.com/keplersj/Selkie/actions/runs/44081405858)). Earlier runs found and fixed three Android-only problems: SIGSYS from musl on x86_64 (fixed by the bionic build), CentOS 10 needing x86-64-v3 (emulator `-cpu host`), and the test's second-digest lookup.

## Real-hardware results

Both devices: install Bluefin LTS in the app (`ghcr.io/projectbluefin/bluefin-lts:stable`, digest
`d2a12ef344f8`, signature verified keyless), then open the terminal.

| Check | Tab S11 | Razr Ultra 2025 |
| --- | --- | --- |
| Download 3.8 GB, unpack 216,143 files / 41,542 hard links as `link2symlink`, 6.4 GB on disk | Works (about 3.5 min) | Works (about 4 min) |
| Login shell under proot as the app uid, prompt `selkie@localhost` | Works | Works |
| `curl https://ghcr.io` (DNS and TLS) | Works | Works |
| D-Bus session bus (`dbus-broker-launch`, `busctl --user list`, a `GetId` call) | Works | Works |
| `fastfetch` (Bluefin's `ublue-fastfetch`) with icon glyphs | Works (Nerd Font bundled) | not run |
| Crashes or SIGSYS in logcat | none | none |

Problems the devices found, all fixed before the release was tagged:

- The guest had no passwd entry for the app uid (`I have no name!`, dbus lookups failing). Selkie
  now binds generated `/etc/passwd` and `/etc/group` (Selkie#2).
- libselinux in the guest saw Android's selinuxfs, could not read it and aborted dbus. Selkie now
  hides `/sys/fs/selinux` and `selinuxfs` in `/proc/filesystems` (Selkie#2).
- `supervise` could not start `dbus-broker-launch`: no `/run/systemd/journal/socket`, an empty
  `/etc/machine-id` (ENOMEDIUM), and a `dbus-daemon` fallback that reported success after it died.
  Fixed in droidc#3.
- Nerd Font icons drew as tofu with Android's stock monospace font; a Nerd Font is now bundled
  (Selkie#3).

Device notes: the Razr has two displays, so `adb exec-out screencap` needs `-d <display id>`. On the
Razr, Selkie's first-run screen blocks until "Disable child process restrictions" is on, and
`adb shell setprop persist.sys.fflag.override.settings_enable_monitor_phantom_procs false` was
refused, so use the Developer options toggle.

### Not working or not done

- **Real-hardware coverage is narrow.** Two arm64 Android 16 devices, a short session each. Not tested:
  x86_64 devices, other Android versions, long sessions, low-memory behaviour, the Mali GPU.
- **`flatpak --version` inside Selkie's terminal is unverified.** Flatpak 1.18.0 is in the Bluefin
  LTS image and runs there natively (arm64 runner, chroot), but Bluefin now runs under proot on the
  Tab S11 and the Razr, but `flatpak` itself was not tried there.
- No GPU or Steam. Desktop sound (the guest's PipeWire through `droidc audio-out` to an Android
  AudioTrack) is built but has not been heard on a device yet; the emulator runs with `-noaudio`.
- The desktop follows Android's look (dark mode, accent, font size, "Remove animations", high
  contrast) through `droidc host-link`. The emulator test checks night mode only; not yet seen on a device.
- Web, mail, phone, SMS and map links opened in the desktop (`xdg-open`, GNOME apps) open in Android's
  own apps, also through `droidc host-link`. The emulator test opens one web link; not yet seen on a device.
- GNOME's top bar shows Android's battery (level, charging), through a UPower stand-in that
  `droidc host-link` serves. Not yet seen on a device.
- The screen stays on while a desktop app asks it to (a playing video), and GNOME's own lock screen
  and idle blank are off, since Android locks the device. Not yet seen on a device.
- Desktop apps' notifications (libnotify and GNotification, Flatpaks included) show in Android's shade
  through `droidc host-link`, and from there on a paired Wear OS watch. Tapping one opens the desktop;
  GNotification buttons and replies reach the app; dismissing one closes it in GNOME. Checked against a
  fake GNOME Shell on a test bus only, not yet on a device or watch. A notification closed inside
  GNOME stays on Android, and Android also shows them while the desktop is in front.
- **Graphical sessions (after the MVP release; see "Graphical sessions" below) are verified on Linux
  only.** The emulator GUI test (`emulator-gui` job) passes on x86_64. One manual run on a Galaxy Tab S11 (arm64, Android 16, debug APK of `449b4aa`; PR #7 comment) showed GNOME rendering, touch, the Super button, Nautilus, soft-keyboard typing and Stop working, with no crashes. Not covered there: frame rate, the Back button, hardware keys, the nested-compositor preset, long sessions. No GPU (llvmpipe),
  no audio (it came later; see above), no Steam.
- The terminal UI was used by hand only, and the foreground service's survival when the activity is
  closed has not been tested.
- Upgrade on a real tag change was not observed, and Upgrade/Rollback were not run on the devices;
  the e2e covers the no-op upgrade and a switch to a second digest.
- DNS via droidc's `--dns` resolver path was not exercised: CI networks provide resolvers
  normally. On Android, Selkie passes the DNS servers from `ConnectivityManager`.

## Release status

`v0.1.0-mvp.1` is published as a GitHub prerelease:
<https://github.com/keplersj/Selkie/releases/tag/v0.1.0-mvp.1> (APK, proot source tarball, droidc
commit, SHA256SUMS). The `prerelease` job in `.github/workflows/android.yml` publishes it when a
`v*-*` tag is pushed, only after the emulator e2e passes on that tag. The APK is signed with the
public MVP test key.

## Install on the Galaxy Tab S11

1. On the tablet, open the release page
   <https://github.com/keplersj/Selkie/releases/tag/v0.1.0-mvp.1> and download
   `selkie-0.1.0-mvp.1.apk`.
2. Open the downloaded file. Allow your browser or Files app to "Install unknown apps" when prompted
   (Settings → Apps → Special access → Install unknown apps), then install.
3. Turn on Developer options: Settings → About tablet → Software information → tap **Build number**
   seven times and enter your PIN.
4. Turn on **Settings → Developer options → Disable child process restrictions** (Android 14 and
   newer). Without it Android kills proot's child processes. Turning Developer options off later
   resets this toggle. Alternative from a computer: `adb shell settings put global
   settings_enable_monitor_phantom_procs false`, which survives toggling Developer options.
5. Open Selkie. The first screen checks the toggle; tap **Re-check** after changing it. Allow
   notifications (the foreground service shows progress).
6. Pick **Bluefin LTS** (default, ≈3.8 GB download; about 11 GB ends up on disk, and droidc refuses to start
   with less than ≈13 GB free) or **CentOS Stream 10 bootc** (≈0.9 GB, tick "Allow unsigned image"), then start.
7. When it finishes, the status screen shows the digest, signature and size. Tap **Open terminal**,
   then try `cat /etc/os-release`, `droidc status --json`, `flatpak --version`.

The APK is signed with a **public MVP test key** committed in this repo (`keystore/selkie-mvp.jks`,
see `docs/signing.md`): anyone can sign an APK that installs over it. Fine for testing; replace it
with a secret-held key before any real release.

## Measurements

| | Bluefin LTS arm64 (`ghcr.io/projectbluefin/bluefin-lts:stable`) | CentOS Stream 10 bootc x86_64 |
| --- | --- | --- |
| Where | GitHub `ubuntu-24.04-arm` runner (native arm64, ext4) | emulator, API 34 x86_64 (and a Linux runner) |
| Manifest digest | `sha256:d2a12ef3…0343d` (index `sha256:baee8880…e022`, built 2026-10-12) | `stream10` on 2026-11-17 (`sha256:058a60b7…79ec`) |
| Signature | keyless, verified: `https://github.com/projectbluefin/actions/.github/workflows/reusable-build.yml@refs/tags/v1`, issuer GitHub Actions | none (unsigned, `--insecure-allow-unsigned`) |
| Download (compressed layers) | **3,797,209,399 bytes (3.80 GB)**, 68 zstd layers | 917,727,248 bytes (0.92 GB), 66 gzip layers |
| Files | 216,143 | 101,564 |
| Hard links in the image | **41,542** (46,856 `.l2s.*` entries after conversion) | 31,863 |
| Deployment size on disk | **7.27 GB** (droidc count; `du -sh` 6.8 GB) | 2.71 GB on the emulator |
| Blob cache kept on disk | 3.6 GB | 0.9 GB |
| Unpack time | 32–33 s (two runs) on the arm64 runner; 81 s wall for download + unpack | **27–52 s** in the emulator (4 runs: 52.4, 39.8, 26.9, 31.8 s; 70–160 s including download); 24 s on an x86_64 Linux runner |

link2symlink costs about 15% extra disk compared to real hard links (CentOS x86_64 locally: 2.07 GB
vs 1.80 GB), and roughly doubles unpack time (25 s vs 13 s), because each hard link becomes two
symlinks.

## `systemd --user` under proot: does not run

Full write-up: [`docs/systemd-user-spike.md`](systemd-user-spike.md). Tested on Linux with the
same patched proot and a CentOS Stream 10 bootc rootfs, unprivileged (uid 1000):

- `systemd --user --test` works (units load, `Found container virtualization proot`).
- The real manager exits 1 at its first cgroup operation: `mkdir /sys/fs/cgroup/.../init.scope`
  → `EACCES`. An Android app can never write cgroups, so this is not fixable in proot.
- Neither image ships `dbus-daemon`; they use dbus-broker, which expects systemd socket activation.
  droidc's `supervise` therefore socket-activates `dbus-broker-launch --scope user` itself
  (`LISTEN_FDS`/`LISTEN_PID`). That is unit-tested with a fake broker only.

## Device facts (Galaxy Tab S11)

From Samsung's own pages, retrieved 2026-11-17 (the US spec table renders only in JavaScript):

- Samsung HK launch release: "Operating Platform: Android 16.0", "Processor: Mediatek | Dimensity
  9400+, Octa-Core (3.73GHz, 3.3GHz, 2.4GHz)", 12 GB RAM, "the first Galaxy tablet to feature One
  UI 8".
- Samsung IE product page: "CPU Speed 3.73GHz, 3.3GHz, 2.4GHz", "Octa-Core"; it also lists an
  **Android 17** user manual (Oct 2026), so current units may already run Android 17.
- **Samsung doesn't name the GPU.** Arm Immortalis-G925 (Mali family) comes from MediaTek's
  Dimensity 9400 series, not verified from a primary source here (mediatek.com was unreachable).

## Judgment calls

1. **Two droidc builds per ABI (correction to my own first plan).** I first planned one static musl
   droidc for both the Android host and the guest. On the x86_64 emulator it died with SIGSYS:
   Android's app seccomp filter only allows `mkdirat`-style syscalls on 64-bit, and musl x86_64 calls
   legacy `mkdir`, `chmod`, `rename`, `poll` and others. Now `libdroidc.so` is a bionic (NDK r27c,
   API 26) build run by Selkie, and `libdroidc-guest.so` is the static musl build bound to
   `/usr/bin/droidc` inside proot. aarch64 has no legacy syscalls, so arm64 would likely have worked,
   but both ABIs use the same scheme.
2. **Emulator CPU.** CentOS Stream 10's glibc requires x86-64-v3, so the emulator runs with
   `-qemu -cpu host`. Irrelevant on arm64.
3. **Signing: settled decision 6 was partly wrong.** Bluefin LTS images are not signed with the
   `cosign.pub` in the repo; they are signed keyless from the
   `projectbluefin/actions/.github/workflows/reusable-build.yml@refs/tags/v1` workflow. The default
   policy checks issuer `https://token.actions.githubusercontent.com` and subject
   `^https://github\.com/projectbluefin/`. Key-based verification is kept for `ghcr.io/ublue-os/*`.
4. **arm64 is no longer testing-only.** `bluefin-lts:stable` is multi-arch (arm64 + amd64), so the
   default is `ghcr.io/projectbluefin/bluefin-lts:stable`, not a testing tag.
5. **Verification crate:** RustCrypto (`p256`, `p384`, `x509-cert`, …) instead of sigstore-rs, which
   brings an async runtime and HTTP/TUF/OCI clients that would have to build for musl and Android.
   Rekor inclusion proofs, SCTs and the newer Ed25519 Rekor keys aren't checked.
6. **Registry client:** reqwest with rustls + webpki-roots and a hickory DNS resolver instead of
   `oci-client`, for custom DNS (Android has no `/etc/resolv.conf` for a non-bionic binary),
   resumable hash-while-streaming downloads and raw manifest bytes.
7. **link2symlink layout** uses absolute host paths, like proot's, so the droidc root must not move
   after unpacking; prefix `.l2s.` (Termux proot).
8. **File modes** are stored with `| 0600` so the app uid can read and replace everything (for
   example mode-000 `/etc/shadow`); the real mode is in the metadata manifest.
9. **Crash recovery rolls forward** when the new deployment was fully committed, instead of
   discarding it.
10. **Blob cache is kept** for current + previous (GC removes the rest). This roughly adds the
    compressed image size again to disk use; a purge option would help phones.
11. **proot** is termux/proot `4dba3afb` plus DroidDeck's 28 patches (`6fe7b4ec`), built by our own
    script that downloads DroidDeck's patches at build time; no DroidDeck file is committed. The
    corresponding source tarball is attached to the release.
12. **Terminal libraries** from JitPack `v0.118.3`; Apache-2.0 per the exception in termux-app's
    `LICENSE.md` (the module folders have no license file of their own).
13. **AGP 8.13 / Gradle 8.14.3**, not AGP 9, which needs Gradle 9 and a new Kotlin setup.
14. **Foreground service type `specialUse`** (no 6-hour limit like `dataSync`).
15. **Phantom-process check order:** `Settings.Global` key, then the
    `persist.sys.fflag.override.*` property the Developer-options toggle writes, then default
    (restricted).

## Open questions

- **Selkie's license** — answered: MIT.
- **Bluefin LTS is in flux.** The bluefin-lts repo was briefly rebranded Uranium and its workflows
  now publish `ghcr.io/thallium-suite/uranium` (multi-arch, still updating), while
  `ghcr.io/projectbluefin/bluefin-lts:stable` has had no new build since 2026-10-12. Selkie keeps
  projectbluefin as the default; switch the default (and add a trust-policy entry for its signing
  identity) if Uranium becomes the maintained line.
- **Mali GPU path (main follow-up).** No usable Mesa driver on Android's proprietary Mali kernel
  driver. The route is Venus: forward guest Vulkan to the tablet's Android Vulkan driver (DroidDeck's
  `docs/development/mali-venus.md`, `tools/venus`), then Zink for GL. Needs a guest-side Venus ICD
  and a host-side renderer bound in from the APK, not written into the deployment.
- **What a GNOME session needs** (answered, see "Graphical sessions"): gnome-shell 50 started
  directly, headless, with a session bus and a system bus from `dbus-daemon`, PipeWire, and
  `droidc bridge` to show it. No logind, accounts-daemon, polkit or upower stand-ins were needed to
  reach a working desktop; their absence only produces warnings.
- **Settled decisions that turned out wrong or partly wrong:** #6 (keyless, not a public key),
  #1's "arm64 is testing-only" (no longer true), and the "Android won't run binaries from writable
  storage" rule needed a refinement: the guest rootfs does live in writable storage and runs fine,
  because proot's loader (shipped in `nativeLibraryDir`) maps guest binaries instead of exec'ing
  them; only the binaries Android itself execs must be in jniLibs.
- Does the Tab S11's One UI kernel/SELinux policy allow everything the emulator did (proot's
  `process_vm`, seccomp acceleration, link2symlink renames)? Answered for what the app does: a full
  install and a login shell with a session bus work on both devices. Whether proot's `process_vm`
  and seccomp acceleration are actually in use there was not measured.

## Graphical sessions (follow-up to the MVP)

Selkie has its own Wayland compositor (`native/selkie-compositor`, Rust) and a full-screen desktop
screen. droidc starts the image's session (`droidc supervise --session`) from the image's
`containers.droidc.session.*` labels, or from a user override for images without them. There are two
renderers. `wayland`: the session's compositor runs nested as a client of Selkie's (KWin, cosmic-comp,
gamescope, ...). `screencast`: it runs headless and `droidc bridge` shows it (GNOME 49+, which has no
nested mode).

| What | Status | How tested |
| --- | --- | --- |
| Bluefin LTS GNOME 50 headless on llvmpipe (no GPU), no systemd | Works | Linux x86_64, unprivileged bwrap sandbox; droidc arm64 CI step `measure-bluefin` captures a frame (added, not run yet) |
| GNOME → `droidc bridge` → Selkie's compositor | Works, ~50 fps on cursor motion at 1280x800; GNOME's blur and animations a few fps on llvmpipe | Linux with the compositor's headless build |
| Keyboard, touch, pointer into GNOME (RemoteDesktop) | Works | Same; Super and Escape toggle overview and dialogs |
| D-Bus-activated apps open inside GNOME, not on Selkie's screen | Works | Same (Nautilus via `gapplication launch`) |
| A GTK 4 app (Nautilus) nested directly in Selkie's compositor | Works (wl_shm, Mesa swrast) | Same |
| Compositor protocol (configure, shm, frame pacing, input, resize) | Works | `cargo test` with a real Wayland client |
| GNOME → bridge → compositor on Android, through the app (frames in ~6-10 s, tap, Escape, Super) | Works on the x86_64 emulator; PipeWire needs `access.socket` unrestricted (Android hides `/proc/<pid>/root`); injected hardware keys reached the view on some runs only (test falls back to the view and logs `key_path`) | `DesktopE2ETest` (`emulator-gui` job) |
| proot instead of bwrap | Works (GNOME and Nautilus run under proot on the emulator) | `emulator-gui` job; also seen on a Galaxy Tab S11 (manual, PR #7 comment) |

Known gaps: popups are dismissed and subsurfaces are not drawn (fine for full-screen session compositors
and the bridge, not for single apps with menus); no clipboard; US keymap only; the session size is fixed
at start (rotation does not resize GNOME's monitor); images with only dbus-broker (CentOS Stream 10 bootc)
get no D-Bus activation.
