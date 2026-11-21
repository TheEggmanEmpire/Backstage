#!/usr/bin/env bash
# Self-test of the upstream/patch/build scripts against a tiny fake upstream (seconds, no network, no SDK).
#
#   scripts/tests/selftest.sh
#
# Copies scripts/ into a throw-away repository root with a two-commit fake "Dextop" (the newer commit carries
# an ANNOTATED tag) and a one-patch series, then checks:
#   1. check-patches.sh passes;
#   2. prepare-work-tree.sh refuses /, $HOME, the repository itself and a non-empty foreign directory;
#   3. a re-prepare saves local commits and uncommitted edits under refs/backstage/saved/* before resetting;
#   4. test.sh stops (and runs no suite) when prepare-work-tree.sh fails on a previously prepared tree;
#   5. build-apk.sh --no-prepare refuses a tree prepared from another series;
#   6. update-upstream.sh <annotated tag> pins the COMMIT (not the tag object) and writes a sourceable env;
#   7. a conflicting bump stops with exit 2, a report with the hunks, and the pin untouched;
#   8. --continue refuses staged conflict markers; a real resolution finishes the bump and is recorded in
#      patches/.rr-cache; the snapshot ignores upstream export-ignore attributes (exact commit tree);
#   9. the committed rr-cache replays the resolution on a fresh mirror (no stop, identical series);
#  10. --abort cleans up (no worktree, no state, no unresolved rerere preimage left in patches/.rr-cache);
#  11. a patch upstream already contains is dropped from the series with a warning in the report;
#  13. an existing upstream mirror follows upstream branches (ensure_cache), so `update-upstream.sh main` is current;
#  14. build-apk.sh release refuses a checkout with uncommitted or untracked files (the APK names its source commit);
#  16. --dry-run changes nothing, replays rr-cache, reports conflicts (exit 2); the glue symbol check flags an upstream
#      class the glue uses that the target dropped; the bump report carries gates, hot spots and next steps;
#  17. upstream-watch.sh reports a newer Dextop tag than the pin (exit 3);
#  15. the series marker alone is not "prepared": a tree reset to `patched` (no hooks) or prepared by another checkout
#      sharing BACKSTAGE_WORK (hooks name its backstage/) is refused by --no-prepare and re-prepared;
#  The repository root of the fixture contains a space (no script may word-split a path).
#  12. the unit tests of verify_apk.py, flutter_test_report.py and the CI workflow rules
#      (scripts/tests/test_*.py).
# `flutter` is a stub that only records its arguments.
set -euo pipefail
export LC_ALL=C
SRC=$(cd "$(dirname "$0")/../.." && pwd -P)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/backstage-selftest.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
G=(git -c user.name=selftest -c user.email=selftest@backstage.invalid -c commit.gpgsign=false -c init.defaultBranch=main
   -c advice.detachedHead=false)
pass=0
ok()   { pass=$((pass + 1)); printf 'ok   %s\n' "$*"; }
fail() { printf 'FAIL %s\n' "$*" >&2; exit 1; }
# expect_fail NAME PATTERN CMD...: CMD must exit non-zero and print PATTERN (stdout+stderr)
expect_fail() {
  local name=$1 pattern=$2; shift 2
  if "$@" >"$tmp/out" 2>&1; then cat "$tmp/out" >&2; fail "$name: succeeded"; fi
  grep -q -- "$pattern" "$tmp/out" || { cat "$tmp/out" >&2; fail "$name: no '$pattern' in output"; }
  ok "$name"
}

