#!/usr/bin/env bash
# Install / verify the toolchain for building Backstage (idempotent; re-run any time).
#
#   scripts/bootstrap-toolchain.sh
#
# * Flutter = FLUTTER_VERSION from toolchain.env (derived from upstream Dextop's CI by scripts/flutter-version.sh),
#   installed to $FLUTTER_ROOT (default ~/flutter) from the official release archive.
# * Android SDK at $ANDROID_HOME (default ~/android-sdk): cmdline-tools, platform-tools, platforms;android-36,
#   build-tools 35.0.0 + 36.0.0, NDK 28.2.13676358 (Flutter 3.47.7's default), CMake 3.22.1; licenses accepted.
# * Needs: curl, tar, xz, unzip, git, python3, Java 17+ (AGP 8.11).
# * Writes ~/.backstage-toolchain.env (sourced automatically by the other scripts).
. "$(dirname "$0")/lib.sh"

# shellcheck disable=SC1090
. "$TOOLCHAIN_ENV"
flutter_version="${FLUTTER_VERSION:-}"
[[ -n "$flutter_version" ]] || die "FLUTTER_VERSION missing in toolchain.env (scripts/flutter-version.sh --sync)"
FLUTTER_ROOT="${FLUTTER_ROOT:-$( [[ -x /home/user/flutter/bin/flutter ]] && echo /home/user/flutter || echo "$HOME/flutter")}"
ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$( [[ -d /home/user/android-sdk ]] && echo /home/user/android-sdk || echo "$HOME/android-sdk")}}"
NDK_VERSION="${BACKSTAGE_NDK_VERSION:-28.2.13676358}"
CMAKE_VERSION="${BACKSTAGE_CMAKE_VERSION:-${CMAKE_VERSION:-3.22.1}}"
CMDLINE_TOOLS_ZIP="${BACKSTAGE_CMDLINE_TOOLS_ZIP:-commandlinetools-linux-13114758_latest.zip}"
packages=(platform-tools "platforms;android-36" "build-tools;35.0.0" "build-tools;36.0.0" "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION")

for tool in curl tar xz unzip git python3 java; do
  command -v "$tool" >/dev/null || die "$tool is required"
done
java_major="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p')"
[[ "${java_major%%.*}" -ge 17 ]] 2>/dev/null || die "Java 17 or newer is required (found ${java_major:-unknown})"

retry() { local n; for n in 1 2 3 4 5; do "$@" && return 0; warn "attempt $n failed: $*"; sleep $((n * 5)); done; return 1; }

# ------------------------------------------------------------------------------------------ Flutter
installed_flutter=""
if [[ -x "$FLUTTER_ROOT/bin/flutter" ]]; then
  installed_flutter="$(sed -n 's/.*"frameworkVersion": *"\([^"]*\)".*/\1/p' "$FLUTTER_ROOT/bin/cache/flutter.version.json" 2>/dev/null | head -n1)"
  [[ -n "$installed_flutter" ]] || installed_flutter="$(git -C "$FLUTTER_ROOT" describe --tags --exact-match 2>/dev/null || true)"
fi
if [[ "$installed_flutter" == "$flutter_version" ]]; then
  log "Flutter $flutter_version already installed at $FLUTTER_ROOT"
elif [[ -x "$FLUTTER_ROOT/bin/flutter" && -d "$FLUTTER_ROOT/.git" ]]; then
  log "switching $FLUTTER_ROOT from ${installed_flutter:-unknown} to $flutter_version"
  retry git -C "$FLUTTER_ROOT" fetch --depth 1 origin "refs/tags/$flutter_version:refs/tags/$flutter_version"
  git -C "$FLUTTER_ROOT" -c advice.detachedHead=false checkout -q "$flutter_version"
else
  [[ ! -e "$FLUTTER_ROOT" ]] || die "$FLUTTER_ROOT exists but is not a Flutter $flutter_version checkout; move it away or set FLUTTER_ROOT"
  archive="flutter_linux_${flutter_version}-stable.tar.xz"
  url="https://storage.googleapis.com/flutter_infra_release/releases/stable/linux/$archive"
  tmp="$(mktemp -d)"
  log "downloading $url"
  retry curl -fL --retry 3 -o "$tmp/$archive" "$url"
  mkdir -p "$(dirname "$FLUTTER_ROOT")"
  tar -xJf "$tmp/$archive" -C "$tmp"
  mv "$tmp/flutter" "$FLUTTER_ROOT"
  rm -rf "$tmp"
fi
git config --global --get-all safe.directory 2>/dev/null | grep -qxF "$FLUTTER_ROOT" ||
  git config --global --add safe.directory "$FLUTTER_ROOT"

# -------------------------------------------------------------------------------------- Android SDK
sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$sdkmanager" ]]; then
  log "installing Android cmdline-tools into $ANDROID_HOME"
  tmp="$(mktemp -d)"
  retry curl -fL --retry 3 -o "$tmp/tools.zip" "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
fi
missing=()
for package in "${packages[@]}"; do
  [[ -f "$ANDROID_HOME/${package//;//}/source.properties" || -f "$ANDROID_HOME/${package//;//}/package.xml" ]] || missing+=("$package")
done
log "accepting Android SDK licenses"
yes 2>/dev/null | "$sdkmanager" --sdk_root="$ANDROID_HOME" --licenses >/dev/null 2>&1 || true
if ((${#missing[@]})); then
  log "installing SDK packages: ${missing[*]}"
  retry "$sdkmanager" --sdk_root="$ANDROID_HOME" --install "${missing[@]}" >/dev/null
else
  log "Android SDK packages already installed: ${packages[*]}"
fi

# --------------------------------------------------------------------------------- configure + env
export PATH="$FLUTTER_ROOT/bin:$PATH" ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
flutter config --no-analytics >/dev/null 2>&1 || true
flutter config --android-sdk "$ANDROID_HOME" >/dev/null
flutter precache --android >/dev/null
env_file="${BACKSTAGE_TOOLCHAIN_ENV:-$HOME/.backstage-toolchain.env}"
cat >"$env_file" <<EOF
# Written by scripts/bootstrap-toolchain.sh
export FLUTTER_ROOT="$FLUTTER_ROOT"
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$FLUTTER_ROOT/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
EOF
flutter --version
log "toolchain ready; environment in $env_file"
