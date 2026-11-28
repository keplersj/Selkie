# Architecture

```
MainActivity (Compose) --binds--> SessionService (foreground, specialUse)
                                    |-- DroidcClient  --ProcessBuilder--> libdroidc.so (bionic) --json (NDJSON)
                                    |-- terminal sessions: TerminalSession(libproot.so ... droidc supervise -- bash -l)
                                    |-- desktop: NativeCompositor (libselkie_compositor.so) + libproot.so ... droidc supervise --session
                                    |-- per container: libproot.so ... droidc user-services --stay, libdroidc.so spawn-server
                                    '-- StateFlow<ServiceState>  (operation, status, sessions)
```

## Packages (`io.github.keplersj.selkie`)

- `engine/` `EngineEnv` (paths, uid, DNS), `DroidcClient` (command line, process, ring buffer, `Flow<DroidcEvent>`), `Models.kt` (sealed events, `NdjsonParser`, status/shell-spec models).
- `proot/` `ProotCommandBuilder` (pure argv/env, unit-tested) and `GuestFiles` (creates the host dirs/files that binds refer to: writable `/tmp`, `/run`, `/dev/shm` (`/tmp` and `/dev/shm` 1777), generated `resolv.conf`, `/etc/passwd`, `/etc/group`, `/etc/hostname` (the container's name, `selkie` for the default one) and `/etc/localtime` (the image's zoneinfo file for the device's time zone), fake `/proc/{stat,version,loadavg,uptime}` and the `/proc/sys` values tools read (overflowuid, cap_last_cap, inotify and file limits, hostname, osrelease) only when unreadable; `/proc/version` uses the real `uname` release, and `SessionService` advances `/proc/uptime` every second while a container runs). Every guest process also gets `TZ` and `LANG` from the device unless the image sets them; `LANG` falls back to `C.UTF-8` when the image lacks the device's locale.
- `usb/` `UsbBridge` (USB devices for the guest), `UsbTree` and `UsbProtocol` (pure, unit-tested).
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
(`/dev /proc /sys`, `libdroidc-guest.so:/usr/bin/droidc`, `libcgshim.so:/usr/lib/droidc/cgshim.so`, `libifshim.so:/usr/lib/droidc/ifshim.so`, `libusbshim.so:/usr/lib/droidc/usbshim.so`, writable tmp/run/shm, resolv.conf, the USB device trees, fake proc files, then the
binds from `droidc shell-spec`). Environment is built from scratch (shell-spec env plus `PROOT_LOADER`,
`PROOT_TMP_DIR`), so Android's own variables never leak into the guest. Root-only commands use `-0`.
`DROIDC_ROOT=/run/droidc/root` is bound inside the guest so `droidc status` works from the terminal.
The shim is bound in so `droidc supervise --systemd auto|on` can run the image's real `systemd --user` on a fake
cgroup tree.

### Per-container services

A container's terminals and desktop share one session bus (or the image's `systemd --user`), so a bus a terminal
started no longer dies with it, nor the desktop's with the desktop. `droidc supervise` would start that keeper
inside its own proot, which `--kill-on-exit` ends with the session, so `SessionService` starts it itself, in a
proot of its own (`ProotCommandBuilder.userServices`: `droidc user-services --systemd auto --stay`, output in
`user-services[-<container>].log`) before a container's first session, and gives every session
`DROIDC_USER_SERVICES_WAIT=10`. The first keeper's `--systemd` decides for the container, so terminals get the
user manager too where the image has one.

The guest's `sudo`, `su` and `pkexec` are the guest droidc (`shell-spec`'s `shims`, bound like
`/usr/bin/droidc`). They ask `droidc spawn-server` on the host, over the abstract socket in `DROIDC_SPAWNER`
(`selkie-<uid>-spawner-<container>`), to run the command in a second proot of the same container with `-0`.
The server takes requests from the app's own uid only. This is proot's fake root, as in the root-only commands
above, not real privilege.

Both run while the container has a terminal or the desktop and stop with the last one. Root-mode terminals
(`droidc enter` through `su`) use neither: they are real root, and chroot has no shims.
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

## adb pairing (`droidc adb`, Android 11+)

`droidc adb pair`, run in a container, pairs that container's own `adb` with this device's Wireless
debugging the way Shizuku pairs. Its request reaches Selkie through host-link (`adb-pair`, see `AdbRequests`):
`AdbPairingService` (foreground, `specialUse`) opens Developer options at Wireless debugging
(`:settings:fragment_args_key=toggle_adb_wireless`), finds `_adb-tls-pairing._tcp` by mDNS once the user taps
"Pair device with pairing code", and takes the code in a notification reply, so Settings stays on screen. A
service counts only if it resolves to one of this device's addresses and its port is taken on loopback. The
port and code go back to the guest, whose adb pairs, so the key stays in the guest's `~/.android`.

Every other `droidc adb ARGS` asks Selkie for Wireless debugging's current port (`adb-port`, by mDNS on
`_adb-tls-connect._tcp`), runs `adb connect`, then `adb ARGS`. Guest sessions get
`ADB_SERVER_SOCKET=localfilesystem:/run/droidc-adb.sock`, so the server never listens on TCP 5037, which every
app shares. host-link runs with each container's services, for terminals as well as the desktop, so
`droidc adb` works from a terminal alone. Selkie doesn't turn Wireless debugging on by itself.

## USB devices (fastboot, adb)

`usb/UsbBridge` hands the guest the USB devices the person allowed (droidc #100, its `docs/design/usb.md`), so
`fastboot` and `adb` in a container can flash or debug a device plugged into this one. An app can't open
`/dev/bus/usb` nodes, so:

- for each device Android granted, `UsbTree` writes an empty `/dev/bus/usb/BBB/DDD` and a
  `/sys/bus/usb/devices/B-D` entry (`busnum`, `devnum`, `idVendor`, `idProduct`, strings, `descriptors`,
  `bConfigurationValue`, interface names) into `filesDir/usb/{dev,sys}`, bound at those guest paths (not in root
  mode, where the real nodes are used). Entries go when the device leaves the bus;
- a newly plugged-in device without permission gets a notification whose tap opens Android's prompt. The manifest's
  device filter (adb and fastboot interfaces) also has Android offer Selkie on plug-in, with "Always open", so a
  target rebooting into its bootloader (a new device number, and a new prompt) doesn't stop a flash each time;
- droidc's usbshim, preloaded by `supervise`, turns an open of a placeholder into a `usb.open` request on the
  abstract socket `selkie-<uid>-usb` (`DROIDC_USB_SOCKET`). UsbBridge checks the peer's uid, calls `openDevice()`
  again and sends that fd with `SCM_RIGHTS`, so every guest open gets its own usbfs file.

## Testing limits

The CI emulator is x86_64 API 34. It exercises the Kotlin code, droidc and proot end to end but not the
arm64 CPU, the Mali GPU, Samsung's One UI, or Android 16 specifics on a real Tab S11.
