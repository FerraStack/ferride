#!/usr/bin/env bash
# Reports what the Rust core costs in the APK: libferride_ffi.so per ABI, the
# APK, and each tree-sitter grammar's compiled objects (arm64, as linked).
# With COMPARE_OS=1 it also recompiles every grammar with -Os to show what
# optimizing the parsers for size would save.
# Usage: native-size-report.sh <repo root>   (needs ANDROID_NDK_HOME)
set -euo pipefail
root="$(cd "$1" && pwd)"
ndk_bin="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin"
size_tool="$ndk_bin/llvm-size"
out="${GITHUB_STEP_SUMMARY:-/dev/stdout}"

kib() { awk -v b="$1" 'BEGIN { printf "%.0f", b / 1024 }'; }
# text + data + bss of the given objects, in bytes.
obj_size() { "$size_tool" "$@" | awk 'NR > 1 { s += $1 + $2 + $3 } END { print s + 0 }'; }

{
  echo "### Native size report"
  echo
  echo "| File | KiB |"
  echo "|---|---:|"
  for so in "$root"/android/app/build/rust/jniLibs/*/libferride_ffi.so; do
    echo "| \`$(basename "$(dirname "$so")")/libferride_ffi.so\` | $(kib "$(stat -c %s "$so")") |"
  done
  apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
  [ -f "$apk" ] && echo "| \`app-debug.apk\` | $(kib "$(stat -c %s "$apk")") |"
  echo
  echo "Grammars (arm64 objects as linked, opt-level 3$([ "${COMPARE_OS:-}" = 1 ] && echo '; -Os recompiled for comparison')):"
  echo
  if [ "${COMPARE_OS:-}" = 1 ]; then
    echo "| Grammar | KiB (-O3) | KiB (-Os) |"
    echo "|---|---:|---:|"
  else
    echo "| Grammar | KiB |"
    echo "|---|---:|"
  fi
} >> "$out"

build="$root/target/aarch64-linux-android/release/build"
total=0
rows=()
for dir in "$build"/tree-sitter-*/out; do
  crate="$(basename "$(dirname "$dir")" | sed -E 's/-[0-9a-f]{16}$//')"
  [ "$crate" = tree-sitter ] && continue
  mapfile -t objs < <(find "$dir" -name '*.o')
  [ ${#objs[@]} -eq 0 ] && continue
  bytes="$(obj_size "${objs[@]}")"
  total=$((total + bytes))
  row="$bytes|$crate|$(kib "$bytes")"
  if [ "${COMPARE_OS:-}" = 1 ]; then
    src="$(find "$HOME/.cargo/registry/src" -maxdepth 1 -mindepth 1 -type d | head -1)"
    ver_dir="$(find "$src" -maxdepth 1 -type d -name "$crate-[0-9]*" | sort -V | tail -1)"
    tmp="$(mktemp -d)"
    for c in $(find "$ver_dir" -path '*/src/*' \( -name parser.c -o -name scanner.c \) -not -path '*/node_modules/*'); do
      d="$(dirname "$c")"
      "$ndk_bin/aarch64-linux-android30-clang" -Os -fPIC -c -w -I "$d" "$c" -o "$tmp/$(echo "$c" | md5sum | cut -c1-8).o"
    done
    os_bytes="$(obj_size "$tmp"/*.o)"
    rm -rf "$tmp"
    row+="|$(kib "$os_bytes")"
  fi
  rows+=("$row")
done
printf '%s\n' "${rows[@]}" | sort -t'|' -k1,1nr | cut -d'|' -f2- | sed -E 's/^/| /; s/\|/ | /g; s/$/ |/' >> "$out"
echo "| **total** | **$(kib "$total")** |" >> "$out"
