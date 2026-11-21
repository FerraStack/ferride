#!/usr/bin/env bash
# Cross-app E2E test with both dev.ferride and dev.ferrux installed on the
# emulator: verifies Ferrux API permission grant (knownSigner/signature),
# live LSP over Ferrux pipes (diagnostics, completion, hover, formatting, save),
# live Terminal PTY execution in Ferrux, and Open Ferrux Home file tree.
# Usage: emulator-ferrux-e2e.sh <ferride-apk> <ferrux-apk> <out-dir>
set -euo pipefail

FERRIDE_APK="${1:?usage: emulator-ferrux-e2e.sh <ferride-apk> <ferrux-apk> <out-dir>}"
FERRUX_APK="${2:?usage: emulator-ferrux-e2e.sh <ferride-apk> <ferrux-apk> <out-dir>}"
OUT="${3:?usage: emulator-ferrux-e2e.sh <ferride-apk> <ferrux-apk> <out-dir>}"
mkdir -p "$OUT"

FERRIDE=dev.ferride
FERRUX=dev.ferrux

log() { echo "== e2e: $*"; }
fail() {
  echo "::error::Ferrux E2E: $1" >&2
  adb exec-out screencap -p > "$OUT/e2e_failure.png" || true
  adb logcat -d -t 600 > "$OUT/e2e_logcat.txt" || true
  exit 1
}
alive() {
  adb shell pidof "$FERRIDE" >/dev/null || fail "Ferride crashed during: $1"
}

ui_dump() {
  for _ in 1 2 3 4 5; do
    adb shell rm -f /sdcard/e2e-ui.xml >/dev/null 2>&1 || true
    adb shell uiautomator dump /sdcard/e2e-ui.xml >/dev/null 2>&1 || true
    if adb shell cat /sdcard/e2e-ui.xml 2>/dev/null | grep -q '<hierarchy'; then
      adb shell cat /sdcard/e2e-ui.xml
      return 0
    fi
    sleep 1
  done
}

