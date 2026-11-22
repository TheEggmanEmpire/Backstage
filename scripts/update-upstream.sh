#!/usr/bin/env bash
# Rebase the patch series and replace the pinned upstream snapshot:
#
#   scripts/update-upstream.sh [--ci] [--dry-run] [--test none|fast|full] <tag|branch|sha|latest-tag>
#   scripts/update-upstream.sh --continue [--test none|fast|full]
#   scripts/update-upstream.sh --abort
#
# Rebases the patch series onto <ref> with git's 3-way machinery (+rerere), rewrites patches/,
# replaces the pristine snapshot, records provenance, syncs the Flutter pin, verifies, and writes a report.
#
#   1. full-history bare mirror of upstream in.cache/dextop.git (ensure_cache; fetched on every run)
#   2. worktree build/upstream-rebase at the OLD pin; `git am --3way patches/*` (must apply to the old pin)
#   3. `git rebase --onto <new> <old>` with rerere enabled; recorded resolutions live in patches/.rr-cache
#      (committed), so a conflict resolved once replays automatically for teammates and CI
#   4. finish: format-patch <new>..HEAD -> patches/ (rewritten), git archive <new> -> upstream/dextop,
#      upstream/UPSTREAM.env rewritten, scripts/flutter-version.sh --sync, scripts/check-patches.sh
#   5. gates (--test): none = check-patches only; fast (default) = + glue symbol check (every upstream class the
#      glue in backstage/dextop-glue uses still exists); full = + scripts/test.sh (all suites) and a debug build
#   6. report: build/upstream-rebase.report.md (printed at the end): commits, hot spots, dropped patches, new rerere
#      resolutions, versionCode check, gate results and the next steps
#
# --dry-run changes nothing in this checkout: it rebases a throw-away clone (with a copy of patches/.rr-cache),
# skips over each conflicting patch to see the later ones too, runs the glue symbol check and writes
# build/upstream-dryrun.report.md. Exit 0: the bump would go through without manual work; 2: conflicts.
#
# On a conflict rerere cannot resolve the real bump stops with exit 2 and a resolvable worktree (conflicted files
# and marker regions are listed, and written to build/upstream-rebase.report.md for CI): fix files, `git add`, then
#   scripts/update-upstream.sh --continue      (or --abort)
# This command leaves changes uncommitted for inspection.
# Exit codes: 0 done (or nothing to do), 1 error or a failed gate, 2 conflict.
. "$(dirname "$0")/lib.sh"
UWT=$ROOT/build/upstream-rebase
STATE=$UWT.state
REPORT=$UWT.report.md
DRY_REPORT=$ROOT/build/upstream-dryrun.report.md
usage() { awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; }
patch_names() { patches | sed 's#.*/[0-9][0-9][0-9][0-9]-##'; }   # series without numbers (renumbering is not a change)
# --git-path is relative to the repository's directory for a plain clone (dry run): resolve it there.
in_rebase() { [ -d "$UWT" ] && (cd "$UWT" && [ -d "$(git rev-parse --git-path rebase-merge 2>/dev/null)" ]); }
unmerged() { git -C "$UWT" diff --name-only --diff-filter=U; }
# Conflict ids in patches/.rr-cache that carry a recorded resolution (postimage).
rr_resolved() { find "$PATCH_DIR/.rr-cache" -mindepth 2 -maxdepth 2 -name 'postimage*' 2>/dev/null | sed 's#/[^/]*$##' | sort -u; }
build_number() { sed -n 's/.*+\([0-9][0-9]*\)$/\1/p' <<<"$1"; }

# ---- glue symbol check ---------------------------------------------------------------------------------------------
# The glue (backstage/dextop-glue, compiled into Dextop's:app) uses Dextop's internal classes by name. A class that
# upstream renamed or removed only shows up as a compile error minutes into a Gradle build; this finds it in seconds:
# every capitalised identifier of the glue that the OLD upstream declares as a class/object/interface/typealias in
# android/ must still be declared by the NEW upstream. Prints the missing names (empty = fine).
decls() { git -C "$CACHE" grep -hoE '(class|object|interface|typealias) [A-Z][A-Za-z0-9_]*' "$1" -- android 2>/dev/null |
          sed 's/.* //' | sort -u; }
