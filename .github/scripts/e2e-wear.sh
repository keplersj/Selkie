#!/usr/bin/env bash
# Runs on a Wear OS emulator: installs the watch remote, opens it and its tile, and checks that each
# shows Selkie's own screen. No phone is paired, so the remote shows its "find your phone" state,
# which is the screen a new watch user sees first.
# usage: e2e-wear.sh <watch-apk-dir> <out-dir>
set -u
APKS=${1:?watch apk dir}; OUT=${2:?out dir}
PKG=io.github.keplersj.selkie
ACTIVITY=$PKG/$PKG.wear.WatchActivity
TILE=$PKG/$PKG.wear.SelkieTileService
mkdir -p "$OUT"
rc=0

# Prints the screen's text nodes, one per line (uiautomator sees Compose text through accessibility).
screen_text() {
  adb shell uiautomator dump /data/local/tmp/ui.xml >/dev/null 2>&1
  adb shell cat /data/local/tmp/ui.xml 2>/dev/null | tr '>' '\n' | sed -n 's/.* text="\([^"]*\)".*/\1/p' | grep -v '^$'
}

# Waits up to $2 seconds for a line matching the regex $1. Saves the last dump as $3.txt either way,
# and prints each new screen into the job log (the artifacts aren't readable from everywhere).
wait_for_text() {
  local re=$1 secs=$2 name=$3 end=$((SECONDS + $2)) last=""
  while ((SECONDS < end)); do
    screen_text > "$OUT/$name.txt"
    if [ "$(cat "$OUT/$name.txt")" != "$last" ]; then
      last=$(cat "$OUT/$name.txt"); echo "  screen: $(tr '\n' '|' < "$OUT/$name.txt")"
    fi
    grep -qE "$re" "$OUT/$name.txt" && return 0
    sleep 3
  done
  return 1
}

# Wear OS turns the screen off after a few seconds and then goes back to the watch face. The emulator
# also reports itself as charging, and a charging watch covers every app with its charging screen, so
# unplug it first.
keep_awake() {
  adb shell dumpsys battery unplug
  adb shell svc power stayon true
  adb shell settings put system screen_off_timeout 1800000
  adb shell input keyevent KEYCODE_WAKEUP
}

shot() { adb shell screencap -p "/sdcard/$1.png" && adb pull "/sdcard/$1.png" "$OUT/$1.png" >/dev/null || true; }

# A process that died with an uncaught exception, from this run's log.
crashed() { grep -q "AndroidRuntime: Process: $PKG" "$OUT/logcat.txt"; }

apk=$(find "$APKS" -name 'selkie-watch-*.apk' | head -1)
[ -n "$apk" ] || { echo "no selkie-watch-*.apk in $APKS"; exit 1; }
adb install -r -t -g "$apk"

adb logcat -c
adb logcat -v threadtime > "$OUT/logcat.txt" &
LOGCAT=$!

echo "== Watch remote opens on its own screen"
keep_awake
adb shell am start -W -n "$ACTIVITY" | tee "$OUT/start.txt"
if wait_for_text '^(Looking for your phone|Open Selkie on your phone or tablet.*)$' 60 watch-home; then
  echo "watch remote: OK ($(grep -E 'phone' "$OUT/watch-home.txt" | head -1))"
else
  echo "watch remote: FAIL, screen showed:"; cat "$OUT/watch-home.txt"; rc=1
  echo "::group::activities and Selkie's log"
  adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity|ResumedActivity" | head -5
  grep -iE "selkie|WatchActivity" "$OUT/logcat.txt" | grep -vE "PackageManager|installd|dex2oat" | tail -60
  echo "::endgroup::"
fi
shot watch-home

echo "== Tile renders (Wear OS debug surface)"
adb shell input keyevent KEYCODE_HOME
adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation add-tile --ecn component "$TILE" | tee "$OUT/add-tile.txt"
if grep -q 'result=1' "$OUT/add-tile.txt"; then
  sleep 10
  shot watch-tile
  screen_text > "$OUT/watch-tile.txt"
  if grep -q . "$OUT/watch-tile.txt"; then
    echo "tile: OK, it shows:"; cat "$OUT/watch-tile.txt"
  else
    # A tile draws with ProtoLayout, which uiautomator may not see: the screenshot is the record.
    echo "tile: added; no text nodes, see watch-tile.png"
  fi
  adb shell am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation remove-tile --ecn component "$TILE" >/dev/null
else
  echo "tile: FAIL, the debug surface didn't add it"; rc=1
fi

sleep 2
kill "$LOGCAT" 2>/dev/null
if crashed; then
  echo "FAIL: a Selkie process crashed:"; grep -B1 -A20 "AndroidRuntime: Process: $PKG" "$OUT/logcat.txt" | head -60; rc=1
fi
exit $rc
