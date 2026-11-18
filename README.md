# Selkie

Selkie is an Android app that runs unmodified **bootc container images** (first target: Bluefin LTS
arm64 on a Samsung Galaxy Tab S11, Android 16) under [proot](https://proot-me.github.io/), without root.

The engine is **droidc**, a separate static Rust binary (`keplersj/droidc`). Selkie launches it as a
subprocess and talks to it with newline-delimited JSON on stdout; Selkie itself is the Kotlin / Jetpack
Compose frontend: image picker, progress, status, a Termux-based terminal, a foreground service that
keeps everything alive, and the proot launch logic.

License: MIT (see `LICENSE`). Bundled proot is GPL-2.0-or-later; see About in the app.

## How it works (short)

1. `droidc switch <ref>` pulls the image, verifies its signature, unpacks it to `filesDir/droidc/deployments/<id>/rootfs`.
2. `droidc shell-spec --json` says how to enter it; Selkie adds Android binds and runs `libproot.so`.
3. The shell is `droidc supervise -- /bin/bash -l` inside the guest, in a Termux `TerminalView`.
4. The desktop is `droidc supervise --session` inside the guest, drawn by Selkie's own Wayland compositor
   (`native/selkie-compositor`) into a full-screen `SurfaceView`. How the session starts comes from the
   image's `containers.droidc.session.*` labels, or from the session settings screen for images without
   them (Bluefin LTS today: GNOME, shown through `droidc bridge`).

See [docs/architecture.md](docs/architecture.md).

## Download

- **Latest `main`:** the [`latest` prerelease](https://github.com/keplersj/Selkie/releases/tag/latest) is replaced on every push to `main` that passes CI (unit tests and the emulator E2E). Grab `selkie-latest.apk` from it; no GitHub login needed.
- **Versioned builds:** prereleases tagged `v*` (see [Releases](https://github.com/keplersj/Selkie/releases)).

Both are signed with the public MVP test key, for testing only (see [docs/signing.md](docs/signing.md)).

## Building

Requirements: JDK 21, Android SDK (platform 36, build-tools 36.0.0). The Gradle wrapper is included.

Native binaries are **not committed**. The APK needs `libdroidc.so` (Android/bionic build), `libdroidc-guest.so` (static musl build), `libcgshim.so` (glibc LD_PRELOAD shim), `libproot.so`, `libproot-loader.so`
and `libselkie_compositor.so` for `arm64-v8a` and `x86_64`:

```
third_party/bin/<abi>/libdroidc.so
third_party/bin/<abi>/libdroidc-guest.so
third_party/bin/<abi>/libcgshim.so
third_party/bin/<abi>/libproot.so
third_party/bin/<abi>/libproot-loader.so
third_party/bin/<abi>/libselkie_compositor.so
```

or any other directory with that layout via `-Pselkie.nativeBinDir=<dir>`. Gradle copies them into
`app/src/main/jniLibs/` (git-ignored) and fails with a clear message if any is missing.
`-Pselkie.allowMissingNative=true` permits a compile-only build with no binaries (the app won't run).

- droidc is built twice per ABI (CI does this; see `.github/workflows/android.yml` and `droidc.ref`):
  - `libdroidc.so`: Android/bionic build (`cargo ndk -t arm64-v8a -t x86_64 build --release --bin droidc`, NDK r27c), run by Selkie. Android's app seccomp filter kills static musl binaries with SIGSYS (legacy syscalls such as `mkdir`, `rename`, `poll` on x86_64), so the app-side engine must be a bionic binary.
  - `libdroidc-guest.so`: static musl build (`cargo zigbuild --release --bin droidc --target {aarch64,x86_64}-unknown-linux-musl`), bound into the guest as `/usr/bin/droidc`; inside proot syscalls are handled by proot, so musl is fine there.
- `libcgshim.so`: droidc's `crates/cgshim` built for the guest's glibc (Rust, `cargo zigbuild --release --target {aarch64,x86_64}-unknown-linux-gnu.2.28 -p cgshim` in droidc). Bound into the guest as `/usr/lib/droidc/cgshim.so`; `droidc supervise --systemd auto|on` uses it to run the image's own `systemd --user`. Selkie passes `--systemd auto` for the graphical session only.
- compositor: `cd native/selkie-compositor && cargo ndk --platform 29 -t arm64-v8a -t x86_64 build --release --lib`
  (pure Rust, no C dependencies; `cargo test` runs its protocol tests on Linux). See its README.
- proot: `third_party/proot/build.sh <outdir> <abi>` (NDK; separate workflow `.github/workflows/proot.yml`, artifact `proot-android`).

```
./gradlew assembleRelease testReleaseUnitTest      # APK + JVM tests
./gradlew assembleDebug assembleDebugAndroidTest   # for the emulator tests
```

The libs are shipped with legacy packaging (`useLegacyPackaging`, `extractNativeLibs="true"`) so they are
extracted to `nativeLibraryDir`, the only place a modern targetSdk app may exec binaries from.

Signing uses the public MVP test key; see [docs/signing.md](docs/signing.md).

## Root mode (optional)

On a rooted device (Magisk, KernelSU, APatch) Home shows a **Root mode** switch: Linux then runs
natively through `droidc enter` (namespaces + chroot) instead of proot, with the image's real file
owners, so it is much faster and `sudo` works. Off by default; see [docs/architecture.md](docs/architecture.md).

## Tests

- JVM unit tests: NDJSON parser/status models, droidc argv, proot argv, guest file generation, phantom-process decision logic.
- `native/selkie-compositor`: `cargo test` drives the compositor with a real Wayland client.
- `app/src/androidTest`: `DesktopE2ETest` (installs Bluefin LTS, opens the GNOME desktop from the home screen,
  checks frames reach the screen and that Super toggles the overview; screenshots in the `gui-results`
  artifact of the `emulator-gui` CI job), `SmokeTest` (Compose launch) and `EngineE2ETest` (real engine: switch CentOS Stream 10, run commands under proot, upgrade, second switch, rollback, unpack-time metric). Run in CI on an x86_64 emulator (API 34). The emulator cannot cover the Tab S11's arm64 CPU or Mali GPU.

## First run on the device

Android 12+ kills proot's children unless Developer options → **Disable child process restrictions** is on.
Selkie explains this on first run (Samsung: Settings → About tablet → Software information → tap Build number 7 times).

## Dependencies

| Dependency | Why |
|---|---|
| AndroidX core-ktx, activity-compose, lifecycle-runtime-compose | Standard Compose activity + lifecycle-aware state |
| Compose BOM (ui, material3, material-icons-core) | The UI toolkit; Material 3 widgets |
| kotlinx-coroutines-android | Flows for droidc events, service scope |
| kotlinx-serialization-json | Parsing droidc NDJSON and status records |
| Termux `terminal-view` + `terminal-emulator` (JitPack, Apache-2.0) | Mature terminal emulator/view with PTY JNI; reimplementing would be pointless |
| JUnit, coroutines-test | JVM unit tests |
| androidx.test (ext-junit, runner, rules), compose ui-test | Instrumented / UI smoke tests |
