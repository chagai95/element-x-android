#!/usr/bin/env bash
# =============================================================================
#  Matrix Chat — start a feature
# =============================================================================
#  Creates a feature branch off chagai/main and opens a changelog stub for it,
#  so the loop is always:   ./feature.sh "<name>"  ->  implement  ->  ./release.sh
#
#  Usage:
#      ./feature.sh "call reminders"        # -> branch chagai/feat/call-reminders
#      ./feature.sh --fix "login crash"     # -> branch chagai/fix/login-crash
#      ./feature.sh --finish                # merge the current feature branch back
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

BASE="chagai/main"
KIND="feat"
STUB="docs/chagai/in-progress.md"

if [ "${1:-}" = "--finish" ]; then
  CUR="$(git rev-parse --abbrev-ref HEAD)"
  [ "$CUR" != "$BASE" ] || { echo "already on ${BASE}; nothing to finish" >&2; exit 1; }
  git diff --quiet && git diff --cached --quiet || { echo "commit your work first" >&2; exit 1; }
  git checkout -q "$BASE"
  git merge --no-ff -q "$CUR" -m "merge: ${CUR}"
  echo "merged ${CUR} into ${BASE}. Next: ./release.sh --notes \"...\""
  exit 0
fi

case "${1:-}" in
  --fix)  KIND="fix";  shift;;
  --docs) KIND="docs"; shift;;
  -h|--help) sed -n '2,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0;;
esac

NAME="${1:-}"
[ -n "$NAME" ] || { echo 'usage: ./feature.sh "<feature name>"' >&2; exit 2; }

SLUG="$(printf '%s' "$NAME" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+|-+$//g')"
BRANCH="chagai/${KIND}/${SLUG}"

git diff --quiet && git diff --cached --quiet || { echo "working tree is dirty — commit or stash first" >&2; exit 1; }
git checkout -q "$BASE"
git checkout -q -b "$BRANCH"

mkdir -p "$(dirname "$STUB")"
cat > "$STUB" <<EOF
# In progress: ${NAME}

Branch: \`${BRANCH}\`
Started: $(date +%Y-%m-%d)

## What it should do

- ${NAME}

## Changelog entry (paste into ./release.sh --notes)

${NAME}
EOF

git add "$STUB"
git commit -q -m "docs(chagai): start \"${NAME}\""
echo "on branch ${BRANCH}"
echo "stub at ${STUB}"
echo
echo "when it works:  ./feature.sh --finish  &&  ./release.sh --notes \"${NAME}\""
