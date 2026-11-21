#!/usr/bin/env bash
# Static verification of a built Backstage APK (no device needed).
#
#   scripts/verify-apk.sh <apk> [--release]
#
# signing : apksigner verify --print-certs: APK Signature Scheme v2/v3, one signer; --release: not the Android
#            debug key (set BACKSTAGE_EXPECT_CERT_SHA256 to pin the release certificate)
# badging : aapt2 dump badging: applicationId com.eggman.backstage, versionName/versionCode as derived from
#            backstage/dextop-integration.gradle + upstream pubspec, minSdk = upstream's, targetSdk >= 34,
#            application label "Backstage" + exactly one launcher ("Backstage" = Dextop's launcher, Home with the AI
#            tab); debuggable only without --release;
#            --release: native code only for BACKSTAGE_TARGET_PLATFORM (default android-arm64)
# manifest: aapt2 dump xmltree of the merged manifest: exactly one phone launcher (Dextop's launcher activity,
#            exported) and no second app entry (no alias, no other MAIN/launcher-like filter except Dextop's
#            non-exported desktop layer, no static shortcuts), the agent screen (AI tab) declared and not a launcher;
#            every exported component guarded (permission/BIND_*),
#            public by design (launcher, UI-only deep link, share/process-text, assist, widgets, Locale plugin,
#            token-checked broadcast API, system broadcasts) or an explicit FLAG policy; unknown exposure = FAIL.
#            Foreground service types + FOREGROUND_SERVICE_* permissions; exactly ONE accessibility service
#            (MirrorService: desktop + agent) with the combined config, never the retired agent service; no Car
#            Companion pieces or CARDEX_RELAY permission; permissions/authorities namespaced; ShizukuProvider once.
#            No telemetry/advertising entries (AD_ID, ad services, install referrer, Firebase init provider,
#            Analytics measurement) and Analytics collection deactivated.
# omp     : GitHub assets/omp manifest versions and every listed hash/size match pins.
# notices : every native library is listed in assets/backstage/THIRD_PARTY_NOTICES.txt with its license texts
#            (licenses/native-libraries.tsv); closed-source Google SDKs in the dex are FLAGged and must be named in
#            NOTICE.md; the asset names the source commit (a -dirty build is FLAGged).
# A Car Companion APK (com.eggman.backstage.cardex) FAILs: Backstage excludes it from distribution.
# Prints PASS/FLAG/FAIL lines (scripts/lib/verify_apk.py); exits 1 when anything FAILed.
. "$(dirname "$0")/lib.sh"

apk=""
release=false
while (($#)); do
  case "$1" in
    --release) release=true; shift ;;
    -h|--help) sed -n '2,27p' "$0"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [ -z "$apk" ] || die "only one APK"; apk="$1"; shift ;;
  esac
done
[ -n "$apk" ] || die "usage: scripts/verify-apk.sh <apk> [--release]"
[ -f "$apk" ] || die "no such APK: $apk"

if [ -z "${ANDROID_HOME:-}" ]; then
  for candidate in "${ANDROID_SDK_ROOT:-}" /home/user/android-sdk "$HOME/Android/Sdk" "$HOME/android-sdk"; do
    [ -n "$candidate" ] && [ -d "$candidate/build-tools" ] && { ANDROID_HOME="$candidate"; break; }
  done
fi
[ -n "${ANDROID_HOME:-}" ] || die "Android SDK not found: set ANDROID_HOME"
build_tools="${BACKSTAGE_BUILD_TOOLS:-$(find "$ANDROID_HOME/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)}"
for tool in aapt2 apksigner; do
  [ -x "$build_tools/$tool" ] || die "$tool not found in $build_tools (set BACKSTAGE_BUILD_TOOLS)"
done

abi="${BACKSTAGE_TARGET_PLATFORM:-android-arm64}"; abi="${abi#android-}"
case "$abi" in arm64) abi=arm64-v8a ;; arm) abi=armeabi-v7a ;; x64) abi=x86_64 ;; esac

args=("$apk" --aapt2 "$build_tools/aapt2" --apksigner "$build_tools/apksigner" --repo-root "$ROOT" --abi "$abi")
$release && args+=(--release)
python3 -I "$ROOT/scripts/lib/omp_payload.py" verify-apk --pins "$ROOT/upstream/omp-termux/UPSTREAM.env" \
  --apk "$apk" || die "OMP payload verification failed"
log "OMP payload checks passed"
# apksigner is a Java tool: keep JAVA_TOOL_OPTIONS chatter out of the report.
JAVA_TOOL_OPTIONS= python3 "$ROOT/scripts/lib/verify_apk.py" "${args[@]}"
