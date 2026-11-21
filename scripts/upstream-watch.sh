#!/usr/bin/env bash
# Is there a new upstream release to integrate? (scheduled by.github/workflows/upstream-watch.yml)
#
#   scripts/upstream-watch.sh [--report FILE]
#
# Compares, with `git ls-remote` only (no API token, no clone, nothing downloaded or executed):
#   * Dextop: the newest version tag of UPSTREAM_URL against the pinned UPSTREAM_REF (upstream/UPSTREAM.env), and
#     whether upstream main moved past the pinned commit (the daily canary rebases onto it).
# Prints a Markdown report (also written to FILE). Exit 0: nothing new; 3: a new release to integrate; 1: error.
# Override for tests: BACKSTAGE_WATCH_DEXTOP_URL.
. "$(dirname "$0")/lib.sh"
report=""
while (($#)); do
  case "$1" in
    --report) report=$2; shift 2 ;;
    -h|--help) awk 'NR > 1 { if (!/^#/) exit; sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
    *) die "unknown argument $1" ;;
  esac
done
dextop_url=${BACKSTAGE_WATCH_DEXTOP_URL:-$UPSTREAM_URL}

remote() {  # url args... with retries (the network is flaky on runners)
  local attempt out
  for attempt in 1 2 3; do
    out=$(git ls-remote "$@" 2>/dev/null) && { printf '%s\n' "$out"; return 0; }
    sleep $((attempt * 3))
  done
  return 1
}
newest_tag() { sed -n 's#.*refs/tags/\(v\{0,1\}[0-9][0-9.]*\)$#\1#p' | sort -V | tail -n1; }
newer() { [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | tail -n1)" = "$1" ]; }   # $1 > $2

dextop_tags=$(remote --tags "$dextop_url") || die "cannot list the tags of $dextop_url"
dextop_tag=$(newest_tag <<<"$dextop_tags")
dextop_main=$(remote "$dextop_url" refs/heads/main | cut -f1) || die "cannot read main of $dextop_url"
pinned_tag=$(sed 's/-[0-9]*-g[0-9a-f]*$//' <<<"$UPSTREAM_REF")   # v1.7.0-26-g5402d09 -> v1.7.0

new=0
out=$(
  echo "# Upstream watch"
  echo
  if [ -n "$dextop_tag" ] && newer "$dextop_tag" "$pinned_tag"; then
    echo "- **Dextop $dextop_tag is released** (pinned: $UPSTREAM_REF). Integrate: \`scripts/update-upstream.sh --dry-run latest-tag\`, then without --dry-run."
  else
    echo "- Dextop: newest tag ${dextop_tag:-none}, pinned $UPSTREAM_REF: nothing new."
  fi
  if [ -n "$dextop_main" ] && [ "$dextop_main" != "$UPSTREAM_COMMIT" ]; then
    echo "- Dextop main is at \`${dextop_main:0:12}\` (pinned \`${UPSTREAM_COMMIT:0:12}\`); the daily upstream canary rebases onto it."
  fi
)
grep -q '^- \*\*' <<<"$out" && new=1
printf '%s\n' "$out"
[ -z "$report" ] || printf '%s\n' "$out" > "$report"
[ "$new" = 0 ] || exit 3
