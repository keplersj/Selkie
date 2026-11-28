#!/usr/bin/env bash
# Runs on an Android Automotive emulator: installs Selkie, runs SmokeTest, then opens the app parked,
# drives (simulated speed and gear through the car service) and parks again. Selkie is parked-only:
# driving must swap every screen for "Selkie is hidden while you drive", and parking must bring the
# app back. Screenshots of each state go to <out-dir>.
# usage: e2e-car.sh <apk-dir> <out-dir>
set -u
APKS=${1:?apk dir}; OUT=${2:?out dir}
PKG=io.github.keplersj.selkie
RUNNER=$PKG.test/androidx.test.runner.AndroidJUnitRunner
DRIVING='^Selkie is hidden while you drive$'
# The parked app: one of its entry screens (as SmokeTest), or its home.
PARKED='^(New container|One-time Android setup|Selkie|Parked)$'
# VHAL properties: PERF_VEHICLE_SPEED (m/s) and GEAR_SELECTION (4 park, 8 drive), global area 0.
SPEED=0x11600207
GEAR=0x11400400
mkdir -p "$OUT"
rc=0

screen_text() {
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  adb shell cat /data/local/tmp/ui.xml 2>/dev/null | tr '>' '\n' | sed -n 's/.* text="\([^"]*\)".*/\1/p' | grep -v '^$'
}

# Waits up to $2 seconds until the screen has a line matching $1 (or, with $4 = absent, has none).
wait_for() {
  local re=$1 secs=$2 name=$3 want=${4:-present} i
  for ((i = 0; i < secs; i += 3)); do
    screen_text > "$OUT/$name.txt"
    if [ "$want" = present ]; then grep -qE "$re" "$OUT/$name.txt" && return 0
    else grep -qE "$re" "$OUT/$name.txt" || return 0; fi
    sleep 3
  done
  return 1
}

shot() { adb shell screencap -p "/sdcard/$1.png" && adb pull "/sdcard/$1.png" "$OUT/$1.png" >/dev/null || true; }

# Moves the car: $1 is the speed in m/s, $2 the gear. Newer car services also take emulate-driving-state.
drive() {
  adb shell cmd car_service inject-vhal-event $GEAR 0 "$2"
  adb shell cmd car_service inject-vhal-event $SPEED 0 "$1"
}

adb shell pm has-feature android.hardware.type.automotive | tee "$OUT/feature.txt"
grep -q true "$OUT/feature.txt" || { echo "not an Automotive image"; exit 1; }

adb shell settings put global settings_enable_monitor_phantom_procs false
adb shell device_config put activity_manager max_phantom_processes 2147483647 || true
adb install -r -t "$APKS/app-debug.apk"
adb install -r -t "$APKS/app-debug-androidTest.apk"
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS || true

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT=$!

echo "== SmokeTest (parked)"
drive 0 4
timeout 300 adb shell am instrument -w -e class $PKG.SmokeTest $RUNNER | tee "$OUT/smoke.txt"
grep -q '^OK (' "$OUT/smoke.txt" || { echo "smoke test failed"; rc=1; }

echo "== Parked: the app's own screens"
adb shell am start -W -n "$PKG/.MainActivity" | tee "$OUT/start.txt"
if wait_for "$PARKED" 60 parked; then echo "parked: OK"
else echo "parked: FAIL, screen showed:"; cat "$OUT/parked.txt"; rc=1; fi
shot car-parked

echo "== Driving: one screen stands in for all of Selkie"
drive 15 8
adb shell dumpsys car_service --services CarUxRestrictionsManagerService 2>/dev/null | grep -iE "restriction|requiresDistraction" | head -10 | tee "$OUT/ux-driving.txt"
if wait_for "$DRIVING" 45 driving; then echo "driving: OK"
else echo "driving: FAIL, screen showed:"; cat "$OUT/driving.txt"; rc=1; fi
shot car-driving

echo "== Parked again: the app comes back"
drive 0 4
if wait_for "$DRIVING" 45 parked-again absent && grep -qE "$PARKED" "$OUT/parked-again.txt"; then echo "parked again: OK"
else echo "parked again: FAIL, screen showed:"; cat "$OUT/parked-again.txt"; rc=1; fi
shot car-parked-again

sleep 2
kill "$LOGCAT" 2>/dev/null
if grep -q "AndroidRuntime: Process: $PKG" "$OUT/logcat.txt"; then
  echo "FAIL: a Selkie process crashed:"; grep -B1 -A20 "AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" | head -60; rc=1
fi
exit $rc
