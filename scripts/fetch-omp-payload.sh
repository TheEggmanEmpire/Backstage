#!/usr/bin/env bash
# Assemble the GitHub-only OMP assets (no Android/Flutter/Gradle build).
#
#   scripts/fetch-omp-payload.sh
# Reads upstream/omp-termux/UPSTREAM.env, reuses SHA-256-verified downloads in.cache/omp and writes
# build/omp-payload/assets/omp. Only the pinned, SHA-verified Linux x64 bun is executed.
# Published omp/pi-natives already contain dist/JS; their build/prepack scripts are publisher steps, not install
# steps. --ignore-scripts disables ALL dependency lifecycle scripts; the Android addon is supplied explicitly.
. "$(dirname "$0")/lib.sh"
. "$ROOT/scripts/lib/omp.sh"
omp_load_pins
omp_urls
device=$ROOT/backstage/omp/device
[[ -d "$device" && -n "$(find "$device" -type f -print -quit)" ]] ||
  die "OMP device scripts missing or empty: $device (implement backstage/omp/device first)"
[[ "$(uname -s)/$(uname -m)" == Linux/x86_64 ]] || die "OMP payload assembly requires Linux x86_64 (pinned host bun)"
mkdir -p "$OMP_CACHE" "$ROOT/build/omp-payload"
exec 9>"$OMP_CACHE/.lock"
flock 9
scratch=$(mktemp -d "$ROOT/build/omp-payload/.assemble.XXXXXX")
trap 'rm -rf "$scratch"' EXIT
omp_download "$OMP_ADDON_URL" "$OMP_ADDON_FILE" "$OMP_ADDON_SHA256"
omp_download "$BUN_ANDROID_URL" "$BUN_ANDROID_FILE" "$BUN_ANDROID_SHA256"
omp_download "$BUN_HOST_URL" "$BUN_HOST_FILE" "$BUN_HOST_SHA256"
omp_download "$TERMUX_BOOTSTRAP_URL" "$TERMUX_BOOTSTRAP_FILE" "$TERMUX_BOOTSTRAP_SHA256"
assets=$scratch/assets/omp
output=$ROOT/build/omp-payload/assets/omp
mkdir -p "$assets"
# Reuse only a tree assembled by this code for these pins AND with the recorded hash. Device edits always copy
# afresh. Transitive npm resolutions are retained with the tree; a pin/code change assembles a fresh install.
tree_key=$({ cat "$OMP_PINS" "$0" "$OMP_HELPER"; } | sha256sum | cut -d' ' -f1)
state=$ROOT/build/omp-payload/tree.sha256
if [[ -f "$state" && -f "$output/omp-tree.tgz" ]] &&
   [[ "$(cat "$state")" == "$tree_key $(sha256sum "$output/omp-tree.tgz" | cut -d' ' -f1)" ]]; then
  log "reusing verified omp-tree.tgz"
  cp --reflink=auto "$output/omp-tree.tgz" "$assets/omp-tree.tgz"
else
  python3 -I "$OMP_HELPER" host-bun --archive "$BUN_HOST_FILE" --output "$scratch/bun"
  [[ "$("$scratch/bun" --version)" == "$BUN_VERSION" ]] || die "host bun version does not equal pinned $BUN_VERSION"
  global=$scratch/tree/install/global
  mkdir -p "$global"
  printf '{"name":"backstage-omp-runtime","private":true,"dependencies":{"@oh-my-pi/pi-coding-agent":"%s"}}\n' "$OMP_VERSION" > "$global/package.json"
  log "assembling omp $OMP_VERSION with bun $BUN_VERSION (production, no optional packages or lifecycle scripts)"
  (cd "$global" && BUN_INSTALL_CACHE_DIR="$scratch/bun-cache" "$scratch/bun" install --production --omit=optional --ignore-scripts)
  [[ -f "$global/node_modules/@oh-my-pi/pi-coding-agent/dist/cli.js" ]] || die "npm package lacks prebuilt dist/cli.js"
  # Inspect package metadata as data; never run a downloaded script to build native code or the CLI.
  python3 -I - "$global" "$OMP_VERSION" "$BUN_VERSION" <<'PY'
