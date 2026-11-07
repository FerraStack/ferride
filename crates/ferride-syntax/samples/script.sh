#!/usr/bin/env bash
# Counts words in the given files and prints the top N.
set -euo pipefail

TOP="${TOP:-3}"
readonly OUT_DIR="$HOME/.cache/wc"

usage() {
  echo "usage: $(basename "$0") [-n N] FILE..." >&2
  exit 1
}

while getopts "n:" opt; do
  case "$opt" in
    n) TOP="$OPTARG" ;;
    *) usage ;;
  esac
done
shift $((OPTIND - 1))

[[ $# -gt 0 ]] || usage
mkdir -p "$OUT_DIR"
for f in "$@"; do
  tr -s '[:space:]' '\n' < "$f"
done | sort | uniq -c | sort -rn | head -n "$TOP" > "$OUT_DIR/top.txt"
cat "$OUT_DIR/top.txt"
