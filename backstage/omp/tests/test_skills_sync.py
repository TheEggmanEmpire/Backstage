"""Exercise the actual wrapper and installer's skill sync with bash, real unzip and fake curl."""
import os
from contextlib import contextmanager
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap
import unittest
import zipfile


DEVICE = Path(__file__).resolve().parents[1] / "device"
WRAPPER = DEVICE / "omp"
SETUP = DEVICE / "termux-setup.sh"


class SkillsSyncTest(unittest.TestCase):
    @contextmanager
    def regression_environment(self, incoming):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            agent = root / ".omp/agent"
            for name in ("managed", "zeta"):
                directory = agent / "skills" / name
                directory.mkdir(parents=True)
                (directory / ".backstage").touch()
                (directory / "SKILL.md").write_text("cached " + name)
            personal = agent / "skills/personal"
            personal.mkdir()
            (personal / "SKILL.md").write_text("personal")
            pairing = root / ".config/backstage-omp/broker"
            pairing.parent.mkdir(parents=True)
            pairing.write_text("port=47821\ntoken=" + "S" * 43 + "\n")
            with zipfile.ZipFile(root / "incoming.zip", "w", zipfile.ZIP_DEFLATED) as archive:
                for path, contents in incoming.items():
                    archive.writestr(path, contents)
            binary = root / "bin"
            binary.mkdir()
            curl = binary / "curl"
            curl.write_text('''#!/usr/bin/env python3
import os, sys, time
from pathlib import Path
root = Path(os.environ["HOME"])
args = sys.argv[1:]
assert "S" * 43 not in "\\0".join(args)
assert sys.stdin.read() == 'header = "Authorization: Bearer ' + "S" * 43 + '"\\n'
name = next(arg for arg in args if arg.startswith("http://")).rsplit("/", 1)[-1]
if name != "skills.zip":
    print("providers: {}")
    sys.exit(0)
if os.environ.get("SLOW_SKILLS"):
    active = root / "download-active"
    try:
        fd = os.open(active, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        (root / "overlap").touch()
        sys.exit(22)
    os.close(fd)
    time.sleep(0.25)
    active.unlink()
with (root / "downloads").open("a") as log:
    log.write("skills.zip\\n")
sys.stdout.buffer.write((root / "incoming.zip").read_bytes())
''')
            curl.chmod(0o700)
            mv = binary / "mv"
            mv.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
root = Path(os.environ["HOME"])
args = sys.argv[1:]
if os.environ.get("FAIL_SKILL_MOVE") and "/unpacked/skills/" in args[-2] and args[-1].endswith("/skills/managed"):
    marker = root / "move-failed"
    if not marker.exists():
        marker.touch()
        sys.exit(1)
