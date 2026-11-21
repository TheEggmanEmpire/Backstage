#!/usr/bin/env bash
# CI: make room for the Flutter/Gradle/NDK build on a GitHub-hosted runner (~14 GB free on / out of the box; the
# all-ABI debug build ran out of space). Prints `df -h` before and after.
#
#   scripts/ci-free-disk-space.sh [--dry-run]                   remove preinstalled toolchains the build never uses
#   scripts/ci-free-disk-space.sh --build-outputs [--dry-run]   remove build intermediates no later CI step needs
#
# default        :.NET, GHC/ghcup, Boost, CodeQL, global npm packages, Swift, PowerShell, Chromium, Azure CLI,
#                   Microsoft tools, Chrome, Julia, and every docker image. Only on a GitHub-hosted runner
#                   (GITHUB_ACTIONS=true, RUNNER_ENVIRONMENT=github-hosted): it never deletes a developer's or a
#                   self-hosted runner's toolchains. The Android SDK (ANDROID_HOME / ANDROID_SDK_ROOT /
#                   /usr/local/lib/android) is what setup-toolchain installs into and is never removed: a path that
#                   is, contains or lies inside it (or this checkout, $HOME, /) is refused.
# --build-outputs: in the prepared work tree ($BACKSTAGE_WORK, default build/work/dextop): the APK outputs (already
#                   copied to dist/), merged/stripped native libraries, dex, compressed assets, CMake/ndk-build
#                   intermediates and the flutter test cache. Compiled classes, resources and reports stay, so the
#                   Android unit tests that follow reuse them (Gradle re-creates anything else it needs).
# Removal failures are warnings (freeing space is best effort); a refused path is an error.
# BACKSTAGE_FREE_DISK_PATHS (newline-separated) replaces the default list; for scripts/tests only.
. "$(dirname "$0")/lib.sh"

mode=system
dry=false
for arg in "$@"; do
  case "$arg" in
    --build-outputs) mode=build ;;
    --dry-run) dry=true ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) die "unknown argument $arg" ;;
  esac
done

disk_usage() {
  log "disk usage ($1):"
  df -h / "$ROOT" "${HOME:-/}" 2>/dev/null | awk 'NR == 1 || !seen[$NF]++' >&2 || true
}

# is_within A B: A is B or lies inside B (both absolute, normalised)
is_within() { [ "$1" = "$2" ] || [[ "$1" == "$2"/* ]]; }

refuse_protected() {
  local path=$1 p
  # Never removed themselves (their children may be):
  for p in / /usr /usr/local /usr/local/lib /usr/local/share /usr/share /opt /home "${AGENT_TOOLSDIRECTORY:-/opt/hostedtoolcache}"; do
    [ "$path" != "$(realpath -m -- "$p")" ] || die "refusing to remove $path (a system directory, not a toolchain)"
  done
  # Never touched: not the directory, nothing inside it, nothing that contains it.
  local protected=(/usr/local/lib/android "$ROOT" "${HOME:-/}")
  [ -n "${ANDROID_HOME:-}" ] && protected+=("$ANDROID_HOME")
  [ -n "${ANDROID_SDK_ROOT:-}" ] && protected+=("$ANDROID_SDK_ROOT")
  [ -n "${JAVA_HOME:-}" ] && protected+=("$JAVA_HOME")
  [ -n "${FLUTTER_ROOT:-}" ] && protected+=("$FLUTTER_ROOT")
  for p in "${protected[@]}"; do
    p=$(realpath -m -- "$p")
    [ "$p" != / ] || continue
    if is_within "$path" "$p" || is_within "$p" "$path"; then
      die "refusing to remove $path: it is, contains or lies inside the protected $p (Android SDK, toolchain, checkout or home)"
    fi
  done
}

remove() { # path...: removes in parallel, with sudo where the runner user cannot write the parent
  local pids=() path rm_cmd pid
  for path in "$@"; do
    [ -e "$path" ] || [ -L "$path" ] || continue
    if $dry; then echo "would remove $path"; continue; fi
    rm_cmd=(rm -rf --one-file-system -- "$path")
    if [ "$(id -u)" != 0 ] && [ ! -w "$(dirname "$path")" ]; then
      if command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then rm_cmd=(sudo -n "${rm_cmd[@]}")
      else warn "cannot remove $path (not writable, no passwordless sudo)"; continue; fi
    fi
    log "removing $path"
    { "${rm_cmd[@]}" || warn "could not remove all of $path"; } &
    pids+=($!)
  done
  for pid in "${pids[@]}"; do wait "$pid" || true; done
}

if [ "$mode" = system ]; then
  if ! $dry && { [ "${GITHUB_ACTIONS:-}" != true ] || [ "${RUNNER_ENVIRONMENT:-}" != github-hosted ]; }; then
    die "only on a GitHub-hosted runner (GITHUB_ACTIONS=true, RUNNER_ENVIRONMENT=github-hosted); --dry-run lists what it would remove"
  fi
  if [ -n "${BACKSTAGE_FREE_DISK_PATHS:-}" ]; then
    mapfile -t candidates <<<"$BACKSTAGE_FREE_DISK_PATHS"
  else
    shopt -s nullglob
    candidates=(
      /usr/share/dotnet
      /opt/ghc /usr/local/.ghcup
      /usr/local/share/boost
      "${AGENT_TOOLSDIRECTORY:-/opt/hostedtoolcache}/CodeQL"
      /usr/local/lib/node_modules          # global npm packages: no step here uses npm (actions bring their own node)
      /usr/share/swift
      /usr/local/share/powershell
      /usr/local/share/chromium /opt/google/chrome
      /opt/az /opt/microsoft
      /usr/local/julia*
    )
    shopt -u nullglob
  fi
  resolved=()
  for c in "${candidates[@]}"; do
    [ -n "$c" ] || continue
    [[ "$c" == /* ]] || die "not an absolute path: $c"
    c=$(realpath -m -- "$c")
    refuse_protected "$c"
    resolved+=("$c")
  done
  disk_usage before
  remove "${resolved[@]}"
  if command -v docker >/dev/null 2>&1; then
    if $dry; then echo "would prune every docker image"
    else log "pruning docker images"; docker image prune --all --force >/dev/null 2>&1 || warn "docker image prune failed"; fi
  fi
  disk_usage after
  exit 0
fi

# --build-outputs ---------------------------------------------------------------------------------------------------
[ -d "$WORK/.git" ] && [ "$(git -C "$WORK" config --get backstage.work 2>/dev/null || true)" = true ] ||
  die "$WORK is not a work tree made by scripts/prepare-work-tree.sh; nothing removed"
WORK=$(cd "$WORK" && pwd -P)
build=$WORK/build
outputs=(
  "$build/app/outputs/apk" "$build/app/outputs/flutter-apk" "$build/app/outputs/bundle"
  "$build/app/outputs/native-debug-symbols" "$build/app/outputs/mapping"
  "$build/app/intermediates/merged_native_libs" "$build/app/intermediates/stripped_native_libs"
  "$build/app/intermediates/merged_jni_libs" "$build/app/intermediates/native_symbol_tables"
  "$build/app/intermediates/dex" "$build/app/intermediates/compressed_assets" "$build/app/intermediates/apk"
  "$build/app/intermediates/cxx" "$WORK/android/app/.cxx"
  "$build/cardex/outputs/apk"  # unused Car Companion APK outputs
  "$build/test_cache"
)
for o in "${outputs[@]}"; do is_within "$(realpath -m -- "$o")" "$WORK" || die "refusing $o: outside $WORK"; done
disk_usage before
remove "${outputs[@]}"
disk_usage after
