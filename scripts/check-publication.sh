#!/usr/bin/env bash
# Check tracked publication content, or the complete index with PUBLICATION_STAGED=1.
set -euo pipefail
exec python3 - <<'PY'
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(['git', *args])


os.chdir(os.fsdecode(git('rev-parse', '--show-toplevel')).rstrip('\n'))
staged = os.environ.get('PUBLICATION_STAGED') == '1'
entries = []
for entry in git('ls-files', '--stage', '-z').split(b'\0'):
    if entry:
        metadata, name = entry.split(b'\t', 1)
        mode, oid, stage = metadata.split()
        entries.append((os.fsdecode(name), mode, oid, stage))
tracked = {name for name, _, _, _ in entries}
allowed_md = {'README.md', 'CHANGELOG.md', 'NOTICE.md', 'CLAUDE.md',
              'docs/KNOWN-ISSUES.md', 'docs/PRIVACY.md'}
version = re.compile(r'\b0\.1\.[0-9]+')
reference = re.compile(r'\b(?:docs|patches)/[A-Za-z0-9_./+@-]+\.md\b')
process_test = re.compile(r'Round[0-9]|Minors')
private_key = re.compile(
    r'BEGIN[^\r\n]*'
    r'PRIVATE KEY'
)
denylist = []
deny_path = os.environ.get('PUBLICATION_DENYLIST')
if deny_path and os.path.isfile(deny_path) and os.access(deny_path, os.R_OK):
    denylist = [line.casefold() for line in Path(deny_path).read_text().splitlines()
                if line and not line.startswith('#')]
errors = 0


def report(name, rule, line=None):
    global errors
    errors += 1
    location = name.replace('\n', '\\n').replace('\r', '\\r')
    print(f'{location}{":" + str(line) if line else ""}: {rule}')


objects = subprocess.Popen(['git', 'cat-file', '--batch'], stdin=subprocess.PIPE,
                           stdout=subprocess.PIPE) if staged else None
try:
    for name, mode, oid, stage in entries:
        path = Path(name)
        if stage != b'0':
            report(name, 'unmerged index entry')
            continue
        if mode == b'160000':
            continue  # Submodule contents are tracked by their own repository.
        if staged:
            objects.stdin.write(oid + b'\n')
            objects.stdin.flush()
            header = objects.stdout.readline().split()
            if len(header) != 3 or header[1] != b'blob':
                raise ValueError('cannot read indexed blob for ' + name)
            data = objects.stdout.read(int(header[2]))
            if objects.stdout.read(1) != b'\n':
                raise ValueError('invalid indexed blob response')
        else:
            if not path.exists() and not path.is_symlink():
                continue  # A working-tree deletion has no content to publish.
            data = os.fsencode(os.readlink(path)) if path.is_symlink() else path.read_bytes()
        upstream = name.startswith('upstream/')
        if name.endswith('.md') and name not in allowed_md and not name.startswith(('licenses/', 'upstream/')):
            report(name, 'markdown path is outside the publication allowlist')
        if name.startswith('docs/') and name not in allowed_md and not name.startswith(('docs/assets/', 'docs/screenshots/')):
            report(name, 'docs path is outside the publication allowlist')
        if path.suffix.lower() in ('.jks', '.keystore', '.p12', '.pfx') or path.name in ('release.env', 'key.properties'):
            report(name, 'signing material must remain local')
        if process_test.search(path.name) and re.search(r'test', name, re.I):
            report(name, 'test filename must describe its behaviour')
        if b'\0' in data:
            continue
        text = data.decode('utf-8', errors='replace')
        for number, line in enumerate(text.splitlines(), 1):
            if not upstream and version.search(line):
                report(name, 'disallowed version reference', number)
            for target in sorted(set(reference.findall(line))) if not upstream else ():
                if target not in tracked:
                    report(name, 'untracked document reference: ' + target, number)
            if private_key.search(line):
                report(name, 'private key material must remain local', number)
            if not upstream and name != 'LICENSE' and not name.startswith('licenses/texts/'):
                folded = line.casefold()
                for index, word in enumerate(denylist, 1):
                    if word in folded:
                        report(name, f'publication denylist entry {index}', number)
finally:
    if objects:
        objects.stdin.close()
        objects.stdout.close()
        if objects.wait() != 0:
            raise RuntimeError('cannot read the staged index')

if errors:
    print(f'Publication check failed: {errors} violation(s).', file=sys.stderr)
    sys.exit(1)
print('Publication check passed.')
PY