os.execv(os.environ["REAL_MV"], [os.environ["REAL_MV"], *args])
''')
            mv.chmod(0o700)
            bun = root / ".bun/bin/bun"
            bun.parent.mkdir(parents=True)
            bun.write_text("#!/bin/sh\nexit 0\n")
            bun.chmod(0o700)
            env = {**os.environ, "HOME": folder, "PATH": str(binary) + ":" + os.environ["PATH"], "REAL_MV": shutil.which("mv")}
            yield root, env

    def sync_command(self, route, directory_limit=None):
        if route == "wrapper":
            if directory_limit is None:
                return ["bash", str(WRAPPER), "--version"]
            phase = WRAPPER.read_text()
            return ["bash", "-c", phase.replace("directory_count > 20000000", f"directory_count > {directory_limit}"), "omp", "--version"]
        phase = SETUP.read_text().split("# BEGIN BACKSTAGE SKILLS SYNC", 1)[1].split("# END BACKSTAGE SKILLS SYNC", 1)[0]
        if directory_limit is not None:
            phase = phase.replace("directory_count > 20000000", f"directory_count > {directory_limit}")
        return ["bash", "-c", 'set -euo pipefail\nport=47821\ntoken=' + "S" * 43 + '\n' + phase]

    def test_directory_bomb_is_rejected_before_extracting_or_replacing_cache(self):
        incoming = {"skills/managed/.backstage": "", "skills/managed/SKILL.md": "new"}
        for i in range(225):
            incoming[f"skills/managed/branch-{i}/a/b/c/d/e/f/g/h/file.md"] = "x"
        for route in ("wrapper", "setup"):
            with self.subTest(route=route), self.regression_environment(incoming) as (root, env):
                result = subprocess.run(self.sync_command(route, directory_limit=2000), env=env, capture_output=True, text=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertIn("keeping cached skills", result.stderr)
                self.assertEqual("cached managed", (root / ".omp/agent/skills/managed/SKILL.md").read_text())
                self.assertFalse((root / ".omp/agent/skills/managed/branch-0").exists())

    def test_large_valid_archive_and_many_directories_are_accepted(self):
        incoming = {"skills/managed/.backstage": "", "skills/managed/SKILL.md": "new",
                    "skills/managed/data.bin": os.urandom(30_100_000)}
        for i in range(230):
            incoming[f"skills/managed/branch-{i}/a/b/c/d/e/f/g/h/file.md"] = "x"
        for route in ("wrapper", "setup"):
            with self.subTest(route=route), self.regression_environment(incoming) as (root, env):
                self.assertGreater((root / "incoming.zip").stat().st_size, 30_000_000)
                result = subprocess.run(self.sync_command(route), env=env, capture_output=True, text=True, timeout=30)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn("keeping cached skills", result.stderr)
                skill = root / ".omp/agent/skills/managed"
                self.assertEqual(30_100_000, (skill / "data.bin").stat().st_size)
                self.assertTrue((skill / "branch-229/a/b/c/d/e/f/g/h/file.md").exists())

    def test_concurrent_launches_serialize_sync_and_never_nest_skill_directories(self):
        incoming = {"skills/managed/.backstage": "", "skills/managed/SKILL.md": "new"}
        for route in ("wrapper", "setup"):
            with self.subTest(route=route), self.regression_environment(incoming) as (root, env):
                env["SLOW_SKILLS"] = "1"
                processes = [subprocess.Popen(self.sync_command(route), env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for _ in range(2)]
                for process in processes:
                    out, err = process.communicate(timeout=20)
                    self.assertEqual(0, process.returncode, out + err)
                self.assertFalse((root / "overlap").exists(), "two refreshes entered the critical section")
                self.assertEqual(2, len((root / "downloads").read_text().splitlines()))
                directory = root / ".omp/agent/skills/managed"
                self.assertEqual("new", (directory / "SKILL.md").read_text())
                self.assertFalse((directory / "managed").exists())

    def test_failed_install_move_rolls_back_every_managed_skill(self):
        incoming = {"skills/fresh/.backstage": "", "skills/fresh/SKILL.md": "fresh",
                    "skills/managed/.backstage": "", "skills/managed/SKILL.md": "new"}
        for route in ("wrapper", "setup"):
            with self.subTest(route=route), self.regression_environment(incoming) as (root, env):
                env["FAIL_SKILL_MOVE"] = "1"
                result = subprocess.run(self.sync_command(route), env=env, capture_output=True, text=True, timeout=15)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertTrue((root / "move-failed").exists())
                self.assertIn("keeping cached skills", result.stderr)
                skills = root / ".omp/agent/skills"
                for name in ("managed", "zeta"):
                    self.assertTrue((skills / name / "SKILL.md").exists(), "cached skill was lost during a failed swap")
                    self.assertEqual("cached " + name, (skills / name / "SKILL.md").read_text())
                self.assertEqual("personal", (skills / "personal/SKILL.md").read_text())
                self.assertFalse((skills / "fresh").exists())
                self.assertEqual([], list((root / ".omp/agent").glob(".backstage-skills.*")))

    def test_wrapper_and_setup_preserve_user_skills_and_refresh_managed_skills(self):
        for route in ("wrapper", "setup"):
            with self.subTest(route=route), tempfile.TemporaryDirectory() as folder:
                root = Path(folder)
                config = root / ".omp/agent"
                skills = config / "skills"
                managed = skills / "managed"
                managed.mkdir(parents=True)
                (managed / ".backstage").touch()
                (managed / "old.txt").write_text("stale")
                removed = skills / "disabled"
                removed.mkdir()
                (removed / ".backstage").touch()
                (removed / "SKILL.md").write_text("disabled")
                user = skills / "personal"
                user.mkdir()
                (user / "SKILL.md").write_text("user authored")
                external = root / "user-symlink-target"
                external.mkdir()
                (external / ".backstage").touch()
                (external / "SKILL.md").write_text("user symlink")
                (skills / "linked").symlink_to(external, target_is_directory=True)
                pairing = root / ".config/backstage-omp/broker"
                pairing.parent.mkdir(parents=True)
                pairing.write_text("port=47821\ntoken=" + "S" * 43 + "\n")
                binary = root / "bin"
                binary.mkdir()
                curl = binary / "curl"
                curl.write_text('''#!/usr/bin/env python3
