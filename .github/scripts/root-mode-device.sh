#!/system/bin/sh
# Runs as root on the CI emulator (adb root) against the deployment EngineE2ETest left behind:
# droidc's root mode on a real Android kernel. Selkie's own root mode needs Magisk/KernelSU, which
# the emulator lacks, so this drives libdroidc.so directly the way Selkie does through su.
# usage: root-mode-device.sh <native lib dir> <droidc root> <step>
D="$1/libdroidc.so"; R="$2"
fail() { echo "ROOTCHECK FAIL: $*"; exit 1; }
labels() { echo "labels:"; ls -Zd "$R" "$R/lock" "$R/policy.json" "$R/blobs" "$R"/deployments/* 2>&1; }
case "$3" in
  apply)
    labels
    F=$($D --root "$R" --json fix-ownership | tail -n1); echo "fix-ownership: $F"
    case "$F" in *'"ok":true'*) ;; *) fail fix-ownership ;; esac
    case "$F" in *'"via_l2s":0,'*) fail "no link2symlink chains followed" ;; esac
    $D --root "$R" enter --hostname selkie-ci -- /bin/sh -c '
      echo "namespaces: $(ls /proc/self/ns | tr "\n" " ")"
      echo "pid=$$ uid=$(id -u) host=$(hostname) cgroup=$(cat /proc/self/cgroup)"
      stat -L -c "passwd %u %a" /usr/bin/passwd
      echo "dev: $(ls /dev | tr "\n" " ")"' || fail enter
    labels ;;
  kill)
    # The emulator kernel has no pid namespace: enter's init must still kill the guest when enter
    # itself is killed (what Selkie does to stop a root-mode terminal).
    guests() { pgrep -f '^sleep 424[67]$' | wc -l; }
    $D --root "$R" enter -- /bin/sh -c '(setsid sleep 4246 &); sleep 4247' >/dev/null 2>&1 &
    P=$!; n=0
    for i in $(seq 100); do n=$(guests); [ "$n" -ge 2 ] && break; sleep 0.1; done
    [ "$n" -ge 2 ] || fail "guest did not start"
    kill -TERM $P
    for i in $(seq 100); do n=$(guests); [ "$n" -eq 0 ] && break; sleep 0.1; done
    [ "$n" -eq 0 ] || { pkill -9 -f '^sleep 424[67]$'; fail "killing enter left $n guest processes"; }
    echo "killing enter left no guest processes" ;;
  undo)
    U=$($D --root "$R" --json fix-ownership --undo | tail -n1); echo "undo: $U"
    case "$U" in *'"ok":true'*) ;; *) fail undo ;; esac
    labels ;;
  *) fail "unknown step $3" ;;
esac
