# Architecture

```
MainActivity (Compose) --binds--> SessionService (foreground, specialUse)
                                    |-- DroidcClient  --ProcessBuilder--> libdroidc.so --json (NDJSON)
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

## proot session

`libproot.so --kill-on-exit --link2symlink -i <uid>:<gid> -r <rootfs> -w <cwd>` + binds
(`/dev /proc /sys`, `libdroidc.so:/usr/bin/droidc`, writable tmp/run/shm, resolv.conf, fake proc files, then the
binds from `droidc shell-spec`). Environment is built from scratch (shell-spec env plus `PROOT_LOADER`,
`PROOT_TMP_DIR`), so Android's own variables never leak into the guest. Root-only commands use `-0`.
`DROIDC_ROOT=/run/droidc/root` is bound inside the guest so `droidc status` works from the terminal.

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
