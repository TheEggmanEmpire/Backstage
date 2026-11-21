"""Host shell regression tests; no Android, provider credentials or real network."""
import io
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import zipfile

WRAPPER = Path(__file__).resolve().parents[1] / 'device/omp'
SETUP = WRAPPER.with_name('termux-setup.sh')


class LiveSyncTest(unittest.TestCase):
    def test_prompt_and_rpc_poller_gate(self):
        text = WRAPPER.read_text()
        self.assertIn('instructions.txt', text)
        self.assertIn('--backstage-sync-poll', text)
        self.assertIn('--mode=rpc', text)
        self.assertIn('setsid', text)
        self.assertIn('sleep 8', text)
        self.assertIn('instructions.txt', SETUP.read_text())
        for script in (text, SETUP.read_text()):
            self.assertNotIn('curl -H', script)
            self.assertNotIn('--header', script)
            self.assertIn('--config -', script)

class RunningWrapperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.processes = []
        self.agent = self.root / '.omp/agent'
        self.agent.mkdir(parents=True)
        pairing = self.root / '.config/backstage-omp/broker'
        pairing.parent.mkdir(parents=True)
        pairing.write_text('port=47821\ntoken=' + 'S' * 43 + '\n')
        self.incoming = self.root / 'incoming'
        self.incoming.mkdir()
        for name, content in {'models.yml': 'providers: {}\n', 'config.yml': 'modelRoles: {}\n',
                              'mcp.json': '{"mcpServers":{}}', 'instructions.txt': 'Shared global guidance.'}.items():
            (self.incoming / name).write_text(content)
        self.skills('first')
        user = self.agent / 'skills/personal'
        user.mkdir(parents=True)
        (user / 'SKILL.md').write_text('user-owned')
        binary = self.root / 'bin'
        binary.mkdir()
        self.script(binary / 'curl', '''#!/usr/bin/env python3
import os, sys, time
from pathlib import Path
root = Path(os.environ['HOME'])
args = sys.argv[1:]
assert 'S' * 43 not in '\0'.join(args)
assert sys.stdin.read() == 'header = "Authorization: Bearer ' + 'S' * 43 + '"\n'
name = next(x for x in args if x.startswith('http://')).rsplit('/', 1)[-1]
with (root / 'requests').open('a') as f: f.write(name + '\n')
if (root / ('stall-' + name)).exists():
    (root / 'stalled').touch()
    time.sleep(60)
if (root / 'offline').exists() or (root / ('fail-' + name)).exists():
    sys.stdout.write('partial'); sys.exit(22)
sys.stdout.buffer.write((root / 'incoming' / name).read_bytes())
''')
        self.script(binary / 'setsid', '''#!/usr/bin/env python3
import os, sys
from pathlib import Path
with (Path(os.environ['HOME']) / 'pollers').open('a') as f: f.write(str(os.getpid()) + '\n')
os.setsid()
os.execvp(sys.argv[1], sys.argv[1:])
''')
        self.script(binary / 'sleep', '''#!/usr/bin/env python3
import time
time.sleep(0.15)
''')
        self.script(self.root / '.bun/bin/bun', '''#!/usr/bin/env python3
import json, os, signal, sys, time
from pathlib import Path
root = Path(os.environ['HOME'])
assert 'S' * 43 not in '\0'.join(sys.argv)
(root / ('argv-' + str(os.getpid()))).write_text(json.dumps(sys.argv))
(root / ('ready-' + str(os.getpid()))).touch()
while True: time.sleep(0.05)
''')
        self.env = {**os.environ, 'HOME': str(self.root), 'PATH': str(binary) + ':' + os.environ['PATH']}

    def script(self, path, content):
        path.parent.mkdir(parents=True, exist_ok=True)
        # Triple-quoted test doubles contain literal control characters: encode them as Python source escapes.
        content = content.replace('\0', '\\0').replace("'\n'", "'\\n'").replace('"\n\'', '"\\n\'')
        path.write_text(content)
        path.chmod(0o700)

    def skills(self, body):
        with zipfile.ZipFile(self.incoming / 'skills.zip', 'w') as z:
            z.writestr('skills/shared/.backstage', '')
            z.writestr('skills/shared/SKILL.md', body)

    def wait_for(self, condition, seconds=8):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition(): return
            time.sleep(0.04)
        self.fail('wrapper condition did not become true')

    def launch(self, *args):
        p = subprocess.Popen(['bash', str(WRAPPER), *args], env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.processes.append(p)
        try:
            self.wait_for(lambda: (self.root / ('ready-' + str(p.pid))).exists() or p.poll() is not None)
            if p.poll() is not None:
                out, err = p.communicate()
                self.fail(f'wrapper exited {p.returncode}: {out!r} {err!r}')
        except Exception:
            if p.poll() is None: p.terminate()
            raise
        return p

    def pollers(self):
        file = self.root / 'pollers'
        ids = [int(x) for x in file.read_text().splitlines()] if file.exists() else []
        active = []
        for pid in ids:
            try:
                stat = Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()
                cmd = Path(f'/proc/{pid}/cmdline').read_bytes()
                if stat[0] != 'Z' and b'--backstage-sync-poll' in cmd: active.append(pid)
            except (FileNotFoundError, ProcessLookupError): pass
        return active

    def tearDown(self):
        import signal
        for p in self.processes:
            if p.poll() is None: p.terminate()
            p.communicate(timeout=5)
        for pid in self.pollers():
            try: os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError: pass
        time.sleep(0.2)
        self.tmp.cleanup()

    def test_only_changed_files_are_replaced_and_live_changes_survive_broker_failure(self):
        p = self.launch('-p', 'task')
        self.wait_for(lambda: len(self.pollers()) == 1)
        paths = [self.agent / x for x in ('models.yml', 'backstage.yml', 'mcp.json', 'instructions.txt',
                                          'skills/shared/SKILL.md', '.skills-backstage.sha256')]
        before = {x: x.stat().st_mtime_ns for x in paths}
        self.wait_for(lambda: (self.root / 'requests').read_text().count('instructions.txt') >= 3)
        self.assertEqual(before, {x: x.stat().st_mtime_ns for x in paths})
        note = __import__('json').loads((self.root / ('argv-' + str(p.pid))).read_text())
        appended = note[note.index('--append-system-prompt') + 1]
        self.assertIn('backstage-sh', appended)
        self.assertTrue(appended.endswith('\n\nShared global guidance.'))
        (self.root / 'offline').touch()
        (self.incoming / 'instructions.txt').write_text('Changed global guidance.')
        self.skills('second')
        time.sleep(0.6)
        self.assertEqual(before, {x: x.stat().st_mtime_ns for x in paths})
        (self.root / 'offline').unlink()
        self.wait_for(lambda: (self.agent / 'instructions.txt').read_text() == 'Changed global guidance.')
        self.wait_for(lambda: (self.agent / 'skills/shared/SKILL.md').read_text() == 'second')
        self.assertEqual('user-owned', (self.agent / 'skills/personal/SKILL.md').read_text())
        self.assertEqual(before[paths[0]], paths[0].stat().st_mtime_ns)
        for path in self.agent.rglob('*'):
            if path.is_file(): self.assertNotIn(b'S' * 43, path.read_bytes(), str(path))
        self.assertNotIn(b'S' * 43, Path(f'/proc/{p.pid}/cmdline').read_bytes())

    def test_one_poller_handles_reentry_and_exits_after_last_parent_dies(self):
        one = self.launch('-p', 'one')
        self.wait_for(lambda: len(self.pollers()) == 1)
        two = self.launch('-p', 'two')
        time.sleep(0.4)
        self.assertEqual(1, len(self.pollers()))
        one.terminate(); one.communicate(timeout=5)
        (self.incoming / 'mcp.json').write_text('{"mcpServers":{"new":{}}}')
        self.wait_for(lambda: 'new' in (self.agent / 'mcp.json').read_text())
        self.assertEqual(1, len(self.pollers()))
        two.kill(); two.communicate(timeout=5)
        self.wait_for(lambda: not self.pollers())
        self.wait_for(lambda: not list((self.agent / '.backstage-sync-clients').iterdir()))

    def test_parent_death_cancels_a_stalled_download_and_cleans_its_stage(self):
        p = self.launch('-p', 'one')
        self.wait_for(lambda: len(self.pollers()) == 1)
        (self.root / 'stall-skills.zip').touch()
        self.wait_for(lambda: (self.root / 'stalled').exists())
        p.kill(); p.communicate(timeout=5)
        self.wait_for(lambda: not self.pollers())
        self.wait_for(lambda: not list(self.agent.glob('.backstage-skills.*')))
        self.assertEqual('first', (self.agent / 'skills/shared/SKILL.md').read_text())

    def test_rpc_modes_never_start_a_poller(self):
        for args in (('--mode', 'rpc'), ('--mode=rpc',)):
            p = self.launch(*args)
            time.sleep(0.3)
            self.assertFalse((self.root / 'pollers').exists())
            p.terminate(); p.communicate(timeout=5)
        self.assertEqual(2, (self.root / 'requests').read_text().splitlines().count('instructions.txt'))

    def test_prompt_options_after_separator_are_data(self):
        self.launch('-p', '--', '--mode=rpc')
        self.wait_for(lambda: len(self.pollers()) == 1)


if __name__ == '__main__':
    unittest.main()
