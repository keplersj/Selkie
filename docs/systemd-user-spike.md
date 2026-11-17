# Spike: `systemd --user` under proot (CentOS Stream 10 bootc rootfs)

Question: can a bootc image's per-user systemd instance (and so a GNOME session
that expects one) run under proot, without root?

**Result: no, not as an unprivileged user, and not for a reason proot can fix.**
`systemd --user` loads and parses its unit tree fine under proot (`--test` works),
but the real manager exits with status 1 at its first cgroup operation,
`mkdir /sys/fs/cgroup/unified/init.scope` -> `EACCES`. Only when proot is run as
real root (which the container allowed, which Android never will) does the
manager come up. Details and caveats below.

## Environment

- Host: this build container, Linux `6.18.44-fc-v553` x86_64 (Firecracker VM),
  Ubuntu 24.04, cgroup v1 hybrid (`/sys/fs/cgroup/{cpu,memory,...}` plus
  `/sys/fs/cgroup/unified` = cgroup2), `Seccomp: 0`, no Yama.
- **Not Android.** Android's kernel, seccomp filter on app processes (which can
  block syscalls proot relies on) and SELinux (`untrusted_app` domain: no cgroup
  writes, no hard links, no exec stack, ...) differ, so results here are an upper
  bound on what works on a phone.
- proot: `third_party/proot/build.sh <out> host`, the same patched source as the
  Android builds (termux/proot `4dba3afb` + DroidDeck patch set `6fe7b4ec`),
  built with gcc/glibc (lld for the loader link, `gcc-multilib` for the m32 loader).
- Rootfs: `quay.io/centos-bootc/centos-bootc:stream10`, linux/amd64, 66 layers
  pulled with a small Python script (registry API, gzip layers, whiteouts),
  ~1 GB extracted under `/home/user/work/spike/rootfs` (deleted afterwards).
  systemd `257-35.el10`, dbus-broker 36, polkit 125. **Not installed in the image:**
  `dbus-daemon` (it ships dbus-broker instead) and `gnome-session`.
- Unprivileged user: uid/gid 1000 (an existing account, via
  `setpriv --reuid=1000 --regid=1000 --clear-groups`).

Common command prefix (`PROOT_LOADER=.../proot-loader`):

    proot --link2symlink -i 1000:1000 -r rootfs -b /dev -b /proc -b /sys -w /tmp /bin/bash -lc '...'

(`-w /tmp` instead of `-w /root`: uid 1000 has no home directory in the image.)

## 1. Basics under proot, as uid 1000: works

    $ cat /etc/os-release | head -2
    NAME="CentOS Stream"
    VERSION="10 (Coughlan)"
    $ id
    uid=1000 gid=1000 groups=1000
    $ ls /        # bin boot dev etc home lib lib64 media mnt opt ostree proc root run sbin srv sys sysroot tmp usr var
    $ uname -a
    Linux vm 6.18.44-fc-v553 #1 SMP PREEMPT_DYNAMIC @0 x86_64 GNU/Linux

(`/usr/bin/id: cannot find name for user ID 1000`: the rootfs has no passwd entry for 1000.)

## 2. D-Bus

    $ dbus-daemon --session --fork --print-address
    timeout: failed to run command 'dbus-daemon': No such file or directory     (rc=127)

The bootc image has no `dbus-daemon`; only `dbus-broker` and `dbus-broker-launch`.
`dbus-broker-launch --scope user` run by hand exits 1 with
`No listener socket inherited`: it is built to be socket-activated by
`dbus.socket` from a running systemd user manager, so it is no standalone
`dbus-daemon --session` replacement. (`dbus-broker` itself was not tried standalone.)

## 3. `systemd --user`, unprivileged

### 3a. Out of the box: sd_booted() check

    $ XDG_RUNTIME_DIR=/tmp/xdg /usr/lib/systemd/systemd --user
    Trying to run as user instance, but the system has not been booted with systemd.   (rc=1)
    $ systemctl --user status
    Failed to connect to user scope bus via local transport: No such file or directory

`sd_booted()` is just `lstat("/run/systemd/system")` being a directory. Proot-level
workaround: `-b <hostdir-with-system/>:/run/systemd`. Not a PID 1 check
(`--user` needs no PID 1).

### 3b. With `/run/systemd/system` present: `--test` works

    $ systemd --user --test --log-level=debug      # exit 0
    systemd 257-35.el10-g20935d2 running in testuser mode for user 1000/1000.
    Found cgroup2 on /sys/fs/cgroup/unified, unified hierarchy for systemd controller
    bpf-firewall: Can't load kernel CGROUP SKB BPF program ...: Operation not permitted
    Found container virtualization proot.
    ...generators run (podman-user-generator, systemd-xdg-autostart-generator), unit
    file search path walked, unit cache built.

So proot is good enough for systemd to start executors, fork generators, parse
units, and it even identifies the container type as `proot`.

### 3c. The real manager: silent exit 1

    $ XDG_RUNTIME_DIR=/tmp/xdg systemd --user --log-level=debug --log-target=console
    (no output)   rc=1

No output because `--log-target=console` writes to `/dev/console`, which an
unprivileged user cannot open (`EACCES`); binding a file over `/dev/console`
did not yield log lines either. The cause was found by running the same manager
natively (no proot) under `strace -f` in a chroot with `/sys` bound:

    statfs("/sys/fs/cgroup/unified/", {f_type=CGROUP2_SUPER_MAGIC ...}) = 0
    mkdir("/sys/fs/cgroup/unified/init.scope", 0755) = -1 EACCES (Permission denied)
    exit_group(1)