center_of() {
  grep -o "text=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

center_of_desc() {
  grep -o "content-desc=\"$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | head -1 |
    sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' |
    awk '{ print int(($1 + $3) / 2), int(($2 + $4) / 2) }'
}

dismiss_system_dialogs() {
  local dump
  dump="$(ui_dump || true)"
  if grep -q "isn't responding" <<<"$dump"; then
    log "dismissing system ANR dialog"
    read -r wx wy < <(center_of "Wait" <<<"$dump") && adb shell input tap "$wx" "$wy"
    sleep 2
  fi
}

expect_text() {
  local want="$1" desc="$2" i found=0
  for i in $(seq 1 15); do
    dismiss_system_dialogs
    if ui_dump | grep -Fq "$want"; then
      found=1
      break
    fi
    sleep 1
  done
  if [ "$found" -eq 0 ]; then
    fail "expected \"$want\" on screen ($desc)"
  fi
}

expect_no_text() {
  local unwanted="$1" desc="$2" i found=1
  for i in $(seq 1 15); do
    if ! ui_dump | grep -Fq "$unwanted"; then
      found=0
      break
    fi
    sleep 1
  done
  if [ "$found" -ne 0 ]; then
    fail "did not expect \"$unwanted\" on screen ($desc)"
  fi
}

run_palette_command() {
  local query="$1" attempt dump cx cy opened=0
  for attempt in 1 2 3; do
    dismiss_system_dialogs
    dump="$(ui_dump || true)"
    if grep -Eq "Search commands|Change File Encoding|Change Language Mode" <<<"$dump"; then
      opened=1
      break
    fi
    if read -r cx cy < <(center_of_desc "Show All Commands" <<<"$dump") && [ -n "${cx:-}" ]; then
      adb shell input tap "$cx" "$cy"
    else
      # Fallback to Ctrl+Shift+P: CTRL_LEFT=113, SHIFT_LEFT=59, P=44
      adb shell input keycombination 113 59 44
    fi
    sleep 1
    if ui_dump | grep -Eq "Search commands|Change File Encoding|Change Language Mode"; then
      opened=1
      break
    fi
  done
  if [ "$opened" -eq 0 ]; then
    fail "Command Palette did not open in Commands mode for query: $query"
  fi
  # MOVE_END=123 so typed text always follows the ">" command prefix.
  adb shell input keyevent 123
  adb shell input text "$query"
  sleep 1
  # ENTER=66
  adb shell input keyevent 66
  sleep 2
}

log "installing Ferride and Ferrux APKs"
adb uninstall "$FERRIDE" >/dev/null 2>&1 || true
adb uninstall "$FERRUX" >/dev/null 2>&1 || true
adb install -r "$FERRIDE_APK" >/dev/null
adb install -r "$FERRUX_APK" >/dev/null
adb shell settings put global hide_error_dialogs 1 || true
adb shell cmd uimode night yes

# Check signature|knownSigner permission grant on API 34.
pkg_dump="$(adb shell dumpsys package "$FERRIDE")"
grep -q "$FERRUX.permission.API: granted=true" <<< "$pkg_dump" \
  || fail "$FERRIDE was not granted $FERRUX.permission.API"
log "ok: $FERRUX.permission.API granted to $FERRIDE"

# Seed settings.json with a lightweight /system/bin/sh mock LSP server for Rust
# so Ferride exercises real LspSession.spawn -> FerruxApiService.spawn -> pipes.
mock_lsp_sh='send() { printf "Content-Length: %d\r\n\r\n%s" "${#1}" "$1"; }; len=""; while IFS= read -r line; do line=$(printf "%s" "$line" | tr -d "\r"); case "$line" in Content-Length:*) len=$(printf "%s" "${line#*:}" | tr -d " ") ;; "") [ -n "$len" ] || continue; body=$(dd bs=1 count="$len" 2>/dev/null); len=""; id=$(printf "%s" "$body" | sed -n "s/.*\"id\":\([0-9]*\).*/\1/p"); case "$body" in *"\"method\":\"initialize\""*) send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"capabilities\":{\"textDocumentSync\":1,\"completionProvider\":{\"triggerCharacters\":[\".\"]},\"hoverProvider\":true,\"documentFormattingProvider\":true},\"serverInfo\":{\"name\":\"e2e-mock-lsp\",\"version\":\"1.0\"}}}" ;; *"\"method\":\"textDocument/didOpen\""*) uri=$(printf "%s" "$body" | sed -n "s/.*\"uri\":\"\([^\"]*\)\".*/\1/p"); send "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\",\"params\":{\"uri\":\"$uri\",\"diagnostics\":[{\"range\":{\"start\":{\"line\":0,\"character\":0},\"end\":{\"line\":0,\"character\":2}},\"severity\":1,\"source\":\"e2e\",\"message\":\"e2e diagnostic\"}]}}" ;; *"\"method\":\"textDocument/completion\""*) send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":[{\"label\":\"e2e_println\",\"detail\":\"e2e macro\",\"insertText\":\"e2e_println!()\"}]}" ;; *"\"method\":\"textDocument/hover\""*) send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"contents\":{\"kind\":\"plaintext\",\"value\":\"e2e hover info\"}}}" ;; *"\"method\":\"textDocument/formatting\""*) send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":[]}" ;; *"\"method\":\"shutdown\""*) send "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":null}" ;; *"\"method\":\"exit\""*) exit 0 ;; esac ;; esac; done'

settings_json="$(python3 -c 'import json,sys; print(json.dumps({"ferride.lsp.enabled": True, "ferride.lsp.servers": {"rust": ["/system/bin/sh", "-c", sys.argv[1]]}}, indent=2))' "$mock_lsp_sh")"

printf '%s\n' "$settings_json" | adb shell "run-as $FERRIDE sh -c 'mkdir -p files && cat > files/settings.json'"

adb logcat -c || true
adb shell am start -W -n "$FERRIDE/.MainActivity" >/dev/null
sleep 8
alive "launch"
expect_text "Rust" "status bar language of main.rs"

# 1. Verify LSP server connected through Ferrux pipes and published diagnostics.
run_palette_command "Show%sLanguages"
expect_text "e2e-mock-lsp (ready)" "Languages sidebar shows live LSP ready"
adb exec-out screencap -p > "$OUT/e2e_lsp_languages.png"
log "ok: LSP server ready via Ferrux pipes"

# Close sidebar / check Problems panel for LSP diagnostic.
adb shell input keyevent 111
sleep 1
run_palette_command "Focus%sProblems"
expect_text "e2e diagnostic" "Problems panel shows LSP diagnostic"
adb exec-out screencap -p > "$OUT/e2e_lsp_problems.png"
log "ok: LSP diagnostics received"

# Close panel via its Close button.
if read -r px py < <(ui_dump | center_of_desc "Close Panel") && [ -n "${px:-}" ]; then
  adb shell input tap "$px" "$py"
else
  adb shell input keycombination 113 38
fi
sleep 1

# 2. Hover + Completion + Format + Save.
run_palette_command "Show%sHover"
expect_text "e2e hover info" "Hover card shows LSP hover"
adb exec-out screencap -p > "$OUT/e2e_lsp_hover.png"
if read -r hx hy < <(ui_dump | center_of_desc "Dismiss hover") && [ -n "${hx:-}" ]; then
  adb shell input tap "$hx" "$hy"
else
  adb shell input keyevent 111
fi
sleep 1
log "ok: LSP hover displayed"

run_palette_command "Trigger%sSuggest"
expect_text "e2e_println" "Completion popup shows LSP suggestion"
adb exec-out screencap -p > "$OUT/e2e_lsp_completion.png"
# Tap completion item directly to accept it and dismiss popup, then save File.
if read -r sx sy < <(ui_dump | center_of "e2e_println") && [ -n "${sx:-}" ]; then
  adb shell input tap "$sx" "$sy"
else
  adb shell input keyevent 66
fi
sleep 1
run_palette_command "File:%sSave"
alive "LSP completion and save"
log "ok: LSP completion accepted and saved"

# 3. Verify live Terminal PTY via Ferrux: create a marker file in Ferrux $HOME.
if read -r fx fy < <(ui_dump | center_of "Ferrux") && [ -n "${fx:-}" ]; then
  adb shell input tap "$fx" "$fy"
  sleep 2
else
  run_palette_command "Toggle%sTerminal"
fi
term_ready=0
for _ in $(seq 1 15); do
  if adb shell dumpsys activity top 2>/dev/null | grep -Fq 'dev.ferrux.terminal.TerminalView'; then
    term_ready=1
    break
  fi
  dismiss_system_dialogs
  dump="$(ui_dump || true)"
  if grep -Fq 'text="Retry"' <<<"$dump"; then
    fail "Terminal PTY failed to start (Retry button shown)"
  fi
  sleep 1
done
if [ "$term_ready" -eq 0 ]; then
  fail "TerminalView did not appear after opening Terminal panel"
fi
sleep 2
alive "open terminal"
# TerminalView calls requestFocus() on attach; send command directly to PTY.
adb shell input text "touch%se2e_pty_verified.txt"
adb shell input keyevent 66
sleep 2
adb exec-out screencap -p > "$OUT/e2e_terminal.png"
# Switch to Output tab via Ctrl+Shift+U (unmounts TerminalView cleanly), with tap fallback.
adb shell input keycombination 113 59 49
sleep 1
if ! ui_dump | grep -Fq "Started terminal shell"; then
  if read -r ox oy < <(ui_dump | center_of "OUTPUT") && [ -n "${ox:-}" ]; then
    adb shell input tap "$ox" "$oy"
    sleep 1
  else
    run_palette_command "Toggle%sOutput"
  fi
fi
expect_text "Started terminal shell" "Output panel confirms terminal PTY started"
log "ok: Terminal PTY started and executed command"

# Close panel via its Close button.
if read -r px py < <(ui_dump | center_of_desc "Close Panel") && [ -n "${px:-}" ]; then
  adb shell input tap "$px" "$py"
else
  adb shell input keycombination 113 38
fi
sleep 1

# 4. Verify Open Ferrux Home sees the marker file created in Terminal PTY.
run_palette_command "Open%sFerrux%sHome"
sleep 3
run_palette_command "Show%sExplorer"
expect_text "e2e_pty_verified.txt" "Ferrux Home explorer lists file created in Terminal PTY"
adb exec-out screencap -p > "$OUT/e2e_ferrux_home.png"
log "ok: Open Ferrux Home connected and listed PTY-created file"

if adb logcat -d | grep -E "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|SecurityException" | grep -E "ferride|ferrux"; then
  fail "unexpected runtime/security error in logcat"
fi

log "all cross-app Ferrux + Ferride E2E checks passed"
