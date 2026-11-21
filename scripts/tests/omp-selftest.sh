#!/usr/bin/env bash
# OMP shell helpers against a temporary cache and fake curl (no network, git, Bun or Android tools).
. "$(dirname "$0")/../lib.sh"
. "$ROOT/scripts/lib/omp.sh"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/backstage-omp-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
fail() { die "OMP self-test: $*"; }
mkdir -p "$tmp/bin"
cat > "$tmp/bin/curl" <<'CURL'
#!/usr/bin/env bash
set -eu
printf called > "$OMP_TEST_CALLED"
while (($#)); do
  if [[ "$1" == -o ]]; then printf runtime > "$2"; exit; fi
  shift
done
exit 1
CURL
chmod +x "$tmp/bin/curl"
export PATH="$tmp/bin:$PATH" OMP_TEST_CALLED="$tmp/curl-called"
printf runtime > "$tmp/cached"
sha=$(sha256sum "$tmp/cached" | cut -d' ' -f1)
omp_download https://example.invalid/artifact "$tmp/cached" "$sha"
[[ ! -e "$OMP_TEST_CALLED" ]] || fail 'matching cache unexpectedly downloaded'
printf broken > "$tmp/cached"
omp_download https://example.invalid/artifact "$tmp/cached" "$sha"
[[ -f "$OMP_TEST_CALLED" && "$(cat "$tmp/cached")" == runtime ]] || fail 'corrupt cache was not replaced'
if (omp_download https://example.invalid/artifact "$tmp/cached" "$(printf '%064d' 0)") > "$tmp/error" 2>&1; then
  fail 'mismatched download was accepted'
fi
[[ ! -f "$tmp/cached.part" ]] || fail 'rejected partial download was retained'
grep -q 'SHA-256 mismatch' "$tmp/error" || fail 'checksum failure had no clear message'
OMP_PINS=$tmp/pins.env
{
  printf '# data only\nOMP_TERMUX_REPO=https://github.com/hmeqo/omp-termux\n'
  printf 'OMP_TERMUX_COMMIT=%040d\nOMP_VERSION=18.18.6\nOMP_ADDON_SHA256=%s\n' 0 "$sha"
  printf 'BUN_VERSION=1.4.4\nBUN_ANDROID_SHA256=%s\nBUN_HOST_SHA256=%s\n' "$sha" "$sha"
  printf 'TERMUX_BOOTSTRAP_TAG=bootstrap-2026.11.08-r1+apt.android-7\nTERMUX_BOOTSTRAP_SHA256=%s\n' "$sha"
} > "$OMP_PINS"
omp_load_pins
[[ "$OMP_VERSION" == 18.18.6 && "$BUN_VERSION" == 1.4.4 && "$TERMUX_BOOTSTRAP_TAG" == *+apt.android-7 ]] || fail 'pin values changed during parsing'
printf 'EVIL=$(touch %s)\n' "$tmp/executed" >> "$OMP_PINS"
if (omp_load_pins) > "$tmp/error" 2>&1; then fail 'unknown executable pin was accepted'; fi
[[ ! -e "$tmp/executed" ]] || fail 'pin data was executed'
printf '%s\n' 'OMP shell self-test: cache verification, replacement, mismatch rejection and literal pins passed'
