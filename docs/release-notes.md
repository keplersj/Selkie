MVP prerelease of Selkie: run an unmodified bootc image (Bluefin LTS by default) on Android under proot, without root.

**Run on two physical devices** (arm64, Android 16): a Samsung Galaxy Tab S11 and a Motorola Razr Ultra 2025. On both, Selkie downloads and verifies Bluefin LTS in the app (3.8 GB, about 4 minutes), opens a terminal as an ordinary user, starts a D-Bus session bus and has working networking. Also built and tested in CI on Linux runners (x86_64 and arm64) and an x86_64 Android 14 emulator. No graphical session yet. See [docs/mvp-status.md](https://github.com/droidc-dev/Selkie/blob/main/docs/mvp-status.md) for what works, what is untested, install steps (including the Developer options toggle "Disable child process restrictions", which you must turn on yourself) and open questions.

Assets:
- `selkie-0.1.0-mvp.1.apk`: arm64-v8a + x86_64, signed with the public MVP test key (`keystore/selkie-mvp.jks`; not secure, for testing only).
- `selkie-watch-*.apk`: the Wear OS remote, signed with the same key. Install it on a watch paired with the phone; it needs Google Play services on both. It adds a Selkie tile to the watch, and each container has an Open on phone button.
- `proot-src-*.tar.gz`: corresponding source for the bundled proot (GPL-2): termux/proot with DroidDeck's patches, talloc, and the build script.
- `DROIDC_COMMIT.txt`: the droidc commit built into this APK.
- `SHA256SUMS`.

Selkie and droidc are MIT. The terminal uses Termux's terminal-view/terminal-emulator (Apache-2.0).