glue_missing() {  # old new
  local glue=$ROOT/backstage/dextop-glue/kotlin
  [ -d "$glue" ] || { echo "(no glue sources)"; return 0; }
  comm -23 <(comm -12 <(decls "$1") <(grep -rhoE '\b[A-Z][A-Za-z0-9_]{2,}\b' "$glue" | sort -u)) <(decls "$2")
}
glue_check_line() {  # old new -> one Markdown bullet; returns 1 when symbols are missing
  local missing; missing=$(glue_missing "$1" "$2")
  case "$missing" in
    "") echo "- glue symbol check: OK (every upstream class backstage/dextop-glue uses still exists)" ;;
    "(no glue sources)") echo "- glue symbol check: skipped (no backstage/dextop-glue/kotlin)" ;;
    *) echo "- **glue symbol check: FAILED** - upstream no longer declares: $(tr '\n' ' ' <<<"$missing")(renamed or" \
            "removed; adapt backstage/dextop-glue before building)"; return 1 ;;
  esac
}

# Files both upstream (old..new) and each patch touch: where conflicts and behaviour changes hide.
hot_spots() {  # old new
  local changed p name hits; changed=$(git -C "$CACHE" diff --name-only "$1" "$2" | sort)
  mapfile -t ps < <(patches)
  for p in "${ps[@]}"; do
    name=$(basename "$p")
    hits=$(comm -12 <(sed -n 's#^diff --git a/\(.*\) b/.*#\1#p' "$p" | sort -u) <(echo "$changed") | grep. || true)
    [ -z "$hits" ] && echo "- \`$name\`: none" || echo "- \`$name\`: $(sed 's/.*/`&`/' <<<"$hits" | paste -sd' ' -)"
  done
}

version_line() {  # new upstream version -> one bullet (versionCode = backstage*1000 + upstream build % 1000)
  local ob nb; ob=$(build_number "$OLD_VERSION") nb=$(build_number "$1")
  if [ -n "$ob" ] && [ -n "$nb" ] && [ $((nb % 1000)) -lt $((ob % 1000)) ]; then
    echo "- **versionCode check: upstream build $ob -> $nb lowers the Backstage versionCode** unless the Backstage" \
         "version in backstage/version.properties goes up with this bump (otherwise the APK installs as a downgrade)"
  else
    echo "- versionCode check: upstream build ${ob:-?} -> ${nb:-?}; bump the Backstage version for the next release anyway"
  fi
}

next_steps() {  # $1 = test level that ran
  cat <<'EOF'

## Next steps

1. Review: `git diff --stat -- upstream patches toolchain.env`, the compare link above and the hot spots: read what
   upstream changed in every file a patch touches (behaviour can change without a conflict).
2. If patches/.rr-cache has new entries: delete the entries of earlier bumps and commit the new ones with this bump.
3. If a conflict touched `lib/l10n/app_en.arb`: regenerate the English localizations in build/work/dextop
   (`flutter gen-l10n`), fold them into 0005 and re-export with scripts/export-patches.sh.
EOF
  [ "$1" = full ] || echo "4. Run the remaining gates: \`scripts/test.sh\` and \`BACKSTAGE_TARGET_PLATFORM=android-arm64 scripts/build-apk.sh debug\`."
  [ "$1" != full ] || echo "4. Gates ran here (--test full); still install the debug APK on a phone for the device checklist."
  cat <<'EOF'
5. Commit: `git add -A -f upstream patches toolchain.env && git commit` (`-f`: upstream's .gitignore files apply
   inside upstream/dextop). Release: bump the Backstage version, build, `verify-apk.sh --release`; the APK names become `backstage-<version>-...`.
EOF
}