# ---- fake upstream: commit A (pinned) and commit B (annotated tag v2.0.0) -----------------------------------
up=$tmp/upstream.git
mkdir -p "$up/android/app/src/main/kotlin" "$up/android/gradle/wrapper" "$up/.github/workflows" "$up/lib"
cd "$up"
"${G[@]}" init -q
printf 'name: fake\nversion: 1.0.0+1\nenvironment:\n  sdk: ">=3.0.0 <4.0.0"\n' > pubspec.yaml
printf 'plugins {\n    id("com.android.application") version "8.11.1" apply false\n    id("org.jetbrains.kotlin.android") version "2.2.20" apply false\n}\n' > android/settings.gradle.kts
printf 'android {\n    externalNativeBuild {\n        cmake {\n            version = "3.22.1"\n        }\n    }\n}\n' > android/app/build.gradle.kts
printf 'distributionUrl=https\\://services.gradle.org/distributions/gradle-8.14.3-all.zip\n' > android/gradle/wrapper/gradle-wrapper.properties
printf 'jobs:\n  build:\n    steps:\n      - with:\n          flutter-version: 3.47.7\n          channel: stable\n      - with:\n          java-version: "21"\n' > .github/workflows/debug-apk.yml
printf 'class Foo\n' > android/app/src/main/kotlin/Foo.kt
printf 'void main() {}\n' > lib/main.dart
"${G[@]}" add -A && "${G[@]}" commit -q -m A
A=$("${G[@]}" rev-parse HEAD)
printf 'void main() {}\n// v2\n' > lib/main.dart
sed -i 's/^version: .*/version: 1.0.1+2/' pubspec.yaml
"${G[@]}" commit -q -am B
B=$("${G[@]}" rev-parse HEAD)
"${G[@]}" tag -a v2.0.0 -m "release 2.0.0"
[ "$("${G[@]}" cat-file -t v2.0.0)" = tag ] || fail "fixture: v2.0.0 is not an annotated tag"

# ---- fake repository root: scripts + snapshot of A + one patch -------------------------------------------------
root="$tmp/my repo"   # a space in the checkout path 
mkdir -p "$root/upstream/dextop" "$root/patches" "$root/backstage"
cp -R "$SRC/scripts" "$root/scripts"
"${G[@]}" -C "$up" archive "$A" | tar -x -C "$root/upstream/dextop"
cat > "$root/upstream/UPSTREAM.env" <<ENV
UPSTREAM_URL=$up
UPSTREAM_REF=$("${G[@]}" -C "$up" describe --tags --always "$A")
UPSTREAM_COMMIT=$A
UPSTREAM_TREE=$("${G[@]}" -C "$up" rev-parse "$A^{tree}")
UPSTREAM_DATE=$("${G[@]}" -C "$up" log -1 --format=%cI "$A")
UPSTREAM_VERSION=1.0.0+1
ENV
"${G[@]}" clone -q "$up" "$tmp/dev" && cd "$tmp/dev" && "${G[@]}" checkout -q "$A"
printf 'class Foo\n// backstage\n' > android/app/src/main/kotlin/Foo.kt
"${G[@]}" commit -q -am fake-change
"${G[@]}" format-patch -q --zero-commit --no-signature --no-numbered --full-index -o "$root/patches" -1 HEAD
"$root/scripts/flutter-version.sh" --sync >/dev/null 2>&1
cd "$tmp"

# Isolated environment: stub flutter, empty SDK dir, private HOME (no ~/.backstage-toolchain.env, no git config).
mkdir -p "$tmp/bin" "$tmp/sdk" "$tmp/home"
printf '#!/bin/sh\necho "$*" >> "%s/flutter.log"\nexit 0\n' "$tmp" > "$tmp/bin/flutter"; chmod +x "$tmp/bin/flutter"
export HOME=$tmp/home ANDROID_HOME=$tmp/sdk PATH="$tmp/bin:$PATH" GIT_CONFIG_NOSYSTEM=1
unset ANDROID_SDK_ROOT FLUTTER_ROOT BACKSTAGE_WORK BACKSTAGE_BUILD_LOCK GRADLE_OPTS
work=$root/build/work/dextop

# 1 ----------------------------------------------------------------------------------------------------------------
"$root/scripts/check-patches.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "check-patches on the fixture"; }
ok "check-patches.sh passes"

