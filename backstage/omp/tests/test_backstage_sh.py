"""Run the real bash helper and Bun JSON code with a curl double; never needs Android or a network."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "device/backstage-sh"
BUN = os.environ.get("BACKSTAGE_TEST_BUN") or shutil.which("bun")


class BackstageShTest(unittest.TestCase):
    def test_bash_syntax(self):
        subprocess.run(["bash", "-n", str(SCRIPT)], check=True)

    @unittest.skipUnless(BUN, "Set BACKSTAGE_TEST_BUN to a host Bun binary")
    def test_real_helper_contract_and_token_stays_out_of_process_arguments(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            pairing = root / ".config/backstage-omp/broker"
            pairing.parent.mkdir(parents=True)
            token = "S" * 43  # synthetic pairing fixture only
            pairing.write_text(f"port=47821\ntoken={token}\n")
            pairing.chmod(0o600)
            (root / ".bun/bin").mkdir(parents=True)
            (root / ".bun/bin/bun").symlink_to(BUN)
            (root / "bin").mkdir()
            curl = root / "bin/curl"
            curl.write_text("""#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
config = sys.stdin.read()
root = Path(os.environ['HOME'])
pairing = dict(line.split('=', 1) for line in (root / '.config/backstage-omp/broker').read_text().splitlines())
token = pairing['token']
assert token not in '\\0'.join(args), 'token leaked into curl argv'
assert token not in '\\0'.join(os.environ.values()), 'token leaked into environment'
assert args[args.index('--config') + 1] == '-'
assert config == 'header = "Authorization: Bearer ' + token + '"\\n'
assert args[args.index('--noproxy') + 1] == '*'
assert args[-1] == 'http://127.0.0.1:47821/omp/v1/shell'
body = json.loads(Path(args[args.index('--data-binary') + 1][1:]).read_text())
(root / 'captured.json').write_text(json.dumps(body))
Path(args[args.index('-o') + 1]).write_text(os.environ['TEST_RESPONSE'])
print(os.environ.get('TEST_HTTP_STATUS', '200'), end='')
""")
            curl.chmod(0o700)
            env = {**os.environ, "HOME": folder, "PATH": str(root / "bin") + ":" + os.environ["PATH"], "token": "inherited-export",
                   "TMPDIR": folder, "TEST_RESPONSE": json.dumps({"exit_code": 23, "stdout": "hello\n🦊", "stderr": "warning\n"})}
            command = "adb shell printf '%s' 'quotes \\\" \\ $HOME; \n🦊'"
            result = subprocess.run(["bash", str(SCRIPT), "--timeout", "999", command], env=env,
                                    capture_output=True, text=True)
            self.assertEqual(23, result.returncode, result.stderr)
            self.assertEqual("hello\n🦊", result.stdout)
            self.assertEqual("warning\n", result.stderr)
            self.assertEqual({"command": command, "timeout": 300}, json.loads((root / "captured.json").read_text()))
            self.assertEqual([], list(root.glob("tmp.*")), "temporary request/response files survived")

            result = subprocess.run(["bash", str(SCRIPT), "--timeout", "1"], input="pm list packages", env=env,
                                    capture_output=True, text=True)
            self.assertEqual(23, result.returncode, result.stderr)
            self.assertEqual({"command": "pm list packages", "timeout": 5}, json.loads((root / "captured.json").read_text()))
            env.update(TEST_HTTP_STATUS="403", TEST_RESPONSE=json.dumps({"error": "Termux shell access is disabled"}))
            result = subprocess.run(["bash", str(SCRIPT), "id"], env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertIn("disabled", result.stderr)
            self.assertNotIn(token, result.stderr)
            env.update(TEST_HTTP_STATUS="401", TEST_RESPONSE="Pairing revoked")
            result = subprocess.run(["bash", str(SCRIPT), "id"], env=env, capture_output=True, text=True)
            self.assertEqual(1, result.returncode)
            self.assertEqual("", result.stdout)
            self.assertIn("run the setup line again", result.stderr)
            self.assertNotIn(token, result.stderr)

            for command in ["", "a" * 4001, "id\x00whoami"]:
                (root / "captured.json").unlink(missing_ok=True)
                result = subprocess.run(["bash", str(SCRIPT)], input=command, env=env,
                                        capture_output=True, text=True)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertFalse((root / "captured.json").exists())

            for data in [f"port=70000\ntoken={token}\n", "port=47821\ntoken=bad\n", f"port=47821;id\ntoken={token}\n"]:
                pairing.write_text(data)
                result = subprocess.run(["bash", str(SCRIPT), "id"], env=env, capture_output=True, text=True)
                self.assertEqual(1, result.returncode)
                self.assertEqual("", result.stdout)
                self.assertNotIn(token, result.stderr)


if __name__ == "__main__":
    unittest.main()
