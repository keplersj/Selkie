#!/usr/bin/env bash
# Runs on the CI emulator: installs the debug + androidTest APKs and runs the instrumented tests.
# usage: e2e.sh <apk-dir> <out-dir>
set -u
APKS=${1:?apk dir}; OUT=${2:?out dir}
PKG=io.github.keplersj.selkie
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUT"
rc=0

# Printed into the job log on failure (artifacts may be unreachable from some sandboxes).
diag() {
  echo "::group::logcat (seccomp|SIGSYS|audit|selkie|droidc|proot)"
  adb logcat -d | grep -iE "seccomp|SIGSYS|audit|droidc|proot" | grep -v gmscore | tail -200
  echo "::group::SelkieE2E log"; adb logcat -d -s SelkieE2E:V | tail -400
  echo "::endgroup::"
  echo "::group::droidc stderr log (app cacheDir/droidc.log)"
  adb shell run-as $PKG cat cache/droidc.log 2>&1 | tail -300 | tee "$OUT/droidc.log"
  echo "::endgroup::"
  echo "::group::container services (Selkie's user-services keeper and spawn-server)"
  for f in cache/e2e-user-services.log cache/e2e-spawner.log cache/user-services.log cache/spawner.log; do
    echo "== $f"; adb shell run-as $PKG cat "$f" 2>&1 | tail -150
  done
  echo "== guest droidc/user-services.log"
  adb shell run-as $PKG sh -c 'cat files/guest/run/user/*/droidc/user-services.log' 2>&1 | tail -150
  echo "::endgroup::"
  echo "::group::app storage"
  adb shell run-as $PKG ls -la files files/droidc cache 2>&1 | head -60
  adb shell run-as $PKG ls -la files/droidc/staging 2>&1 | head -20
  adb shell df -h /data
  echo "::endgroup::"
  echo "::group::native libs"
  adb shell pm path $PKG
  adb shell "ls -la \$(dirname \$(pm path $PKG | head -1 | cut -d: -f2))/lib/*/ 2>&1" | head -30
  echo "::endgroup::"
}

# Android kills proot's children ("phantom processes") unless monitoring is off.
adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647 || true
adb shell settings get global settings_enable_monitor_phantom_procs

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell df -h /data | tee "$OUT/df-before.txt"

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT=$!

echo "== SmokeTest"
timeout 300 adb shell am instrument -w -e class $PKG.SmokeTest $RUNNER | tee "$OUT/smoke.txt"
grep -q '^OK (' "$OUT/smoke.txt" || { echo "smoke test failed"; rc=1; diag; }

echo "== EngineE2ETest (pull + unpack in an emulator can take 10-20 min)"
timeout 3000 adb shell am instrument -w -e class $PKG.EngineE2ETest $RUNNER | tee "$OUT/e2e.txt"
grep -q '^OK (' "$OUT/e2e.txt" || { echo "e2e test failed"; rc=1; diag; }

# droidc root mode on this emulator's kernel, on the deployment EngineE2ETest left behind. Also
# proves the app still owns (uid and SELinux label) everything root touched: status and gc run as
# the app via run-as afterwards.
root_mode() {
  adb root >/dev/null 2>&1 && adb wait-for-device || { echo "adb root unavailable: skipped"; return 0; }
  local lib droot app
  lib=$(adb shell "dirname \$(pm path $PKG | head -1 | cut -d: -f2)" | tr -d '\r')/lib/x86_64
  droot=/data/data/$PKG/files/droidc
  app() { adb shell run-as $PKG "$lib/libdroidc.so" --root "$droot" --json "$@" | tail -n1 | tr -d '\r'; }
  adb push .github/scripts/root-mode-device.sh /data/local/tmp/ >/dev/null
  adb shell sh /data/local/tmp/root-mode-device.sh "$lib" "$droot" apply | tee "$OUT/root-mode.txt"
  grep -q "ROOTCHECK FAIL" "$OUT/root-mode.txt" && return 1
  grep -q "^passwd 0 4755" "$OUT/root-mode.txt" || { echo "passwd not 0 4755 under enter"; return 1; }
  adb shell sh /data/local/tmp/root-mode-device.sh "$lib" "$droot" kill | tee -a "$OUT/root-mode.txt"
  grep -q "ROOTCHECK FAIL" "$OUT/root-mode.txt" && return 1
  local s; s=$(app status); echo "app status after root: $s"
  echo "$s" | grep -q '"ok":true' || { echo "app cannot read status after root commands"; return 1; }
  s=$(app gc); echo "app gc while image-owned: $s"
  echo "$s" | grep -q '"kind":"invalid"' || { echo "app gc should refuse while image-owned"; return 1; }
  adb shell sh /data/local/tmp/root-mode-device.sh "$lib" "$droot" undo | tee -a "$OUT/root-mode.txt"
  grep -q "ROOTCHECK FAIL" "$OUT/root-mode.txt" && return 1
  s=$(app gc); echo "app gc after undo: $s"
  echo "$s" | grep -q '"ok":true' || { echo "app cannot gc after undo (owner or SELinux label)"; return 1; }
  adb unroot >/dev/null 2>&1; adb wait-for-device
}
if grep -q '^OK (' "$OUT/e2e.txt"; then
  echo "== droidc root mode (adb root)"
  root_mode || { echo "root mode checks failed"; rc=1; diag; }
fi

adb shell df -h /data | tee "$OUT/df-after.txt"
adb pull /sdcard/Android/data/$PKG/files/selkie_metric.txt "$OUT/" || true
sleep 2; kill $LOGCAT 2>/dev/null || true
grep -h SELKIE_METRIC "$OUT/logcat.txt" | tee "$OUT/metrics.txt" || true
cat "$OUT/selkie_metric.txt" 2>/dev/null || true
exit $rc
