#!/usr/bin/env bash
# Build the patched Dextop work tree that every build/test uses.
#
#   scripts/prepare-work-tree.sh [--mode debug|release|profile] [--no-droiddeck]
#
# snapshot -> $WORK, then patches/ applied STRICTLY (git am: no fuzz, no 3-way; the series must match the
# pinned snapshot exactly), then the idempotent Gradle hooks (scripts/inject.sh). $WORK (default
# build/work/dextop, override with BACKSTAGE_WORK) is its own git repo, so `git apply` never resolves paths
# against the outer repo, and `git reset --hard` only rewrites files whose content changed (Gradle/CMake/
# Flutter incremental state and build/ survive). History in $WORK: tag `upstream` = pristine snapshot, one
# commit per patch (edit + scripts/export-patches.sh to change the series), then "backstage build hooks".
#
# Drift guard: the snapshot's git tree id must equal UPSTREAM_TREE (upstream/UPSTREAM.env); upstream/dextop
# is never edited by hand. Afterwards (every run): DroidDeck session scripts upstream keeps out of git, and
# android/local.properties (sdk.dir, flutter.sdk, build mode, version).
#
# Safety: $WORK must be empty or a work tree made by this script (never /, $HOME or the repository). The
# marker `git config backstage.prepared` is dropped before a re-prepare and written only after every step
# succeeded (build-apk.sh/test.sh --no-prepare check it). Before a re-prepare resets $WORK, commits that are
# not part of the prepared series and uncommitted edits of tracked files are saved as refs/backstage/saved/*
# (a warning names them; `git -C build/work/dextop log refs/backstage/saved/<name>`), never dropped silently.
. "$(dirname "$0")/lib.sh"

mode="${BACKSTAGE_BUILD_MODE:-debug}"
droiddeck=true
[ "${BACKSTAGE_SKIP_DROIDDECK:-0}" = 1 ] && droiddeck=false
while (($#)); do
  case "$1" in
    --mode) mode="$2"; shift 2 ;;
    --no-droiddeck) droiddeck=false; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
case "$mode" in debug|release|profile) ;; *) die "--mode must be debug, release or profile" ;; esac
[ -f "$UPSTREAM_DIR/pubspec.yaml" ] || die "upstream/dextop is missing"

HOOKS_SUBJECT="backstage build hooks (scripts/inject.sh)"
key=$(series_key)
guard_work_dir "$WORK"
mkdir -p "$WORK"; WORK=$(cd "$WORK" && pwd -P); cd "$WORK"
if [ ! -d .git ]; then git init -q -b backstage; fi
git config backstage.work true
g() { git "${BOT[@]}" "$@"; }
# Content changes of tracked files only: the DroidDeck sync below makes some tracked scripts executable, and
# that mode change alone must neither force a re-prepare nor count as local work.
tracked_changes() { git -c core.fileMode=false status --porcelain --untracked-files=no; }

# Keeps a developer's work in $WORK reachable before the reset below: commits beyond the prepared series and
# uncommitted edits of tracked files. Untracked build outputs are never touched.
save_local_work() {
  local stamp prepared_head wip
  git rev-parse -q --verify HEAD >/dev/null || return 0
  stamp=$(date -u +%Y%m%dT%H%M%SZ)
  prepared_head=$(git config --get backstage.head || true)
  if [ -n "$prepared_head" ] && [ -n "$(git rev-list -n1 HEAD --not "$prepared_head" -- 2>/dev/null)" ]; then
    git update-ref "refs/backstage/saved/$stamp-commits" HEAD
    warn "$WORK has commits that are not in patches/: saved as refs/backstage/saved/$stamp-commits (scripts/export-patches.sh exports commits)"
  fi
  if [ -n "$(tracked_changes)" ]; then
    wip=$(g stash create "prepare-work-tree $stamp" 2>/dev/null || true)
    if [ -n "$wip" ]; then
      git update-ref "refs/backstage/saved/$stamp-uncommitted" "$wip"
      warn "$WORK has uncommitted changes to tracked files: saved as refs/backstage/saved/$stamp-uncommitted"
    fi
  fi
}

