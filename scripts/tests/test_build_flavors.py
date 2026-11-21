"""Exercise the build CLI against a fake Flutter, including artifact names and accepted options."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class BuildFlavorTest(unittest.TestCase):
    def run_build(self, *args, skip_omp=False):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        scripts = root / "scripts"
        scripts.mkdir()
        shutil.copy(Path(__file__).resolve().parents[1] / "build-apk.sh", scripts)
        fetch = scripts / "fetch-omp-payload.sh"
        fetch.write_text('#!/bin/sh\nprintf fetched > "$(dirname "$0")/../omp-fetched"\n')
        fetch.chmod(0o755)
        (root / "work/android").mkdir(parents=True)
        gradlew = root / "work/android/gradlew"
        gradlew.write_text("#!/bin/sh\nexit 0\n")
        gradlew.chmod(0o755)
        (scripts / "lib.sh").write_text('''set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
WORK=$ROOT/work
DIST=$ROOT/dist
log() { :; }
warn() { :; }
die() { echo "$*" >&2; exit 1; }
setup_toolchain_env() { export PATH="$ROOT/bin:$PATH"; }
require_prepared() { :; }
heavy() { "$@"; }
''')
        (root / "bin").mkdir()
        flutter = root / "bin/flutter"
        flutter.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
a=sys.argv[1:]
if not a or a[0] != 'build': sys.exit(0)
with open('calls.jsonl','a') as f: f.write(json.dumps({'args':a})+'\\n')
flavor=a[a.index('--flavor')+1]
kind='release' if '--release' in a else 'debug'
out=pathlib.Path('build/app/outputs')
metadata={'elements':[{'versionName':'1.0.0'}]}
p=out/f'apk/{flavor}/{kind}/output-metadata.json'; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(metadata))
p=out/f'flutter-apk/app-{flavor}-{kind}.apk'
p.parent.mkdir(parents=True,exist_ok=True); p.write_text('artifact')
''')
        flutter.chmod(0o755)
        env = {**os.environ, "BACKSTAGE_TOOLCHAIN_ENV": "/nonexistent"}
        env.pop("BACKSTAGE_SKIP_OMP", None)
        if skip_omp:
            env["BACKSTAGE_SKIP_OMP"] = "1"
        run = subprocess.run(["bash", str(scripts / "build-apk.sh"), "--no-prepare", *args], env=env, capture_output=True, text=True)
        return root, run

    def test_default_github_output_stays_the_same(self):
        root, run = self.run_build("debug")
        self.assertEqual(run.returncode, 0, run.stderr)
        self.assertTrue((root / "dist/backstage-1.0.0-debug.apk").is_file())
        calls = [json.loads(s) for s in (root / "work/calls.jsonl").read_text().splitlines()]
        self.assertEqual(len(calls), 1)
        self.assertEqual(calls[0]["args"][1], "apk")
        self.assertTrue((root / "omp-fetched").is_file())

    def test_invalid_flavor_and_missing_values_fail_early(self):
        for args in (("--flavor", "other"), ("--flavor",), ("--with-companion",), ("--no-companion",)):
            with self.subTest(args=args):
                root, run = self.run_build(*args)
                self.assertNotEqual(run.returncode, 0)
                self.assertFalse((root / "work/calls.jsonl").exists())

    def test_omp_skip_is_explicit_and_debug_only(self):
        root, run = self.run_build("debug", skip_omp=True)
        self.assertEqual(0, run.returncode, run.stderr)
        self.assertFalse((root / "omp-fetched").exists())
        args = json.loads((root / "work/calls.jsonl").read_text())["args"]
        self.assertIn("--android-project-arg=backstage.skipOmp=true", args)
        root, run = self.run_build("release", skip_omp=True)
        self.assertNotEqual(0, run.returncode)
        self.assertIn("debug only", run.stderr)
        self.assertFalse((root / "work/calls.jsonl").exists())
