#!/usr/bin/env bash
# Runs on the CI emulator: DesktopE2ETest (Bluefin LTS: GNOME through droidc bridge, and a GTK app
# nested directly in Selkie's compositor), then pulls its screenshots and logs.
# usage: e2e-gui.sh <apk-dir> <out-dir>
set -u
APKS=${1:?apk dir}; OUT=${2:?out dir}
PKG=io.github.keplersj.selkie
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
mkdir -p "$OUT"
rc=0

adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647 || true
# A tablet-sized landscape screen (the default AVD is 320x640): GNOME's virtual monitor takes the
# view's size.
adb shell settings put system accelerometer_rotation 0
adb shell wm size 1280x800
adb shell wm density 160
adb shell wm size; adb shell wm density

adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true
adb shell df -h /data | tee "$OUT/df-before.txt"

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT=$!

echo "== DesktopE2ETest (Bluefin LTS download + unpack, then GNOME on llvmpipe: 20-60 min)"
timeout 7800 adb shell am instrument -w -e class $PKG.DesktopE2ETest $RUNNER | tee "$OUT/gui.txt"
grep -q '^OK (' "$OUT/gui.txt" || { echo "desktop test failed"; rc=1; }

adb shell screencap -p /sdcard/final.png && adb pull /sdcard/final.png "$OUT/screen-final.png" || true
adb pull "/sdcard/Android/data/$PKG/files/." "$OUT/screenshots" || true
adb shell run-as $PKG cat cache/desktop.log > "$OUT/desktop.log" 2>&1 || true
adb shell run-as $PKG cat cache/droidc.log > "$OUT/droidc.log" 2>&1 || true
adb shell df -h /data | tee "$OUT/df-after.txt"
sleep 2; kill $LOGCAT 2>/dev/null || true
grep -h SELKIE_METRIC "$OUT/logcat.txt" | tee "$OUT/metrics.txt" || true
if [ $rc -ne 0 ]; then
  echo "::group::desktop.log (tail)"; tail -120 "$OUT/desktop.log"; echo "::endgroup::"
  for f in "$OUT"/screenshots/*-session-failed.log; do
    [ -f "$f" ] || continue
    echo "::group::$(basename "$f") (pipewire, mutter, shell, bridge, droidc lines)"
    grep -i -E "PWDIAG|pipewire|pw-|wireplumber|mutter|gnome-shell|screencast|bridge|droidc|error|fail" "$f" | grep -v -E "Vulkan|Tracker|GVFS|landlock" | head -250
    echo "::endgroup::"
  done
  echo "::group::SelkieE2E log"; grep -E "SelkieE2E|SelkieDesktop|AndroidRuntime" "$OUT/logcat.txt" | tail -200; echo "::endgroup::"
fi
exit $rc
