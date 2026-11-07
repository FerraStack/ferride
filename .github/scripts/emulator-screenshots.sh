#!/usr/bin/env bash
# Installs the debug APK on the running emulator, launches it (it opens the
# sample project's src/main.rs), checks it didn't crash, and saves dark/light
# screenshots. Usage: emulator-screenshots.sh <apk> <out-dir>
set -euo pipefail
apk="$1"
out="$2"
mkdir -p "$out"

adb install -r "$apk"

shoot() {
  local mode="$1"
  adb shell am force-stop dev.ferride || true
  adb shell cmd uimode night "$([ "$mode" = dark ] && echo yes || echo no)"
  adb shell am start -W -n dev.ferride/.MainActivity
  sleep 8
  if ! adb shell pidof dev.ferride >/dev/null; then
    echo "::error::Ferride is not running after launch ($mode)"
    adb logcat -d -t 400 | tee "$out/logcat.txt"
    exit 1
  fi
  adb exec-out screencap -p > "$out/emulator_editor_${mode}.png"
}

# Tab lifecycle smoke test (regression for a use-after-close crash): open two
# files in a row through Quick Open (the second replaces the preview tab), then
# close tabs with Ctrl+W. Key codes: CTRL_LEFT=113, P=44, W=51, ENTER=66.
quick_open() {
  adb shell input keycombination 113 44
  sleep 1
  adb shell input text "$1"
  sleep 1
  adb shell input keyevent 66
  sleep 2
}
alive() {
  if ! adb shell pidof dev.ferride >/dev/null; then
    echo "::error::Ferride crashed during: $1"
    adb logcat -d -t 400 | tee "$out/logcat.txt"
    exit 1
  fi
}

shoot dark
quick_open lib
alive "open lib.rs"
quick_open app
alive "replace preview tab with app.ts"
adb exec-out screencap -p > "$out/emulator_quickopen_dark.png"
adb shell input keycombination 113 51
sleep 1
adb shell input keycombination 113 51
sleep 2
alive "close tabs with Ctrl+W"
shoot light
adb logcat -d -t 400 > "$out/logcat.txt" || true
ls -la "$out"