# Up to date = prepared from this series, no edits, AND the hooks name this checkout's backstage/ (inject.sh --check):
# the marker alone passes a tree another checkout prepared (shared BACKSTAGE_WORK) or one reset to `patched` by hand.
hooks_ok() { "$ROOT/scripts/inject.sh" --check "$WORK" 2>/dev/null; }
if [ "$(git config --get backstage.prepared || true)" = "$key" ] && [ -z "$(tracked_changes)" ] && hooks_ok; then
  log "work tree up to date: $WORK"
else
  if [ "$(git config --get backstage.prepared || true)" = "$key" ] && [ -z "$(tracked_changes)" ]; then
    warn "$WORK: the Gradle hooks are missing or point at another checkout than $ROOT; preparing again"
  fi
  save_local_work
  # Not prepared until every step below has succeeded.
  git config --unset backstage.prepared >/dev/null 2>&1 || true
  # Pristine snapshot -> tree object, without copying: a temporary index over upstream/dextop.
  idx=$(mktemp -u)
  (cd "$UPSTREAM_DIR" && GIT_INDEX_FILE=$idx g --git-dir="$WORK/.git" --work-tree=. add -A -f .)
  tree=$(GIT_INDEX_FILE=$idx git write-tree); rm -f "$idx"
  [ "$tree" = "$UPSTREAM_TREE" ] ||
    die "upstream/dextop (tree $tree) drifted from $UPSTREAM_COMMIT (tree $UPSTREAM_TREE); never edit it, add a patch"
  export GIT_AUTHOR_DATE="1970-01-01T00:00:00Z" GIT_COMMITTER_DATE="1970-01-01T00:00:00Z"
  base=$(g commit-tree "$tree" -m "upstream $UPSTREAM_REF $UPSTREAM_COMMIT")
  git am --abort >/dev/null 2>&1 || true
  git rebase --abort >/dev/null 2>&1 || true
  git reset -q --hard >/dev/null 2>&1 || true
  g checkout -q -f -B backstage "$base"
  git tag -f upstream "$base" >/dev/null
  n=0
  mapfile -t ps < <(patches)
  # --keep-cr: some upstream files (the ARB catalogs) have CRLF line endings; their hunks must keep the CR.
  for p in "${ps[@]}"; do
    if ! g am -q --keep-cr --committer-date-is-author-date "$p"; then
      git am --abort >/dev/null 2>&1 || true
      die "$(basename "$p") does not apply to the pinned upstream; refresh the series with: scripts/update-upstream.sh $UPSTREAM_COMMIT"
    fi
    n=$((n + 1))
  done
  git tag -f patched >/dev/null
  "$ROOT/scripts/inject.sh" "$WORK"
  g add -A -f android/settings.gradle.kts android/app/build.gradle.kts
  g commit -q -m "$HOOKS_SUBJECT"
  if grep -rIl --include='*.kt' --include='*.kts' --include='*.dart' --include='*.xml' --include='*.gradle' \
       -e '^<<<<<<< ' -e '^>>>>>>> ' android lib 2>/dev/null | grep -q .; then
    die "conflict markers in the patched tree"
  fi
  git config backstage.head "$(git rev-parse HEAD)"
  git config backstage.prepared "$key"
  log "work tree ready: $WORK ($n patches on $UPSTREAM_REF ${UPSTREAM_COMMIT:0:12}, hooks injected)"
fi