# ---- finish a bump -------------------------------------------------------------------------------------------------
finish() {
  local new old_names dropped rr gates_ok=1 gate_lines=""; new=$(cat "$STATE")
  old_names=$(patch_names)
  rr=$(comm -13 <(cat "$STATE.rr" 2>/dev/null) <(rr_resolved) | grep -c . || true)
  rm -f "$PATCH_DIR"/[0-9][0-9][0-9][0-9]-*.patch
  git -C "$UWT" format-patch -q --zero-commit --no-signature --no-numbered --full-index \
      --start-number 1 -o "$PATCH_DIR" "$new..HEAD"
  # A commit that became empty (upstream took the change, or a resolution dropped it) leaves the series silently.
  dropped=$(comm -23 <(sort <<<"$old_names") <(patch_names | sort) | grep . || true)
  [ -z "$dropped" ] || warn "patches no longer in the series (empty after the rebase): $(tr '\n' ' ' <<<"$dropped")"
  rm -rf "$UPSTREAM_DIR"; mkdir -p "$UPSTREAM_DIR"
  git -C "$CACHE" archive "$new" | tar -x -C "$UPSTREAM_DIR"   # exact tree: ensure_cache neutralises export-* attributes
  local ref version; ref=$(git -C "$CACHE" describe --tags --always "$new")
  version=$(pubspec_version "$UPSTREAM_DIR")
  cat > "$UPSTREAM_ENV" <<ENV
UPSTREAM_URL=$UPSTREAM_URL
UPSTREAM_REF=$ref
UPSTREAM_COMMIT=$new
UPSTREAM_TREE=$(git -C "$CACHE" rev-parse "$new^{tree}")
UPSTREAM_DATE=$(git -C "$CACHE" log -1 --format=%cI "$new")
UPSTREAM_VERSION=$version
ENV
  git -C "$CACHE" worktree remove --force "$UWT"; rm -f "$STATE" "$STATE.rr"
  "$ROOT/scripts/flutter-version.sh" --sync >/dev/null
  if "$ROOT/scripts/check-patches.sh"; then gate_lines+="- check-patches: OK"$'\n'
  else gate_lines+="- **check-patches: FAILED**"$'\n'; gates_ok=0; fi
  if [ "$TEST" != none ]; then
    gate_lines+=$(glue_check_line "$OLD_COMMIT" "$new")$'\n' || gates_ok=0
  fi
  if [ "$TEST" = full ] && [ "$gates_ok" = 1 ]; then
    if "$ROOT/scripts/test.sh"; then gate_lines+="- scripts/test.sh (library, lint, flutter, android): OK"$'\n'
    else gate_lines+="- **scripts/test.sh: FAILED** (see the log above)"$'\n'; gates_ok=0; fi
    if [ "$gates_ok" = 1 ]; then
      if "$ROOT/scripts/build-apk.sh" debug --no-prepare; then gate_lines+="- debug build: OK"$'\n'
      else gate_lines+="- **debug build: FAILED**"$'\n'; gates_ok=0; fi
    fi
  fi
  {
    echo "# Upstream bump: Dextop $OLD_REF -> $ref"
    echo
    echo "- commit: \`$new\` (version $version; was $OLD_VERSION)"
    echo "- upstream commits: $(git -C "$CACHE" rev-list --count "$OLD_COMMIT..$new")"
    echo "- patches regenerated: $(patches | wc -l)"
    [ -z "$dropped" ] || echo "- **patches dropped (empty after the rebase): $(tr '\n' ' ' <<<"$dropped")** - check that upstream really took them"
    [ "$rr" = 0 ] || echo "- new conflict resolutions recorded by rerere: $rr (commit \`patches/.rr-cache\` with this bump)"
    echo "- toolchain: $(grep '^FLUTTER_VERSION=' "$TOOLCHAIN_ENV")"
    version_line "$version"
    echo "- compare: $UPSTREAM_URL/compare/$OLD_COMMIT...$new"
    echo; echo "## Hot spots (files upstream changed that a patch touches)"; echo
    hot_spots "$OLD_COMMIT" "$new"
    echo; echo "## Gates (--test $TEST)"; echo
    printf '%s' "$gate_lines"
    next_steps "$TEST"
  } > "$REPORT"
  echo; cat "$REPORT"; echo
  [ "$gates_ok" = 1 ] || die "bumped to $ref, but a gate failed (see $REPORT)"
  echo "Bumped upstream to $ref. Report: ${REPORT#$ROOT/}"
}