(With `/sys/fs/cgroup` missing, it exits even earlier: `statfs("/sys/fs/cgroup/") = -1 ENOENT`, `exit_group(1)`.)
Inside proot the same mkdir fails identically:

    $ mkdir /sys/fs/cgroup/unified/init.scope
    mkdir: cannot create directory '/sys/fs/cgroup/unified/init.scope': Permission denied

The user manager insists on being in a cgroup hierarchy it can write to
(normally the `user@UID.service` cgroup delegated by system systemd/logind), then
creates `init.scope` there. A process outside a delegated cgroup has none.

### 3d. `-0` (fake root)

`-0` only fakes `getuid()` and friends (the manager prints `uid=0`); file-system
permissions are still those of the real uid 1000, so the result is the same:
`systemd --user` exits `rc=1` at the cgroup `mkdir`.

### 3e. `systemctl --user status`

With no manager running: `Failed to connect to user scope bus via local transport: No such file or directory` (or `Connection refused` if a stale `$XDG_RUNTIME_DIR/bus` exists).

## 4. Diagnostic: proot as real root (not available on Android)

Running proot as the container's real root with `-0`, `/run/systemd` bound to a
directory containing `system/`, the manager does come up:

    $ systemctl --user status
    ● vm
        State: running
        Units: 27 loaded (incl. loaded aliases)
        Jobs: 0 queued    Failed: 0 units
      systemd: 257-35.el10-g20935d2
      Tainted: cgroupsv1
    $ systemctl --user is-active dbus.socket default.target
    active
    active

but `dbus-broker.service` then fails (`ExecStart=/usr/bin/dbus-broker-launch
--scope user (code=exited, status=182)`) with no journal to say why (no
journald under proot; the user manager logs to the unreachable console), so
the D-Bus session bus was **not** obtained even here. Cause undetermined
(the launcher talks to the manager over `$XDG_RUNTIME_DIR/systemd/private` and
checks the session scope; not investigated further).
Side effect worth knowing: as root the manager created `init.scope` in the
container's cgroup2 root and pulled existing processes into it. That only
works because root may write to cgroupfs, which an Android app never can.

## Conclusion

| Check | Result |
|---|---|
| proot runs CentOS 10 bootc rootfs unprivileged (`os-release`, `ls`, `id`, bash) | works |
| `dbus-daemon --session` | not in image (dbus-broker only) |
| `systemd --user` needs `/run/systemd/system` | works with a bind/dir |
| `systemd --user --test` | works |
| `systemd --user` (real), unprivileged, with or without `-0` | **fails**: cannot create its cgroup (`EACCES`) |
| `systemd --user` as real root | runs, but dbus-broker fails to start (status 182) |

Blocker is the kernel permission model for cgroups (and on Android additionally
SELinux and the lack of a writable cgroup2 mount for apps), not a missing
syscall emulation in proot. Faking it inside proot would mean emulating
`statfs` magic and a writable cgroupfs subtree, which proot does not do and
no flag provides. Other things the manager would hit even with cgroups solved:
no logind/PAM (`user@.service`), `/run/user/UID` creation, sd_notify sockets
between manager and services (plain unix sockets, should work), PID 1 reaping
(`PR_SET_CHILD_SUBREAPER` succeeds unprivileged), namespaces/sandboxing options
in unit files (`PrivateTmp=`, `ProtectSystem=`: need `unshare`, not available
under proot), `BPF` features (already refused), and Android's seccomp policy.

## What a GNOME session would need instead (not tested)

Design for "no systemd --user":

- Do not depend on the user manager at all. Start a session bus ourselves:
  install/run a plain `dbus-daemon --session` (add the `dbus-daemon` package to
  the image, since bootc ships only dbus-broker; dbus-broker-launch cannot run
  without socket activation), export `DBUS_SESSION_BUS_ADDRESS`, `XDG_RUNTIME_DIR`
  (a private 0700 dir), `XDG_SESSION_TYPE=wayland`, `XDG_CURRENT_DESKTOP=GNOME`.
- Launch `gnome-shell`/`gnome-session` from a small supervisor script instead of
  systemd units. `gnome-session --builtin` (older gnome-session, up to about
  GNOME 48) runs the session without systemd by starting components directly;
  newer GNOME releases dropped that mode and require the systemd-managed
  session, so the GNOME version in the image matters. **Unverified:**
  `gnome-session` is not installed in this base image, and I did not test it.
- Provide logind substitutes: GNOME Shell and mutter want `org.freedesktop.login1`
  (session, seat, `TakeControl`/device access). Under proot there is no logind;
  options are a stub login1 service on the session/system bus, or running mutter
  nested/headless against a Wayland/X server provided by the Android app (this
  is probably the right shape for Selkie: a nested compositor or the app's
  own display surface, no DRM seat).
- Services otherwise started through user units (`xdg-desktop-portal`,
  `gnome-keyring`, `at-spi`, pipewire/wireplumber) need D-Bus activation via
  `.service` files (dbus-daemon supports this natively) or explicit starts.
- `systemd-run --user`/`systemctl --user` callers will fail; stub or avoid.

## Not verified

- Behaviour on Android (kernel, seccomp, SELinux, bionic) was not tested;
  only this x86_64 glibc host.
- Why `dbus-broker-launch` exited 182 under root; whether a standalone
  `dbus-daemon` plus `gnome-session --builtin` would start.
- The root-run `systemd --user` left an `init.scope` cgroup (busy) in the
  container's cgroup2 root; harmless here, but it was not removed.
