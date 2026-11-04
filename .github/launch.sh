#!/usr/bin/env bash
# Opens Cielo on the cover-sized screen, scrubs the day, picks another day, opens Places and adds
# a city, swipes between places, opens Settings, then unfolds (a larger, squarer screen) and folds
# back. Screenshots of each go to shots/, and any crash fails the run.
set +e
PKG=com.fergusonconsultancy.cielo
APK=$(ls app/build/outputs/apk/debug/*.apk | head -1)
# Let a freshly booted emulator finish its first-boot work before loading it further
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 40
echo "settled: $(adb get-state 2>&1)"
# Control: an ordinary app first. If the emulator survives this but not Cielo, Cielo is the cause
adb shell am start -W -a android.settings.SETTINGS >/dev/null 2>&1; sleep 8
echo "control (Settings) survived: $(adb get-state 2>&1)"
adb shell input keyevent KEYCODE_HOME; sleep 2
adb install -r "$APK"
adb logcat -c
mkdir -p shots
# A live log, kept even if the emulator dies, so the last seconds can be read
adb logcat -v time > shots/logcat-live.txt 2>&1 &
alive() { [ "$(adb get-state 2>/dev/null)" = "device" ] || { echo "::error::the emulator went offline at: $1"; tail -80 shots/logcat-live.txt | while IFS= read -r l; do echo "LIVE $l"; done; exit 1; }; }
shot() { adb exec-out screencap -p > "shots/$1.png"; echo "shot $1"; alive "$1"; }
size() { SIZE=$(adb shell wm size | grep -o "[0-9]*x[0-9]*" | tail -1); W=${SIZE%x*}; H=${SIZE#*x}; }
lay() { adb shell run-as $PKG cat files/layout.json 2>/dev/null; }
# Reads a value from the recorded layout: lay_get 'plot' -> "l t r b"
lay_get() { lay | python3 -c "import sys,json;d=json.load(sys.stdin);v=d$1;print(*v) if isinstance(v,list) else print(v)"; }
desc() {
  adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -v "dumped to" ; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  python3 -c "
import re;x=open('ui.xml',encoding='utf-8').read()
m=[d for d in re.findall(r'package=\"com.fergusonconsultancy.cielo\"[^>]*?content-desc=\"([^\"]*)\"',x) if '°' in d or 'orecast' in d]
print(m[0] if m else '')"
}
tap_text() {
  adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -v "dumped to" ; adb pull /sdcard/ui.xml ui.xml >/dev/null 2>&1
  b=$(python3 - "$1" <<'PY'
import re,sys
x=open('ui.xml',encoding='utf-8').read()
hit=None
for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x):
    if sys.argv[1] in (m.group(1), m.group(2)):
        a,b,c,d=map(int,m.groups()[2:]);hit=((a+c)//2,(b+d)//2)
        break
if hit: print(*hit)
PY
)
  if [ -n "$b" ]; then adb shell input tap $b; echo "tapped '$1' at $b"; else echo "::warning::no '$1' on screen"; fi
}

size
adb shell am start -W -n $PKG/.MainActivity
for i in 1 2 3 4 5 6; do sleep 2; echo "t+$((i*2))s: $(adb get-state 2>&1) pid=$(adb shell pidof $PKG 2>&1)"; done
alive "launch"
# At rest the app must stop drawing: frames counted three seconds apart should barely move
f1=$(adb shell dumpsys gfxinfo $PKG | grep -m1 "Total frames rendered" | grep -o "[0-9]*$"); sleep 3
f2=$(adb shell dumpsys gfxinfo $PKG | grep -m1 "Total frames rendered" | grep -o "[0-9]*$")
echo "::notice::frames at rest: $f1 then $f2 three seconds later"
[ $((f2 - f1)) -gt 20 ] && echo "::error::Cielo keeps drawing at rest ($((f2 - f1)) frames in 3 s)"
shot 01-cover
echo "::notice::summary: $(desc)"
if desc | grep -q "Fetching\|No forecast"; then echo "::warning::no forecast arrived on the emulator (network?)"; fi

# Scrub the day: hold and slide along the curve
read L T R B <<< "$(lay_get "['plot']")"
Y=$(( (T + B) / 2 ))
adb shell input swipe $((L + (R-L)/5)) $Y $((L + (R-L)*4/5)) $Y 3200 &
SW=$!
sleep 2
shot 02-scrub
wait $SW
sleep 1

# Another day: the fourth row of the week
read L T R B <<< "$(lay_get "['rows'][3]")"
if [ -n "$T" ]; then
  # Rows may be below the fold: scroll them into view first
  if [ "$B" -gt $((H - 200)) ]; then adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H*3/4 - (B - H + 600))) 600; sleep 1; OFF=$((B - H + 600)); else OFF=0; fi
  adb shell input tap $(( (L+R)/2 )) $(( (T+B)/2 - OFF ))
  sleep 2
  shot 03-another-day
  echo "::notice::after picking a day: $(desc)"
  adb shell input tap $(( (L+R)/2 )) $(( (T+B)/2 - OFF ))
  sleep 1
fi

# The tiles and the foot
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/5)) 250; sleep 0.4; done
sleep 1
shot 04-tiles
# Back to the top, then pull the membrane down to refresh
for i in 1 2 3; do adb shell input swipe $((W/2)) $((H/5)) $((W/2)) $((H*4/5)) 250; sleep 0.4; done
sleep 1
adb shell input swipe $((W/2)) $((H/4)) $((W/2)) $((H*3/4)) 2400 &
SW=$!
sleep 1.6
shot 05-pull
wait $SW
sleep 3

# Places: tap the name, search, add a city
read L T R B <<< "$(lay_get "['header']")"
adb shell input tap $((L + 60)) $(( (T+B)/2 ))
sleep 2
shot 06-places
tap_text "Add a town or city"; sleep 1
adb shell input text "Lisbon"
sleep 5
shot 07-search
tap_text "Add"; sleep 6
shot 08-added
tap_text "Done"; sleep 2
shot 09-lisbon
echo "::notice::after adding: $(desc)"

# Swipe back to the first place, photographed mid-swipe
adb shell input swipe $((W/5)) $((H/2)) $((W*4/5)) $((H/2)) 1400 &
SW=$!
sleep 0.8
shot 10-swipe
wait $SW
sleep 2
shot 11-back
echo "::notice::after swiping: $(desc)"

# Settings, from the foot
for i in 1 2 3 4; do adb shell input swipe $((W/2)) $((H*4/5)) $((W/2)) $((H/5)) 250; sleep 0.4; done
sleep 1.5
read L T R B <<< "$(lay_get "['settings']")"
HT=$(lay_get "['height']")
OFF=$(( HT > H ? HT - H : 0 ))
adb shell input tap $(( (L+R)/2 )) $(( (T+B)/2 - OFF ))
sleep 2
shot 12-settings
tap_text "°F"; sleep 1
tap_text "Done"; sleep 2
shot 13-fahrenheit
for i in 1 2 3 4; do adb shell input swipe $((W/2)) $((H/5)) $((W/2)) $((H*4/5)) 250; sleep 0.4; done
sleep 1
read L T R B <<< "$(lay_get "['header']")"
adb shell input tap $((L + 60)) $(( (T+B)/2 ))
sleep 2
tap_text "Remove"; sleep 2
tap_text "Done"; sleep 1
# Celsius again for the rest
adb shell run-as $PKG sh -c "sed -i 's/name=\"fahrenheit\" value=\"true\"/name=\"fahrenheit\" value=\"false\"/' shared_prefs/cielo.xml" 2>/dev/null

# Unfold: the main screen is larger and nearly square (about 832 x 750 dp at this density)
DPI=$(adb shell wm density | grep -o "[0-9]*" | tail -1)
adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.MainActivity; sleep 6
MW=$(( 832 * DPI / 160 )); MH=$(( 750 * DPI / 160 ))
adb shell wm size ${MW}x${MH}
sleep 0.35
shot 14-unfolding
sleep 3
shot 15-main
echo "::notice::main screen: $(lay | head -c 300)"
read L T R B <<< "$(lay_get "['plot']")"
Y=$(( (T + B) / 2 ))
adb shell input swipe $((L + (R-L)/4)) $Y $((L + (R-L)*3/4)) $Y 3000 &
SW=$!
sleep 2
shot 16-main-scrub
wait $SW
adb shell wm size reset
sleep 0.35
shot 17-folding
sleep 3
shot 18-cover-again

kill %1 2>/dev/null
adb logcat -d > logcat.txt
if grep -q "FATAL EXCEPTION" logcat.txt; then
  grep -A 40 "FATAL EXCEPTION" logcat.txt | grep -E "AndroidRuntime" | head -40 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r line; do echo "::error::$line"; done
  exit 1
fi
adb shell run-as $PKG cat files/crash.txt 2>/dev/null | head -30 | while IFS= read -r line; do echo "::error::$line"; done
if adb shell pidof $PKG >/dev/null; then echo "::notice::Cielo ran every step and is still running"; else echo "::error::Cielo is not running"; exit 1; fi
exit 0