conflict_report() {  # files: stop with exit 2, a terminal summary and build/upstream-rebase.report.md
  local files=$1 subject; subject=$(git -C "$UWT" log -1 --format=%s REBASE_HEAD)
  echo "CONFLICT in $subject. Resolve in $UWT, git add, then: $0 --continue" >&2
  sed 's/^/  conflicted: /' <<<"$files" >&2
  {
    echo "# Upstream bump CONFLICT: patch \`$subject\`"
    echo
    echo "Rebasing the Backstage patch series from \`$OLD_COMMIT\` onto \`$(cat "$STATE")\` stopped."
    echo "Conflicted files (git rerere had no recorded resolution):"; echo; sed 's/^/- /' <<<"$files"; echo
    echo "Resolve locally: \`scripts/update-upstream.sh $(cat "$STATE")\`, fix the files in build/upstream-rebase,"
    echo "\`git add\`, \`scripts/update-upstream.sh --continue\`; commit patches/.rr-cache with the result."
    echo "Read each affected patch and the conflicted source before resolving it."
    # Only the marker regions (line: text): a combined diff of a big file buries them under auto-merged hunks.
    echo; echo "Conflict hunks (first 200 lines):"; echo; echo '```text'
    while IFS= read -r f; do
      echo "== $f"
      if [ -f "$UWT/$f" ] && grep -q '^<<<<<<< ' "$UWT/$f"; then
        awk '/^<<<<<<< /{s=1} s{print FNR": "$0} /^>>>>>>> /{s=0}' "$UWT/$f"
      else
        echo "(no conflict markers: modify/delete, rename or mode conflict; see git -C build/upstream-rebase status)"
      fi
    done <<<"$files" | head -n 200 || true   # SIGPIPE on long output must not trip set -e
    echo '```'
  } > "$REPORT"
  exit 2
}
check_markers() {  # a resolution (by hand, or replayed from a bad recording) must not carry conflict markers
  local markers
  markers=$(git -C "$UWT" diff --cached --check 2>/dev/null | grep 'conflict marker' || true)
  [ -z "$markers" ] || { sed 's/^/  /' <<<"$markers" >&2
    die "staged files in $UWT still contain conflict markers (resolve them, git add, then $0 --continue)"; }
}
continue_rebase() { GIT_EDITOR=true git -C "$UWT" "${BOT[@]}" rebase --continue; }
resume() {  # continue while rerere (recorded resolutions in patches/.rr-cache) resolved everything
  local files before
  while in_rebase; do
    files=$(unmerged)
    [ -z "$files" ] || conflict_report "$files"
    before=$(git -C "$UWT" rev-parse -q --verify REBASE_HEAD || true)
    echo "  auto-resolved by rerere: $(git -C "$UWT" log -1 --format=%s REBASE_HEAD)"
    check_markers
    if ! continue_rebase >"$UWT.continue.log" 2>&1 && in_rebase && [ -z "$(unmerged)" ] &&
       [ "$(git -C "$UWT" rev-parse -q --verify REBASE_HEAD || true)" = "$before" ]; then
      # No progress and nothing to resolve: never spin (CI would hang until its timeout).
      sed 's/^/  /' "$UWT.continue.log" >&2
      die "git rebase --continue made no progress in $UWT (see above); fix it there, then $0 --continue"
    fi
  done
  rm -f "$UWT.continue.log"
}