# DroidDeck session scripts: upstream ships them as APK resources under
# android/app/src/main/resources/moe/n4tsu/dextop/linux/droiddeck/linuxfs/, but its.gitignore rule
# `local/` keeps the usr/local/bin/ half out of git, so a build from the git sources lacks them (the
# Steam/game-mode setup then fails, and so do 13 upstream unit tests). Restore them exactly as upstream
# does (tool/sync_droiddeck_linuxfs.sh) from Droid-Deck/DroidDeck (GPL-3.0) at the commit upstream pins
# in droiddeck/source.env. --no-droiddeck / BACKSTAGE_SKIP_DROIDDECK=1 skips this (offline; Steam setup broken).
resources="$WORK/android/app/src/main/resources/moe/n4tsu/dextop/linux/droiddeck"
if ! $droiddeck; then
  log "DroidDeck linuxfs scripts not restored (--no-droiddeck)"
elif [ -f "$resources/source.env" ] && [ -f "$WORK/tool/sync_droiddeck_linuxfs.sh" ]; then
  droiddeck_commit="$(sed -n 's/^DROIDDECK_COMMIT=//p' "$resources/source.env")"
  [[ "$droiddeck_commit" =~ ^[0-9a-f]{40}$ ]] || die "unexpected DROIDDECK_COMMIT in $resources/source.env"
  droiddeck_dir="$ROOT/build/cache/droiddeck/$droiddeck_commit"
  if [ ! -d "$droiddeck_dir/tools/linuxfs" ]; then
    log "fetching Droid-Deck/DroidDeck@${droiddeck_commit:0:12} (session scripts upstream does not commit)"
    rm -rf "$droiddeck_dir"; mkdir -p "$droiddeck_dir"
    g -C "$droiddeck_dir" init -q
    fetched=false
    for attempt in 1 2 3; do
      g -C "$droiddeck_dir" fetch -q --depth 1 "${BACKSTAGE_DROIDDECK_REPO:-https://github.com/Droid-Deck/DroidDeck}" "$droiddeck_commit" &&
        { fetched=true; break; }
      warn "DroidDeck fetch failed (attempt $attempt)"; sleep $((attempt * 5))
    done
    $fetched || { rm -rf "$droiddeck_dir"; die "could not fetch DroidDeck $droiddeck_commit (use --no-droiddeck to build without it)"; }
    g -C "$droiddeck_dir" checkout -q FETCH_HEAD
  fi
  sh tool/sync_droiddeck_linuxfs.sh "$droiddeck_dir" >/dev/null
  # The sync must only ADD the git-ignored files; tracked files keep their content.
  git -c core.fileMode=false diff --quiet -- android/app/src/main/resources ||
    die "tool/sync_droiddeck_linuxfs.sh changed tracked files: upstream drifted from DroidDeck@$droiddeck_commit"
fi

# local.properties (ignored upstream): Flutter rewrites parts of it during `flutter build`; these values
# make plain Gradle invocations (unit tests) work too.
version="$(pubspec_version "$WORK")"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
flutter_sdk="${FLUTTER_ROOT:-}"
if [ -z "$sdk_dir" ] || [ -z "$flutter_sdk" ]; then
  if (setup_toolchain_env) >/dev/null 2>&1; then setup_toolchain_env; sdk_dir="$ANDROID_HOME"; flutter_sdk="$FLUTTER_ROOT"; fi
fi
[ -n "$sdk_dir" ] || warn "Android SDK not found; android/local.properties has no sdk.dir"
[ -n "$flutter_sdk" ] || warn "Flutter SDK not found; android/local.properties has no flutter.sdk"
{
  echo "# Written by scripts/prepare-work-tree.sh"
  [ -n "$sdk_dir" ] && echo "sdk.dir=$sdk_dir"
  [ -n "$flutter_sdk" ] && echo "flutter.sdk=$flutter_sdk"
  echo "flutter.buildMode=$mode"
  echo "flutter.versionName=${version%%+*}"
  [[ "$version" == *+* ]] && echo "flutter.versionCode=${version##*+}"
} > "$WORK/android/local.properties"
printf '%s\n' "$WORK"
