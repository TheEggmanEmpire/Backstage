#!/usr/bin/env bash
# Build the Backstage APK into dist/.
#
#   scripts/build-apk.sh [debug|release] [--no-prepare]
#
# Dextop's Car Companion (:cardex) is not built.
#
# debug  : flutter build apk --flavor github --debug (all ABIs, like upstream CI; set
#           BACKSTAGE_TARGET_PLATFORM=android-arm64 to build arm64 only)
# release: flutter build apk --flavor github --release --target-platform android-arm64, R8 on.
#           Signed with BACKSTAGE_KEYSTORE / BACKSTAGE_KEYSTORE_PASSWORD / BACKSTAGE_KEY_ALIAS /
#           BACKSTAGE_KEY_PASSWORD; with none of them the DEBUG key is used and a warning is printed
#           (some but not all of them set is an error).
#           Refuses a checkout with uncommitted or untracked files (the APK names its source commit);
#           BACKSTAGE_ALLOW_DIRTY=1 overrides that for local test builds.
# Outputs: dist/backstage-<version>-debug.apk | dist/backstage-<version>-arm64-v8a-release.apk
#           dist/SHA256SUMS (only this run's file)
#      <version> = versionName with '+' replaced by '_' (e.g. ): no '+' in asset names.
# GitHub embeds the pinned OMP runtime (scripts/fetch-omp-payload.sh,.cache/omp).
# BACKSTAGE_SKIP_OMP=1 skips it for debug only (OMP is unavailable in that APK); releases always include it.
# Heavy steps are serialised through $BACKSTAGE_BUILD_LOCK (a flock file) when it is set.
. "$(dirname "$0")/lib.sh"

type=debug
prepare=true
while (($#)); do
  case "$1" in
    debug|release) type="$1"; shift ;;
    --no-prepare) prepare=false; shift ;;
    -h|--help) awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
[[ "${BACKSTAGE_SKIP_OMP:-0}" != 1 || "$type" == debug ]] || die "BACKSTAGE_SKIP_OMP=1 is debug only"
start=$SECONDS

# A release's Corresponding Source (GPL-3.0 §6) is this repository at the commit it is built from (NOTICE.md), and
# the APK records that commit (assets/backstage/THIRD_PARTY_NOTICES.txt, BuildConfig.SOURCE_REVISION). Uncommitted
# or untracked (not ignored) files would be built in without being in any commit: refuse them.
if [[ "$type" == release ]]; then
  if git -C "$ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    dirty="$(git -C "$ROOT" status --porcelain 2>/dev/null | head -n 20)"
    if [[ -n "$dirty" ]]; then
      [[ "${BACKSTAGE_ALLOW_DIRTY:-0}" == 1 ]] ||
        die "release builds need a clean checkout (commit or remove these first; BACKSTAGE_ALLOW_DIRTY=1 overrides for a local test build, which is then marked -dirty):"$'\n'"$dirty"
      warn "BACKSTAGE_ALLOW_DIRTY=1: building a release from uncommitted changes - its source revision is marked -dirty; do not distribute it"
    fi
  else
    warn "$ROOT is not a git checkout: the APK cannot name the commit that is its corresponding source"
  fi
fi
setup_toolchain_env

if $prepare; then
  "$ROOT/scripts/prepare-work-tree.sh" --mode "$type" >/dev/null || die "scripts/prepare-work-tree.sh failed"
fi
require_prepared

