#!/usr/bin/env python3
"""Strict OMP pin parsing, deterministic manifests and APK asset integrity (no shell evaluation)."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile

PIN_PATTERNS = {
    'OMP_TERMUX_REPO': r'https://github\.com/hmeqo/omp-termux',
    'OMP_TERMUX_COMMIT': r'[0-9a-f]{40}',
    'OMP_VERSION': r'\d+\.\d+\.\d+',
    'OMP_ADDON_SHA256': r'[0-9a-f]{64}',
    'BUN_VERSION': r'\d+\.\d+\.\d+',
    'BUN_ANDROID_SHA256': r'[0-9a-f]{64}',
    'BUN_HOST_SHA256': r'[0-9a-f]{64}',
    'TERMUX_BOOTSTRAP_TAG': r'bootstrap-[A-Za-z0-9._+-]+',
    'TERMUX_BOOTSTRAP_SHA256': r'[0-9a-f]{64}',
}
VERSIONS = {'ompVersion': 'OMP_VERSION', 'bunVersion': 'BUN_VERSION',
            'bootstrapTag': 'TERMUX_BOOTSTRAP_TAG', 'ompTermuxCommit': 'OMP_TERMUX_COMMIT'}
REQUIRED_FILES = {'termux-bootstrap-aarch64.zip', 'bun-linux-aarch64-android.zip',
                  'omp-tree.tgz', 'omp-termux', *('device/' + name for name in
                  ('install.sh', 'run.sh', 'job.sh', 'rpc-bridge.js', 'omp', 'backstage-omp-key', 'backstage-sh', 'termux-setup.sh'))}


def read_pins(path):
    pins = {}
    for number, line in enumerate(Path(path).read_text().splitlines(), 1):
        if not line.strip() or line.startswith('#'):
            continue
        key, sep, value = line.partition('=')
        if not sep or key not in PIN_PATTERNS or key in pins or not re.fullmatch(PIN_PATTERNS[key], value):
            raise ValueError(f'{path}:{number}: invalid, unknown or duplicate OMP pin: {key}')
        pins[key] = value
    if missing := PIN_PATTERNS.keys() - pins.keys():
        raise ValueError(f'{path}: missing OMP pins: {", ".join(sorted(missing))}')
    return pins


def check_bun_version(version, requirement):
    # Fail closed if npm ever changes the expression: do not guess at a new semver range.
    match = re.fullmatch(r'>=(\d+)\.(\d+)\.(\d+)', requirement)
    if not match:
        raise ValueError(f'unsupported omp engines.bun: {requirement!r}; update the floor parser')
    if tuple(map(int, version.split('.'))) < tuple(map(int, match.groups())):
        raise ValueError(f'bun {version} is below omp engines.bun {requirement}')


def sha256(path):
    with Path(path).open('rb') as stream:
        return hash_stream(stream)


def hash_stream(stream):
    digest = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        digest.update(chunk)
    return digest.hexdigest()


def safe_name(name):
    if not isinstance(name, str) or not name or '\\' in name:
        return False
    path = PurePosixPath(name)
    return bool(path.parts) and not path.is_absolute() and '..' not in path.parts and str(path) == name


def write_manifest(directory, pins):
    directory = Path(directory)
    files = {}
    for path in sorted(directory.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'payload must not contain symlinks: {path}')
        if path.is_file() and path.name != 'manifest.json':
            name = path.relative_to(directory).as_posix()
            if not safe_name(name):
                raise ValueError(f'unsafe payload name: {name}')
            files[name] = {'sha256': sha256(path), 'size': path.stat().st_size}
    if missing := REQUIRED_FILES - files.keys():
        raise ValueError(f'incomplete OMP payload (missing {", ".join(sorted(missing))}); run scripts/fetch-omp-payload.sh')
    manifest = {'payload': 1, **{field: pins[key] for field, key in VERSIONS.items()}, 'files': files}
    (directory / 'manifest.json').write_text(json.dumps(manifest, sort_keys=True, indent=2) + '\n')


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate manifest key: {key}')
        result[key] = value
    return result


def check_manifest(raw, pins):
    manifest = json.loads(raw, object_pairs_hook=unique_object)
    if not isinstance(manifest, dict) or type(manifest.get('payload')) is not int or manifest['payload'] != 1:
        raise ValueError('unsupported OMP payload manifest')
    for field, key in VERSIONS.items():
        if manifest.get(field) != pins[key]:
            raise ValueError(f'OMP manifest {field}: expected {pins[key]}, got {manifest.get(field)}')
    files = manifest.get('files')
    if not isinstance(files, dict) or REQUIRED_FILES - files.keys():
        raise ValueError('OMP manifest is missing required files')
    for name, record in files.items():
        if not safe_name(name) or name == 'manifest.json':
            raise ValueError(f'unsafe OMP manifest path: {name}')
        if (not isinstance(record, dict) or not re.fullmatch(r'[0-9a-f]{64}', str(record.get('sha256', '')))
                or type(record.get('size')) is not int or record['size'] < 0):
            raise ValueError(f'invalid OMP manifest entry: {name}')
    # These verbatim archives are independently pinned; a rewritten manifest must not bypass their pins.
    for name, key in (('termux-bootstrap-aarch64.zip', 'TERMUX_BOOTSTRAP_SHA256'),
                      ('bun-linux-aarch64-android.zip', 'BUN_ANDROID_SHA256')):
        if files[name]['sha256'] != pins[key]:
            raise ValueError(f'OMP manifest {name}: does not match {key}')
    return files


def verify_directory(directory, pins):
    directory = Path(directory)
    files = check_manifest((directory / 'manifest.json').read_bytes(), pins)
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
    if actual != set(files) | {'manifest.json'}:
        raise ValueError('OMP payload files do not match manifest')
    for name, record in files.items():
        path = directory / name
        if any(p.is_symlink() for p in (path, *path.parents) if p != directory.parent) or path.stat().st_size != record['size'] or sha256(path) != record['sha256']:
            raise ValueError(f'OMP payload integrity mismatch: {name}')


def verify_apk(apk, pins):
    with zipfile.ZipFile(apk) as archive:
        entries = [info for info in archive.infolist() if info.filename.startswith('assets/omp/') or info.filename == 'assets/omp']
        names = [info.filename for info in entries]
        if 'assets/omp/manifest.json' not in names:
            raise ValueError('GitHub APK is missing assets/omp/manifest.json')
        if len(set(names)) != len(names):
            raise ValueError('duplicate OMP assets in APK')
        if archive.getinfo('assets/omp/manifest.json').file_size > 1024 * 1024:
            raise ValueError('OMP manifest exceeds 1 MiB')
        files = check_manifest(archive.read('assets/omp/manifest.json'), pins)
        actual = {info.filename[len('assets/omp/'):] for info in entries if not info.is_dir()}
        if actual != set(files) | {'manifest.json'}:
            raise ValueError('APK OMP assets do not match manifest')
        for name, record in files.items():
            info = archive.getinfo('assets/omp/' + name)
            if info.file_size != record['size']:
                raise ValueError(f'APK OMP size mismatch: {name}')
            if name.endswith(('.zip', '.gz')) and info.compress_type != zipfile.ZIP_STORED:
                raise ValueError(f'APK OMP archive must be uncompressed: {name}')
            with archive.open(info) as stream:
                if hash_stream(stream) != record['sha256']:
                    raise ValueError(f'APK OMP SHA-256 mismatch: {name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('pins', 'floor', 'manifest', 'verify-dir', 'verify-apk', 'host-bun', 'source-row'))
    parser.add_argument('--pins', type=Path)
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--apk', type=Path)
    parser.add_argument('--version')
    parser.add_argument('--metadata', type=Path)
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        pins = read_pins(args.pins) if args.pins else None
        if args.command == 'pins':
            for key in PIN_PATTERNS:
                print(f'{key}={pins[key]}')
        elif args.command == 'floor':
            check_bun_version(args.version, json.loads(args.metadata.read_text())['engines']['bun'])
        elif args.command == 'manifest':
            write_manifest(args.directory, pins)
        elif args.command == 'verify-dir':
            verify_directory(args.directory, pins)
        elif args.command == 'verify-apk':
            verify_apk(args.apk, pins)
        elif args.command == 'source-row':
            tag = pins['TERMUX_BOOTSTRAP_TAG']
            print(f'termux-packages-{tag}.tar.gz\thttps://github.com/termux/termux-packages/archive/refs/tags/{tag}.tar.gz\t-\tTermux bootstrap {tag}: package build recipes, patches and upstream source locations')
        elif args.command == 'host-bun':
            # Read exactly the host binary, never extract archive paths/symlinks or execute an installer.
            with zipfile.ZipFile(args.archive) as archive:
                info = archive.getinfo('bun-linux-x64/bun')
                if info.file_size > 512 * 1024 * 1024:
                    raise ValueError('host bun binary exceeds 512 MiB')
                with archive.open(info) as src, args.output.open('wb') as dst:
                    while chunk := src.read(1024 * 1024):
                        dst.write(chunk)
            args.output.chmod(0o755)
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile) as error:
        parser.exit(1, f'OMP payload: {error}\n')


if __name__ == '__main__':
    main()
