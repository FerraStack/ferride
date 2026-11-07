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

# UI tree helpers (uiautomator sees Compose text nodes).
ui_dump() {
  for _ in 1 2 3; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/ui.xml
      return 0
    fi
    sleep 1
  done
  echo "::warning::uiautomator dump failed" >&2
}
# Center "x y" of the first node whose text is exactly $1, read from a dump on stdin.
center_of() {
  grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}
expect_text() {
  if ! ui_dump | grep -q "text=\"$1\""; then
    echo "::error::Expected \"$1\" on screen: $2"
    adb exec-out screencap -p > "$out/failure.png" || true
    adb logcat -d -t 400 > "$out/logcat.txt" || true
    exit 1
  fi
}
expect_no_text() {
  if ui_dump | grep -q "text=\"$1\""; then
    echo "::error::Did not expect \"$1\" on screen: $2"
    adb exec-out screencap -p > "$out/failure.png" || true
    exit 1
  fi
}

shoot dark

# Touch selection (#8): long press in the editor shows the floating toolbar;
# Select All keeps it open with Cut/Copy; Copy closes it.
size="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
sw="${size%x*}"
sh="${size#*x}"
lx=$((sw / 2))
ly=$((sh * 3 / 10))
adb shell input swipe "$lx" "$ly" "$lx" "$ly" 1200
sleep 2
alive "long press in the editor"
expect_text "Select All" "selection toolbar after a long press"
read -r ax ay < <(ui_dump | center_of "Select All") || { echo "::error::Select All has no bounds"; exit 1; }
adb shell input tap "$ax" "$ay"
sleep 2
expect_text "Copy" "toolbar stays open with Cut/Copy after Select All"
expect_text "Cut" "toolbar stays open with Cut/Copy after Select All"
adb exec-out screencap -p > "$out/emulator_selection_dark.png"
read -r cx cy < <(ui_dump | center_of "Copy") || { echo "::error::Copy has no bounds"; exit 1; }
adb shell input tap "$cx" "$cy"
sleep 2
expect_no_text "Cut" "toolbar closes after Copy"
alive "selection toolbar"
echo "selection toolbar: OK"

quick_open lib
alive "open lib.rs"
quick_open app
alive "replace preview tab with app.ts"
adb exec-out screencap -p > "$out/emulator_quickopen_dark.png"

# File: Open Folder… launches the system folder picker (SAF); Back returns.
adb shell input keycombination 113 59 44   # Ctrl+Shift+P (SHIFT_LEFT=59)
sleep 1
adb shell input keyevent 123   # MOVE_END: type after the ">" prefix
adb shell input text "openfolder"
sleep 1
adb shell input keyevent 66
sleep 4
if ! adb shell dumpsys activity activities | grep -iE "(topResumedActivity|mResumedActivity).*documentsui" >/dev/null; then
  echo "::error::Open Folder did not show the system folder picker"
  adb exec-out screencap -p > "$out/failure.png" || true
  exit 1
fi
adb exec-out screencap -p > "$out/emulator_open_folder_picker.png"
adb shell input keyevent 4   # BACK
sleep 2
alive "cancel the folder picker"
echo "open folder picker: OK"
adb shell input keycombination 113 51
sleep 1
adb shell input keycombination 113 51
sleep 2
alive "close tabs with Ctrl+W"
shoot light
adb logcat -d -t 400 > "$out/logcat.txt" || true
ls -la "$out"
