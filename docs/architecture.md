# Architecture

```
MainActivity (Compose) --binds--> SessionService (foreground, specialUse)
                                    |-- DroidcClient  --ProcessBuilder--> libdroidc.so (bionic) --json (NDJSON)
                                    |-- terminal sessions: TerminalSession(libproot.so ... droidc supervise -- bash -l)
                                    '-- StateFlow<ServiceState>  (operation, status, sessions)
```

## Packages (`io.github.keplersj.selkie`)

- `engine/` `EngineEnv` (paths, uid, DNS), `DroidcClient` (command line, process, ring buffer, `Flow<DroidcEvent>`), `Models.kt` (sealed events, `NdjsonParser`, status/shell-spec models).
- `proot/` `ProotCommandBuilder` (pure argv/env, unit-tested) and `GuestFiles` (creates the host dirs/files that binds refer to: writable `/tmp`, `/run`, `/dev/shm`, generated `resolv.conf`, fake `/proc/{stat,version,loadavg,uptime}` only when unreadable).
- `platform/PhantomProcessCheck` the one class that decides whether "Disable child process restrictions" is on.
- `service/` `SessionService`, `ServiceState`/`Operation` state.
- `ui/` Compose screens: phantom explainer, setup, progress/error cards, home, terminal, about.

## droidc contract

Command line: `libdroidc.so --root <filesDir>/droidc --json [--dns a,b] <cmd>`. stdout lines are JSON with an
`event` of `progress`, `log` or `result`; the last is always `result`. stderr goes to `cacheDir/droidc.log`
(its tail is shown on error cards). Cancelling kills the process; droidc is crash-safe and recovers on its next run.
DNS servers come from `ConnectivityManager` link properties because Android has no `/etc/resolv.conf` for a static binary.

## Two droidc builds

Android applies a seccomp allowlist to app processes. A static musl binary uses legacy syscalls (`mkdir`, `chmod`,
`rename`, `unlink`, `poll`, `access`, `lstat`, ... on x86_64) that are not on it, so it dies with SIGSYS (exit status 159).
Selkie therefore runs a **bionic** build (`libdroidc.so`, built with the NDK) itself, while the **static musl** build is
shipped as `libdroidc-guest.so` and bound into the guest as `/usr/bin/droidc`, where proot intercepts syscalls.
The filesDir path is canonicalized before it is given to droidc and proot, because link2symlink entries contain absolute
host paths that must resolve the same way for both.

## proot session

`libproot.so --kill-on-exit --link2symlink -i <uid>:<gid> -r <rootfs> -w <cwd>` + binds
(`/dev /proc /sys`, `libdroidc-guest.so:/usr/bin/droidc`, writable tmp/run/shm, resolv.conf, fake proc files, then the
binds from `droidc shell-spec`). Environment is built from scratch (shell-spec env plus `PROOT_LOADER`,
`PROOT_TMP_DIR`), so Android's own variables never leak into the guest. Root-only commands use `-0`.
`DROIDC_ROOT=/run/droidc/root` is bound inside the guest so `droidc status` works from the terminal.

## Root mode (optional)

Shown only where an `su` binary exists (Magisk, KernelSU, APatch; `RootShell.SU_CANDIDATES`). Off by
default; turning it on asks su once (`su -c 'id -u'`, the root manager prompts) and stores `root_mode`
in the `selkie` preferences. Then:

- `switch`/`upgrade`/`rollback` run as root through `su -c` (`DroidcClient.events(asRoot = true)`):
  switch with `--links auto --apply-ownership` (real hard links; the image's owners and setuid). After
  any of them Selkie applies `fix-ownership` if the deployment now current is not image-owned (a
  rollback to one made before root mode). `status` and `shell-spec` stay unprivileged: droidc hands
  records, the lock and blobs back to the app uid with the app's SELinux label.
- Terminals run `su -c '... libdroidc.so enter --bind <guest droidc>:/usr/bin/droidc --bind
  <resolv.conf>:/etc/resolv.conf ... -- /usr/bin/droidc supervise -- /bin/bash -l'` instead of proot:
  namespaces + chroot, private /proc /sys /dev /run /tmp, the guest runs as root
  (`EnterCommandBuilder`).
- Every su script is built by `RootShell`: each word single-quoted (image refs are user input),
  `exec /system/bin/env -i` with an explicit environment. With Magisk the root process is magiskd's
  child, not ours, so killing `su` does not stop it: the script reports its pid first (operations: a
  `selkie-pid` line on stdout; terminals: a pid file in `cacheDir/root-sessions`) and Cancel, closing a
  terminal and the notification's Stop kill that pid through su. `enter` then tears its namespace down.
- Turning root mode off runs `fix-ownership --undo` (everything back to the app uid with safe modes)
  when a deployment is image-owned, then clears the setting. Both directions need closed terminals.
- The phantom-process screen offers "Turn it off with root" (`settings put global ...` through su).

CI cannot run Selkie's root mode (the emulator has no Magisk). `e2e.sh` instead drives
`libdroidc.so` as root with `adb root` on the deployment EngineE2ETest left: `fix-ownership`, `enter`
(logs the kernel's namespaces), then checks as the app (`run-as`) that `status` works, `gc` refuses
while image-owned and works after `--undo`.

## Service lifecycle

Operations (switch/upgrade/rollback) and terminal sessions live in `SessionService` so they outlive the activity.
The service enters the foreground (type `specialUse`) while an operation runs or a session is open; the
notification shows progress or "N sessions running" with a Stop action. The activity re-binds and re-attaches.

## Phantom-process check

The Developer-options toggle writes the system property `persist.sys.fflag.override.settings_enable_monitor_phantom_procs`;
the check follows AOSP `FeatureFlagUtils`: Settings.Global key first, then that property, then default "true".
`"false"` is OK. Unreadable shows "couldn't verify". Which source answered is logged under tag `SelkiePhantom`.

## Testing limits

The CI emulator is x86_64 API 34. It exercises the Kotlin code, droidc and proot end to end but not the
arm64 CPU, the Mali GPU, Samsung's One UI, or Android 16 specifics on a real Tab S11.