# 2 ----------------------------------------------------------------------------------------------------------------
expect_fail "refuses the repository root" "contains the Backstage repository" env BACKSTAGE_WORK="$root" "$root/scripts/prepare-work-tree.sh"
expect_fail "refuses an ancestor of the repository" "contains the Backstage repository" env BACKSTAGE_WORK="$tmp" "$root/scripts/prepare-work-tree.sh"
expect_fail "refuses /" "dedicated directory" env BACKSTAGE_WORK=/ "$root/scripts/prepare-work-tree.sh"
expect_fail "refuses \$HOME" "dedicated directory" env BACKSTAGE_WORK="$HOME" "$root/scripts/prepare-work-tree.sh"
mkdir -p "$tmp/foreign" && echo keep > "$tmp/foreign/data.txt"
expect_fail "refuses a non-empty foreign directory" "not a work tree made by" env BACKSTAGE_WORK="$tmp/foreign" "$root/scripts/prepare-work-tree.sh"
[ "$(cat "$tmp/foreign/data.txt")" = keep ] && [ ! -e "$tmp/foreign/.git" ] || fail "foreign directory was modified"
ok "foreign directory untouched"
[ ! -e "$root/.git" ] && [ ! -e "$tmp/.git" ] && [ ! -e "$HOME/.git" ] || fail "a refused directory became a git repository"

# 3 ----------------------------------------------------------------------------------------------------------------
"$root/scripts/prepare-work-tree.sh" >/dev/null 2>&1 || fail "prepare-work-tree.sh on the fixture"
grep -q '// backstage' "$work/android/app/src/main/kotlin/Foo.kt" || fail "patch not applied"
grep -q '// backstage:app' "$work/android/app/build.gradle.kts" || fail "app hook not injected"
[ -n "$(git -C "$work" config --get backstage.prepared)" ] || fail "no backstage.prepared marker"
ok "prepare-work-tree.sh applies the series and records the marker"
echo "dev" > "$work/lib/dev.dart"
"${G[@]}" -C "$work" add lib/dev.dart && "${G[@]}" -C "$work" commit -q -m "local work"
echo "// edited" >> "$work/lib/main.dart"
echo "# selftest: series changed" >> "$root/scripts/inject.sh"
"$root/scripts/prepare-work-tree.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "re-prepare"; }
saved=$("${G[@]}" -C "$work" for-each-ref --format='%(refname)' refs/backstage/saved)
grep -q -- '-commits$' <<<"$saved" || fail "local commit not saved: $saved"
grep -q -- '-uncommitted$' <<<"$saved" || fail "uncommitted edit not saved: $saved"
[ ! -e "$work/lib/dev.dart" ] && ! grep -q edited "$work/lib/main.dart" || fail "work tree not reset to the series"
"${G[@]}" -C "$work" show "$(grep -- '-commits$' <<<"$saved"):lib/dev.dart" | grep -q dev || fail "saved commit lost its content"
grep -q "refs/backstage/saved" "$tmp/out" || fail "no warning about saved work"
ok "re-prepare saves local commits and edits before resetting"

# 4 ----------------------------------------------------------------------------------------------------------------
# The series changed AND the snapshot drifted: prepare dies at the drift guard before touching the old tree, which
# still carries the hooks of the previous series (the case the old hook-marker check let through).
echo "# selftest: series changed for test 4" >> "$root/scripts/inject.sh"
echo "// drift" >> "$root/upstream/dextop/lib/main.dart"
rm -f "$tmp/flutter.log"
expect_fail "test.sh stops when prepare fails" "prepare-work-tree.sh failed" "$root/scripts/test.sh" --only flutter
! grep -qs '^test' "$tmp/flutter.log" || fail "flutter test ran on a stale tree"
ok "no suite ran on the stale tree"
"${G[@]}" -C "$up" show "$A:lib/main.dart" > "$root/upstream/dextop/lib/main.dart"

