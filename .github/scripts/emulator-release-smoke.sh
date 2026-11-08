#!/usr/bin/env bash
# Smoke test of the R8-minified release build (signed with the debug key for
# CI only): launch, check the Rust core loaded (the status bar shows the
# grammar's language, which comes from the native document), open a file
# through Quick Open, filter the Command Palette and run Change Language Mode.
# Usage: emulator-release-smoke.sh <apk> <out-dir>
set -euo pipefail
apk="$1"
out="$2"
mkdir -p "$out"

fail() {
  echo "::error::Release build: $1"
  adb exec-out screencap -p > "$out/release_failure.png" || true
  adb logcat -d -t 600 > "$out/release_logcat.txt" || true
  exit 1
}
alive() { adb shell pidof dev.ferride > /dev/null || fail "crashed during: $1"; }
ui_dump() {
  for _ in 1 2 3 4 5; do
    adb shell rm -f /sdcard/ui.xml > /dev/null 2>&1 || true
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    if adb shell cat /sdcard/ui.xml 2> /dev/null | grep -q '<hierarchy'; then
      adb shell cat /sdcard/ui.xml
      return 0
    fi
    sleep 2
  done
}
expect_text() { ui_dump | grep -q "text=\"$1\"" || fail "expected \"$1\" on screen: $2"; }

adb uninstall dev.ferride > /dev/null 2>&1 || true
adb install -r "$apk"
adb shell cmd uimode night yes
adb logcat -c || true
adb shell am start -W -n dev.ferride/.MainActivity
sleep 8
alive "launch"
# main.rs opens at start; "Rust" in the status bar means libferride_ffi loaded
# and the UniFFI bindings survived R8.
expect_text "Rust" "status bar language of main.rs (native document)"
adb exec-out screencap -p > "$out/emulator_release_editor_dark.png"

# Quick Open (Ctrl+P): open web/app.ts. Key codes: CTRL_LEFT=113, SHIFT_LEFT=59, P=44, ENTER=66, ESCAPE=111.
adb shell input keycombination 113 44
sleep 1
adb shell input text "app"
sleep 1
adb shell input keyevent 66
sleep 2
alive "Quick Open"
expect_text "TypeScript" "status bar language after opening app.ts"

# Command Palette (Ctrl+Shift+P).
adb shell input keycombination 113 59 44
sleep 2
alive "Command Palette"
adb shell input text "language"
sleep 2
expect_text "Change Language Mode" "the Command Palette filtering commands"
adb exec-out screencap -p > "$out/emulator_release_palette_dark.png"

# Run the highlighted command (Enter): Change Language Mode opens its picker.
adb shell input keyevent 66
sleep 2
alive "running Change Language Mode from the palette"
expect_text "Auto Detect" "Change Language Mode picker"
adb shell input keyevent 111
sleep 1
alive "closing the picker"
if adb logcat -d | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError" | grep -i ferride; then
  fail "runtime error in logcat"
fi
echo "release smoke test: OK"
