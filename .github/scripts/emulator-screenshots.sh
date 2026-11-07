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

shoot dark
shoot light
adb logcat -d -t 400 > "$out/logcat.txt" || true
ls -la "$out"
