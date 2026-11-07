#!/usr/bin/env bash
# Pushes CI screenshots to the orphan branch `ci-screenshots` under
# pr-<number>/<run_id>/ and creates or updates one PR comment (found by a
# hidden marker) that embeds them, light and dark side by side.
# Env: GH_TOKEN, REPO (owner/name), PR, RUN_ID, SHA. Usage: post-screenshots.sh <dir>
set -euo pipefail
src="$(cd "$1" && pwd)"
marker='<!-- ferra-screenshots -->'
dest="pr-${PR}/${RUN_ID}"

shopt -s nullglob
pngs=("$src"/*.png)
if [ ${#pngs[@]} -eq 0 ]; then
  echo "No screenshots found in $src"
  exit 0
fi

work="$(mktemp -d)"
git -C "$work" init -q
git -C "$work" config user.name "github-actions[bot]"
git -C "$work" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git -C "$work" remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"
if git -C "$work" fetch -q --depth 1 origin ci-screenshots 2>/dev/null; then
  git -C "$work" checkout -q -b ci-screenshots FETCH_HEAD
else
  git -C "$work" checkout -q --orphan ci-screenshots
  printf 'Screenshots posted by CI for pull requests. Not source code.\n' > "$work/README.md"
  git -C "$work" add README.md
fi
mkdir -p "$work/$dest"
cp "${pngs[@]}" "$work/$dest/"
git -C "$work" add "$dest"
git -C "$work" commit -q -m "Screenshots for PR #${PR} (run ${RUN_ID})"
for attempt in 1 2 3; do
  if git -C "$work" push -q origin ci-screenshots; then break; fi
  # Another run pushed first: rebase onto it and retry.
  git -C "$work" pull -q --rebase origin ci-screenshots
done

base="https://raw.githubusercontent.com/${REPO}/ci-screenshots/${dest}"
img() { [ -f "$src/$1" ] && echo "<img src=\"${base}/$1\" width=\"260\">" || echo "—"; }

# Pair <screen>_light.png / <screen>_dark.png; anything else gets its own row.
mapfile -t screens < <(for f in "${pngs[@]}"; do basename "$f" .png; done | sed -E 's/_(light|dark)$//' | sort -u)
body="${marker}
### 📸 Screenshots

Commit ${SHA:0:7} · [run ${RUN_ID}](https://github.com/${REPO}/actions/runs/${RUN_ID})

| Screen | Light | Dark |
|---|---|---|"
for s in "${screens[@]}"; do
  if [ -f "$src/${s}_light.png" ] || [ -f "$src/${s}_dark.png" ]; then
    body+=$'\n'"| \`${s}\` | $(img "${s}_light.png") | $(img "${s}_dark.png") |"
  else
    body+=$'\n'"| \`${s}\` | $(img "${s}.png") | |"
  fi
done
body+=$'\n\n'"<sub>Roborazzi (JVM, fake data) + emulator (real APK, API 34). Images live on the \`ci-screenshots\` branch.</sub>"

existing="$(gh api "repos/${REPO}/issues/${PR}/comments" --paginate \
  --jq ".[] | select(.body | contains(\"${marker}\")) | .id" | head -n 1)"
if [ -n "$existing" ]; then
  gh api -X PATCH "repos/${REPO}/issues/comments/${existing}" -f body="$body" >/dev/null
  echo "Updated comment $existing"
else
  gh api -X POST "repos/${REPO}/issues/${PR}/comments" -f body="$body" >/dev/null
  echo "Created comment"
fi
