"""Regression coverage for signing, interrupted preparation, and test-suite selection."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]


class ScriptSafetyTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        (self.root / "scripts").mkdir()

    def run_script(self, name, *args, extra_env=None):
        shutil.copy(SCRIPTS / name, self.root / "scripts" / name)
        return subprocess.run(
            ["bash", str(self.root / "scripts" / name), *args],
            env={**os.environ, **(extra_env or {})}, capture_output=True, text=True,
        )

    def test_plain_signing_env_is_exported_without_logging_passwords(self):
        names = ("BACKSTAGE_KEYSTORE", "BACKSTAGE_KEYSTORE_PASSWORD",
                 "BACKSTAGE_KEY_ALIAS", "BACKSTAGE_KEY_PASSWORD")
        store = self.root / "private-store"
        store.touch()
        values = [str(store), "private-store-password", "private-alias", "private-key-password"]
        signing_env = self.root / "release.env"
        signing_env.write_text("".join(f"{name}='{value}'\n" for name, value in zip(names, values)))
        source = (SCRIPTS / "build-apk.sh").read_text()
        section = source.split("relative_to_work()", 1)[1].split(
            "# ------------------------------------------------------------------------------------------- build", 1)[0]
        snippet = self.root / "signing.sh"
        snippet.write_text("set -euo pipefail\nlog() { echo \"$*\" >&2; }\n"
                           "die() { echo \"$*\" >&2; exit 1; }\n"
                           'WORK="$PWD"\ntype=release\nrelative_to_work()' + section +
                           '\npython3 -c \'import json,os; print(json.dumps([os.environ.get(n) for n in '
                           + json.dumps(names) + ']))\'\n')
        env = {k: v for k, v in os.environ.items() if k not in names}
        run = subprocess.run(["bash", str(snippet)], env={**env, "BACKSTAGE_SIGNING_ENV": str(signing_env)},
                             capture_output=True, text=True)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertEqual(values, json.loads(run.stdout))
        # The keystore path and alias are logged on purpose (they say which key signed); passwords never are.
        for value in (values[1], values[3]):
            self.assertNotIn(value, run.stderr)

    def export_fixture(self):
        (self.root / "work/.git").mkdir(parents=True)
        (self.root / "patches").mkdir()
        for name in ("0001-first.patch", "0002-second.patch"):
            (self.root / "patches" / name).write_text("original " + name)
        (self.root / "scripts/lib.sh").write_text('''set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$ROOT/work
PATCH_DIR=$ROOT/patches
log() { echo "$*" >&2; }
die() { echo "$*" >&2; exit 1; }
patches() { find "$PATCH_DIR" -name '*.patch' | sort; }
git() {
  shift 2  # -C repo
  case "$1" in
    config) printf '%s' "${TEST_MARKER:-}" ;;
    rev-parse|status) : ;;
    log) printf 'abc first\\n';;
    format-patch)
      while [ "$1" != -o ]; do shift; done
      printf 'exported first' > "$2/0001-first.patch" ;;
    *) echo "unexpected git $*" >&2; return 99 ;;
  esac
}
''')

    def test_export_requires_completed_marker_even_with_force(self):
        for args in ((), ("--force",)):
            with self.subTest(args=args):
                if not (self.root / "work").exists():
                    self.export_fixture()
                run = self.run_script("export-patches.sh", *args)
                self.assertNotEqual(0, run.returncode)
                self.assertIn("prepared", run.stderr)
                self.assertEqual("original 0002-second.patch", (self.root / "patches/0002-second.patch").read_text())
                self.assertEqual("original 0001-first.patch", (self.root / "patches/0001-first.patch").read_text())

    def test_export_refuses_truncation_before_overwriting_any_patch(self):
        self.export_fixture()
        run = self.run_script("export-patches.sh", extra_env={"TEST_MARKER": "complete"})
        self.assertNotEqual(0, run.returncode)
        self.assertIn("--force", run.stderr)
        for p in (self.root / "patches").glob("*.patch"):
            self.assertEqual("original " + p.name, p.read_text())

    def test_forced_export_allows_intentional_patch_removal(self):
        self.export_fixture()
        run = self.run_script("export-patches.sh", str(self.root / "work"), "--force",
                              extra_env={"TEST_MARKER": "complete"})
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertFalse((self.root / "patches/0002-second.patch").exists())
        self.assertEqual("exported first", (self.root / "patches/0001-first.patch").read_text())

    def test_equal_patch_count_exports_normally(self):
        self.export_fixture()
        (self.root / "patches/0002-second.patch").unlink()
        run = self.run_script("export-patches.sh", extra_env={"TEST_MARKER": "complete"})
        self.assertEqual(0, run.returncode, run.stderr)

    def test_prepare_snapshot_and_checkout_override_global_autocrlf(self):
        source = (SCRIPTS / "prepare-work-tree.sh").read_text().splitlines()
        commands = [line.strip() for line in source
                    if ('GIT_INDEX_FILE=$idx' in line and 'add -A -f' in line)
                    or 'checkout -q -f -B backstage' in line
                    or 'add -A -f android/settings.gradle.kts' in line]
        self.assertEqual(3, len(commands))
        for command in commands:
            with self.subTest(command=command):
                # Intercept git rather than touching a repository: content-changing commands must
                # explicitly override the user's global setting at their process boundary.
                run = subprocess.run(["bash", "-c", '''set -eu
BOT=(-c core.autocrlf=false)
g() { git "${BOT[@]}" "$@"; }
git() { [[ " $* " == *" -c core.autocrlf=false "* ]] || exit 91; }
UPSTREAM_DIR=$PWD; WORK=$PWD; idx=/unused; base=base
''' + command], capture_output=True, text=True)
                self.assertEqual(0, run.returncode, command)

    def suite_fixture(self):
        (self.root / "scripts/lib.sh").write_text('''set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
log() { echo "$*" >&2; }
die() { echo "$*" >&2; exit 1; }
setup_toolchain_env() { :; }
heavy() { "$@"; }
''')
        (self.root / "backstage").mkdir()
        gradle = self.root / "backstage/gradlew"
        gradle.write_text("#!/bin/sh\nexit 0\n")
        gradle.chmod(0o755)

    def test_invalid_suite_names_exit_two_with_choices_before_toolchain(self):
        self.suite_fixture()
        for selection in ("typo", "", "library lint", "Library"):
            with self.subTest(selection=selection):
                run = self.run_script("test.sh", "--only", selection)
                self.assertEqual(2, run.returncode, run.stderr)
                for name in ("library", "lint", "flutter", "android"):
                    self.assertIn(name, run.stderr)
                self.assertNotIn("all requested test suites passed", run.stderr)

    def test_valid_suite_runs_and_succeeds(self):
        self.suite_fixture()
        run = self.run_script("test.sh", "--only", "library")
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertIn("library: ok", run.stderr)

    def test_empty_dispatch_cannot_report_success(self):
        self.suite_fixture()
        shutil.copy(SCRIPTS / "test.sh", self.root / "scripts/test.sh")
        script = self.root / "scripts/test.sh"
        script.write_text(script.read_text().replace(
            'want() { [[ " ${suites[*]} " == *" $1 "* ]]; }', 'want() { return 1; }'))
        run = subprocess.run(["bash", str(script), "--only", "library"], capture_output=True, text=True)
        self.assertNotEqual(0, run.returncode)
        self.assertIn("no test suites", run.stderr)
