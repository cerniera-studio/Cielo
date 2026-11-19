#!/usr/bin/env bash
# Windows (adaptive quality, 19 Nov 2026; Sveglia's tools/windows.sh is the template). Pop-up
# windows, resizing and continuity on the main screen's shape (859 x 896 dp). Results go to
# shots/windows.txt; a line starting ::error:: fails the run.
#
# 1. Places open in a pop-up window, shrunk to half width: the sheet must stay open. Before Kit
#    PR #7 this read as a fold, which closed the sheet and played the fold.
# 2. The sky in a pop-up window, narrow then wide: the narrow layout, then the wide one.
# 3. Across a turn: Places stays open.
#
# Pop-up windows need freeform support, which the emulator only reads at boot: this reboots it,
# so it runs after the launch test.
PKG=com.fergusonconsultancy.cielo
mkdir -p shots
out=shots/windows.txt; : > $out
note() { echo "::notice::$*" | tee -a $out; }
fail() { echo "::error::$*" | tee -a $out; }
warn() { echo "::warning::$*" | tee -a $out; }
shot() { adb exec-out screencap -p > "shots/win-$1.png"; }

adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
adb reboot
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done
sleep 30
adb shell input keyevent 82
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb logcat -c

# The main screen, upright
adb shell wm density 280; adb shell wm size 1503x1568; sleep 3

task() {  # "<id> <mode>" of the task whose affinity is exactly $1
  adb shell dumpsys activity activities | python3 -c "
import sys, re
for line in sys.stdin:
    m = re.search(r'\* Task\{\S+ #(\d+) .*?A=\d+:(\S+) .*?mode=(\S+)', line)
    if m and m.group(2) == '$1': print(m.group(1), m.group(3)); break"
}
has_text() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml | grep -qF "\"$1\""; }
wide() {  # the sky's layout: True or False
  for try in 1 2 3 4 5 6 7 8; do
    w=$(adb shell run-as $PKG cat files/layout.json 2>/dev/null | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)['wide']))" 2>/dev/null)
    [ -n "$w" ] && break; sleep 1
  done
  echo "$w"
}
places_open() { has_text "Add a town or city"; }

# ---- 1. Places in a pop-up window, shrunk
adb shell am force-stop $PKG
adb shell am start -W --windowingMode 5 -n $PKG/.MainActivity --es open places >/dev/null; sleep 6
set -- $(task $PKG); id=$1; mode=$2
echo "task: $id $mode" | tee -a $out
if [ "$mode" != freeform ]; then
  warn "Pop-up window: the emulator would not open one (mode ${mode:-none}); checks 1 and 2 skipped"
else
  adb shell am task resize "$id" 0 0 1503 1568; sleep 4
  if places_open; then
    shot places-popup-wide
    adb shell am task resize "$id" 0 0 740 1568; sleep 4
    shot places-popup-narrow
    if places_open; then note "Places in a pop-up window: still open after shrinking to half width"
    else fail "Places in a pop-up window: the sheet closed when the window was shrunk (read as a fold?)"; fi
  else
    warn "Places in a pop-up window: the sheet didn't open; skipped"
  fi
  adb shell input keyevent KEYCODE_BACK; sleep 2

  # ---- 2. The sky, narrow then wide. Start wide, so that shrinking is a real change of size (a
  # resize to the size it already has changes nothing, and nothing new is recorded)
  adb shell am task resize "$id" 0 0 1503 1568; sleep 4
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 740 1568; sleep 4; shot sky-popup-narrow
  w=$(wide); [ "$w" = false ] && note "Sky in a narrow pop-up window: the narrow layout" || fail "Sky in a narrow pop-up window: wide=${w:-unknown}, expected the narrow layout"
  adb shell run-as $PKG rm -f files/layout.json
  adb shell am task resize "$id" 0 0 1503 1568; sleep 4; shot sky-popup-wide
  w=$(wide); [ "$w" = true ] && note "Sky in a wide pop-up window: the wide layout" || fail "Sky in a wide pop-up window: wide=${w:-unknown}, expected the wide layout"
fi
adb shell am force-stop $PKG; sleep 2

# ---- 3. Places across a turn (full screen)
adb shell am start -W --windowingMode 1 -n $PKG/.MainActivity --es open places >/dev/null; sleep 6
if places_open; then
  shot places-upright
  adb shell settings put system user_rotation 1; sleep 4; shot places-turned
  if places_open; then note "Places: still open after turning"; else fail "Places: closed by turning the phone"; fi
  adb shell settings put system user_rotation 0; sleep 3
else
  warn "Places: didn't open full screen; skipped"
fi
adb shell am force-stop $PKG
adb shell wm size reset; adb shell wm density reset

adb logcat -d > shots/logcat-windows.txt
if grep -A2 "FATAL EXCEPTION" shots/logcat-windows.txt | grep -q "Process: $PKG"; then fail "Cielo crashed in the windows test"; grep -A20 "FATAL EXCEPTION" shots/logcat-windows.txt | head -40; fi
grep -q "^::error::" $out && exit 1
exit 0
