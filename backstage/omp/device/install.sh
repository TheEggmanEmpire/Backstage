#!/system/bin/sh
# The APK is trusted by Android; every extracted asset is checked against its pinned manifest.
set -eu
umask 077
ROOT=${1:?ROOT}; APK=${2:?APK}; LIBDIR=${3:?LIBDIR}
case "$ROOT" in /data/local/tmp/backstage-omp) ;; *) echo 'Invalid runtime root' >&2; exit 2;; esac
[ -f "$LIBDIR/libproot.so" ] && [ -f "$LIBDIR/libproot_loader.so" ] || { echo 'Dextop proot libraries unavailable' >&2; exit 1; }
step() { printf 'step: %s\n' "$1"; }
LOCK=$ROOT.install-lock
# A lock whose owner is gone (killed install, older version without a pid) is reclaimed.
if [ -d "$LOCK" ]; then
    owner=$(cat "$LOCK/pid" 2>/dev/null || :)
    # Match job creation: allow the mkdir owner time to publish its PID.
    if [ -z "$owner" ]; then sleep 0.2; owner=$(cat "$LOCK/pid" 2>/dev/null || :); fi
    case "$owner" in ''|*[!0-9]*) rm -rf "$LOCK";; *) kill -0 "$owner" 2>/dev/null || rm -rf "$LOCK";; esac
fi
mkdir "$LOCK" 2>/dev/null || { echo 'OMP install already running' >&2; exit 1; }
printf '%s\n' "$$" > "$LOCK/pid"
# Trees left by killed installs.
for stale in "$ROOT".new.* "$ROOT".old.*; do
    [ -d "$stale" ] || continue
    spid=${stale##*.}
    case "$spid" in ''|*[!0-9]*) continue;; esac
    if [ "$spid" != "$$" ] && ! kill -0 "$spid" 2>/dev/null; then
        case "$stale" in "$ROOT".old.*) [ -d "$ROOT" ] || { mv "$stale" "$ROOT"; continue; };; esac
        rm -rf "$stale"
    fi
done
NEW=$ROOT.new.$$
OLD=$ROOT.old.$$
cleanup() {
    if [ -d "$OLD" ] && [ ! -d "$ROOT" ]; then mv "$OLD" "$ROOT"; fi
    rm -rf "$NEW"
    rm -rf "$LOCK"
}
[ ! -e "$OLD" ] || { rm -rf "$LOCK"; echo 'Stale OMP backup path' >&2; exit 1; }
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir "$NEW"
mkdir -p "$NEW/assets" "$NEW/usr" "$NEW/home/.bun/bin" "$NEW/jobs" "$NEW/.tmp" "$NEW/termux/files/usr" "$NEW/termux/files/home"
/system/bin/unzip -p "$APK" assets/omp/manifest.json > "$NEW/installed.json"
[ -s "$NEW/installed.json" ] || { echo 'OMP not available in this build' >&2; exit 1; }
if [ "${BACKSTAGE_OMP_REPAIR:-0}" != 1 ] && [ -x "$ROOT/home/.bun/bin/bun" ] && [ -f "$ROOT/run.sh" ] && cmp -s "$NEW/installed.json" "$ROOT/installed.json"; then
    printf '%s\n' 'OMP already installed'
    exit 0
fi
# A running job refers to this tree; finish/cancel it before swapping the installation.
for pidfile in "$ROOT"/jobs/*/pid; do
    [ -f "$pidfile" ] || continue
    jobdir=${pidfile%/pid}
    if [ ! -f "$jobdir/exit" ]; then
        pid=$(cat "$pidfile")
        case "$pid" in ''|*[!0-9]*) ;; *) if kill -0 "$pid" 2>/dev/null; then echo 'Finish or cancel OMP jobs before repairing' >&2; exit 1; fi;; esac
    fi
done
step 'checking payload'
MANIFEST=$(tr -d '\n\r' < "$NEW/installed.json")
for file in termux-bootstrap-aarch64.zip bun-linux-aarch64-android.zip omp-tree.tgz omp-termux device/install.sh device/run.sh device/job.sh device/rpc-bridge.js device/omp device/backstage-omp-key device/backstage-sh device/termux-setup.sh; do
    hash=$(printf '%s\n' "$MANIFEST" | sed -n 's|.*"'"$file"'"[[:space:]]*:[[:space:]]*{[^}]*"sha256"[[:space:]]*:[[:space:]]*"\([a-f0-9]*\)".*|\1|p')
    [ ${#hash} -eq 64 ] || { echo "Missing payload hash: $file" >&2; exit 1; }
    dest=$NEW/assets/$file
    mkdir -p "${dest%/*}"
    /system/bin/unzip -p "$APK" "assets/omp/$file" > "$dest"
    actual=$(sha256sum "$dest"); actual=${actual%% *}
    [ "$actual" = "$hash" ] || { echo "Payload checksum failed: $file" >&2; exit 1; }
done
step 'unpacking Termux userland'
/system/bin/unzip -q "$NEW/assets/termux-bootstrap-aarch64.zip" -d "$NEW/usr"
# Bootstrap symlinks are text records: target U+2190 relative-link-path.
arrow='←'
while IFS= read -r line || [ -n "$line" ]; do
    [ -n "$line" ] || continue
    target=${line%%"$arrow"*}; link=${line#*"$arrow"}
    case "$link" in ''|/*|..|../*|*/../*|*/..) echo 'Unsafe bootstrap symlink' >&2; exit 1;; esac
    [ "$target" != "$line" ] || { echo 'Malformed bootstrap symlink' >&2; exit 1; }
    mkdir -p "$(dirname "$NEW/usr/$link")"
    ln -sf "$target" "$NEW/usr/$link"
