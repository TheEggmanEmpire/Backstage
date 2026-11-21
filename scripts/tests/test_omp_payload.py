"""OMP pins and asset integrity, without network, Android SDK or an app build."""
import importlib.util
import json
from pathlib import Path
import tempfile
import subprocess
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('omp_payload', ROOT / 'scripts/lib/omp_payload.py')
omp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(omp)


class OmpPayloadTest(unittest.TestCase):
    def fixture(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        pins = {
            'OMP_TERMUX_REPO': 'https://github.com/hmeqo/omp-termux',
            'OMP_TERMUX_COMMIT': 'a' * 40, 'OMP_VERSION': '18.18.6', 'BUN_VERSION': '1.4.4',
            'TERMUX_BOOTSTRAP_TAG': 'bootstrap-2026.11.08-r1+apt.android-7',
            **{k: 'b' * 64 for k in ('OMP_ADDON_SHA256', 'BUN_ANDROID_SHA256', 'BUN_HOST_SHA256', 'TERMUX_BOOTSTRAP_SHA256')},
        }
        env = root / 'UPSTREAM.env'
        env.write_text('# provenance\n' + ''.join(f'{k}={v}\n' for k, v in pins.items()))
        assets = root / 'omp'
        assets.mkdir()
        for name in omp.REQUIRED_FILES:
            path = assets / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(name.encode())
        pins['BUN_ANDROID_SHA256'] = omp.sha256(assets / 'bun-linux-aarch64-android.zip')
        pins['TERMUX_BOOTSTRAP_SHA256'] = omp.sha256(assets / 'termux-bootstrap-aarch64.zip')
        env.write_text('# provenance\n' + ''.join(f'{k}={v}\n' for k, v in pins.items()))
        return root, env, pins, assets

    def test_parse_real_pins_and_literal_plus(self):
        _, env, pins, _ = self.fixture()
        self.assertEqual(pins, omp.read_pins(env))

    def test_pin_parser_rejects_missing_duplicate_unknown_and_shell(self):
        for bad in ('OMP_VERSION=1.2.3\n', 'BUN_VERSION=$(touch /tmp/omp-no)\n',
                    'UNKNOWN=value\n', 'OMP_VERSION=18.18.6 # inline shell\n'):
            with self.subTest(bad=bad):
                _, env, _, _ = self.fixture()
                env.write_text(env.read_text() + bad)
                with self.assertRaises(ValueError):
                    omp.read_pins(env)
        _, env, _, _ = self.fixture()
        env.write_text('OMP_VERSION=18.18.6\n')
        with self.assertRaises(ValueError):
            omp.read_pins(env)

    def test_bun_floor(self):
        omp.check_bun_version('1.4.4', '>=1.4.4')
        omp.check_bun_version('1.5.0', '>=1.4.4')
        with self.assertRaises(ValueError):
            omp.check_bun_version('1.4.3', '>=1.4.4')
        with self.assertRaises(ValueError):
            omp.check_bun_version('1.4.4', '^1.4.4')

    def test_manifest_is_stable_and_hashes_every_file(self):
        _, _, pins, assets = self.fixture()
        omp.write_manifest(assets, pins)
        first = (assets / 'manifest.json').read_bytes()
        omp.write_manifest(assets, pins)
        self.assertEqual(first, (assets / 'manifest.json').read_bytes())
        manifest = json.loads(first)
        self.assertEqual(1, manifest['payload'])
        self.assertEqual(pins['OMP_VERSION'], manifest['ompVersion'])
        self.assertEqual(set(omp.REQUIRED_FILES), set(manifest['files']))
        for name, entry in manifest['files'].items():
            self.assertEqual(len(name.encode()), entry['size'])
            self.assertEqual(omp.sha256(assets / name), entry['sha256'])

    def apk(self, root, assets, mutate=None):
        apk = root / 'test.apk'
        with zipfile.ZipFile(apk, 'w') as archive:
            for path in assets.rglob('*'):
                if path.is_file():
                    data = path.read_bytes()
                    archive.writestr('assets/omp/' + path.relative_to(assets).as_posix(),
                                     mutate(path.name, data) if mutate else data)
        return apk

    def test_apk_integrity_versions_compression(self):
        root, _, pins, assets = self.fixture()
        omp.write_manifest(assets, pins)
        apk = self.apk(root, assets)
        omp.verify_apk(apk, pins)
        wrong = {**pins, 'OMP_VERSION': '18.18.7'}
        with self.assertRaisesRegex(ValueError, 'ompVersion'):
            omp.verify_apk(apk, wrong)
        apk = self.apk(root, assets, lambda name, data: data + b'bad' if name == 'omp-termux' else data)
        with self.assertRaisesRegex(ValueError, 'omp-termux'):
            omp.verify_apk(apk, pins)
        with zipfile.ZipFile(apk, 'w'):
            pass
        with self.assertRaisesRegex(ValueError, 'manifest'):
            omp.verify_apk(apk, pins)

    def test_manifest_requires_complete_device_and_forbids_symlinks(self):
        _, _, pins, assets = self.fixture()
        (assets / 'device/run.sh').unlink()
        with self.assertRaises(ValueError):
            omp.write_manifest(assets, pins)
        (assets / 'device/run.sh').symlink_to('/etc/passwd')
        with self.assertRaises(ValueError):
            omp.write_manifest(assets, pins)

    def test_apk_rejects_unlisted_missing_and_traversal_entries(self):
        for change in ('extra', 'missing', 'traversal', 'duplicate', 'compression'):
            with self.subTest(change=change):
                root, _, pins, assets = self.fixture()
                omp.write_manifest(assets, pins)
                manifest = json.loads((assets / 'manifest.json').read_text())
                if change == 'extra':
                    (assets / 'extra').write_text('unexpected')
                elif change == 'missing':
                    (assets / 'device/run.sh').unlink()
                elif change == 'traversal':
                    manifest['files']['../escape'] = manifest['files'].pop('device/run.sh')
                    (assets / 'manifest.json').write_text(json.dumps(manifest))
                apk = self.apk(root, assets)
                if change == 'duplicate':
                    with warnings.catch_warnings(), zipfile.ZipFile(apk, 'a') as z:
                        warnings.simplefilter('ignore', UserWarning)
                        z.writestr('assets/omp/omp-termux', 'duplicate')
                if change == 'compression':
                    with zipfile.ZipFile(apk, 'w', compression=zipfile.ZIP_DEFLATED) as z:
                        for path in assets.rglob('*'):
                            if path.is_file():
                                z.write(path, 'assets/omp/' + path.relative_to(assets).as_posix())
                with self.assertRaises(ValueError):
                    omp.verify_apk(apk, pins)

    def test_source_archive_row_follows_bootstrap_pin(self):
        _, env, pins, _ = self.fixture()
        run = subprocess.run(['python3', '-I', str(ROOT / 'scripts/lib/omp_payload.py'),
                              'source-row', '--pins', str(env)], capture_output=True, text=True)
        self.assertEqual(0, run.returncode, run.stderr)
        name, url, sha, covers = run.stdout.strip().split('\t')
        self.assertIn(pins['TERMUX_BOOTSTRAP_TAG'], name)
        self.assertIn('/refs/tags/' + pins['TERMUX_BOOTSTRAP_TAG'], url)
        self.assertEqual('-', sha)
        self.assertIn('upstream source locations', covers)

    def test_safe_names(self):
        for path in ('../escape', '/escape', '.', 'device/../escape', 'device//run.sh', 'device/./run.sh', 'device\\run.sh'):
            self.assertFalse(omp.safe_name(path), path)
        self.assertTrue(omp.safe_name('device/run.sh'))

    def test_packaged_license_texts_exist(self):
        rows = [line.split('\t') for line in (ROOT / 'licenses/omp-runtime.tsv').read_text().splitlines()
                if line and not line.startswith('#')]
        self.assertEqual({'omp', 'omp-termux', 'bun', 'Termux bootstrap'}, {row[0] for row in rows})
        for row in rows:
            self.assertEqual(4, len(row))
            for name in row[3].split():
                if name != '-':
                    self.assertTrue((ROOT / name).is_file(), name)
