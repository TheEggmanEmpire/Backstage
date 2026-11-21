#!/usr/bin/env bash
# CI gate: snapshot integrity + every patch applies exactly, in order, to the pinned snapshot + hooks inject.
#
#   scripts/check-patches.sh
#
# Prepares a throw-away work tree (scripts/prepare-work-tree.sh into a temp dir, DroidDeck skipped), which
# enforces the tree-hash drift guard on upstream/dextop and applies patches/ with `git am` (no fuzz, no 3-way).
# Also checks patch file names (NNNN-name.patch) and that each patch has a commit message. Changes nothing.
. "$(dirname "$0")/lib.sh"
fail=0
mapfile -t ps < <(patches)
for p in "${ps[@]}"; do
  name=$(basename "$p")
  [[ "$name" =~ ^[0-9]{4}-[A-Za-z0-9._-]+\.patch$ ]] || { echo "FAIL    $name: name must look like NNNN-short-name.patch" >&2; fail=1; }
  grep -q '^Subject: ' "$p" || { echo "FAIL    $name: not a git format-patch file (no Subject:)" >&2; fail=1; }
done
[ "$fail" = 0 ] || die "patch naming problems"
stray=$(find "$PATCH_DIR" -maxdepth 1 -name '*.patch' ! -name '[0-9][0-9][0-9][0-9]-*.patch')
[ -z "$stray" ] || die "stray patch files (not applied): $stray"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/backstage-check-patches.XXXXXX"); trap 'rm -rf "$tmp"' EXIT
BACKSTAGE_WORK=$tmp BACKSTAGE_SKIP_DROIDDECK=1 ANDROID_HOME=${ANDROID_HOME:-none} FLUTTER_ROOT=${FLUTTER_ROOT:-none} \
  "$ROOT/scripts/prepare-work-tree.sh" >/dev/null 2>"$tmp.log" || { cat "$tmp.log" >&2; rm -f "$tmp.log"; die "series check failed"; }
rm -f "$tmp.log"
for p in "${ps[@]}"; do echo "OK      $(basename "$p")"; done
echo "OK: upstream/dextop == $UPSTREAM_REF (tree ${UPSTREAM_TREE:0:12}); ${#ps[@]} patches apply exactly; hooks injected"
