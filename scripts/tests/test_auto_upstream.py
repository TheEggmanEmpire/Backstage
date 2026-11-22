"""The publisher accepts update data only and edits release metadata deterministically."""
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class AutomaticUpdateTest(unittest.TestCase):
    def module(self):
        script = ROOT / 'scripts/auto-upstream.py'
        self.assertTrue(script.is_file(), 'automatic update helper must exist')
        spec = importlib.util.spec_from_file_location('auto_upstream', script)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def fixture(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        root = Path(tmp.name)
        (root / 'backstage').mkdir()
        (root / 'backstage/version.properties').write_text("version=1.0.1\n")
        (root / 'README.md').write_text('![Release](https://img.shields.io/badge/release-v1.0.1-blue)\n')
        (root / 'CHANGELOG.md').write_text('# Changelog\n\n## 1.0.1\n\nExisting release.\n')
        return root

    def test_release_metadata_bumps_patch_once(self):
        auto = self.module()
        root = self.fixture()
        self.assertEqual('1.0.2', auto.bump_release(root, 'v2.3.4'))
        self.assertIn("version=1.0.2", (root / 'backstage/version.properties').read_text())
        self.assertEqual('![Release](https://img.shields.io/badge/release-v1.0.1-blue)\n', (root / 'README.md').read_text())
        self.assertEqual('# Changelog\n\n## 1.0.2\n\nDextop updated to v2.3.4.\n\n## 1.0.1\n\nExisting release.\n', (root / 'CHANGELOG.md').read_text())

    def test_release_metadata_preserves_the_live_release_badge(self):
        auto = self.module()
        root = self.fixture()
        (root / 'README.md').write_text('[![release](https://img.shields.io/github/v/release/TheEggmanEmpire/Backstage?color=brightgreen&logo=github&label=release)](https://github.com/TheEggmanEmpire/Backstage/releases/latest)\n')
        original = (root / 'README.md').read_bytes()
        auto.bump_release(root, 'v2.3.4')
        self.assertEqual(original, (root / 'README.md').read_bytes())

    def test_release_metadata_does_not_require_a_readme(self):
        auto = self.module()
        root = self.fixture()
        (root / 'README.md').unlink()
        self.assertEqual('1.0.2', auto.bump_release(root, 'v2.3.4'))
        self.assertFalse((root / 'README.md').exists())

    def test_metadata_is_not_partially_written_when_version_section_is_missing(self):
        auto = self.module()
        root = self.fixture()
        (root / 'CHANGELOG.md').write_text('# Changelog\n')
        original = (root / 'backstage/version.properties').read_bytes()
        with self.assertRaises(ValueError):
            auto.bump_release(root, 'v2.3.4')
        self.assertEqual(original, (root / 'backstage/version.properties').read_bytes())

    def test_archive_allows_only_update_outputs(self):
        auto = self.module()
        for name in ('upstream/dextop/gradlew', 'upstream/UPSTREAM.env', 'patches/0001-feature.patch', 'toolchain.env'):
            self.assertTrue(auto.allowed_path(name), name)
        for name in ('.github/workflows/build.yml', 'scripts/publish.sh', '../README.md', '/tmp/a',
                     'upstream/dextop/../../.git/config', 'patches/' + 'internal.md', 'upstream/omp-termux/UPSTREAM.env'):
            self.assertFalse(auto.allowed_path(name), name)

    def test_archive_rejects_links_and_duplicates_before_writing(self):
        auto = self.module()
        root = self.fixture()
        for kind in ('symlink', 'duplicate'):
            archive = root / (kind + '.tar.gz')
            with tarfile.open(archive, 'w:gz') as out:
                entry = tarfile.TarInfo('upstream/dextop/gradlew')
                if kind == 'symlink':
                    entry.type = tarfile.SYMTYPE
                    entry.linkname = '/tmp/escape'
                    out.addfile(entry)
                else:
                    entry.size = 1
                    out.addfile(entry, io.BytesIO(b'x'))
                    out.addfile(entry, io.BytesIO(b'y'))
            with self.assertRaises(ValueError):
                auto.read_archive(archive)
            self.assertFalse((root / 'upstream').exists())

    def test_archive_round_trip_preserves_modes_dotfiles_and_deletions(self):
        auto = self.module()
        source = self.fixture()
        target = self.fixture()
        for root in (source, target):
            (root / 'upstream/dextop').mkdir(parents=True)
            (root / 'patches').mkdir()
        (target / 'upstream/dextop/removed').write_text('obsolete')
        (source / 'upstream/dextop/.gitignore').write_text('build/\n')
        executable = source / 'upstream/dextop/gradlew'
        executable.write_text('#!/bin/sh\n')
        executable.chmod(0o755)
        (source / 'upstream/UPSTREAM.env').write_text('UPSTREAM_REF=v2.3.4\n')
        (source / 'toolchain.env').write_text('JAVA_VERSION=21\n')
        archive = source / 'update.tar.gz'
        auto.export_archive(source, archive, {'base': 'a' * 40, 'tag': 'v2.3.4'})
        metadata, entries = auto.read_archive(archive)
        self.assertEqual('v2.3.4', metadata['tag'])
        auto.apply_entries(target, entries)
        self.assertFalse((target / 'upstream/dextop/removed').exists())
        self.assertEqual('build/\n', (target / 'upstream/dextop/.gitignore').read_text())
        self.assertEqual(0o755, (target / 'upstream/dextop/gradlew').stat().st_mode & 0o777)

    def test_skip_existing_tag_failure_issue_and_pinned_version(self):
        auto = self.module()
        self.assertTrue(auto.should_skip('v2.3.4', 'v2.3.4-2-gabcdef', set(), set()))
        self.assertTrue(auto.should_skip('v2.3.4', 'v2.3.3', {'dextop-v2.3.4'}, set()))
        self.assertTrue(auto.should_skip('v2.3.4', 'v2.3.3', set(), {'Dextop v2.3.4: automatic update failed'}))
        self.assertFalse(auto.should_skip('v2.3.4', 'v2.3.3', set(), set()))

    def test_reference_listing_does_not_use_unsupported_pagination(self):
        from unittest.mock import patch
        auto = self.module()
        references = [{'ref': f'refs/tags/v1.0.{i}'} for i in range(101)]
        def response(path):
            return references if '/git/matching-refs/' in path else []
        with patch.object(auto, 'api', side_effect=response) as api:
            tags, issues = auto.state('owner/repo')
        self.assertEqual(101, len(tags))
        self.assertEqual({}, issues)
        self.assertEqual(2, api.call_count)

    def test_read_only_job_reads_public_issues_without_extra_token_permissions(self):
        from unittest.mock import patch
        auto = self.module()
        self.assertTrue(hasattr(auto, 'public_api'), 'public issue discovery must not require issues permission')
        with patch.object(auto, 'api', return_value=[]) as authenticated, patch.object(auto, 'public_api', return_value=[]) as public:
            self.assertEqual((set(), {}), auto.state('owner/repo', public_issues=True))
        authenticated.assert_called_once_with('repos/owner/repo/git/matching-refs/tags/')
        public.assert_called_once_with('repos/owner/repo/issues?state=open&per_page=100&page=1')

    def test_failure_report_updates_the_existing_issue(self):
        from unittest.mock import patch
        auto = self.module()
        root = self.fixture()
        report = root / 'failure.txt'
        report.write_text('Conflict in a patched file.\n')
        with patch.object(auto, 'state', return_value=(set(), {auto.issue_title('v2.3.4'): 42})), patch.object(auto, 'run') as run:
            auto.failure('owner/repo', 'v2.3.4', report)
        self.assertEqual(('gh', 'issue', 'edit', '42', '--repo', 'owner/repo', '--body-file'), run.call_args.args[:-1])
        self.assertIn('Conflict in a patched file.', Path(run.call_args.args[-1]).read_text())

    def test_publisher_applies_data_and_only_invokes_git_and_github(self):
        from unittest.mock import patch
        auto = self.module()
        source, target = self.fixture(), self.fixture()
        pin = ('UPSTREAM_URL=https://github.com/NarYuki/Dextop.git\n'
               'UPSTREAM_REF=v2.3.4\nUPSTREAM_COMMIT=' + 'b' * 40 + '\n')
        (source / 'upstream/dextop').mkdir(parents=True)
        (source / 'upstream/dextop/gradlew').write_text('must never execute')
        (source / 'upstream/UPSTREAM.env').write_text(pin)
        (source / 'toolchain.env').write_text('JAVA_VERSION=21\n')
        (target / 'upstream').mkdir()
        (target / 'upstream/UPSTREAM.env').write_text(pin.replace('v2.3.4', 'v2.3.3'))
        archive = source / 'update.tar.gz'
        base = 'a' * 40
        auto.export_archive(source, archive, {'base': base, 'tag': 'v2.3.4'})
        def git_result(*args):
            return base if args[0] == 'rev-parse' else ''
        with patch.object(auto, 'ROOT', target), patch.object(auto, 'state', return_value=(set(), {})), \
             patch.object(auto, 'api', return_value={'sha': 'b' * 40}), \
             patch.object(auto, 'git', side_effect=git_result) as git, patch.object(auto, 'run') as run:
            auto.publish('owner/repo', 'v2.3.4', base, archive)
        staged = next(call.args for call in git.call_args_list if call.args[0] == 'add')
        self.assertEqual(('add', '-A', '-f', '--', *auto.UPDATE_PATHS,
                          'backstage/version.properties', 'CHANGELOG.md'), staged)
        self.assertEqual(self.fixture().joinpath('README.md').read_bytes(), (target / 'README.md').read_bytes())
        git.assert_any_call('push', '--atomic', 'origin', 'HEAD:main', 'refs/tags/v1.0.2', 'refs/tags/dextop-v2.3.4')
        run.assert_called_once_with('gh', 'workflow', 'run', 'build.yml', '--repo', 'owner/repo', '--ref', 'v1.0.2', '-f', 'prerelease=true')
        self.assertEqual('must never execute', (target / 'upstream/dextop/gradlew').read_text())


class AutomaticWorkflowTest(unittest.TestCase):
    def workflow(self, name):
        import yaml
        return yaml.safe_load((ROOT / '.github/workflows' / name).read_text())

    def test_daily_permission_split_and_no_upstream_execution_by_publisher(self):
        wf = self.workflow('upstream-watch.yml')
        events = wf.get('on', wf.get(True))
        self.assertEqual('41 5 * * *', events['schedule'][0]['cron'])
        self.assertFalse(wf['concurrency']['cancel-in-progress'])
        jobs = wf['jobs']
        self.assertEqual({'contents': 'read'}, jobs['validate']['permissions'])
        self.assertEqual({'contents': 'write', 'issues': 'write', 'actions': 'write'}, jobs['publish']['permissions'])
        runs = '\n'.join(step.get('run', '') for step in jobs['publish']['steps'])
        for forbidden in ('gradlew', 'flutter ', 'update-upstream.sh', 'check-patches.sh', 'test.sh'):
            self.assertNotIn(forbidden, runs)
        self.assertIn('auto-upstream.py', runs)
        self.assertEqual('off', next(s for s in jobs['validate']['steps'] if s.get('uses') == './.github/actions/setup-toolchain')['with']['cache'])

    def test_dispatched_tags_publish_with_explicit_prerelease_flag(self):
        wf = self.workflow('build.yml')
        events = wf.get('on', wf.get(True))
        flag = events['workflow_dispatch']['inputs']['prerelease']
        self.assertEqual('boolean', flag['type'])
        self.assertFalse(flag['default'])
        release = wf['jobs']['release']
        self.assertIn("refs/tags/v", release['if'])
        self.assertEqual(['build', 'release-secrets'], release['needs'])
        publish = next(s for s in release['steps'] if str(s.get('uses', '')).startswith('softprops/action-gh-release'))
        self.assertIn('inputs.prerelease', publish['with']['prerelease'])
