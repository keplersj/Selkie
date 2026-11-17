MVP prerelease of Selkie: run an unmodified bootc image (Bluefin LTS by default) on Android under proot, without root.

**Untested on real hardware.** Built and tested in CI on Linux runners and an x86_64 Android 14 emulator. See [docs/mvp-status.md](https://github.com/keplersj/Selkie/blob/main/docs/mvp-status.md) for what works, install steps for the Galaxy Tab S11 (including the Developer options toggle "Disable child process restrictions"), measurements and open questions.

Assets:
- `selkie-0.1.0-mvp.1.apk`: arm64-v8a + x86_64, signed with the public MVP test key (`keystore/selkie-mvp.jks`; not secure, for testing only).
- `proot-src-*.tar.gz`: corresponding source for the bundled proot (GPL-2): termux/proot with DroidDeck's patches, talloc, and the build script.
- `DROIDC_COMMIT.txt`: the droidc commit built into this APK.
- `SHA256SUMS`.

Selkie and droidc are MIT. The terminal uses Termux's terminal-view/terminal-emulator (Apache-2.0).