import json, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
agent = json.loads((root / 'node_modules/@oh-my-pi/pi-coding-agent/package.json').read_text())
assert agent['version'] == sys.argv[2], 'resolved omp version differs from pin'
natives = json.loads((root / 'node_modules/@oh-my-pi/pi-natives/package.json').read_text())
assert natives['version'] == sys.argv[2], 'pi-natives version differs from the Android addon release'
floor = re.fullmatch(r'>=(\d+)\.(\d+)\.(\d+)', agent['engines']['bun'])
assert floor and tuple(map(int, sys.argv[3].split('.'))) >= tuple(map(int, floor.groups())), 'bun below omp floor'
for package in root.glob('node_modules/**/package.json'):
    data = json.loads(package.read_text())
    scripts = data.get('scripts', {})
    lifecycle = [key for key in ('preinstall', 'install', 'postinstall') if key in scripts]
    if lifecycle:
        print(f'Lifecycle scripts intentionally disabled: {data.get("name", package)}: {", ".join(lifecycle)}', file=sys.stderr)
PY
  # Optional host native packages should be absent. Prune any that a future dependency nevertheless includes.
  find "$global/node_modules" -type f \( -name '*.node' -o -name '*.dll' -o -name '*.dylib' \) -print -delete
  find "$global/node_modules/@oh-my-pi" -mindepth 1 -maxdepth 1 -type d \
    \( -name 'pi-natives-linux-*' -o -name 'pi-natives-darwin-*' -o -name 'pi-natives-win32-*' \) -exec rm -rf -- {} +
  native=$global/node_modules/@oh-my-pi/pi-natives/native
  mkdir -p "$native"
  cp "$OMP_ADDON_FILE" "$native/pi_natives.android-arm64.node"
  # GNU tar normalizes metadata, order and permissions; gzip -n omits wall-clock/name headers.
  # --hard-dereference: bun dedupes files as hard links, and Android forbids apps (Termux) from creating them.
  tar --sort=name --mtime=@0 --owner=0 --group=0 --numeric-owner --format=gnu --hard-dereference \
    --mode='u+rwX,go+rX,go-w' -C "$scratch/tree" -cf - install | gzip -n > "$assets/omp-tree.tgz"
fi
hard_links="$(tar -tvzf "$assets/omp-tree.tgz" | grep -c '^h' || true)"
[[ "$hard_links" == 0 ]] || die "omp-tree.tgz contains $hard_links hard links; Android apps cannot extract them"

cp --reflink=auto "$TERMUX_BOOTSTRAP_FILE" "$assets/termux-bootstrap-aarch64.zip"
cp --reflink=auto "$BUN_ANDROID_FILE" "$assets/bun-linux-aarch64-android.zip"
cp "$ROOT/upstream/omp-termux/bin/omp-termux" "$assets/omp-termux"
cp -R "$device" "$assets/device"
python3 -I "$OMP_HELPER" manifest --pins "$OMP_PINS" --directory "$assets"
python3 -I "$OMP_HELPER" verify-dir --pins "$OMP_PINS" --directory "$assets"
# Install only a complete payload; a failed download/install never leaves a half-written asset tree.
mkdir -p "$(dirname "$output")"
rm -rf "$output"
mv "$assets" "$output"
printf '%s %s\n' "$tree_key" "$(sha256sum "$output/omp-tree.tgz" | cut -d' ' -f1)" > "$state"
log "OMP payload ready (bytes):"
python3 -I - "$output" <<'PY'
import pathlib, sys
root = pathlib.Path(sys.argv[1])
total = 0
for path in sorted(root.rglob('*')):
    if path.is_file():
        size = path.stat().st_size
        total += size
        print(f'{size:12d}  {path.relative_to(root)}')
print(f'{total:12d}  TOTAL')
PY
