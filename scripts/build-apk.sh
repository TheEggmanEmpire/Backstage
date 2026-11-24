#!/usr/bin/env bash
# Build Backstage release or debug artifacts into dist/.
#
#   scripts/build-apk.sh [debug|release] [--flavor github|play|companion] [--no-prepare]
#
# github (default): APK with the pinned OMP runtime. BACKSTAGE_SKIP_OMP=1 skips it for debug.
# play: APK and, in release mode, an AAB. Release APKs target arm64.
# companion: APK for the separate Backstage Agent app.
# Release signing uses BACKSTAGE_KEYSTORE, BACKSTAGE_KEYSTORE_PASSWORD, BACKSTAGE_KEY_ALIAS and
# BACKSTAGE_KEY_PASSWORD. Set all four, or omit all four for a debug-signed local test build.
# BACKSTAGE_ALLOW_DIRTY=1 permits local release builds from a modified checkout.
# SHA256SUMS lists the artifacts produced by this run. BACKSTAGE_WRITE_SHA256SUMS=0 leaves it to the caller.
# Heavy steps are serialised through $BACKSTAGE_BUILD_LOCK (a flock file) when it is set.
. "$(dirname "$0")/lib.sh"

type=debug
flavor=github
prepare=true
while (($#)); do
  case "$1" in
    debug|release) type="$1"; shift ;;
    --flavor)
      (($# >= 2)) || die "--flavor requires github, play or companion"
      flavor=$2
      shift 2 ;;
    --no-prepare) prepare=false; shift ;;
    -h|--help) awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
case "$flavor" in github|play|companion) ;; *) die "unknown flavor $flavor" ;; esac
if [[ "$flavor" == github && "${BACKSTAGE_SKIP_OMP:-0}" == 1 && "$type" != debug ]]; then
  die "BACKSTAGE_SKIP_OMP=1 is debug only"
fi
start=$SECONDS

write_checksums() {
  if [[ "${BACKSTAGE_WRITE_SHA256SUMS:-1}" != 0 ]]; then
    (cd "$DIST" && sha256sum -- "$@" >SHA256SUMS)
  fi
}

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

if [[ "$flavor" != companion ]]; then
  if $prepare; then
    "$ROOT/scripts/prepare-work-tree.sh" --mode "$type" >/dev/null || die "scripts/prepare-work-tree.sh failed"
  fi
  require_prepared