import os, sys
from pathlib import Path
args = sys.argv[1:]
assert "S" * 43 not in "\\0".join(args)
assert sys.stdin.read() == 'header = "Authorization: Bearer ' + "S" * 43 + '"\\n'
name = next(a for a in args if a.startswith("http://")).rsplit("/", 1)[-1]
if name == "skills.zip":
    if os.environ.get("FAIL_SKILLS"):
        sys.stdout.buffer.write(b"partial zip")
        sys.exit(22)
    sys.stdout.buffer.write(Path(os.environ["HOME"], "incoming.zip").read_bytes())
else:
    print("providers: {}")
''')
                curl.chmod(0o700)
                bun = root / ".bun/bin/bun"
                bun.parent.mkdir(parents=True)
                bun.write_text("#!/bin/sh\nexit 0\n")
                bun.chmod(0o700)
                env = {**os.environ, "HOME": folder, "PATH": str(binary) + ":" + os.environ["PATH"]}

                def archive(files):
                    with zipfile.ZipFile(root / "incoming.zip", "w", zipfile.ZIP_DEFLATED) as zip_:
                        for name, text in files.items():
                            zip_.writestr(name, text)

                def launch():
                    if route == "wrapper":
                        command = ["bash", str(WRAPPER), "--version"]
                        script = None
                    else:
                        # Run the real setup sync phase, without reinstalling the runtime.
                        script = SETUP.read_text().split("# BEGIN BACKSTAGE SKILLS SYNC", 1)[1].split("# END BACKSTAGE SKILLS SYNC", 1)[0]
                        script = 'set -euo pipefail\nport=47821\ntoken=' + "S" * 43 + '\n' + script
                        command = ["bash"]
                    result = subprocess.run(command, input=script, env=env, text=True, capture_output=True)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertNotIn("S" * 43, result.stdout + result.stderr)
                    self.assertEqual("user authored", (user / "SKILL.md").read_text())
                    self.assertFalse((user / ".backstage").exists())
                    self.assertTrue((skills / "linked").is_symlink())
                    self.assertEqual("user symlink", (external / "SKILL.md").read_text())
                    self.assertEqual([], list(config.glob(".backstage-skills.*")))
                    return result

                archive({"skills/managed/.backstage": "", "skills/managed/SKILL.md": "version one",
                         "skills/managed/references/a b.md": "reference", "skills/personal/.backstage": "",
                         "skills/personal/SKILL.md": "must not overwrite user", "skills/linked/.backstage": "",
                         "skills/linked/SKILL.md": "must not follow user symlink"})
                launch()
                self.assertFalse((managed / "old.txt").exists())
                self.assertFalse(removed.exists())
                self.assertEqual("version one", (managed / "SKILL.md").read_text())
                self.assertEqual("reference", (managed / "references/a b.md").read_text())
                archive({"skills/managed/.backstage": "", "skills/managed/SKILL.md": "version two"})
                launch()
                self.assertEqual("version two", (managed / "SKILL.md").read_text())
                self.assertFalse((managed / "references").exists())
                env["FAIL_SKILLS"] = "1"
                self.assertIn("keeping cached skills", launch().stderr)
                self.assertEqual("version two", (managed / "SKILL.md").read_text())
                del env["FAIL_SKILLS"]
                for hostile in ({"../escaped": "bad"}, {"skills/managed/.backstage": "", "skills/managed/../../escaped": "bad"},
                                {"skills/managed/SKILL.md": "missing ownership marker"},
                                {"skills/managed/.backstage": "", "skills/managed/" + "deep/" * 11 + "file": "x"}):
                    archive(hostile)
                    self.assertIn("keeping cached skills", launch().stderr)
                    self.assertEqual("version two", (managed / "SKILL.md").read_text())
                    self.assertFalse((root / "escaped").exists())
                archive({})
                launch()
                self.assertFalse(managed.exists())

    def test_sync_implementation_is_identical_in_both_entry_points(self):
        def phase(path):
            return textwrap.dedent(path.read_text().split("# BEGIN BACKSTAGE SKILLS SYNC", 1)[1].split("# END BACKSTAGE SKILLS SYNC", 1)[0]).strip()
        self.assertEqual(phase(WRAPPER).split("\nif ! backstage_sync_skills", 1)[0], phase(SETUP).split("\nif ! backstage_sync_skills", 1)[0])

    def test_shell_syntax(self):
        for path in (WRAPPER, SETUP):
            subprocess.run(["bash", "-n", str(path)], check=True)


if __name__ == "__main__":
    unittest.main()
