#!/usr/bin/env python3
"""Exchange upstream updates as bounded data; publish using only this trusted helper and GitHub/Git APIs."""
import argparse
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
VERSION = r'\d+\.\d+\.\d+'
UPDATE_PATHS = ('upstream/dextop', 'upstream/UPSTREAM.env', 'patches', 'toolchain.env')


def version(tag):
    if not re.fullmatch('v?' + VERSION, tag):
        raise ValueError('expected a stable version tag')
    return tuple(map(int, tag.removeprefix('v').split('.')))


def allowed_path(name):
    p = PurePosixPath(name)
    if p.is_absolute() or '..' in p.parts or p.as_posix() != name or '\\' in name or any(part.lower() == '.git' for part in p.parts):
        return False
    return (name in ('upstream/UPSTREAM.env', 'toolchain.env') or
            name.startswith('upstream/dextop/') or
            re.fullmatch(r'patches/\d{4}-[A-Za-z0-9_.-]+\.patch', name) is not None or
            re.fullmatch(r'patches/\.rr-cache/[0-9a-f]{40}/(?:preimage|postimage|thisimage)(?:\.\d+)?', name) is not None)


def export_archive(root, archive, metadata):
    with tarfile.open(archive, 'w:gz') as out:
        data = json.dumps(metadata, sort_keys=True).encode()
        entry = tarfile.TarInfo('metadata.json')
        entry.size = len(data)
        out.addfile(entry, io.BytesIO(data))
        for rel in UPDATE_PATHS:
            path = root / rel
            paths = sorted(path.rglob('*')) if path.is_dir() else [path]
            for source in paths:
                name = source.relative_to(root).as_posix()
                if source.is_symlink():
                    raise ValueError('update contains a link: ' + name)
                if source.is_file() and allowed_path(name):
                    out.add(source, arcname=name, recursive=False)


def read_archive(archive):
    entries, seen, total, metadata = {}, set(), 0, None
    with tarfile.open(archive, 'r:gz') as src:
        for entry in src:
            name = entry.name
            if name in seen or not entry.isfile() or (name != 'metadata.json' and not allowed_path(name)):
                raise ValueError('invalid or duplicate archive member: ' + name)
            total += entry.size
            if len(seen) > 30000 or entry.size > 128 * 1024**2 or total > 512 * 1024**2 or entry.size < 0:
                raise ValueError('update archive is too large')
            seen.add(name)
            data = src.extractfile(entry).read()
            if name == 'metadata.json':
                metadata = json.loads(data)
            else:
                entries[name] = (data, 0o755 if entry.mode & 0o111 else 0o644)
    if not isinstance(metadata, dict):
        raise ValueError('missing archive metadata')
    return metadata, entries


def apply_entries(root, entries):
    # Snapshot/series replacement preserves deletions. No archive member is ever executed or extracted by tar.
    for rel in UPDATE_PATHS:
        path = root / rel
        if path.is_dir():
            for old in sorted(path.rglob('*'), reverse=True):
                if allowed_path(old.relative_to(root).as_posix()) and (old.is_file() or old.is_symlink()):
                    old.unlink()
        elif path.exists():
            path.unlink()
    for name, (data, mode) in entries.items():
        if not allowed_path(name):
            raise ValueError('invalid update path: ' + name)
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        path.chmod(mode)


def bump_release(root, tag):
    upstream = '.'.join(map(str, version(tag)))
    properties = root / 'backstage/version.properties'
    changelog = root / 'CHANGELOG.md'
    before = properties.read_text()
    pattern = r"(?m)^(version=)(" + VERSION + r")$"
    match = re.search(pattern, before)
    if not match or len(re.findall(pattern, before)) != 1:
        raise ValueError('cannot find the default Backstage version')
    parts = list(version(match[2])); parts[2] += 1
    new = '.'.join(map(str, parts))
    properties_text = re.sub(pattern, lambda m: m[1] + new, before)
    text = changelog.read_text()
    heading = re.search(r'^## ', text, re.M)
    if not heading:
        raise ValueError('changelog has no version section')
    changelog_text = text[:heading.start()] + f'## {new}\n\nDextop updated to v{upstream}.\n\n' + text[heading.start():]
    # Validate both release metadata edits before writing either file.
    properties.write_text(properties_text); changelog.write_text(changelog_text)
    return new


def read_pin(path):
    result = {}
    for line in path.read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        if not re.fullmatch(r'[A-Z_]+=[A-Za-z0-9_./:+-]+', line):
            raise ValueError('invalid pin line')
        key, value = line.split('=', 1)
        if key in result:
            raise ValueError('duplicate pin key')
        result[key] = value
    return result


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def api(path):
    return json.loads(run('gh', 'api', path))


def public_api(path):
    # Public issue discovery must also work in the contents-only validation job.
    request = Request('https://api.github.com/' + path, headers={
        'Accept': 'application/vnd.github+json', 'User-Agent': 'Backstage-upstream-watch',
    })
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def pages(path, *, request=None):
    for page in range(1, 101):
        items = (request or api)(path + ('&' if '?' in path else '?') + f'per_page=100&page={page}')
        yield from items
        if len(items) < 100:
            return
    raise ValueError('too many API pages')


def issue_title(tag):
    return f'Dextop v{".".join(map(str, version(tag)))}: automatic update failed'