# ------------------------------------------------------------------------------------------ signing
# Upstream's own Gradle guard refuses to package release builds without DEXTOP_* signing values
# (paths relative to the work tree root). Mirror the Backstage key into them; the authoritative
# signing configuration is set by backstage/dextop-integration.gradle from BACKSTAGE_*.
relative_to_work() { python3 -c 'import os,sys; print(os.path.relpath(sys.argv[1], sys.argv[2]))' "$1" "$WORK"; }
if [[ "$type" == release ]]; then
  # Local release key: read when no BACKSTAGE_* signing variable is set.
  signing_env="${BACKSTAGE_SIGNING_ENV:-$HOME/.config/backstage/release.env}"
  if [[ -z "${BACKSTAGE_KEYSTORE:-}${BACKSTAGE_KEYSTORE_PASSWORD:-}${BACKSTAGE_KEY_ALIAS:-}${BACKSTAGE_KEY_PASSWORD:-}" && -f "$signing_env" ]]; then
    # shellcheck disable=SC1090
    . "$signing_env"
    log "release signing variables from $signing_env"
  fi
  if [[ -n "${BACKSTAGE_KEYSTORE:-}" && -n "${BACKSTAGE_KEYSTORE_PASSWORD:-}" && -n "${BACKSTAGE_KEY_ALIAS:-}" && -n "${BACKSTAGE_KEY_PASSWORD:-}" ]]; then
    [[ -f "$BACKSTAGE_KEYSTORE" ]] || die "configured release keystore does not exist"
    export BACKSTAGE_KEYSTORE BACKSTAGE_KEYSTORE_PASSWORD BACKSTAGE_KEY_ALIAS BACKSTAGE_KEY_PASSWORD
    export DEXTOP_KEYSTORE_FILE="$(relative_to_work "$(readlink -f "$BACKSTAGE_KEYSTORE")")"
    export DEXTOP_STORE_PASSWORD="$BACKSTAGE_KEYSTORE_PASSWORD" DEXTOP_KEY_ALIAS="$BACKSTAGE_KEY_ALIAS" \
           DEXTOP_KEY_PASSWORD="$BACKSTAGE_KEY_PASSWORD"
    log "release signing: $BACKSTAGE_KEYSTORE (alias $BACKSTAGE_KEY_ALIAS)"
  elif [[ -n "${BACKSTAGE_KEYSTORE:-}${BACKSTAGE_KEYSTORE_PASSWORD:-}${BACKSTAGE_KEY_ALIAS:-}${BACKSTAGE_KEY_PASSWORD:-}" ]]; then
    die "release signing is configured only partly: set all of BACKSTAGE_KEYSTORE, BACKSTAGE_KEYSTORE_PASSWORD, BACKSTAGE_KEY_ALIAS and BACKSTAGE_KEY_PASSWORD (or none for a debug-signed test build)"
  else
    unset BACKSTAGE_KEYSTORE BACKSTAGE_KEYSTORE_PASSWORD BACKSTAGE_KEY_ALIAS BACKSTAGE_KEY_PASSWORD
    debug_store="${ANDROID_USER_HOME:-$HOME/.android}/debug.keystore"
    if [[ ! -f "$debug_store" ]]; then
      mkdir -p "$(dirname "$debug_store")"
      keytool -genkeypair -keystore "$debug_store" -storepass android -alias androiddebugkey -keypass android \
        -keyalg RSA -keysize 2048 -validity 10950 -dname 'CN=Android Debug,O=Android,C=US' >/dev/null
    fi
    export DEXTOP_KEYSTORE_FILE="$(relative_to_work "$debug_store")" DEXTOP_STORE_PASSWORD=android \
           DEXTOP_KEY_ALIAS=androiddebugkey DEXTOP_KEY_PASSWORD=android
    warn "BACKSTAGE_KEYSTORE* not set: the release APK is signed with the DEBUG key - do not distribute it"
  fi
fi

# ------------------------------------------------------------------------------------------- build
if [[ "${BACKSTAGE_SKIP_OMP:-0}" == 1 ]]; then
  warn "BACKSTAGE_SKIP_OMP=1: debug APK will not include the OMP runtime"
else
  "$ROOT/scripts/fetch-omp-payload.sh"
fi
cd "$WORK"
flutter config --no-analytics >/dev/null 2>&1 || true
log "flutter pub get"
flutter pub get >/dev/null
flutter_args=(build apk --flavor github "--$type" --dart-define=DISTRIBUTION_CHANNEL=github)
if [[ "${BACKSTAGE_SKIP_OMP:-0}" == 1 ]]; then
  flutter_args+=(--android-project-arg=backstage.skipOmp=true)
fi
# Extra Gradle project properties (space-separated name=value), forwarded explicitly because dotted
# environment variable names (ORG_GRADLE_PROJECT_kotlin.…) do not survive every shell. Low-memory machines:
#   BACKSTAGE_GRADLE_PROJECT_ARGS="kotlin.compiler.execution.strategy=in-process"   (no separate Kotlin daemon)
for arg in ${BACKSTAGE_GRADLE_PROJECT_ARGS:-}; do
  flutter_args+=("--android-project-arg=$arg")
done
if [[ "$type" == release ]]; then
  flutter_args+=(--target-platform "${BACKSTAGE_TARGET_PLATFORM:-android-arm64}")
elif [[ -n "${BACKSTAGE_TARGET_PLATFORM:-}" ]]; then
  flutter_args+=(--target-platform "$BACKSTAGE_TARGET_PLATFORM")
fi
log "flutter ${flutter_args[*]}"
app_start=$SECONDS
heavy flutter "${flutter_args[@]}"
log "app build took $((SECONDS - app_start))s"

(cd android && ./gradlew --stop >/dev/null 2>&1) || true

# ------------------------------------------------------------------------------------------ outputs
metadata="$WORK/build/app/outputs/apk/github/$type/output-metadata.json"
[[ -f "$metadata" ]] || die "missing $metadata"
version_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata")"
version_name="${version_name//+/_}"
mkdir -p "$DIST"
apk="$WORK/build/app/outputs/flutter-apk/app-github-$type.apk"
[[ -f "$apk" ]] || die "missing $apk"
if [[ "$type" == release ]]; then
  abi="${BACKSTAGE_TARGET_PLATFORM:-android-arm64}"; abi="${abi#android-}"; [[ "$abi" == arm64 ]] && abi=arm64-v8a
  out="$DIST/backstage-$version_name-$abi-release.apk"
else
  out="$DIST/backstage-$version_name-debug.apk"
fi
cp "$apk" "$out"
produced=("$(basename "$out")")
log "wrote ${out#$ROOT/}"
(cd "$DIST" && sha256sum -- "${produced[@]}" >SHA256SUMS)
log "dist/SHA256SUMS lists this run's ${#produced[@]} file(s); total $((SECONDS - start))s"
