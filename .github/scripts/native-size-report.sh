#!/usr/bin/env bash
# What users download: the per-ABI release APKs (GitHub), the Play download
# size estimated by bundletool from the App Bundle for an arm64 phone, and
# what the Rust core and each tree-sitter grammar contribute.
#
# Usage: native-size-report.sh <repo root>
# Env: ANDROID_NDK_HOME (llvm-size), BUNDLETOOL (path to bundletool-all.jar).
# Prints Markdown to stdout and appends it to $GITHUB_STEP_SUMMARY.
set -uo pipefail
root="$(cd "$1" && pwd)"
ndk_bin="${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin"
size_tool="${SIZE_TOOL:-$ndk_bin/llvm-size}"
build="${BUILD_DIR:-$root/target/aarch64-linux-android/release/build}"
apk_dir="$root/android/app/build/outputs/apk/play/release"
aab="$root/android/app/build/outputs/bundle/playRelease/app-play-release.aab"
report="$(mktemp)"
work="$(mktemp -d)"

mib() { awk -v b="$1" 'BEGIN { printf "%.1f", b / 1048576 }'; }
kib() { awk -v b="$1" 'BEGIN { printf "%.0f", b / 1024 }'; }
# Uncompressed bytes of the APK entries matching an ERE.
entries() { unzip -lv "$1" | awk -v re="$2" '$NF ~ re { s += $1 } END { print s + 0 }'; }
# text + data + bss of the given objects, in bytes.
obj_size() { "$size_tool" "$@" | awk 'NR > 1 { s += $1 + $2 + $3 } END { print s + 0 }'; }

{
  echo "### Release size (play flavor)"
  echo
  echo "| APK | MiB | libferride_ffi.so | dex | other |"
  echo "|---|---:|---:|---:|---:|"
  for apk in "$apk_dir"/*.apk; do
    [ -f "$apk" ] || continue
    total=$(stat -c %s "$apk")
    so=$(entries "$apk" 'libferride_ffi\.so$')
    dex=$(entries "$apk" '\.dex$')
    echo "| \`$(basename "$apk")\` | $(mib "$total") | $(mib "$so") | $(mib "$dex") (uncompressed) | $(mib $((total - so)))* |"
  done
  echo
  echo "<sub>* APK size minus the (stored, uncompressed) native library.</sub>"
  echo
  if [ -f "$aab" ]; then
    echo "App Bundle: $(mib "$(stat -c %s "$aab")") MiB."
    if [ -n "${BUNDLETOOL:-}" ] && [ -f "$BUNDLETOOL" ]; then
      cat > "$work/arm64.json" <<'JSON'
{ "supportedAbis": ["arm64-v8a"], "supportedLocales": ["en-US"], "screenDensity": 420, "sdkVersion": 34 }
JSON
      if java -jar "$BUNDLETOOL" build-apks --bundle="$aab" --output="$work/app.apks" > "$work/bt.log" 2>&1 &&
        java -jar "$BUNDLETOOL" get-size total --apks="$work/app.apks" --device-spec="$work/arm64.json" > "$work/size.csv" 2>> "$work/bt.log"; then
        IFS=, read -r min max < <(tail -n 1 "$work/size.csv")
        echo "Play download for an arm64 phone (bundletool \`get-size total\`, compressed): **$(mib "$min")–$(mib "$max") MiB**."
      else
        echo "bundletool failed:"
        echo '```'
        tail -n 15 "$work/bt.log"
        echo '```'
      fi
    fi
    echo
  fi
  echo "Grammars (arm64 objects as linked, KiB):"
  echo
  echo "| Grammar | KiB |"
  echo "|---|---:|"
} >> "$report"

total=0
rows=()
# Newest build dir per grammar crate (the cache can hold older ones).
declare -A newest
for dir in "$build"/tree-sitter-*/out; do
  [ -d "$dir" ] || continue
  crate="$(basename "$(dirname "$dir")" | sed -E 's/-[0-9a-f]{16}$//')"
  [ "$crate" = tree-sitter ] && continue
  if [ -z "${newest[$crate]:-}" ] || [ "$dir" -nt "${newest[$crate]}" ]; then
    newest[$crate]="$dir"
  fi
done
for crate in "${!newest[@]}"; do
  mapfile -t objs < <(find "${newest[$crate]}" -name '*.o')
  [ ${#objs[@]} -eq 0 ] && continue
  bytes="$(obj_size "${objs[@]}")"
  total=$((total + bytes))
  rows+=("$bytes|$crate|$(kib "$bytes")")
done
printf '%s\n' "${rows[@]}" | sort -t'|' -k1,1nr | cut -d'|' -f2- | sed -E 's/\|/ | /g; s/^/| /; s/$/ |/' >> "$report"
echo "| **total** | **$(kib "$total")** |" >> "$report"

cat "$report"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && cat "$report" >> "$GITHUB_STEP_SUMMARY"
rm -rf "$report" "$work"
