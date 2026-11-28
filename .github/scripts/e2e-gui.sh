#!/usr/bin/env bash
# Runs on the CI emulator: DesktopE2ETest (Bluefin LTS: GNOME through droidc bridge, a GTK app
# nested directly in Selkie's compositor, and a pinned app at tablet and phone size), then GNOME's
# display scale at tablet and phone sizes, then pulls its screenshots and logs.
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

# A pinned app at phone size and density: app mode at 2.625x, about 411 logical pixels wide.
adb shell wm size 1080x2340
adb shell wm density 420
echo "== DesktopE2ETest#appShortcutAtDisplayScale at 1080x2340 @420dpi"
timeout 1800 adb shell am instrument -w -e class $PKG.DesktopE2ETest#appShortcutAtDisplayScale $RUNNER | tee "$OUT/gui-phone-app.txt"
grep -q '^OK (' "$OUT/gui-phone-app.txt" || { echo "phone app shortcut test failed"; rc=1; }

# Display scale (DesktopE2ETest#gnomeScale): GNOME's scale follows Android's density and survives a
# change made like GNOME Settings does, on a tablet and on a phone screen.
scale_run() { # form size density
  echo "== display scale: $1 ($2 at ${3}dpi)"
  adb shell wm size "$2"; adb shell wm density "$3"; sleep 5
  timeout 2700 adb shell am instrument -w -e class "$PKG.DesktopE2ETest#gnomeScale" -e scale "$1" $RUNNER | tee "$OUT/scale-$1.txt"
  grep -q '^OK (' "$OUT/scale-$1.txt" || { echo "display scale test ($1) failed"; rc=1; }
}
scale_run tablet 2560x1600 320
scale_run phone 1080x2340 420
adb shell wm size 1280x800; adb shell wm density 160

adb shell screencap -p /sdcard/final.png && adb pull /sdcard/final.png "$OUT/screen-final.png" || true
adb pull "/sdcard/Android/data/$PKG/files/." "$OUT/screenshots" || true
adb shell run-as $PKG cat cache/desktop.log > "$OUT/desktop.log" 2>&1 || true
adb shell run-as $PKG cat cache/droidc.log > "$OUT/droidc.log" 2>&1 || true
adb shell run-as $PKG cat cache/user-services.log > "$OUT/user-services.log" 2>&1 || true
adb shell run-as $PKG cat cache/audio-out.log > "$OUT/audio-out.log" 2>&1 || true
adb shell df -h /data | tee "$OUT/df-after.txt"
sleep 2; kill $LOGCAT 2>/dev/null || true
grep -h SELKIE_METRIC "$OUT/logcat.txt" | tee "$OUT/metrics.txt" || true
if [ -f "$OUT/screenshots/desktop-gnome.log" ]; then
  g="$OUT/screenshots/desktop-gnome.log"
  echo "::group::desktop-gnome.log (the GNOME session): terminal / agent / droidc lines"
  grep -a -i -E "ptyxis|agent|terminal|scope|transient|launch|app-gnome|StartTransient|^note:|^warn:|droidc" "$g" | grep -a -v -E "Vulkan|cgshim:" | cut -c1-400 | head -150; echo "::endgroup::"
  echo "::group::desktop-gnome.log (last 120 lines without cgshim/Vulkan noise)"
  grep -a -v -E "Vulkan|cgshim:|Tracker|GVFS|St-CRITICAL" "$g" | tail -120 | cut -c1-400; echo "::endgroup::"