# 5 ----------------------------------------------------------------------------------------------------------------
echo "# selftest: series changed again" >> "$root/scripts/inject.sh"
rm -f "$tmp/flutter.log"
expect_fail "build-apk.sh --no-prepare refuses a stale tree" "not prepared from the current" "$root/scripts/build-apk.sh" debug --no-prepare
! grep -qs '^build' "$tmp/flutter.log" || fail "flutter build ran on a stale tree"
ok "no build ran on the stale tree"

# 15 ---------------------------------------------------------------------------------------------------------------
# The series marker alone is not "prepared": a tree reset to `patched` by hand has no hooks, and a work tree shared by
# a second checkout has hooks naming the other checkout's backstage/. Both are refused and re-prepared.
"$root/scripts/prepare-work-tree.sh" >/dev/null 2>&1 || fail "prepare before the hook checks"
"$root/scripts/inject.sh" --check "$work" || fail "inject.sh --check rejects a freshly prepared tree"
"${G[@]}" -C "$work" checkout -q patched
expect_fail "build-apk.sh --no-prepare refuses a tree without the hooks" "lacks the Gradle hooks" \
  "$root/scripts/build-apk.sh" debug --no-prepare
"$root/scripts/prepare-work-tree.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "re-prepare after checkout of patched"; }
! grep -q 'work tree up to date' "$tmp/out" && grep -q '// backstage:app' "$work/android/app/build.gradle.kts" ||
  { cat "$tmp/out" >&2; fail "prepare kept a tree without hooks"; }
ok "prepare re-injects the hooks of a tree reset to 'patched'"
other="$tmp/other checkout"; mkdir -p "$other"
tar -C "$root" --exclude=./build -cf - . | tar -C "$other" -xf -
BACKSTAGE_WORK=$work "$other/scripts/prepare-work-tree.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "prepare from a 2nd checkout"; }
grep -qF 'other checkout/backstage/dextop-integration.gradle' "$work/android/app/build.gradle.kts" ||
  { cat "$tmp/out" >&2; fail "shared work tree still builds the first checkout's backstage/"; }
expect_fail "the first checkout refuses the tree the second prepared" "lacks the Gradle hooks" \
  "$root/scripts/build-apk.sh" debug --no-prepare
"$root/scripts/prepare-work-tree.sh" >/dev/null 2>&1 || fail "prepare back from the first checkout"
"$root/scripts/inject.sh" --check "$work" && ! grep -q 'other checkout' "$work/android/app/build.gradle.kts" &&
  [ "$(grep -c "backstage:app" "$work/android/app/build.gradle.kts")" = 1 ] || { cat "$work/android/app/build.gradle.kts" >&2; fail "hooks not rewritten for the first checkout"; }
rm -rf "$other"
ok "a work tree shared by two checkouts is re-prepared for the one that builds"
cp "$root/patches/0001-fake-change.patch" "$tmp/before.patch"
"$root/scripts/export-patches.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "export-patches.sh on the prepared tree"; }
cmp -s "$tmp/before.patch" "$root/patches/0001-fake-change.patch" && [ "$(ls "$root"/patches/*.patch | wc -l)" = 1 ] ||
  { cat "$tmp/out" >&2; fail "export-patches.sh changed an unchanged series"; }
ok "export-patches.sh round-trips the series (checkout path with a space)"

# 6 ----------------------------------------------------------------------------------------------------------------
"$root/scripts/update-upstream.sh" --ci v2.0.0 >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "update-upstream.sh v2.0.0"; }
env_check=$(set -e; . "$root/upstream/UPSTREAM.env"; echo "$UPSTREAM_COMMIT|$UPSTREAM_VERSION|$UPSTREAM_REF")
[ "${env_check%%|*}" = "$B" ] || fail "UPSTREAM_COMMIT is not the tagged commit: $env_check"
[ "$(wc -l < "$root/upstream/UPSTREAM.env")" -eq 6 ] || fail "UPSTREAM.env is not 6 lines"
grep -q '^UPSTREAM_VERSION=1.0.1+2$' "$root/upstream/UPSTREAM.env" || fail "version not updated"
grep -q '// v2' "$root/upstream/dextop/lib/main.dart" || fail "snapshot not replaced"
[ -f "$root/patches/0001-fake-change.patch" ] || fail "patch series not regenerated"
"$root/scripts/check-patches.sh" >/dev/null 2>&1 || fail "check-patches after the bump"
ok "update-upstream.sh <annotated tag> pins the commit and writes a sourceable UPSTREAM.env"

