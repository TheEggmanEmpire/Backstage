"""Exercise the device wrapper's live MCP refresh without Android, Bun, or a network."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


REPO = Path(__file__).resolve().parents[2]
WRAPPER = REPO / "backstage/omp/device/omp"
SETUP = REPO / "backstage/omp/device/termux-setup.sh"


class OmpMcpTest(unittest.TestCase):
    def test_wrapper_refetches_mcp_each_launch_and_preserves_cache_on_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            pairing = root / ".config/backstage-omp/broker"
            pairing.parent.mkdir(parents=True)
            pairing.write_text("port=47821\ntoken=" + "S" * 43 + "\n")
            config = root / ".omp/agent"
            config.mkdir(parents=True)
            (config / "config.yml").write_text("user-owned: true\n")
            (root / "bin").mkdir()
            curl = root / "bin/curl"
            curl.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
root = Path(os.environ["HOME"])
args = sys.argv[1:]
token = dict(line.split("=", 1) for line in (root / ".config/backstage-omp/broker").read_text().splitlines())["token"]
assert token not in "\\0".join(args)
assert args[args.index("--config") + 1] == "-"
assert sys.stdin.read() == 'header = "Authorization: Bearer ' + token + '"\\n'
assert args[args.index("--max-time") + 1] == "1"
name = args[-1].rsplit("/", 1)[-1]
with (root / "requests").open("a") as log:
    log.write(name + "\\n")
if name == "mcp.json":
    if os.environ.get("FAIL_MCP"):
        print("partial response", end="")
        sys.exit(22)
    print(json.dumps({"mcpServers": {os.environ["SERVER_NAME"]: {"type": "http", "url": "https://remote.test/mcp", "headers": {"Authorization": "!backstage-omp-key mcp-remote"}, "enabled": True}}}), end="")
else:
    print("providers: {}" if name == "models.yml" else "modelRoles: {}")
''')
            curl.chmod(0o700)
            bun = root / ".bun/bin/bun"
            bun.parent.mkdir(parents=True)
            bun.write_text("#!/usr/bin/env bash\nprintf '%s\\n' \"$@\" > \"$HOME/bun-argv\"\n")
            bun.chmod(0o700)
            env = {**os.environ, "HOME": folder, "PATH": str(root / "bin") + ":" + os.environ["PATH"], "SERVER_NAME": "First"}

            def launch():
                result = subprocess.run(["bash", str(WRAPPER), "--version"], env=env, capture_output=True, text=True)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn("S" * 43, result.stdout + result.stderr + (root / "bun-argv").read_text())
                self.assertEqual("user-owned: true\n", (config / "config.yml").read_text())
                return json.loads((config / "mcp.json").read_text())

            self.assertEqual({"First"}, set(launch()["mcpServers"]))
            env["SERVER_NAME"] = "Second"
            self.assertEqual({"Second"}, set(launch()["mcpServers"]))
            cached = (config / "mcp.json").read_bytes()
            env["FAIL_MCP"] = "1"
            self.assertEqual({"Second"}, set(launch()["mcpServers"]))
            self.assertEqual(cached, (config / "mcp.json").read_bytes())
            self.assertEqual(3, (root / "requests").read_text().splitlines().count("mcp.json"))
            self.assertFalse(list(config.glob("*.tmp.*")))
            self.assertNotIn("S" * 43, cached.decode())
            self.assertIn("!backstage-omp-key mcp-remote", cached.decode())

    def test_setup_refreshes_mcp_and_preserves_cache_when_fetch_fails(self):
        subprocess.run(["bash", "-n", str(WRAPPER)], check=True)
        subprocess.run(["bash", "-n", str(SETUP)], check=True)
        # Run the installer's real config-install phase, with its network fetch supplied by a double.
        phase = "fetch config/models.yml" + SETUP.read_text().split("fetch config/models.yml", 1)[1].split("export PATH=", 1)[0]
        script = '''set -euo pipefail
umask 077
stage=$(mktemp -d "$HOME/setup.XXXXXX")
trap 'rm -rf "$stage"' EXIT
fetch() {
    if [[ $1 == config/mcp.json ]]; then
        if [[ ${FAIL_MCP:-} == 1 ]]; then printf partial > "$2"; return 22; fi
        printf '%s' "$MCP_JSON" > "$2"
    else
        printf 'providers: {}\\n' > "$2"
    fi
}
''' + phase
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            config = root / ".omp/agent"
            config.mkdir(parents=True)
            cached = config / "mcp.json"
            original = '{"mcpServers":{"old":{}}}'
            cached.write_text(original)
            shared = json.dumps({"mcpServers": {"remote": {"type": "http", "url": "https://remote.test/mcp", "headers": {"Authorization": "!backstage-omp-key mcp-remote"}, "enabled": True}}})
            env = {**os.environ, "HOME": folder, "MCP_JSON": shared}
            result = subprocess.run(["bash"], input=script, env=env, capture_output=True, text=True)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(shared, cached.read_text())
            self.assertEqual(0o600, cached.stat().st_mode & 0o777)
            backups = list(config.glob("mcp.json.bak-*"))
            self.assertEqual(1, len(backups))
            self.assertEqual(original, backups[0].read_text())
            env["FAIL_MCP"] = "1"
            result = subprocess.run(["bash"], input=script, env=env, capture_output=True, text=True)
            self.assertEqual(22, result.returncode)
            self.assertEqual(shared, cached.read_text())
            self.assertEqual([], list(root.glob("setup.*")))


if __name__ == "__main__":
    unittest.main()