# ---- dry run -------------------------------------------------------------------------------------------------------
# Rebases a throw-away clone with its own copy of the recorded resolutions; nothing in this checkout changes (not
# even patches/.rr-cache). A conflicting patch is skipped so the later patches are tried as well.
dry_run() {  # old new target
  local old=$1 new=$2 target=$3 tmp files subject conflicts="" auto="" before rc=0 glue
  tmp=$(mktemp -d "${TMPDIR:-/tmp}/backstage-dryrun.XXXXXX")
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" EXIT
  git clone -q --shared --no-checkout "$CACHE" "$tmp/r"
  [ -d "$PATCH_DIR/.rr-cache" ] && cp -R "$PATCH_DIR/.rr-cache" "$tmp/r/.git/rr-cache"
  UWT=$tmp/r   # the rebase helpers act on $UWT
  git -C "$UWT" config rerere.enabled true; git -C "$UWT" config rerere.autoupdate true
  git -C "$UWT" "${BOT[@]}" checkout -q --detach "$old"
  mapfile -t ps < <(patches)
  ((${#ps[@]} == 0)) || git -C "$UWT" "${BOT[@]}" am -q --keep-cr --3way "${ps[@]}" >/dev/null 2>&1 ||
    die "series does not apply to the CURRENT pin $old (run check-patches.sh)"
  git -C "$UWT" "${BOT[@]}" rebase -q --onto "$new" "$old" >/dev/null 2>&1 || true
  while in_rebase; do
    subject=$(git -C "$UWT" log -1 --format=%s REBASE_HEAD 2>/dev/null || echo '?')
    before=$(git -C "$UWT" rev-parse -q --verify REBASE_HEAD || true)
    files=$(unmerged)
    if [ -n "$files" ]; then
      conflicts+="- \`$subject\`: $(sed 's/.*/`&`/' <<<"$files" | paste -sd' ' -)"$'\n'
      echo "  CONFLICT (would stop here): $subject" >&2
      GIT_EDITOR=true git -C "$UWT" "${BOT[@]}" rebase --skip >/dev/null 2>&1 || true
    else
      auto+="- \`$subject\`"$'\n'
      echo "  auto-resolved by rerere: $subject"
      continue_rebase >/dev/null 2>&1 || true
    fi
    if in_rebase && [ "$(git -C "$UWT" rev-parse -q --verify REBASE_HEAD || true)" = "$before" ]; then
      die "dry run: the rebase made no progress at $subject"
    fi
  done
  local after; after=$(git -C "$UWT" log --reverse --format=%s "$new..HEAD")
  mkdir -p "$(dirname "$DRY_REPORT")"
  {
    echo "# Upstream bump DRY RUN: Dextop $OLD_REF -> $(git -C "$CACHE" describe --tags --always "$new")"
    echo
    echo "Nothing in the checkout was changed. Target \`$target\` = \`$new\`" \
         "(version $(git -C "$CACHE" show "$new:pubspec.yaml" 2>/dev/null | sed -n 's/^version:[[:space:]]*//p' | head -n1 | tr -d '\r'))."
    echo
    echo "- upstream commits: $(git -C "$CACHE" rev-list --count "$old..$new") new, $(git -C "$CACHE" rev-list --count "$new..$old") pinned commit(s) not in the target"
    version_line "$(git -C "$CACHE" show "$new:pubspec.yaml" 2>/dev/null | sed -n 's/^version:[[:space:]]*//p' | head -n1 | tr -d '\r')"
    echo "- compare: $UPSTREAM_URL/compare/$old...$new"
    glue=$(glue_check_line "$old" "$new") || rc=1
    echo "$glue"
    echo; echo "## Conflicts rerere cannot resolve"; echo
    if [ -n "$conflicts" ]; then printf '%s' "$conflicts"; echo
      echo "Each conflicting patch was skipped to try the later ones; later results assume it is resolved like before."
    else echo "None."; fi
    echo; echo "## Replayed from patches/.rr-cache"; echo
    [ -n "$auto" ] && printf '%s' "$auto" || echo "None."
    local dropped; dropped=$(comm -23 <(patch_names | sed 's/\.patch$//' | sort) <(sed 's/[^A-Za-z0-9._-]/-/g' <<<"$after" | sort) | grep . || true)
    if [ -n "$dropped" ] && [ -z "$conflicts" ]; then
      echo; echo "## Patches that would leave the series (empty after the rebase)"; echo; sed 's/.*/- `&`/' <<<"$dropped"
    fi
    echo; echo "## Hot spots (files upstream changed that a patch touches)"; echo
    hot_spots "$old" "$new"
    echo; echo "## Next step"; echo
    if [ -n "$conflicts" ]; then
      echo "Run \`scripts/update-upstream.sh $target\`, resolve the conflicts above in build/upstream-rebase"
      echo "then \`scripts/update-upstream.sh --continue\`."
    else
      echo "Run \`scripts/update-upstream.sh $target\` (no manual conflict work expected)."
    fi
  } > "$DRY_REPORT"
  echo; cat "$DRY_REPORT"
  if [ -n "$conflicts" ]; then exit 2; fi
  exit "$rc"
}

# ---- arguments -----------------------------------------------------------------------------------------------------
OLD_COMMIT=$UPSTREAM_COMMIT OLD_REF=$UPSTREAM_REF OLD_VERSION=$UPSTREAM_VERSION
DRY=0 TEST=fast mode=bump target=""
while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --dry-run) DRY=1 ;;
    --ci)
      export GIT_TERMINAL_PROMPT=0 GIT_EDITOR=true GIT_SEQUENCE_EDITOR=true
      exec </dev/null ;;

    --test) [ $# -ge 2 ] || die "--test needs a level (none, fast or full)"; TEST=$2; shift ;;
    --test=*) TEST=${1#--test=} ;;
    --continue) mode=continue ;;
    --abort) mode=abort ;;
    -*) die "unknown option $1 (see --help)" ;;
    *) [ -z "$target" ] || die "more than one ref given ($target, $1)"; target=$1 ;;
  esac
  shift
