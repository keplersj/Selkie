# Architecture

```
MainActivity (Compose) --binds--> SessionService (foreground, specialUse)
                                    |-- DroidcClient  --ProcessBuilder--> libdroidc.so (bionic) --json (NDJSON)
                                    |-- terminal sessions: TerminalSession(libproot.so ... droidc supervise -- bash -l)
                                    |-- desktop: NativeCompositor (libselkie_compositor.so) + libproot.so ... droidc supervise --session
                                    '-- StateFlow<ServiceState>  (operation, status, sessions)
```

## Packages (`io.github.keplersj.selkie`)

- `engine/` `EngineEnv` (paths, uid, DNS), `DroidcClient` (command line, process, ring buffer, `Flow<DroidcEvent>`), `Models.kt` (sealed events, `NdjsonParser`, status/shell-spec models).
- `proot/` `ProotCommandBuilder` (pure argv/env, unit-tested) and `GuestFiles` (creates the host dirs/files that binds refer to: writable `/tmp`, `/run`, `/dev/shm`, generated `resolv.conf`, fake `/proc/{stat,version,loadavg,uptime}` only when unreadable).
- `platform/PhantomProcessCheck` the one class that decides whether "Disable child process restrictions" is on.
- `service/` `SessionService`, `ServiceState`/`Operation` state.
- `display/` `NativeCompositor` (JNI to the Rust compositor), `CompositorView` (SurfaceView + touch/mouse/key input),
  `KeyMap` (Android key codes and US-layout characters to evdev codes).
- `ui/` Compose screens: phantom explainer, setup, progress/error cards, home, terminal, desktop, session settings, about.

## droidc contract

Command line: `libdroidc.so --root <filesDir>/droidc [--container <name>] --json [--dns a,b] <cmd>`. stdout lines are JSON with an
`event` of `progress`, `log` or `result`; the last is always `result`. stderr goes to `cacheDir/droidc.log`
(its tail is shown on error cards). Cancelling kills the process; droidc is crash-safe and recovers on its next run.
DNS servers come from `ConnectivityManager` link properties because Android has no `/etc/resolv.conf` for a static binary.

## Containers

Selkie manages any number of droidc containers under the one root (`droidc --container`). They share
downloaded images; each has its own deployments, `/var`, `/home` and desktop session. Home shows one container
at a time as chips (`ServiceState.container`, remembered across launches); new operations, terminals and the
desktop act on it, and terminals of every container share the terminal screen's tabs.

| | `default` | named, e.g. `dev` |
|---|---|---|
| droidc flag | none (works with a droidc from before containers) | `--container dev` |
| droidc state | the top of `filesDir/droidc` | `filesDir/droidc/containers/dev` |
| guest tmp/run/shm/cgroup/generated | `filesDir/guest/...` | `filesDir/c/dev/...` |
| logs | `cacheDir/droidc.log`, `desktop.log` | `droidc-dev.log`, `desktop-dev.log` |

Named containers use the short `c/<name>` directory, and Selkie allows names of up to 20 characters (droidc allows
63), so the longest guest socket path (`run/user/<uid>/pipewire-0-manager`) stays inside the 108-byte Unix socket
limit. A container appears in `droidc container list` once something is deployed in it: a new container whose
first install fails disappears again, and Home goes back to `default`. There is one compositor, so one desktop at a
time; Home says which container it belongs to.

## Two droidc builds

Android applies a seccomp allowlist to app processes. A static musl binary uses legacy syscalls (`mkdir`, `chmod`,
`rename`, `unlink`, `poll`, `access`, `lstat`, ... on x86_64) that are not on it, so it dies with SIGSYS (exit status 159).
Selkie therefore runs a **bionic** build (`libdroidc.so`, built with the NDK) itself, while the **static musl** build is
shipped as `libdroidc-guest.so` and bound into the guest as `/usr/bin/droidc`, where proot intercepts syscalls.
The filesDir path is canonicalized before it is given to droidc and proot, because link2symlink entries contain absolute
host paths that must resolve the same way for both.

## proot session

`libproot.so --kill-on-exit --link2symlink -i <uid>:<gid> -r <rootfs> -w <cwd>` + binds
(`/dev /proc /sys`, `libdroidc-guest.so:/usr/bin/droidc`, `libcgshim.so:/usr/lib/droidc/cgshim.so`, writable tmp/run/shm, resolv.conf, fake proc files, then the
binds from `droidc shell-spec`). Environment is built from scratch (shell-spec env plus `PROOT_LOADER`,
`PROOT_TMP_DIR`), so Android's own variables never leak into the guest. Root-only commands use `-0`.
`DROIDC_ROOT=/run/droidc/root` is bound inside the guest so `droidc status` works from the terminal.
The shim is bound in so `droidc supervise --systemd auto|on` can run the image's real `systemd --user` on a fake
cgroup tree, but sessions do not pass it yet: on the CI emulator it produced no session bus.
Debuggable builds also set `DROIDC_LOGCAT=auto`, so the guest's journal and unit output show up in `adb logcat`
(`droidc log` writes a line from a script); release builds leave it off because logs can hold paths and user names.

## Graphical session

```
SurfaceView <- libselkie_compositor.so (in the app process) <- <guest dir>/run/user/<uid>/wayland-0
                                                                  = guest $XDG_RUNTIME_DIR/wayland-0
   proot: droidc supervise --session --width W --height H
            renderer=wayland:    the image's compositor runs nested as a Wayland client
            renderer=screencast: headless compositor (gnome-shell) + droidc bridge as the client
```

`DesktopScreen` measures its view and asks `SessionService.startDesktop(w, h)`: the compositor starts
listening first (`GuestFiles` creates the 0700 runtime dir), then proot runs `droidc supervise --session`
with `WAYLAND_DISPLAY=wayland-0` and `LIBGL_ALWAYS_SOFTWARE=1` (no GPU path yet: Mesa llvmpipe). Output goes to
`cacheDir/desktop.log` (`desktop-<name>.log` for a named container), whose tail is shown if the session exits with an error. The compositor shows the most
recently mapped toplevel full screen: it copies each committed `wl_shm` buffer into the window
(RGBX at the client's size, scaled by SurfaceFlinger) and completes frame callbacks after that, so the
session pauses while no Surface exists (activity in the background). Input arrives in view pixels and is
scaled onto the client. The session belongs to the foreground service, like terminals, and keeps running
when the screen is left; Stop ends it.

Which session runs is the image's business (`containers.droidc.session.renderer|exec|services` labels,
see droidc's design doc). For images without them the session settings screen writes droidc's override
(`droidc session set`); there is no table of desktops in Selkie or droidc.

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