done < "$NEW/usr/SYMLINKS.txt"
rm "$NEW/usr/SYMLINKS.txt"
# zip permission metadata is not consistently restored by Android unzip.
for dir in bin libexec lib/apt; do
    if [ -d "$NEW/usr/$dir" ]; then find "$NEW/usr/$dir" -type f -exec chmod 700 {} \;; fi
done
find "$NEW/usr" -type d -exec chmod 700 {} \;
step 'unpacking bun'
/system/bin/unzip -q "$NEW/assets/bun-linux-aarch64-android.zip" -d "$NEW/bun-unpack"
bun=$(find "$NEW/bun-unpack" -type f -name bun | head -n 1)
[ -n "$bun" ] || { echo 'Missing bun binary' >&2; exit 1; }
cp "$bun" "$NEW/home/.bun/bin/bun"
chmod 700 "$NEW/home/.bun/bin/bun"
step 'unpacking omp'
# gzip + plain tar: toybox tar's -z support varies between Android builds.
gzip -dc "$NEW/assets/omp-tree.tgz" | tar -xf - -C "$NEW/home/.bun"
[ -f "$NEW/home/.bun/install/global/node_modules/@oh-my-pi/pi-coding-agent/dist/cli.js" ] || { echo 'omp files missing after unpacking' >&2; exit 1; }
[ -f "$NEW/home/.bun/install/global/node_modules/@oh-my-pi/pi-natives/native/pi_natives.android-arm64.node" ] || { echo 'omp Android addon missing after unpacking' >&2; exit 1; }
step 'finishing'
cp "$NEW/assets/omp-termux" "$NEW/usr/bin/omp-termux"
cp "$NEW/assets/device/omp" "$NEW/home/.bun/bin/omp"
cp "$NEW/assets/device/backstage-omp-key" "$NEW/usr/bin/backstage-omp-key"
cp "$NEW/assets/device/backstage-sh" "$NEW/usr/bin/backstage-sh"
cp "$NEW/assets/device/run.sh" "$NEW/run.sh"
cp "$NEW/assets/device/job.sh" "$NEW/job.sh"
cp "$NEW/assets/device/rpc-bridge.js" "$NEW/rpc-bridge.js"
chmod 700 "$NEW/usr/bin/omp-termux" "$NEW/usr/bin/backstage-omp-key" "$NEW/usr/bin/backstage-sh" "$NEW/home/.bun/bin/omp" "$NEW/run.sh" "$NEW/job.sh" "$NEW/rpc-bridge.js"
mkdir -p "$NEW/usr/tmp"
# Preserve user agent state and job history; API keys are never copied or materialized.
if [ -d "$ROOT/home/.omp" ]; then cp -a "$ROOT/home/.omp" "$NEW/home/.omp"; fi
if [ -d "$ROOT/home/.config/backstage-omp" ]; then mkdir -p "$NEW/home/.config"; cp -a "$ROOT/home/.config/backstage-omp" "$NEW/home/.config/"; fi
if [ -d "$ROOT/jobs" ]; then cp -a "$ROOT/jobs/." "$NEW/jobs/"; fi
rm -rf "$NEW/assets" "$NEW/bun-unpack"
[ ! -e "$ROOT" ] || mv "$ROOT" "$OLD"
mv "$NEW" "$ROOT"
rm -rf "$OLD"
printf '%s\n' 'OMP installed'
