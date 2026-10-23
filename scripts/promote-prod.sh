#!/usr/bin/env bash
# Promote a commit on main to production (ADR-0006): moves every prod pin in infra/argocd/envs/prod to <commit>,
# checks the GitOps policy, and opens a pull request listing what changes in prod. Merging it deploys.
#   scripts/promote-prod.sh [<commit>]     (default: the current origin/main)
#   DRY_RUN=1 scripts/promote-prod.sh ...  (edit and check only; no branch, commit or PR)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PROD_DIR="infra/argocd/envs/prod"

git fetch --quiet origin main
TARGET=$(git rev-parse --verify "${1:-origin/main}^{commit}")
if ! git merge-base --is-ancestor "$TARGET" origin/main; then
  echo "error: $TARGET is not on origin/main; only commits that passed review on main can reach prod" >&2
  exit 1
fi

CURRENT=$(grep -hoE '(targetRevision|revision): [0-9a-f]{40}' "$PROD_DIR"/*.yaml | awk '{print $2}' | sort -u)
if [ "$(wc -l <<<"$CURRENT")" -ne 1 ]; then
  echo "error: prod pins disagree today ($CURRENT); fix that first" >&2
  exit 1
fi
if [ "$CURRENT" = "$TARGET" ]; then
  echo "prod is already pinned to $TARGET"
  exit 0
fi

for f in "$PROD_DIR"/*.yaml; do
  sed -i.bak -E "s/((targetRevision|revision): )$CURRENT/\\1$TARGET/" "$f" && rm -f "$f.bak"
done
python3 scripts/gitops_policy.py

CHANGES=$(git log --oneline --no-merges "$CURRENT..$TARGET")
echo "Promoting ${CURRENT:0:12} -> ${TARGET:0:12}:"
echo "$CHANGES"

if [ -n "${DRY_RUN:-}" ]; then
  echo "(dry run: pins edited locally, nothing committed)"
  exit 0
fi

BRANCH="promote-prod-${TARGET:0:12}"
git switch --quiet -c "$BRANCH"
git commit --quiet -m "chore(release): promote ${TARGET:0:12} to prod" -- "$PROD_DIR"
git push --quiet -u origin "$BRANCH"
gh pr create --base main --head "$BRANCH" --title "Promote ${TARGET:0:12} to prod" --body "## Promotion

Moves every production pin in \`$PROD_DIR\` from \`$CURRENT\` to \`$TARGET\`. ArgoCD syncs prod to this commit when the PR merges.

## Changes reaching prod

\`\`\`
$CHANGES
\`\`\`

## Checks

- \`$TARGET\` is on \`main\`
- GitOps policy passes (one pin for every prod source; dev/staging untouched)"