def should_skip(tag, pinned, tags, issues):
    pin = re.match(r'v?(\d+\.\d+\.\d+)', pinned)
    return ('dextop-v' + '.'.join(map(str, version(tag))) in tags or issue_title(tag) in issues or
            bool(pin and version(tag) <= version(pin[1])))


def state(repo, *, public_issues=False):
    tags = {entry['ref'].removeprefix('refs/tags/') for entry in api(f'repos/{repo}/git/matching-refs/tags/')}
    issues = {entry['title']: entry['number'] for entry in pages(f'repos/{repo}/issues?state=open', request=public_api if public_issues else None) if 'pull_request' not in entry}
    return tags, issues


def git(*args):
    # Disable hooks, signing and external filters for this data-only publisher.
    return run('git', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', '-c', 'tag.gpgsign=false', *args)


def failure(repo, tag, report):
    _, issues = state(repo)
    body = report.read_text(errors='replace')[-40000:] if report.exists() else 'Validation failed; see the workflow logs.'
    body = ('Automatic integration failed. No release was published.\n\n' + body).replace('@', '@\u200b')
    target = report.parent / 'issue-body.txt'; target.write_text(body)
    if issue_title(tag) in issues:
        run('gh', 'issue', 'edit', str(issues[issue_title(tag)]), '--repo', repo, '--body-file', str(target))
    else:
        run('gh', 'issue', 'create', '--repo', repo, '--title', issue_title(tag), '--body-file', str(target))


def publish(repo, tag, base, archive):
    metadata, entries = read_archive(archive)
    if metadata != {'base': base, 'tag': tag} or not re.fullmatch(r'[0-9a-f]{40}', base):
        raise ValueError('artifact identity does not match this run')
    pin_data = entries.get('upstream/UPSTREAM.env')
    if pin_data is None or not any(name.startswith('upstream/dextop/') for name in entries) or 'toolchain.env' not in entries:
        raise ValueError('incomplete update archive')
    pin_file = archive.parent / 'validated-pin.env'; pin_file.write_bytes(pin_data[0])
    pin = read_pin(pin_file)
    if pin.get('UPSTREAM_REF', '').removeprefix('v') != tag.removeprefix('v') or not re.fullmatch(r'[0-9a-f]{40}', pin.get('UPSTREAM_COMMIT', '')):
        raise ValueError('artifact upstream pin does not match target')
    old_pin = read_pin(ROOT / 'upstream/UPSTREAM.env')
    if pin.get('UPSTREAM_URL') != old_pin['UPSTREAM_URL']:
        raise ValueError('upstream URL changed')
    upstream_repo = old_pin['UPSTREAM_URL'].removesuffix('.git').removeprefix('https://github.com/')
    if api(f'repos/{upstream_repo}/commits/{tag}')['sha'] != pin['UPSTREAM_COMMIT']:
        raise ValueError('upstream tag commit differs from the artifact')
    tags, issues = state(repo)
    marker = 'dextop-v' + '.'.join(map(str, version(tag)))
    if should_skip(tag, old_pin['UPSTREAM_REF'], tags, issues):
        return
    git('fetch', 'origin', 'main')
    if git('rev-parse', 'origin/main') != base or git('rev-parse', 'HEAD') != base:
        print('main advanced during validation; skipping stale update')
        return
    apply_entries(ROOT, entries)
    new = bump_release(ROOT, tag)
    git('config', 'user.name', 'github-actions[bot]')
    git('config', 'user.email', '41898282+github-actions[bot]@users.noreply.github.com')
    git('add', '-A', '-f', '--', *UPDATE_PATHS, 'backstage/version.properties', 'CHANGELOG.md')
    git('commit', '-m', f'Dextop updated to {tag}')
    git('tag', '-a', 'v' + new, '-m', f'Dextop {tag} ({pin["UPSTREAM_COMMIT"]})')
    git('tag', marker)
    git('push', '--atomic', 'origin', 'HEAD:main', 'refs/tags/v' + new, 'refs/tags/' + marker)
    run('gh', 'workflow', 'run', 'build.yml', '--repo', repo, '--ref', 'v' + new, '-f', 'prerelease=true')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=('plan', 'export', 'publish', 'failure'))
    parser.add_argument('--tag'); parser.add_argument('--base'); parser.add_argument('--archive', type=Path)
    parser.add_argument('--report', type=Path); parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    repo = os.environ.get('GH_REPO', os.environ.get('GITHUB_REPOSITORY', ''))
    if args.command == 'plan':
        pin = read_pin(ROOT / 'upstream/UPSTREAM.env')
        upstream = pin['UPSTREAM_URL'].removesuffix('.git').removeprefix('https://github.com/')
        tags = [item['name'] for item in pages(f'repos/{upstream}/tags') if re.fullmatch('v?' + VERSION, item['name'])]
        tag = max(tags, key=version)
        existing, issues = state(repo, public_issues=True)
        needed = not should_skip(tag, pin['UPSTREAM_REF'], existing, issues)
        with args.output.open('a') as out:
            out.write(f'tag={tag}\nneeded={str(needed).lower()}\n')
    elif args.command == 'export':
        version(args.tag)
        export_archive(ROOT, args.archive, {'base': args.base, 'tag': args.tag})
    elif args.command == 'publish':
        publish(repo, args.tag, args.base, args.archive)
    else:
        failure(repo, args.tag, args.report)


if __name__ == '__main__':
    main()