fi
echo "::group::links: the guest's xdg-open and the Android side"
grep -a -h -E "SELKIE_GUEST links|SelkieLink" "$OUT/logcat.txt" | head -60 | cut -c1-400; echo "::endgroup::"
echo "::group::sound: audio-out.log, the guest's tone probe, Selkie's player"
tail -40 "$OUT/audio-out.log" | cut -c1-400
grep -a -h -E "SELKIE_GUEST sound|SELKIE_PROCS sound|SelkieAudio|AudioTrack" "$OUT/logcat.txt" | head -60 | cut -c1-400; echo "::endgroup::"
echo "::group::desktop.log (journal, warnings)"
grep -a -E "^(journal|warn):|systemd|dbus|cgshim" "$OUT/desktop.log" | head -400; echo "::endgroup::"
echo "::group::user-services.log (the container's bus and systemd --user, started by Selkie)"
grep -a -E "^(journal|warn|note):|systemd|dbus|cgshim" "$OUT/user-services.log" | head -400; echo "::endgroup::"
if [ $rc -ne 0 ]; then
  echo "::group::desktop.log (tail)"; tail -120 "$OUT/desktop.log"; echo "::endgroup::"
  echo "::group::user-services.log (tail)"; tail -120 "$OUT/user-services.log"; echo "::endgroup::"
  echo "::group::ld.so traces (guest /tmp/lddebug.*): files that looked for the shim but never initialised it"
  cat > "$OUT/lddbg.sh" <<'LDEOF'
cd files/guest/tmp 2>/dev/null || { echo "no files/guest/tmp"; exit 0; }
echo "lddebug files: $(ls lddebug.* 2>/dev/null | wc -l)"
echo "that mention the shim: $(grep -l 'cgshim' lddebug.* 2>/dev/null | wc -l)"
bad=""
for f in $(grep -l 'cgshim' lddebug.* 2>/dev/null); do
  grep -q 'calling init: /usr/lib/droidc/cgshim.so' "$f" || bad="$bad $f"
done
echo "that looked for it but never initialised it: $(echo $bad | wc -w)"
n=0
for f in $bad; do
  n=$((n+1)); [ $n -gt 4 ] && break
  echo "=== $f"; head -50 "$f"
done
LDEOF
  adb push "$OUT/lddbg.sh" /data/local/tmp/lddbg.sh >/dev/null 2>&1 || true
  adb shell run-as $PKG sh /data/local/tmp/lddbg.sh 2>&1 | head -260 || true
  echo "::endgroup::"
  for f in "$OUT"/screenshots/*-session-failed.log; do
    [ -f "$f" ] || continue
    echo "::group::$(basename "$f") (why the session ended: exit/stop/died/signal lines, then the last 80 lines)"
    grep -a -n -i -E "exit|stopp|died|killed|signal|abort|segfault|core dump|critical|fatal|Failed with|Main process|entered failed|shutdown" "$f" | grep -a -v -E "Vulkan|Tracker|GVFS|cap_last_cap|nr_open|ManagedOOM|root.mount|Failed to get cgroup ID|unit_file_build_name_map" | head -120
    echo "---- last 80 lines"; tail -80 "$f"
    echo "::endgroup::"
    echo "::group::$(basename "$f") (droidc / bridge lines, then the last 150 lines without the shell's dock-extension and cgroup noise)"
    grep -a -n -E "droidc|bridge|supervise|screencast|ScreenCast|RemoteDesktop|pw-link|pipewire.*(error|fail)|Wayland" "$f" | grep -a -v -E "Vulkan|cgshim:" | head -80
    echo "---- filtered tail"
    grep -a -v -E "St-CRITICAL|GLib-GObject-CRITICAL|libecal|Vulkan|cgshim: /proc|cgshim: open|ManagedOOM|dmi/id|cgroup ID|root.mount|cap_last_cap|nr_open|Tracker|GVFS" "$f" | tail -150
    echo "::endgroup::"
    echo "::group::$(basename "$f") (ptyxis lines)"
    grep -a -i -E "ptyxis|agent" "$f" | grep -a -v -E "Vulkan|cgshim:" | head -200
    echo "::endgroup::"
    echo "::group::$(basename "$f") (pipewire, mutter, shell, bridge, droidc lines)"
    grep -i -E "PWDIAG|pipewire|pw-|wireplumber|mutter|gnome-shell|screencast|bridge|droidc|error|fail" "$f" | grep -v -E "Vulkan|Tracker|GVFS|landlock" | head -250
    echo "::endgroup::"
  done
  echo "::group::SelkieE2E log"; grep -E "SelkieE2E|SelkieDesktop|AndroidRuntime" "$OUT/logcat.txt" | tail -200; echo "::endgroup::"
fi
exit $rc
