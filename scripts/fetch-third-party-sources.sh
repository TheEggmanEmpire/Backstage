#!/usr/bin/env bash
# Download the corresponding source of the prebuilt third-party binaries in the APK (licenses/third-party-sources.tsv)
# so a release can carry it next to the APK (GPL-3.0 §6, LGPL-2.1 §6: the source must stay available even when the
# third-party server drops it).
#
#   scripts/fetch-third-party-sources.sh OUT_DIR [TABLE]
#
# Writes OUT_DIR/<file> for every row and OUT_DIR/SOURCES-SHA256SUMS. A pinned SHA-256 that does not match, or a
# failed download, is an error (exit 1): a release must not go out without its sources.
set -euo pipefail
export LC_ALL=C
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
out=${1:?usage: scripts/fetch-third-party-sources.sh OUT_DIR [TABLE]}
table=${2:-$ROOT/licenses/third-party-sources.tsv}
# The bootstrap pin can move independently of Dextop: derive its source row from the same APK pin file.
# An explicit TABLE is a complete caller-provided list (also used by the offline tests).
if (( $# < 2 )); then
  generated_table=$(mktemp "${TMPDIR:-/tmp}/backstage-sources.XXXXXX")
  trap 'rm -f "$generated_table"' EXIT
  cat "$table" > "$generated_table"
  python3 -I "$ROOT/scripts/lib/omp_payload.py" source-row --pins "$ROOT/upstream/omp-termux/UPSTREAM.env" >> "$generated_table"
  table=$generated_table
fi
mkdir -p "$out"
: > "$out/SOURCES-SHA256SUMS"
n=0
while IFS=$'\t' read -r name url sha covers; do
  case "$name" in ''|'#'*) continue ;; esac
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._+-]*$ ]] || { echo "bad file name '$name' in $table" >&2; exit 1; }
  [ -n "${url:-}" ] && [ -n "${sha:-}" ] && [ -n "${covers:-}" ] || { echo "row '$name' needs 4 tab-separated columns" >&2; exit 1; }
  echo "fetching $name ($covers)" >&2
  curl -fsSL --retry 3 -o "$out/$name.part" "$url" || { echo "download failed: $url" >&2; exit 1; }
  got=$(sha256sum "$out/$name.part" | cut -d' ' -f1)
  if [ "$sha" != - ] && [ "$got" != "$sha" ]; then
    echo "SHA-256 mismatch for $name: got $got, pinned $sha" >&2; exit 1
  fi
  mv "$out/$name.part" "$out/$name"
  printf '%s  %s\n' "$got" "$name" >> "$out/SOURCES-SHA256SUMS"
  n=$((n + 1))
done < "$table"
[ "$n" -gt 0 ] || { echo "no sources listed in $table" >&2; exit 1; }
echo "fetched $n source archive(s) into $out" >&2