# ---- conflict / rerere fixtures: C (tag v3.0.0) conflicts with the patch, D (tag v2.1.0, off B) contains it ------
foo=android/app/src/main/kotlin/Foo.kt
cd "$up"
{ printf 'class Foo\n// upstream v3\n'; seq -f '// upstream line %g' 20000; } > "$foo"   # hunk report > pipe buffer
printf 'secret.txt export-ignore\nstamp.txt export-subst\n' > .gitattributes
echo secret > secret.txt; echo '$Format:%H$' > stamp.txt
"${G[@]}" add -A && "${G[@]}" commit -q -m C && "${G[@]}" tag v3.0.0
C=$("${G[@]}" rev-parse HEAD)
"${G[@]}" checkout -q "$B" && printf 'class Foo\n// backstage\n' > "$foo"
"${G[@]}" commit -q -am D && "${G[@]}" tag v2.1.0 && "${G[@]}" checkout -q main
cd "$tmp"
save_pin()    { rm -rf "$tmp/pin" && mkdir -p "$tmp/pin" && cp -a "$root/upstream" "$root/toolchain.env" "$tmp/pin/" &&
                cp "$root"/patches/*.patch "$tmp/pin/"; }
restore_pin() { rm -rf "$root/upstream" "$root"/patches/*.patch && cp -a "$tmp/pin/upstream" "$tmp/pin/toolchain.env" "$root/" &&
                cp "$tmp"/pin/*.patch "$root/patches/"; }
pinned() { (set -e; . "$root/upstream/UPSTREAM.env"; echo "$UPSTREAM_COMMIT"); }
save_pin

expect_fail "CI refuses a tag changed since discovery" "latest upstream tag changed" env BACKSTAGE_EXPECT_UPSTREAM_TAG=v99.0.0 "$root/scripts/update-upstream.sh" --ci latest-tag
[ "$(pinned)" = "$B" ] || fail "CI tag mismatch changed the snapshot pin"

# 7 ----------------------------------------------------------------------------------------------------------------
set +e; "$root/scripts/update-upstream.sh" v3.0.0 >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 2 ] || { cat "$tmp/out" >&2; fail "conflicting bump: exit $rc, expected 2"; }
grep -q 'CONFLICT in fake-change' "$tmp/out" && grep -q "conflicted: $foo" "$tmp/out" || { cat "$tmp/out" >&2; fail "no conflict summary"; }
report=$root/build/upstream-rebase.report.md
grep -q "^- $foo" "$report" && grep -q '^[0-9]*: <<<<<<< ' "$report" || { cat "$report" >&2; fail "report lacks files or hunks"; }
[ "$(pinned)" = "$B" ] && [ -f "$root/build/upstream-rebase.state" ] || fail "pin changed or no state after a conflict"
ok "conflicting bump stops with exit 2, a hunk report and the pin untouched"

# 8 ----------------------------------------------------------------------------------------------------------------
uwt=$root/build/upstream-rebase
"${G[@]}" -C "$uwt" add "$foo"   # markers still in the file
expect_fail "--continue refuses staged conflict markers" "conflict markers" "$root/scripts/update-upstream.sh" --continue
[ "$(pinned)" = "$B" ] || fail "pin changed after a refused --continue"
printf 'class Foo\n// upstream v3\n// backstage\n' > "$uwt/$foo" && "${G[@]}" -C "$uwt" add "$foo"
"$root/scripts/update-upstream.sh" --continue >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "--continue after resolving"; }
[ "$(pinned)" = "$C" ] && [ ! -e "$uwt" ] && [ ! -e "$root/build/upstream-rebase.state" ] || fail "bump not finished cleanly"
grep -q '^+// backstage' "$root/patches/0001-fake-change.patch" || fail "resolved patch not regenerated"
[ -f "$root/upstream/dextop/secret.txt" ] && grep -qF '$Format:%H$' "$root/upstream/dextop/stamp.txt" ||
  fail "snapshot honoured export-ignore/export-subst (not the exact commit tree)"
ls "$root"/patches/.rr-cache/*/postimage >/dev/null 2>&1 || fail "no recorded resolution in patches/.rr-cache"
grep -q 'new conflict resolutions recorded by rerere: 1' "$report" || { cat "$report" >&2; fail "report does not mention the resolution"; }
"$root/scripts/check-patches.sh" >/dev/null 2>&1 || fail "check-patches after the resolved bump"
cp "$root/patches/0001-fake-change.patch" "$tmp/resolved.patch"
ok "--continue refuses markers; the resolved bump finishes, records the resolution, snapshots the exact tree"

# 9 ----------------------------------------------------------------------------------------------------------------
restore_pin && rm -rf "$root/.cache"   # fresh mirror: only the committed patches/.rr-cache carries the resolution
"$root/scripts/update-upstream.sh" v3.0.0 >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "replayed bump"; }
grep -q 'auto-resolved by rerere: fake-change' "$tmp/out" || { cat "$tmp/out" >&2; fail "rerere did not replay"; }
cmp -s "$tmp/resolved.patch" "$root/patches/0001-fake-change.patch" || fail "replayed series differs from the resolved one"
! grep -q 'new conflict resolutions' "$report" || fail "a replayed resolution was reported as new"
ok "committed rr-cache replays the resolution on a fresh mirror"

