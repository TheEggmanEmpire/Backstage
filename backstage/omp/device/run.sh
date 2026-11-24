#!/system/bin/sh
set -eu
ROOT=${1:?ROOT}; LIBDIR=${2:?LIBDIR}; shift 2
[ "${1:-}" = -- ] || exit 2
shift
[ "$#" -gt 0 ] || exit 2
case "$ROOT" in /data/local/tmp/backstage-omp|/data/local/tmp/backstage-agent-omp) ;; *) exit 2;; esac
PREFIX=/data/data/com.termux/files/usr
HOME=/data/data/com.termux/files/home
export PREFIX HOME
export TMPDIR=$PREFIX/tmp PATH=$HOME/.bun/bin:$PREFIX/bin:/system/bin
export BUN_INSTALL=$HOME/.bun
export LANG=en_US.UTF-8 TERM=dumb OMP_TERMUX_NO_UPDATE_CHECK=1
export PROOT_LOADER=$LIBDIR/libproot_loader.so PROOT_TMP_DIR=$ROOT/.tmp
mkdir -p "$PROOT_TMP_DIR"
# The real /data/data/com.termux (when the Termux app is installed) is private to Termux: proot would stat it as
# the shell user while walking the path and fail every lookup. Bind the whole app directory to our own tree;
# the longer usr/home binds below still win for those two.
mkdir -p "$ROOT/termux/files/usr" "$ROOT/termux/files/home"
# Apply termux-exec inside the tracee, never to Android's proot executable itself.
unset LD_PRELOAD
exec "$LIBDIR/libproot.so" --kill-on-exit --link2symlink -r / \
    -b "$ROOT/termux:/data/data/com.termux" -b "$ROOT/usr:$PREFIX" -b "$ROOT/home:$HOME" -w "$HOME" \
    /system/bin/env LD_PRELOAD="$PREFIX/lib/libtermux-exec-ld-preload.so" "$@"
