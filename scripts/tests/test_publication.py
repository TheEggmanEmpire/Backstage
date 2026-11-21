"""Publication checks use tracked paths and select working-tree or index content."""
import os
from pathlib import Path
import subprocess

import tempfile
import unittest

try:
    import yaml
except ImportError:
    yaml = None

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'scripts/check-publication.sh'


def track(repo, name, content='allowed content\n'):
    path = repo / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content if isinstance(content, bytes) else content.encode())
    subprocess.run(['git', '-C', str(repo), 'add', '--', name], check=True)
    return path


def check(repo, **variables):
    env = {k: v for k, v in os.environ.items() if not k.startswith('PUBLICATION_')}
    env.update(variables)
    return subprocess.run(['bash', str(SCRIPT)], cwd=repo, env=env, capture_output=True, text=True)


class PublicationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repo = Path(temporary.name)
        # Fixture Git operations are confined to this temporary repository.
        subprocess.run(['git', 'init', '-q', str(self.repo)], check=True)

    def test_allowed_paths_and_untracked_files(self):
        repo = self.repo
        for name in ('README.md', 'CHANGELOG.md', 'NOTICE.md', 'CLAUDE.md',
                     'docs/' + 'KNOWN-ISSUES.md', 'docs/' + 'PRIVACY.md',
                     'licenses/package.md',
                     'upstream/vendor/internal.md', 'docs/assets/icon.png', 'docs/screenshots/home.png'):
            track(repo, name)
        (repo / 'untracked.md').write_text('local notes')
        result = check(repo)
        assert result.returncode == 0, result.stdout + result.stderr


    def test_reports_every_disallowed_document_and_docs_file(self):
        repo = self.repo
        names = ('INTERNAL.md', 'docs/' + 'design.md', 'docs/misc.json', 'other/NOTICE.md')
        for name in names:
            track(repo, name)
        result = check(repo)
        assert result.returncode == 1
        for name in names:
            assert name in result.stdout


    def test_version_boundary_binary_and_upstream_exclusions(self):
        repo = self.repo
        old_version = '.'.join(('0', '1', '27'))
        track(repo, 'source.txt', 'v' + old_version + '\nx' + old_version + '\n10.1.27\n')
        track(repo, 'binary.dat', b'\0' + old_version.encode())
        track(repo, 'upstream/source.txt', old_version)
        assert check(repo).returncode == 0
        track(repo, 'source.txt', old_version + '\n' + old_version)
        result = check(repo)
        assert result.returncode == 1
        assert 'source.txt:1:' in result.stdout
        assert 'source.txt:2:' in result.stdout
        assert 'binary.dat' not in result.stdout
        assert 'upstream/source.txt' not in result.stdout


    def test_document_references_require_tracked_targets(self):
        repo = self.repo
        target = 'docs/' + 'PRIVACY.md'
        track(repo, target)
        track(repo, 'source.txt', target)
        assert check(repo).returncode == 0
        missing = ['docs/' + 'absent.md', 'patches/' + 'guide.md']
        for name in missing:
            path = repo / name
            path.parent.mkdir(exist_ok=True)
            path.write_text('untracked')
        track(repo, 'source.txt', '\n'.join(missing))
        result = check(repo)
        assert result.returncode == 1
        assert all(name in result.stdout for name in missing)
        track(repo, 'upstream/reference.txt', missing[0])
        assert 'upstream/reference.txt' not in check(repo).stdout  # vendored upstream docs are not ours


    def test_signing_files_are_rejected_even_if_binary_or_under_upstream(self):
        repo = self.repo
        for name in ('key.jks', 'key.keystore', 'key.p12', 'key.pfx', 'release.env', 'nested/key.properties'):
            with self.subTest(name=name):
                track(repo, 'upstream/' + name, b'\x00\xff')
                result = check(repo)
                assert result.returncode == 1
                assert name in result.stdout


    def test_private_key_markers_and_process_named_tests(self):
        repo = self.repo
        marker = '-----BEGIN '
        marker += 'RSA PRIVATE KEY-----\n'
        track(repo, 'source.txt', marker)
        names = ('tests/GuardRound7Test.kt', 'tests/ExampleMinorsTest.kt')
        for name in names:
            track(repo, name)
        result = check(repo)
        assert result.returncode == 1
        assert 'source.txt:1:' in result.stdout
        assert all(name in result.stdout for name in names)


    def test_denylist_is_literal_case_insensitive_and_respects_exclusions(self):
        repo = tmp_path = self.repo
        denylist = tmp_path / 'local-denylist.txt'
        denylist.write_text('# ignored\n\nNeutral.Marker[42]\nAnother Placeholder\n')
        track(repo, 'source.txt', 'neutral.marker[42]\nANOTHER PLACEHOLDER\n')
        track(repo, 'near.txt', 'neutralXmarker4\n')
        for name in ('upstream/source.txt', 'LICENSE', 'licenses/texts/package.txt'):
            track(repo, name, 'neutral.marker[42]\n')
        result = check(repo, PUBLICATION_DENYLIST=str(denylist))
        assert result.returncode == 1
        assert 'source.txt:1:' in result.stdout and 'source.txt:2:' in result.stdout
        assert 'near.txt' not in result.stdout
        assert all(name not in result.stdout for name in ('upstream/source.txt', 'LICENSE', 'licenses/texts/package.txt'))
        # Diagnostics identify locations without repeating denylist contents.
        assert 'neutral.marker' not in result.stdout.lower()
        assert check(repo, PUBLICATION_DENYLIST=str(tmp_path / 'missing')).returncode == 0


    def test_staged_mode_reads_index_content_and_includes_all_index_paths(self):
        repo = self.repo
        path = track(repo, 'source.txt', '.'.join(('0', '1', '9')))
        path.write_text('clean working copy')
        assert check(repo).returncode == 0
        assert check(repo, PUBLICATION_STAGED='1').returncode == 1
        track(repo, 'source.txt', 'clean index')
        path.write_text('.'.join(('0', '1', '9')))
        assert check(repo, PUBLICATION_STAGED='1').returncode == 0
        assert check(repo).returncode == 1
        path.unlink()
        assert check(repo, PUBLICATION_STAGED='1').returncode == 0


    def test_staged_deletions_are_absent_and_unstaged_deletions_are_not(self):
        repo = self.repo
        target = 'docs/' + 'PRIVACY.md'
        track(repo, target)
        track(repo, 'source.txt', target)
        (repo / target).unlink()
        assert check(repo, PUBLICATION_STAGED='1').returncode == 0
        subprocess.run(['git', '-C', str(repo), 'add', '-u'], check=True)
        assert check(repo, PUBLICATION_STAGED='1').returncode == 1


    def test_spaces_and_newlines_in_tracked_names(self):
        repo = self.repo
        for name in ('space name.md', 'line\nbreak.md'):
            track(repo, name)
        result = check(repo)
        assert result.returncode == 1
        assert 'space name.md' in result.stdout
        assert 'line\\nbreak.md' in result.stdout


    @unittest.skipIf(yaml is None, 'PyYAML not installed')
    def test_ci_checks_publication_before_build_work(self):
        workflow = yaml.safe_load((ROOT / '.github/workflows/build.yml').read_text())
        events = workflow.get('on', workflow.get(True))
        assert 'push' in events and 'pull_request' in events
        steps = workflow['jobs']['patches']['steps']
        assert 'if' not in workflow['jobs']['patches']
        assert steps[0]['uses'].startswith('actions/checkout@')
        assert steps[1]['run'] == 'scripts/check-publication.sh'
        assert 'if' not in steps[1]


    def test_script_is_executable(self):
        assert SCRIPT.is_file() and os.access(SCRIPT, os.X_OK)
