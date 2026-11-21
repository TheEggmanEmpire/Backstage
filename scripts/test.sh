#!/usr/bin/env bash
# Run the Backstage test gates.
#
#   scripts/test.sh [--only library|lint|flutter|android]... [--no-prepare]
#
# library: cd backstage &&./gradlew:backstage:testDebugUnitTest   (the agent library, JVM unit tests)
# lint   : cd backstage &&./gradlew:backstage:lintDebug           (Android Lint; any error fails, no baseline)
# flutter: flutter test on the patched Dextop tree    (failures listed in scripts/known-flutter-test-failures.txt
#                                                       are pre-existing upstream failures and tolerated)
# android:./gradlew testGithubDebugUnitTest on the patched tree
#           (upstream Dextop unit tests, compiled together with:backstage and the glue; the Car Companion is not
#      built )
# Heavy steps are serialised through $BACKSTAGE_BUILD_LOCK (a flock file) when it is set.
. "$(dirname "$0")/lib.sh"

suites=()
prepare=true
while (($#)); do
  case "$1" in
    --only)
      if (($# < 2)); then
        printf '%s\n' '--only needs a suite; valid suites: library lint flutter android' >&2
        exit 2
      fi
      case "$2" in
        library|lint|flutter|android) suites+=("$2") ;;
        *) printf 'unknown suite %s; valid suites: library lint flutter android\n' "$2" >&2; exit 2 ;;
      esac
      shift 2 ;;
    --no-prepare) prepare=false; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
((${#suites[@]})) || suites=(library lint flutter android)
want() { [[ " ${suites[*]} " == *" $1 "* ]]; }
setup_toolchain_env
failed=()
timing=()

run_suite() {
  local name="$1"; shift
  local start=$SECONDS
  log "=== $name ==="
  if "$@"; then timing+=("$name: ok ($((SECONDS - start))s)")
  else failed+=("$name"); timing+=("$name: FAILED ($((SECONDS - start))s)"); fi
}

library_tests() {
  (cd "$ROOT/backstage" && heavy ./gradlew --console=plain :backstage:testDebugUnitTest; rc=$?; ./gradlew --stop >/dev/null 2>&1; exit $rc)
}

library_lint() {
  local report="$ROOT/backstage/android/build/reports/lint-results-debug.txt"
  (cd "$ROOT/backstage" && heavy ./gradlew --console=plain :backstage:lintDebug; rc=$?; ./gradlew --stop >/dev/null 2>&1; exit $rc) ||
    { [ -f "$report" ] && grep -E '^[^ ].*: Error: ' "$report" >&2; return 1; }
  [ -f "$report" ] && log "lint: $(tail -n 1 "$report")"
  return 0
}

prepared=false
ensure_prepared() {
  $prepared && return 0
  # Explicit `|| die`: this runs inside `if "$@"` (run_suite), where bash ignores `set -e`; a failed prepare
  # must never leave the suites running on a stale or half-patched tree.
  if $prepare; then
    "$ROOT/scripts/prepare-work-tree.sh" >/dev/null || die "scripts/prepare-work-tree.sh failed; nothing was tested"
  fi
  require_prepared
  (cd "$WORK" && flutter pub get >/dev/null)
  prepared=true
}

flutter_tests() {
  ensure_prepared
  local report="$ROOT/build/flutter-test-report.json"
  mkdir -p "$(dirname "$report")"
  rm -f "$report"
  # flutter's exit status is non-zero for the known upstream failures too, so the report decides; the status is
  # passed along so that a crash or kill which left no failing test in the report still fails the gate.
  local rc=0
  (cd "$WORK" && heavy flutter test --file-reporter "json:$report") || rc=$?
  python3 "$ROOT/scripts/lib/flutter_test_report.py" "$report" \
    "$ROOT/scripts/known-flutter-test-failures.txt" "$WORK" "$rc"
}

android_tests() {
  ensure_prepared
  local gradle_args=(--console=plain testGithubDebugUnitTest)
  (cd "$WORK/android" && heavy ./gradlew "${gradle_args[@]}"
   rc=$?; ./gradlew --stop >/dev/null 2>&1; exit $rc)
}

want library && run_suite library library_tests
want lint && run_suite lint library_lint
want flutter && run_suite flutter flutter_tests
want android && run_suite android android_tests

((${#timing[@]} > 0)) || die "no test suites were run"
printf '%s\n' "${timing[@]}" >&2
((${#failed[@]} == 0)) || die "failed: ${failed[*]}"
log "all requested test suites passed"