# 10 ---------------------------------------------------------------------------------------------------------------
restore_pin && mv "$root/patches/.rr-cache" "$tmp/rr-saved" && mkdir "$root/patches/.rr-cache"
set +e; "$root/scripts/update-upstream.sh" v3.0.0 >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 2 ] || { cat "$tmp/out" >&2; fail "bump without rr-cache: exit $rc, expected 2"; }
"$root/scripts/update-upstream.sh" --abort >/dev/null 2>&1 || fail "--abort"
[ ! -e "$uwt" ] && [ ! -e "$root/build/upstream-rebase.state" ] && [ "$(pinned)" = "$B" ] || fail "--abort left state behind"
[ -z "$(ls -A "$root/patches/.rr-cache")" ] || fail "--abort left rerere entries: $(ls -A "$root/patches/.rr-cache")"
rm -rf "$root/patches/.rr-cache" && mv "$tmp/rr-saved" "$root/patches/.rr-cache"
ok "--abort removes the worktree, the state and unresolved rerere preimages"

# 11 ---------------------------------------------------------------------------------------------------------------
restore_pin
"$root/scripts/update-upstream.sh" v2.1.0 >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "bump to a tree containing the patch"; }
[ -z "$(ls "$root"/patches/*.patch 2>/dev/null)" ] || fail "upstreamed patch still in the series"
grep -q 'patches no longer in the series' "$tmp/out" && grep -q 'patches dropped' "$report" ||
  { cat "$tmp/out" "$report" >&2; fail "dropped patch not reported"; }
ok "a patch upstream already took is dropped with a warning"

# 13 ---------------------------------------------------------------------------------------------------------------
# A persistent mirror must follow upstream branches: `update-upstream.sh main` resolves the bare name `main`, which
# A cached bare clone must expose commits added to upstream after its creation.
[ -d "$root/.cache/dextop.git" ] || fail "fixture: no persistent mirror left by the previous steps"
cd "$up" && echo "// v4" >> lib/main.dart && "${G[@]}" commit -q -am E && E=$("${G[@]}" rev-parse HEAD) && cd "$tmp"
(set -e; . "$root/scripts/lib.sh"; ensure_cache) >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "ensure_cache on the existing mirror"; }
[ "$(git -C "$root/.cache/dextop.git" rev-parse -q --verify 'main^{commit}')" = "$E" ] ||
  fail "the mirror's main is stale ($(git -C "$root/.cache/dextop.git" rev-parse main)), upstream main is $E"
