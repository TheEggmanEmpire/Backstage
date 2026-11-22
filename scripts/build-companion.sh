#!/usr/bin/env bash
set -euo pipefail
if (($# == 0)); then set -- release; fi
exec "$(dirname "$0")/build-apk.sh" "$@" --flavor companion
