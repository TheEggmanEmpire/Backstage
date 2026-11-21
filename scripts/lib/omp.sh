# shellcheck shell=bash
# OMP-only helpers; source scripts/lib.sh first. Pins are data, never sourced as shell code.
OMP_PINS=$ROOT/upstream/omp-termux/UPSTREAM.env
OMP_CACHE=$ROOT/.cache/omp
OMP_HELPER=$ROOT/scripts/lib/omp_payload.py

omp_load_pins() {
  local parsed key value
  parsed=$(python3 -I "$OMP_HELPER" pins --pins "$OMP_PINS") || die "invalid OMP pins: $OMP_PINS"
  while IFS='=' read -r key value; do printf -v "$key" '%s' "$value"; done <<< "$parsed"
}

omp_download() { # URL, cache file, optional expected SHA-256. No expected hash = update and re-pin.
  local url=$1 path=$2 expected=${3:-} got
  mkdir -p "$(dirname "$path")"
  if [[ -n "$expected" && -f "$path" ]] && [[ "$(sha256sum "$path" | cut -d' ' -f1)" == "$expected" ]]; then
    log "cached $(basename "$path") (SHA-256 verified)"
    return
  fi
  log "downloading $url"
  curl --fail --location --silent --show-error --retry 3 --proto '=https' --proto-redir '=https' \
    --connect-timeout 30 -o "$path.part" "$url" || { rm -f "$path.part"; die "download failed: $url"; }
  got=$(sha256sum "$path.part" | cut -d' ' -f1)
  if [[ -n "$expected" && "$got" != "$expected" ]]; then
    rm -f "$path.part"
    die "SHA-256 mismatch for $(basename "$path"): got $got, pinned $expected"
  fi
  mv -f "$path.part" "$path"
}

omp_urls() {
  OMP_ADDON_URL=$OMP_TERMUX_REPO/releases/download/omp-$OMP_VERSION/pi_natives.android-arm64.node
  BUN_ANDROID_URL=https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/bun-linux-aarch64-android.zip
  BUN_HOST_URL=https://github.com/oven-sh/bun/releases/download/bun-v$BUN_VERSION/bun-linux-x64.zip
  TERMUX_BOOTSTRAP_URL=https://github.com/termux/termux-packages/releases/download/$TERMUX_BOOTSTRAP_TAG/bootstrap-aarch64.zip
  OMP_ADDON_FILE=$OMP_CACHE/pi_natives.android-arm64-$OMP_VERSION.node
  BUN_ANDROID_FILE=$OMP_CACHE/bun-linux-aarch64-android-$BUN_VERSION.zip
  BUN_HOST_FILE=$OMP_CACHE/bun-linux-x64-$BUN_VERSION.zip
  TERMUX_BOOTSTRAP_FILE=$OMP_CACHE/bootstrap-aarch64-$TERMUX_BOOTSTRAP_TAG.zip
}