[ "$(git -C "$root/.cache/dextop.git" rev-parse -q --verify 'upstream/main^{commit}')" = "$E" ] || fail "upstream/main not updated"
ok "an existing mirror follows upstream branches (update-upstream.sh main sees the new commit)"

# 16 ---------------------------------------------------------------------------------------------------------------
# --dry-run changes nothing (pin, series, rr-cache, no worktree/state), replays recorded resolutions, reports
# conflicts with exit 2; the glue symbol check flags an upstream class the glue uses that the target does not
# declares (dry run: exit 1; real bump: done, but exit 1 with the failure in the report); bad --test is refused.
restore_pin
rr_list() { (cd "$root/patches/.rr-cache" && find . -type f | sort | xargs -r sha256sum); }
rr_before=$(rr_list)
dry_report=$root/build/upstream-dryrun.report.md
"$root/scripts/update-upstream.sh" --dry-run v3.0.0 >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "dry run with a recorded resolution"; }
grep -q 'auto-resolved by rerere: fake-change' "$tmp/out" && grep -q '^## Conflicts rerere cannot resolve' "$dry_report" &&
  grep -q 'Nothing in the checkout was changed' "$dry_report" || { cat "$tmp/out" >&2; fail "dry-run report"; }
[ "$(pinned)" = "$B" ] && [ ! -e "$uwt" ] && [ ! -e "$root/build/upstream-rebase.state" ] || fail "dry run changed the pin or left state"
cmp -s "$tmp/pin/0001-fake-change.patch" "$root/patches/0001-fake-change.patch" || fail "dry run changed the series"
[ "$rr_before" = "$(rr_list)" ] || fail "dry run changed patches/.rr-cache"
ok "--dry-run replays recorded resolutions and changes nothing"
mv "$root/patches/.rr-cache" "$tmp/rr-saved" && mkdir "$root/patches/.rr-cache"
set +e; "$root/scripts/update-upstream.sh" --dry-run v3.0.0 >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 2 ] || { cat "$tmp/out" >&2; fail "dry run with a conflict: exit $rc, expected 2"; }
grep -q "^- \`fake-change\`: \`$foo\`" "$dry_report" || { cat "$dry_report" >&2; fail "dry run does not list the conflict"; }
[ -z "$(ls -A "$root/patches/.rr-cache")" ] && [ "$(pinned)" = "$B" ] && [ ! -e "$uwt" ] || fail "conflicting dry run left something behind"
rm -rf "$root/patches/.rr-cache" && mv "$tmp/rr-saved" "$root/patches/.rr-cache"
ok "--dry-run reports a conflict with exit 2 and leaves no state"
expect_fail "--test with an unknown level is refused" "--test must be" "$root/scripts/update-upstream.sh" --test bogus v3.0.0
cd "$up" && "${G[@]}" checkout -q "$B" && printf 'class Bar\n' > android/app/src/main/kotlin/Bar.kt &&
  "${G[@]}" add -A && "${G[@]}" commit -q -m H && "${G[@]}" tag v2.3.0 && H=$("${G[@]}" rev-parse HEAD) &&
  "${G[@]}" rm -q android/app/src/main/kotlin/Bar.kt && "${G[@]}" commit -q -m I && "${G[@]}" tag v2.4.0 &&
  I=$("${G[@]}" rev-parse HEAD) && "${G[@]}" checkout -q main && cd "$tmp"
mkdir -p "$root/backstage/dextop-glue/kotlin" && printf 'object Glue { val b = Bar(); val f = Foo() }\n' > "$root/backstage/dextop-glue/kotlin/Glue.kt"
"$root/scripts/update-upstream.sh" v2.3.0 >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "bump to v2.3.0 with the glue check"; }
grep -q 'glue symbol check: OK' "$report" && grep -q '^## Next steps' "$report" && grep -q 'versionCode check' "$report" &&
  grep -q '^## Hot spots' "$report" || { cat "$report" >&2; fail "bump report lacks gates, hot spots or next steps"; }