done
case "$TEST" in none|fast|full) ;; *) die "--test must be none, fast or full (got '$TEST')" ;; esac
case "$mode" in
  abort) git -C "$UWT" rebase --abort 2>/dev/null || true; git -C "$CACHE" worktree remove --force "$UWT" 2>/dev/null || true
         rm -f "$STATE" "$STATE.rr" "$UWT.continue.log"; exit 0 ;;
  continue) [ "$DRY" = 0 ] || die "--dry-run and --continue do not go together"
            [ -f "$STATE" ] || die "no bump in progress"
            if in_rebase; then check_markers; continue_rebase || true; fi
            resume; finish; exit 0 ;;
esac
[ -n "$target" ] || { usage; exit 1; }
ensure_cache
if [ "$target" = latest-tag ]; then
  target=$(git -C "$CACHE" tag -l | grep -E '^v?[0-9]+(\.[0-9]+)*$' | sort -V | tail -n1)
  [ -n "$target" ] || die "no version tags upstream"
  if [ -n "${BACKSTAGE_EXPECT_UPSTREAM_TAG:-}" ] && [ "$target" != "$BACKSTAGE_EXPECT_UPSTREAM_TAG" ]; then
    die "latest upstream tag changed during discovery; retry with the current tag"
  fi
  log "latest tag: $target"
fi
old=$UPSTREAM_COMMIT
new=$(git -C "$CACHE" rev-parse -q --verify "$target^{commit}" || git -C "$CACHE" rev-parse -q --verify "upstream/$target^{commit}") ||
  die "unknown ref $target"
[ "$DRY" = 1 ] || rm -f "$REPORT"
if [ "$new" = "$old" ]; then echo "Already at $target ($new)."; exit 0; fi
if git -C "$CACHE" merge-base --is-ancestor "$new" "$old" && [ "${BACKSTAGE_ALLOW_DOWNGRADE:-0}" != 1 ]; then
  echo "$target ($new) is an ancestor of the pinned $UPSTREAM_REF; nothing to do (BACKSTAGE_ALLOW_DOWNGRADE=1 forces it)."
  exit 0
fi
"$ROOT/scripts/check-patches.sh" >/dev/null || die "current pin is inconsistent; fix before bumping"
if [ "$DRY" = 1 ]; then dry_run "$old" "$new" "$target"; fi
if [ -f "$STATE" ]; then  # drop an unfinished bump; `rebase --abort` also clears its unresolved rerere preimages
  warn "discarding the unfinished bump to $(cat "$STATE") in $UWT"
  if in_rebase; then git -C "$UWT" rebase --abort >/dev/null 2>&1 || true; fi
fi
git -C "$CACHE" worktree prune; rm -rf "$UWT" "$UWT.continue.log"
git -C "$CACHE" worktree add -q --detach "$UWT" "$old"
git -C "$UWT" config rerere.enabled true; git -C "$UWT" config rerere.autoupdate true
mkdir -p "$PATCH_DIR/.rr-cache"; [ -L "$CACHE/rr-cache" ] || rm -rf "$CACHE/rr-cache"
ln -sfn "$PATCH_DIR/.rr-cache" "$CACHE/rr-cache"   # recorded conflict resolutions are committed with the series
mapfile -t ps < <(patches)
((${#ps[@]} == 0)) || git -C "$UWT" "${BOT[@]}" am -q --keep-cr --3way "${ps[@]}" ||
  die "series does not apply to the CURRENT pin $old (run check-patches.sh)"
echo "$new" > "$STATE"; rr_resolved > "$STATE.rr"
ahead=$(git -C "$CACHE" rev-list --count "$old..$new") behind=$(git -C "$CACHE" rev-list --count "$new..$old")
echo "Upstream $(git -C "$CACHE" describe --tags --always "$old") -> $(git -C "$CACHE" describe --tags --always "$new"):" \
     "$ahead new upstream commit(s)$([ "$behind" = 0 ] || echo ", $behind pinned commit(s) not in the target (downgrade/rewrite)")"
echo "Files touched by both upstream and our series:"; comm -12 <(git -C "$CACHE" diff --name-only "$old" "$new" | sort) <(git -C "$UWT" diff --name-only "$old" HEAD | sort) | sed 's/^/  /'
git -C "$UWT" "${BOT[@]}" rebase -q --onto "$new" "$old" || true
resume
finish
