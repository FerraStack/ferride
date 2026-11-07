#!/usr/bin/env bash
# Installs the debug APK on the running emulator, launches it (it opens the
# sample project's src/main.rs), checks it didn't crash, and saves dark/light
# screenshots. Usage: emulator-screenshots.sh <apk> <out-dir>
set -euo pipefail
apk="$1"
out="$2"
mkdir -p "$out"

adb install -r "$apk"
# The slow CI emulator often shows "Pixel Launcher isn't responding" over the
# app under test; hide system crash/ANR dialogs (Settings.Global.HIDE_ERROR_DIALOGS).
adb shell settings put global hide_error_dialogs 1 || true

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
# The CI emulator sometimes shows "Pixel Launcher isn't responding" over our
# app; it's not ours, so dismiss it ("Wait") instead of failing on it.
dismiss_system_dialogs() {
  local dump
  dump="$(ui_dump)"
  if grep -q "isn't responding" <<<"$dump"; then
    echo "dismissing a system ANR dialog"
    read -r wx wy < <(center_of "Wait" <<<"$dump") && adb shell input tap "$wx" "$wy"
    sleep 2
  fi
}
expect_text() {
  dismiss_system_dialogs
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
expect_text "Welcome" "the Welcome tab opens on first launch"

# Touch selection (#8): long press in the editor shows the floating toolbar;
# Select All keeps it open with Cut/Copy; Copy closes it.
size="$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"
sw="${size%x*}"
sh="${size#*x}"
lx=$((sw / 2))
ly=$((sh * 3 / 10))
long_press() {
  adb shell input swipe "$lx" "$ly" "$lx" "$ly" 1200
  sleep 2
}
dismiss_system_dialogs
long_press
if ! ui_dump | grep -q 'text="Select All"'; then
  # A system dialog may have eaten the gesture; clear it and retry once.
  dismiss_system_dialogs
  adb shell input tap "$lx" "$ly"
  sleep 1
  long_press
fi
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
dismiss_system_dialogs
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

# Explorer: File: New File… opens the inline name box; typing a name creates
# the file in the sample project (real VFS) and opens it in a tab.
dismiss_system_dialogs
adb shell input keycombination 113 59 44
sleep 1
adb shell input keyevent 123
adb shell input text "newfile"
sleep 1
adb shell input keyevent 66
sleep 2
adb shell input text "notes.md"
adb shell input keyevent 66
sleep 3
alive "create a file from the Explorer"
expect_text "notes.md" "new file appears in the Explorer and opens in a tab"
adb exec-out screencap -p > "$out/emulator_explorer_new_file.png"
echo "explorer new file: OK"
adb shell input keycombination 113 51
sleep 1
adb shell input keycombination 113 51
sleep 2
alive "close tabs with Ctrl+W"
# Source Control (git on gix, real native core): initialize a repository in the
# sample project, stage everything, commit with Ctrl+Enter (answering the
# identity prompt), and check the commit exists on disk and the view moved on.
# Key codes: SHIFT_LEFT=59, G=35, ENTER=66.
palette_command() {
  adb shell input keycombination 113 59 44
  sleep 1
  adb shell input text "$1"
  sleep 1
  adb shell input keyevent 66
  sleep 3
}
center_of_prefix() {
  grep -o "text=\"$1[^\"]*\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}
repo_dir=files/projects/hello-ferride
palette_command "Initialize%sRepository"
alive "git init"
adb shell input keycombination 113 59 35
sleep 2
expect_text "Changes" "Source Control view lists the untracked sample files"
palette_command "Stage%sAll%sChanges"
alive "stage all"
expect_text "Staged Changes" "files moved to Staged Changes"
read -r mx my < <(ui_dump | center_of_prefix "Message (Ctrl+Enter") || { echo "::error::commit message box not found"; exit 1; }
adb shell input tap "$mx" "$my"
sleep 1
adb shell input text "First%scommit%sfrom%sthe%ssmoke%stest"
sleep 1
adb shell input keycombination 113 66
sleep 2
expect_text "Name" "commit asks for git.user.name"
adb shell input text "Ferride%sSmoke"
adb shell input keyevent 66
sleep 2
adb shell input text "smoke@example.com"
adb shell input keyevent 66
sleep 4
alive "commit"
expect_text "Publish Branch" "after the commit the view offers to publish the branch"
expect_no_text "Staged Changes" "nothing is staged after the commit"
if ! adb shell run-as dev.ferride cat "$repo_dir/.git/logs/HEAD" | grep -q "commit (initial): First commit from the smoke test"; then
  echo "::error::the commit is not in .git/logs/HEAD"
  adb shell run-as dev.ferride ls -la "$repo_dir/.git" || true
  exit 1
fi
adb exec-out screencap -p > "$out/emulator_scm_dark.png"
echo "source control: OK"
shoot light
adb logcat -d -t 400 > "$out/logcat.txt" || true
ls -la "$out"
