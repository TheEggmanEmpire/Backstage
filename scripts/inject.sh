#!/usr/bin/env bash
# Idempotent one-line hooks into upstream's two Gradle BUILD ENTRY POINTS. Never diff patches for these:
# both files grow at EOF upstream, so EOF diff hunks conflict on bumps (research/dextop-upstream.md §3/§5.3).
#
#   scripts/inject.sh [TREE]           (default: the work tree, build/work/dextop)
#   scripts/inject.sh --check [TREE]   exit 0 only when both hooks are present exactly once AND point at THIS
#                                      checkout's backstage/ (changes nothing, prints nothing)
#
# android/settings.gradle.kts: apply(from = "<rel>/backstage/dextop-settings.gradle")     // backstage:settings
# android/app/build.gradle.kts: apply(from = "<rel>/backstage/dextop-integration.gradle")  // backstage:app
# Each line is appended only when its marker is absent; a missing target file fails loudly.
# --check backs prepare-work-tree.sh's "up to date" fast path and require_prepared (lib.sh): a work tree shared by two
# checkouts (BACKSTAGE_WORK) or reset to the `patched` tag by hand carries the right series marker, but its hooks
# are missing or name another checkout's backstage/.
. "$(dirname "$0")/lib.sh"
check=false
if [ "${1:-}" = --check ]; then check=true; shift; fi
tree=${1:-$WORK}
rel=$(realpath --relative-to="$tree/android/app" "$ROOT/backstage")
relS=$(realpath --relative-to="$tree/android" "$ROOT/backstage")
inject() { # file marker line
  local f=$tree/$1
  if $check; then
    [ -f "$f" ] && grep -qxF -- "$3 $2" "$f" && [ "$(grep -cF -- "$2" "$f")" = 1 ]
    return
  fi
  [ -f "$f" ] || die "inject target missing: $1 (upstream restructured? update scripts/inject.sh)"
  grep -qF -- "$2" "$f" || printf '\n%s %s\n' "$3" "$2" >> "$f"
  grep -qF -- "$2" "$f" || die "inject failed: $1"
}
inject android/settings.gradle.kts "// backstage:settings" "apply(from = \"$relS/dextop-settings.gradle\")"
inject android/app/build.gradle.kts "// backstage:app"   "apply(from = \"$rel/dextop-integration.gradle\")"