fi

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
    BACKSTAGE_KEYSTORE="$(readlink -f "$BACKSTAGE_KEYSTORE")"
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
if [[ "$flavor" == companion ]]; then
  "$ROOT/scripts/fetch-omp-payload.sh"
  task=assembleDebug
  [[ "$type" != release ]] || task=assembleRelease
  gradle_args=()
  for arg in ${BACKSTAGE_GRADLE_PROJECT_ARGS:-}; do
    gradle_args+=("-P$arg")
  done
  (
    cd "$ROOT/backstage"
    heavy ./gradlew --console=plain ":companion:$task" "${gradle_args[@]}"
    ./gradlew --stop >/dev/null
  )
  outputs="$ROOT/backstage/companion/build/outputs/apk/$type"
  readarray -t artifact < <(python3 - "$outputs/output-metadata.json" <<'PYCODE'
import json, sys

with open(sys.argv[1], encoding="utf-8") as metadata:
    output, = json.load(metadata)["elements"]
print(output["outputFile"])
print(output["versionName"])
PYCODE
  )
  [[ ${#artifact[@]} -eq 2 ]] || die "invalid companion APK metadata"
  mkdir -p "$DIST"
  version_name="${artifact[1]//+/_}"
  out="backstage-play-agent-$version_name-$type.apk"
  cp "$outputs/${artifact[0]}" "$DIST/$out"
  write_checksums "$out"
  log "wrote dist/$out; total $((SECONDS - start))s"
  exit 0
fi

if [[ "$flavor" == github ]]; then
  if [[ "${BACKSTAGE_SKIP_OMP:-0}" == 1 ]]; then
    warn "BACKSTAGE_SKIP_OMP=1: debug APK will not include the OMP runtime"
  else
    "$ROOT/scripts/fetch-omp-payload.sh"
  fi
fi
cd "$WORK"
flutter config --no-analytics >/dev/null 2>&1 || true
log "flutter pub get"
flutter pub get >/dev/null
flutter_args=(--flavor "$flavor" "--$type" "--dart-define=DISTRIBUTION_CHANNEL=$flavor")
if [[ "$flavor" == github && "${BACKSTAGE_SKIP_OMP:-0}" == 1 ]]; then
  flutter_args+=(--android-project-arg=backstage.skipOmp=true)
fi
# Extra Gradle project properties (space-separated name=value), forwarded explicitly because dotted
# environment variable names (ORG_GRADLE_PROJECT_kotlin.…) do not survive every shell. Low-memory machines:
#   BACKSTAGE_GRADLE_PROJECT_ARGS="kotlin.compiler.execution.strategy=in-process"   (no separate Kotlin daemon)
for arg in ${BACKSTAGE_GRADLE_PROJECT_ARGS:-}; do
  flutter_args+=("--android-project-arg=$arg")
done
target_platform="${BACKSTAGE_TARGET_PLATFORM:-android-arm64}"
if [[ "$type" == release ]]; then
  [[ "$flavor" != play ]] || target_platform=android-arm64
  flutter_args+=(--target-platform "$target_platform")
elif [[ -n "${BACKSTAGE_TARGET_PLATFORM:-}" ]]; then
  flutter_args+=(--target-platform "$BACKSTAGE_TARGET_PLATFORM")
fi
app_start=$SECONDS
if [[ "$flavor" == play && "$type" == release ]]; then
  log "flutter build appbundle ${flutter_args[*]}"
  heavy flutter build appbundle "${flutter_args[@]}"
  (cd android && ./gradlew --stop >/dev/null)
  "$ROOT/backstage/gradlew" --stop
  free -g
fi
log "flutter build apk ${flutter_args[*]}"
heavy flutter build apk "${flutter_args[@]}"
log "app build took $((SECONDS - app_start))s"

(cd android && ./gradlew --stop >/dev/null 2>&1) || true

# ------------------------------------------------------------------------------------------ outputs
metadata="$WORK/build/app/outputs/apk/$flavor/$type/output-metadata.json"
[[ -f "$metadata" ]] || die "missing $metadata"
version_name="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["elements"][0]["versionName"])' "$metadata")"
version_name="${version_name//+/_}"
mkdir -p "$DIST"
apk="$WORK/build/app/outputs/flutter-apk/app-$flavor-$type.apk"
[[ -f "$apk" ]] || die "missing $apk"
prefix=backstage
[[ "$flavor" != play ]] || prefix=backstage-play
if [[ "$type" == release ]]; then
  abi="${target_platform#android-}"; [[ "$abi" == arm64 ]] && abi=arm64-v8a
  out="$DIST/$prefix-$version_name-$abi-release.apk"
else
  out="$DIST/$prefix-$version_name-debug.apk"
fi
cp "$apk" "$out"
produced=("$(basename "$out")")
log "wrote ${out#$ROOT/}"
if [[ "$flavor" == play && "$type" == release ]]; then
  bundle="$WORK/build/app/outputs/bundle/playRelease/app-play-release.aab"
  [[ -f "$bundle" ]] || die "missing $bundle"
  bundle_name="$prefix-$version_name-release.aab"
  cp "$bundle" "$DIST/$bundle_name"
  produced+=("$bundle_name")
  log "wrote dist/$bundle_name"
fi
write_checksums "${produced[@]}"
log "wrote ${#produced[@]} artifact(s); total $((SECONDS - start))s"
