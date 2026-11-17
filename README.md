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

See [docs/architecture.md](docs/architecture.md).

## Building

Requirements: JDK 21, Android SDK (platform 36, build-tools 36.0.0). The Gradle wrapper is included.

Native binaries are **not committed**. The APK needs `libdroidc.so`, `libproot.so`, `libproot-loader.so`
for `arm64-v8a` and `x86_64`:

```
third_party/bin/<abi>/libdroidc.so
third_party/bin/<abi>/libproot.so
third_party/bin/<abi>/libproot-loader.so
```

or any other directory with that layout via `-Pselkie.nativeBinDir=<dir>`. Gradle copies them into
`app/src/main/jniLibs/` (git-ignored) and fails with a clear message if any is missing.
`-Pselkie.allowMissingNative=true` permits a compile-only build with no binaries (the app won't run).

- droidc: static musl build (`cargo zigbuild --release --bin droidc --target {aarch64,x86_64}-unknown-linux-musl`), copy as `libdroidc.so`. CI does this; see `.github/workflows/android.yml` and `droidc.ref`.
- proot: `third_party/proot/build.sh <outdir> <abi>` (NDK; separate workflow `.github/workflows/proot.yml`, artifact `proot-android`).

```
./gradlew assembleRelease testReleaseUnitTest      # APK + JVM tests
./gradlew assembleDebug assembleDebugAndroidTest   # for the emulator tests
```

The libs are shipped with legacy packaging (`useLegacyPackaging`, `extractNativeLibs="true"`) so they are
extracted to `nativeLibraryDir`, the only place a modern targetSdk app may exec binaries from.

Signing uses the public MVP test key; see [docs/signing.md](docs/signing.md).

## Tests

- JVM unit tests: NDJSON parser/status models, droidc argv, proot argv, guest file generation, phantom-process decision logic.
- `app/src/androidTest`: `SmokeTest` (Compose launch) and `EngineE2ETest` (real engine: switch CentOS Stream 10, run commands under proot, upgrade, second switch, rollback, unpack-time metric). Run in CI on an x86_64 emulator (API 34). The emulator cannot cover the Tab S11's arm64 CPU or Mali GPU.

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