set +e; "$root/scripts/update-upstream.sh" --dry-run v2.4.0 >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 1 ] && grep -q 'glue symbol check: FAILED.*Bar' "$dry_report" || { cat "$tmp/out" >&2; fail "dry run missed the removed class (exit $rc)"; }
[ "$(pinned)" = "$H" ] || fail "dry run moved the pin"
set +e; "$root/scripts/update-upstream.sh" v2.4.0 >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 1 ] && [ "$(pinned)" = "$I" ] && grep -q 'glue symbol check: FAILED.*Bar' "$report" ||
  { cat "$tmp/out" >&2; fail "bump with a removed glue class: exit $rc, pin $(pinned)"; }
ok "the glue symbol check flags an upstream class the glue still uses (dry run and bump)"
rm -rf "$root/backstage/dextop-glue"; restore_pin

# 17 ---------------------------------------------------------------------------------------------------------------
# upstream-watch.sh: ls-remote only; exit 3 and a bold line for a newer Dextop tag than the pin, exit 0 when current.
set +e; "$root/scripts/upstream-watch.sh" --report "$tmp/watch.md" >"$tmp/out" 2>&1; rc=$?; set -e
[ "$rc" = 3 ] && grep -q '\*\*Dextop v3.0.0 is released\*\*' "$tmp/watch.md" ||
  { cat "$tmp/out" >&2; fail "watch: newer Dextop tag not reported (exit $rc)"; }
cp "$root/upstream/UPSTREAM.env" "$tmp/env.saved" && sed -i 's/^UPSTREAM_REF=.*/UPSTREAM_REF=v3.0.0-1-gabcdef0/' "$root/upstream/UPSTREAM.env"
"$root/scripts/upstream-watch.sh" >"$tmp/out" 2>&1 || { cat "$tmp/out" >&2; fail "watch: current pin reported as new"; }
cp "$tmp/env.saved" "$root/upstream/UPSTREAM.env"
ok "upstream-watch.sh reports a new Dextop tag (exit 3) and stays quiet otherwise"

# 14 ---------------------------------------------------------------------------------------------------------------
# A release must be built from a commit (its corresponding source): uncommitted or untracked files are refused before
# anything is prepared or built; a clean checkout gets past the check.
"${G[@]}" -C "$root" init -q && printf 'build/\n.cache/\n' > "$root/.gitignore"
"${G[@]}" -C "$root" add -A && "${G[@]}" -C "$root" commit -q -m fixture
echo "// local edit" >> "$root/scripts/inject.sh"
rm -f "$tmp/flutter.log"
expect_fail "build-apk.sh release refuses uncommitted changes" "release builds need a clean checkout" \
  "$root/scripts/build-apk.sh" release --no-prepare
"${G[@]}" -C "$root" checkout -q -- scripts/inject.sh && echo x > "$root/untracked.txt"
expect_fail "build-apk.sh release refuses untracked files" "untracked.txt" "$root/scripts/build-apk.sh" release --no-prepare
! grep -qs '^build' "$tmp/flutter.log" || fail "flutter build ran from a dirty checkout"
rm -f "$root/untracked.txt"
"$root/scripts/build-apk.sh" release --no-prepare >"$tmp/out" 2>&1 || true
! grep -q "clean checkout" "$tmp/out" || { cat "$tmp/out" >&2; fail "a clean checkout was refused"; }
ok "a clean checkout passes the release source check"
rm -rf "$root/.git" "$root/.gitignore"

# 12 ---------------------------------------------------------------------------------------------------------------
python3 -B -m unittest discover -s "$SRC/scripts/tests" -p 'test_*.py' >"$tmp/out" 2>&1 ||
  { cat "$tmp/out" >&2; fail "verify_apk unit tests"; }
ok "verify_apk.py unit tests"

echo "selftest: $pass checks passed"
