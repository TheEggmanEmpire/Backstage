#!/usr/bin/env bash
# Regenerate patches/NNNN-*.patch from the commits of a prepared work tree (developer loop).
#
#   scripts/export-patches.sh [--force] [REPO]          (default: build/work/dextop)
#
# Every commit in upstream..HEAD except the "backstage build hooks" commit becomes one patch, numbered in
# order (git format-patch --zero-commit --no-signature --no-numbered --full-index, the same format
# scripts/update-upstream.sh writes; file names come from the commit subjects). Typical loop:
#   scripts/prepare-work-tree.sh
#   cd build/work/dextop && <edit> && git commit --fixup=<patch commit>
#   git -c sequence.editor=: rebase -i --autosquash upstream      # keep the hooks commit last
#   cd - && scripts/export-patches.sh && scripts/check-patches.sh
# New behaviour goes into a new commit (= a new patch); keep subjects short slugs (they name the files).
. "$(dirname "$0")/lib.sh"
force=false
repo=$WORK
repo_set=false
while (($#)); do
  case "$1" in
    --force) force=true; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    -*) die "unknown argument $1" ;;
    *) $repo_set && die "only one work tree may be exported"; repo=$1; repo_set=true; shift ;;
  esac
done
[ -d "$repo/.git" ] || die "$repo is not a prepared work tree (run scripts/prepare-work-tree.sh)"
# Preparation clears this marker before applying patches and writes it only on success.
[ -n "$(git -C "$repo" config --get backstage.prepared 2>/dev/null || true)" ] ||
  die "$repo is not fully prepared (run scripts/prepare-work-tree.sh)"
git -C "$repo" rev-parse -q --verify refs/tags/upstream >/dev/null || die "$repo has no 'upstream' tag"
# Mode-only changes (DroidDeck scripts made executable by prepare-work-tree.sh) are not uncommitted work.
[ -z "$(git -C "$repo" -c core.fileMode=false status --porcelain --untracked-files=no)" ] ||
  die "$repo has uncommitted changes; commit them first"
tmp=$(mktemp -d); trap 'rm -rf "$tmp"' EXIT
n=0
while read -r c subject; do
  [ "$subject" = "backstage build hooks (scripts/inject.sh)" ] && continue
  n=$((n + 1))
  git -C "$repo" format-patch -q -1 --zero-commit --no-signature --no-numbered --full-index \
    --start-number "$n" -o "$tmp" "$c"
done < <(git -C "$repo" log --reverse --format='%H %s' upstream..HEAD)
# Check before copying OR deleting: a truncated tree must leave the whole series intact.
mapfile -t ps < <(patches)
if ((n < ${#ps[@]})) && ! $force; then
  die "$repo has $n patch commits but patches/ has ${#ps[@]} patches; refusing to truncate the series (use --force for intentional removals)"
fi
changed=0
for f in "$tmp"/*.patch; do
  [ -e "$f" ] || continue
  cmp -s "$f" "$PATCH_DIR/$(basename "$f")" || { cp "$f" "$PATCH_DIR/"; log "wrote patches/$(basename "$f")"; changed=$((changed + 1)); }
done
for p in "${ps[@]}"; do
  [ -e "$tmp/$(basename "$p")" ] || { rm -f "$p"; log "removed patches/$(basename "$p") (no commit produced it)"; changed=$((changed + 1)); }
done
log "$n patch(es) exported, $changed file(s) changed"
