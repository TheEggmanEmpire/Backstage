"""Exercise updater defaults and rejection gates with publisher responses represented as fixtures."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
COMMIT = '9ac6aa555aa368b7d71003603c454d4b1f7ed646'


class OmpUpdaterTest(unittest.TestCase):
    def fixture(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        (root / 'scripts/lib').mkdir(parents=True)
        (root / 'upstream/omp-termux').mkdir(parents=True)
        (root / 'upstream/UPSTREAM.env').write_text('# fixture\n')
        (root / 'upstream/omp-termux/UPSTREAM.env').write_text('# old pin preserved on failure\n')
        for name in ('lib.sh', 'update-omp-termux.sh', 'fetch-omp-payload.sh', 'lib/omp.sh', 'lib/omp_payload.py'):
            shutil.copy(ROOT / 'scripts' / name, root / 'scripts' / name)
        (root / 'bin').mkdir()
        curl = root / 'bin/curl'
        curl.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
args=sys.argv[1:]
url=next(a for a in args if a.startswith('https://'))
with open(os.environ['OMP_FIXTURE_LOG'], 'a') as f: f.write(url+'\\n')
output=args[args.index('-o')+1] if '-o' in args else None
if url.endswith('/releases/latest'):
    tag='omp-18.18.6' if 'omp-termux' in url else ('bun-v1.4.4' if 'oven-sh/bun' in url else 'bootstrap-2026.11.08-r1%2Bapt.android-7')
    print(url.replace('/latest', '/tag/'+tag), end=''); sys.exit(0)
if 'pi_natives.android-arm64.node' in url and os.environ.get('OMP_FIXTURE_MISSING_ADDON'):
    print('curl: release asset not found', file=sys.stderr); sys.exit(22)
if url.endswith('/commits/main.atom'):
    body='<feed><id>tag:github.com,2008:Grit::Commit/9ac6aa555aa368b7d71003603c454d4b1f7ed646</id></feed>'
elif url.startswith('https://registry.npmjs.org/'):
    body=json.dumps({'engines':{'bun':'>=1.4.4'}})
else: body='fixture:'+url+'\\n'
pathlib.Path(output).write_text(body)
''')
        curl.chmod(0o755)
        env = {**os.environ, 'PATH': str(root / 'bin') + ':' + os.environ['PATH'],
               'OMP_FIXTURE_LOG': str(root / 'requests.log')}
        env.pop('OMP_FIXTURE_MISSING_ADDON', None)
        return root, env

    def run_update(self, root, env, *args):
        return subprocess.run(['bash', str(root / 'scripts/update-omp-termux.sh'), *args],
                              env=env, capture_output=True, text=True)

    def test_defaults_follow_release_redirects_vendor_commit_and_hash_artifacts(self):
        root, env = self.fixture()
        run = self.run_update(root, env)
        self.assertEqual(0, run.returncode, run.stderr)
        vendor = root / 'upstream/omp-termux'
        pins = dict(line.split('=', 1) for line in (vendor / 'UPSTREAM.env').read_text().splitlines() if not line.startswith('#'))
        self.assertEqual(COMMIT, pins['OMP_TERMUX_COMMIT'])
        self.assertEqual('18.18.6', pins['OMP_VERSION'])
        self.assertEqual('1.4.4', pins['BUN_VERSION'])
        self.assertEqual('bootstrap-2026.11.08-r1+apt.android-7', pins['TERMUX_BOOTSTRAP_TAG'])
        for name in ('bin/omp-termux', 'install.sh', 'LICENSE', 'README.md'):
            self.assertEqual(f'fixture:https://raw.githubusercontent.com/hmeqo/omp-termux/{COMMIT}/{name}\n',
                             (vendor / name).read_text())
        for pattern, key in (('*.node', 'OMP_ADDON_SHA256'), ('bun-linux-x64*.zip', 'BUN_HOST_SHA256'),
                             ('bun-linux-aarch64*.zip', 'BUN_ANDROID_SHA256'), ('bootstrap*.zip', 'TERMUX_BOOTSTRAP_SHA256')):
            path = next((root / '.cache/omp').glob(pattern))
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), pins[key])
        self.assertNotIn('api.github.com', (root / 'requests.log').read_text())
        self.assertTrue((root / 'licenses/texts/omp/LICENSE').is_file())
        self.assertFalse(list((root / '.cache/omp').glob('update.*')), 'scratch directory leaked')

    def test_bun_below_engine_floor_is_rejected_before_artifact_downloads(self):
        root, env = self.fixture()
        run = self.run_update(root, env, '--omp', '18.18.6', '--bun', '1.4.3',
                              '--commit', COMMIT, '--bootstrap', 'bootstrap-fixture')
        self.assertNotEqual(0, run.returncode)
        self.assertIn('below omp engines.bun', run.stderr)
        self.assertNotIn('/releases/download/', (root / 'requests.log').read_text())
        self.assertEqual('# old pin preserved on failure\n', (root / 'upstream/omp-termux/UPSTREAM.env').read_text())

    def test_missing_android_release_does_not_move_pins(self):
        root, env = self.fixture()
        env['OMP_FIXTURE_MISSING_ADDON'] = '1'
        run = self.run_update(root, env, '--omp', '18.18.6', '--bun', '1.4.4',
                              '--commit', COMMIT, '--bootstrap', 'bootstrap-fixture')
        self.assertNotEqual(0, run.returncode)
        self.assertIn('download failed', run.stderr)
        self.assertEqual('# old pin preserved on failure\n', (root / 'upstream/omp-termux/UPSTREAM.env').read_text())
        self.assertFalse(list((root / '.cache/omp').glob('*.part')))

    def test_fetch_refuses_missing_or_empty_device_scripts_before_network(self):
        root, env = self.fixture()
        run = self.run_update(root, env)
        self.assertEqual(0, run.returncode, run.stderr)
        requests = (root / 'requests.log').read_bytes()
        for exists in (False, True):
            if exists:
                (root / 'backstage/omp/device').mkdir(parents=True)
            run = subprocess.run(['bash', str(root / 'scripts/fetch-omp-payload.sh')],
                                 env=env, capture_output=True, text=True)
            self.assertNotEqual(0, run.returncode)
            self.assertIn('device scripts missing or empty', run.stderr)
            self.assertEqual(requests, (root / 'requests.log').read_bytes())
