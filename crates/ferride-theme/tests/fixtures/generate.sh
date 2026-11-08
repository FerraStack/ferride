#!/usr/bin/env bash
# Regenerates tests/fixtures/vscode/<theme>/<sample>.tsv: what VS Code renders
# for each token (VS Code's TextMate grammars + vscode-textmate's theme engine).
#
# Usage: generate.sh <node_modules> <grammars dir>
#   node_modules: `npm install vscode-textmate@9 vscode-oniguruma@2` (anywhere)
#   grammars dir: *.tmLanguage.json from github.com/microsoft/vscode/tree/main/extensions
#                 (rust, javascript, typescript-basics, python, go, css, html,
#                 markdown-basics, json, cpp (c + cpp), java, shellscript, sql)
set -euo pipefail
NODE_MODULES="$1"; GRAMMARS="$2"; R="$(cd "$(dirname "$0")/../../../.." && pwd)"
OUT="$R/crates/ferride-theme/tests/fixtures/vscode"
S="$R/crates/ferride-syntax/samples"
declare -A SCOPE=(
  [main.rs]=source.rust [app.js]=source.js [app.ts]=source.ts [stats.py]=source.python
  [main.go]=source.go [style.css]=source.css [index.html]=text.html.basic [README.md]=text.html.markdown
  [package.json]=source.json [sample.c]=source.c [sample.cpp]=source.cpp [Main.java]=source.java
  [query.sql]=source.sql [script.sh]=source.shell
)
for theme in dracula one-dark-pro night-owl; do
  mkdir -p "$OUT/$theme"
  for f in "${!SCOPE[@]}"; do
    node "$R/crates/ferride-theme/tests/fixtures/generate.mjs" "$NODE_MODULES" "$GRAMMARS" "${SCOPE[$f]}" \
      "$R/crates/ferride-theme/tests/fixtures/themes/$theme.json" "$S/$f" |
      python3 -c '
import sys, json
print("# line\tstart\tend\tforeground\tfontStyle\ttext\tscope (VS Code)")
for t in json.load(sys.stdin):
    print("\t".join(map(str, [t["line"], t["start"], t["end"], t["fg"], t["fontStyle"], json.dumps(t["text"]), t["scopes"][-1]])))
' > "$OUT/$theme/$f.tsv"
  done
done
