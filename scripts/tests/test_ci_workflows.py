#!/usr/bin/env python3
"""Checks of the CI workflows and the scripts/steps they run.

    python3 -m unittest discover -s scripts/tests -p 'test_*.py'

* Caches are a write channel the token split does not cover: the upstream canary builds and tests untrusted upstream
  code, and the signed release job holds the keystore. Neither may restore or save the Flutter SDK, pub or Gradle
  caches (a cache saved next to untrusted code would be restored by later trusted builds, the signed release included).
* Disk space: every job that builds frees the runner's disk first (never the Android SDK), builds arm64 only and
  drops intermediates no later step needs to keep each build within the runner capacity.
* The signed release is skipped, not failed, without signing secrets; a partial set fails.
* Third-party actions are pinned to commit SHAs; the sdkmanager retry loop fails when every attempt fails.
"""
import sys
sys.dont_write_bytecode = True  # keep scripts/ free of __pycache__

import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

try:
    import yaml
except ImportError:  # pragma: no cover - PyYAML is present on the CI images and here
    yaml = None

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACTION = os.path.join(ROOT, ".github", "actions", "setup-toolchain", "action.yml")
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")


def load(path):
    with open(path, encoding="utf-8") as f:
        return yaml.safe_load(f)


def evaluate(expr, ctx):
    """Evaluate the small subset of GitHub Actions expressions the workflows use in `if:` (always(), &&, ||, ==, !=,
    format(), context lookups). A missing context value is None, formatted as '' (as on GitHub)."""
    def lookup(m):
        return f"_get({m.group(0)!r})"

    def _get(path):
        node = ctx
        for part in path.split("."):
            if not isinstance(node, dict) or part not in node:
                return None
            node = node[part]
        return node

    def _format(fmt, *args):
        return re.sub(r"\{(\d+)\}", lambda m: "" if args[int(m.group(1))] is None else str(args[int(m.group(1))]), fmt)

    def _starts_with(s, prefix):
        return str("" if s is None else s).lower().startswith(str(prefix).lower())

    py = re.sub(r"\b(?:github|inputs|steps|needs)(?:\.[A-Za-z_][\w-]*)+", lookup, expr)
    py = py.replace("&&", " and ").replace("||", " or ").replace("always()", "True").replace("format(", "_format(")
    py = py.replace("startsWith(", "_starts_with(")
    py = re.sub(r"!(?!=)", " not ", py)
    return eval(py, {"__builtins__": {}}, {"_get": _get, "_format": _format, "_starts_with": _starts_with,
                                           "True": True})


def toolchain_steps(job):
    return [s for s in job.get("steps", []) if s.get("uses") == "./.github/actions/setup-toolchain"]


@unittest.skipIf(yaml is None, "PyYAML not installed")
class CiCacheTest(unittest.TestCase):
    def test_action_can_switch_every_cache_off(self):
        action = load(ACTION)
        self.assertEqual("on", str(action["inputs"]["cache"]["default"]))
        steps = action["runs"]["steps"]
        flutter = next(s for s in steps if str(s.get("uses", "")).startswith("subosito/flutter-action"))
        self.assertIn("inputs.cache", str(flutter["with"]["cache"]))
        gradle = next(s for s in steps if str(s.get("uses", "")).startswith("gradle/actions/setup-gradle"))
        self.assertIn("inputs.cache == 'off'", str(gradle["with"]["cache-disabled"]))

    def test_canary_running_upstream_code_uses_no_cache(self):
        canary = load(os.path.join(WORKFLOWS, "upstream-canary.yml"))["jobs"]["canary"]
        steps = toolchain_steps(canary)
        self.assertTrue(steps, "canary sets up the toolchain")
        for step in steps:
            self.assertEqual("off", str(step.get("with", {}).get("cache")), step)

    def test_signed_release_starts_from_clean_downloads(self):
        release = load(os.path.join(WORKFLOWS, "build.yml"))["jobs"]["release"]
        steps = toolchain_steps(release)
        self.assertTrue(steps, "release sets up the toolchain")
        for step in steps:
            self.assertEqual("off", str(step.get("with", {}).get("cache")), step)



@unittest.skipIf(yaml is None, "PyYAML not installed")
class ReleaseSourcesTest(unittest.TestCase):
    """GPL-3.0 §6 / LGPL-2.1 §6: a release carries the sources of the prebuilt third-party binaries in its APK."""

    def test_release_tag_check_matches_the_built_apk_name(self):
        steps = load(os.path.join(WORKFLOWS, "build.yml"))["jobs"]["release"]["steps"]
        step = next(s for s in steps if s.get("name") == "Check the version against the tag")
        with tempfile.TemporaryDirectory() as directory:
            dist = os.path.join(directory, "dist")
            os.mkdir(dist)
            apk = os.path.join(dist, "backstage-1.2.3-arm64-v8a-release.apk")
            with open(apk, "wb"):
                pass
            env = {**os.environ, "GITHUB_REF_NAME": "v1.2.3"}
            match = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=directory, env=env,
                                   capture_output=True, text=True)
            self.assertEqual(0, match.returncode, match.stderr)
            self.assertNotIn("::warning::", match.stdout)
            os.rename(apk, os.path.join(dist, "backstage-1.2.2-arm64-v8a-release.apk"))
            mismatch = subprocess.run(["bash", "-e", "-c", step["run"]], cwd=directory, env=env,
                                      capture_output=True, text=True)
            self.assertIn("::warning::", mismatch.stdout)

    def test_release_mirrors_and_attaches_third_party_sources(self):
        steps = load(os.path.join(WORKFLOWS, "build.yml"))["jobs"]["release"]["steps"]
        runs = [str(s.get("run", "")) for s in steps]
        fetch = next(i for i, r in enumerate(runs) if "scripts/fetch-third-party-sources.sh" in r)
        publish = next(i for i, s in enumerate(steps) if str(s.get("uses", "")).startswith("softprops/action-gh-release"))
        self.assertLess(fetch, publish, "sources are fetched before the release is published")
        self.assertIn("$RUNNER_TEMP/sources", runs[fetch])
        files = steps[publish]["with"]["files"]
        self.assertIn("runner.temp }}/sources/*", files)
        self.assertTrue(steps[publish]["with"]["fail_on_unmatched_files"])
        self.assertIn("release-notes.md", steps[publish]["with"]["body_path"])


@unittest.skipIf(yaml is None, "PyYAML not installed")
class UpstreamReportingTest(unittest.TestCase):
    """New upstream releases are reported without secrets: the watch job only lists tags and may only write issues;
    the canary puts its report on the run page."""

    def test_watch_validates_without_write_permissions(self):
        wf = load(os.path.join(WORKFLOWS, "upstream-watch.yml"))
        self.assertEqual({"contents": "read"}, wf["jobs"]["validate"]["permissions"])
        self.assertEqual({"contents": "write", "issues": "write", "actions": "write"}, wf["jobs"]["publish"]["permissions"])
        self.assertIn("schedule", wf[True] if True in wf else wf["on"])

    def test_canary_writes_a_step_summary_and_runs_the_fast_gates(self):
        text = read_text(os.path.join(WORKFLOWS, "upstream-canary.yml"))
        self.assertIn("GITHUB_STEP_SUMMARY", text)
        self.assertIn("scripts/update-upstream.sh --test fast", text)


@unittest.skipIf(yaml is None, "PyYAML not installed")
class CanaryPublishTest(unittest.TestCase):
    """The publish job (bump PR / conflict issue) must run for the daily schedule, whose event payload has no
    `repository` object, and for a manual run on the default branch; never for a manual run on another branch."""

    def condition(self):
        return load(os.path.join(WORKFLOWS, "upstream-canary.yml"))["jobs"]["publish"]["if"]

    def test_scheduled_run_publishes(self):
        ctx = {"github": {"event_name": "schedule", "ref": "refs/heads/main", "ref_name": "main",
                          "event": {"schedule": "23 4 * * *"}}}
        self.assertTrue(evaluate(self.condition(), ctx))

    def test_manual_run_on_default_branch_publishes(self):
        ctx = {"github": {"event_name": "workflow_dispatch", "ref": "refs/heads/main", "ref_name": "main",
                          "event": {"repository": {"default_branch": "main"}}}}
        self.assertTrue(evaluate(self.condition(), ctx))

    def test_manual_run_on_other_branch_does_not_publish(self):
        ctx = {"github": {"event_name": "workflow_dispatch", "ref": "refs/heads/topic", "ref_name": "topic",
                          "event": {"repository": {"default_branch": "main"}}}}
        self.assertFalse(evaluate(self.condition(), ctx))

    def test_evaluator_reproduces_the_schedule_payload_trap(self):
        old = "always() && github.ref == format('refs/heads/{0}', github.event.repository.default_branch)"
        ctx = {"github": {"event_name": "schedule", "ref": "refs/heads/main", "event": {"schedule": "23 4 * * *"}}}
        self.assertFalse(evaluate(old, ctx))


FREE_DISK = os.path.join(ROOT, "scripts", "ci-free-disk-space.sh")


def workflow_files():
    return sorted(os.path.join(WORKFLOWS, f) for f in os.listdir(WORKFLOWS) if f.endswith((".yml", ".yaml")))


def all_jobs():
    for path in workflow_files():
        for name, job in (load(path).get("jobs") or {}).items():
            yield f"{os.path.basename(path)}:{name}", job


def step_index(steps, predicate):
    return next((i for i, s in enumerate(steps) if predicate(s)), None)


def runs(step, needle):
    return needle in str(step.get("run", ""))


def is_system_free_step(step):
    return runs(step, "scripts/ci-free-disk-space.sh") and "--build-outputs" not in str(step.get("run", ""))


def builds(job):
    return any(runs(s, "scripts/build-apk.sh") for s in job.get("steps", []))


def read_text(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def write_exe(path, text):
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)


def clean_env(**extra):
    env = {k: v for k, v in os.environ.items()
           if k not in ("GITHUB_ACTIONS", "RUNNER_ENVIRONMENT", "BACKSTAGE_FREE_DISK_PATHS", "BACKSTAGE_WORK",
                        "ANDROID_HOME", "ANDROID_SDK_ROOT", "AGENT_TOOLSDIRECTORY")}
    env.update(extra)
    return env


@unittest.skipIf(yaml is None, "PyYAML not installed")
class DiskSpaceWorkflowTest(unittest.TestCase):
    """Build jobs free disk space and limit native build output."""

    def test_every_job_that_builds_frees_disk_before_the_toolchain(self):
        seen = 0
        for name, job in all_jobs():
            steps = job.get("steps", [])
            toolchain = step_index(steps, lambda s: s.get("uses") == "./.github/actions/setup-toolchain")
            if toolchain is None and not builds(job):
                continue
            seen += 1
            free = step_index(steps, is_system_free_step)
            self.assertIsNotNone(free, f"{name}: no scripts/ci-free-disk-space.sh step")
            self.assertLess(free, toolchain, f"{name}: disk is freed after the toolchain was installed")
            # Conditional toolchain (canary): free under the same condition, or always.
            self.assertIn(steps[free].get("if"), (None, steps[toolchain].get("if")), name)
        self.assertGreaterEqual(seen, 3, "build, release and canary jobs")

    def test_every_job_that_builds_targets_arm64_only(self):
        seen = 0
        for name, job in all_jobs():
            if not builds(job):
                continue
            seen += 1
            for step in job["steps"]:
                if runs(step, "scripts/build-apk.sh"):
                    platform = (step.get("env") or {}).get("BACKSTAGE_TARGET_PLATFORM",
                                                         (job.get("env") or {}).get("BACKSTAGE_TARGET_PLATFORM"))
                    self.assertEqual("android-arm64", platform, f"{name}: {step.get('name')}")
        self.assertGreaterEqual(seen, 3)

    def test_debug_job_drops_intermediates_between_verify_and_unit_tests(self):
        steps = load(os.path.join(WORKFLOWS, "build.yml"))["jobs"]["build"]["steps"]
        build = step_index(steps, lambda s: runs(s, "scripts/build-apk.sh debug"))
        verify = step_index(steps, lambda s: runs(s, "scripts/verify-apk.sh"))
        prune = step_index(steps, lambda s: runs(s, "scripts/ci-free-disk-space.sh --build-outputs"))
        unit = step_index(steps, lambda s: runs(s, "--only android"))
        upload = step_index(steps, lambda s: str(s.get("uses", "")).startswith("actions/upload-artifact")
                            and "dist/*.apk" in str(s.get("with", {}).get("path", "")))
        self.assertTrue(None not in (build, verify, prune, unit, upload), (build, verify, prune, unit, upload))
        self.assertLess(build, verify)
        self.assertLess(verify, prune)
        self.assertLess(prune, unit)
        self.assertLess(prune, upload)

    def test_android_sdk_is_not_in_the_removal_list(self):
        with open(FREE_DISK, encoding="utf-8") as f:
            text = f.read()
        block = re.search(r"\n    candidates=\(\n(.*?)\n    \)\n", text, re.S)
        self.assertIsNotNone(block, "default candidates=( ... ) list")
        listed = [line.split("#")[0].strip() for line in block.group(1).splitlines()]
        listed = [p for line in listed for p in line.split()]
        for expected in ("/usr/share/dotnet", "/opt/ghc", "/usr/local/.ghcup", "/usr/local/share/boost",
                         '"${AGENT_TOOLSDIRECTORY:-/opt/hostedtoolcache}/CodeQL"', "/usr/share/swift"):
            self.assertIn(expected, listed)
        for path in listed:
            self.assertNotIn("android", path.lower(), path)
        # setup-toolchain installs the SDK packages into the runner's preinstalled SDK.
        with open(ACTION, encoding="utf-8") as f:
            self.assertIn('${ANDROID_HOME:-$ANDROID_SDK_ROOT}/cmdline-tools', f.read())


class FreeDiskScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="backstage-free-disk.")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.bin = os.path.join(self.tmp, "bin")
        os.mkdir(self.bin)
        self.docker_log = os.path.join(self.tmp, "docker.log")
        write_exe(os.path.join(self.bin, "docker"), f'#!/bin/sh\necho "$*" >> "{self.docker_log}"\n')

    def mkdirs(self, *names):
        paths = []
        for name in names:
            p = os.path.join(self.tmp, name)
            os.makedirs(os.path.join(p, "sub"))
            with open(os.path.join(p, "sub", "f"), "w") as f:
                f.write("x")
            paths.append(p)
        return paths

    def run_script(self, *args, **env):
        e = clean_env(**env)
        e["PATH"] = self.bin + os.pathsep + e.get("PATH", "")
        p = subprocess.run(["bash", FREE_DISK, *args], env=e, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, timeout=120)
        return p.returncode, p.stdout

    hosted = {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "github-hosted"}

    def test_removes_the_listed_toolchains_and_prunes_docker_on_a_hosted_runner(self):
        a, b = self.mkdirs("dotnet", "ghc")
        rc, out = self.run_script(BACKSTAGE_FREE_DISK_PATHS=f"{a}\n{b}", **self.hosted)
        self.assertEqual(0, rc, out)
        self.assertFalse(os.path.exists(a) or os.path.exists(b), out)
        self.assertIn("disk usage (before)", out)
        self.assertIn("disk usage (after)", out)
        with open(self.docker_log) as f:
            self.assertIn("image prune --all --force", f.read())

    def test_dry_run_removes_nothing(self):
        (a,) = self.mkdirs("dotnet")
        rc, out = self.run_script("--dry-run", BACKSTAGE_FREE_DISK_PATHS=a)
        self.assertEqual(0, rc, out)
        self.assertIn(f"would remove {a}", out)
        self.assertTrue(os.path.isdir(a))
        self.assertFalse(os.path.exists(self.docker_log))

    def test_refuses_outside_a_github_hosted_runner(self):
        (a,) = self.mkdirs("dotnet")
        for env in ({}, {"GITHUB_ACTIONS": "true", "RUNNER_ENVIRONMENT": "self-hosted"}):
            rc, out = self.run_script(BACKSTAGE_FREE_DISK_PATHS=a, **env)
            self.assertNotEqual(0, rc, out)
            self.assertIn("only on a GitHub-hosted runner", out)
            self.assertTrue(os.path.isdir(a))

    def test_never_touches_the_android_sdk(self):
        parent, other = self.mkdirs("android", "dotnet")
        sdk = os.path.join(parent, "sdk")
        os.makedirs(os.path.join(sdk, "ndk"))
        for victim in ("/usr/local/lib/android", "/usr/local/lib/android/sdk/ndk/28.2.13676358",
                       sdk, os.path.join(sdk, "ndk"), parent):
            rc, out = self.run_script(BACKSTAGE_FREE_DISK_PATHS=f"{other}\n{victim}", ANDROID_HOME=sdk,
                                      **self.hosted)
            self.assertNotEqual(0, rc, victim)
            self.assertIn("refusing to remove", out)
            self.assertTrue(os.path.isdir(os.path.join(sdk, "ndk")))
            self.assertTrue(os.path.isdir(other), "nothing is removed when one path is refused")

    def test_never_removes_the_checkout_or_system_directories(self):
        for victim in (ROOT, os.path.join(ROOT, "scripts"), "/", "/usr", "/opt", "/opt/hostedtoolcache"):
            rc, out = self.run_script(BACKSTAGE_FREE_DISK_PATHS=victim, **self.hosted)
            self.assertNotEqual(0, rc, victim)
            self.assertIn("refusing to remove", out)
        self.assertTrue(os.path.isfile(FREE_DISK))

    def make_work_tree(self, marker=True):
        work = os.path.join(self.tmp, "work tree")
        os.makedirs(work)
        subprocess.run(["git", "init", "-q", work], check=True)
        if marker:
            subprocess.run(["git", "-C", work, "config", "backstage.work", "true"], check=True)
        gone = ["build/app/outputs/apk/github/debug/app.apk", "build/app/outputs/flutter-apk/app-github-debug.apk",
                "build/app/intermediates/merged_native_libs/githubDebug/lib.so",
                "build/app/intermediates/stripped_native_libs/githubDebug/lib.so",
                "build/app/intermediates/dex/githubDebug/classes.dex", "android/app/.cxx/Debug/x.o",
                "build/cardex/outputs/apk/debug/cardex-debug.apk", "build/test_cache/build/x.dill"]
        kept = ["build/app/intermediates/javac/githubDebug/classes/A.class", "build/app/reports/tests/index.html",
                "build/backstage/intermediates/compile_library_classes_jar/debug/classes.jar",
                "android/app/build.gradle.kts"]
        for rel in gone + kept:
            p = os.path.join(work, rel)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            with open(p, "w") as f:
                f.write("x")
        return work, gone, kept

    def test_build_outputs_drops_apks_native_libs_and_dex_but_keeps_classes_and_reports(self):
        work, gone, kept = self.make_work_tree()
        rc, out = self.run_script("--build-outputs", BACKSTAGE_WORK=work)
        self.assertEqual(0, rc, out)
        for rel in gone:
            self.assertFalse(os.path.exists(os.path.join(work, rel)), rel)
        for rel in kept:
            self.assertTrue(os.path.exists(os.path.join(work, rel)), rel)
        self.assertFalse(os.path.exists(self.docker_log), "--build-outputs leaves docker alone")

    def test_build_outputs_refuses_a_directory_that_is_not_a_work_tree(self):
        work, gone, _ = self.make_work_tree(marker=False)
        rc, out = self.run_script("--build-outputs", BACKSTAGE_WORK=work)
        self.assertNotEqual(0, rc, out)
        self.assertIn("nothing removed", out)
        self.assertTrue(os.path.exists(os.path.join(work, gone[0])))


@unittest.skipIf(yaml is None, "PyYAML not installed")
class ReleaseSecretsTest(unittest.TestCase):
    """A tag build without signing secrets skips the signed release cleanly; a partial set fails."""

    NAMES = ("KEYSTORE_BASE64", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")

    def jobs(self):
        return load(os.path.join(WORKFLOWS, "build.yml"))["jobs"]

    def test_release_runs_only_with_secrets_on_tags(self):
        release = self.jobs()["release"]
        self.assertIn("release-secrets", release["needs"])
        for ref, present, expected in (("refs/tags/v1.0.0", "true", True), ("refs/tags/v1.0.0", "false", False),
                                       ("refs/tags/v1.0.0", None, False), ("refs/heads/main", "true", False)):
            ctx = {"github": {"ref": ref},
                   "needs": {"release-secrets": {"outputs": {} if present is None else {"present": present}}}}
            self.assertEqual(expected, bool(evaluate(release["if"], ctx)), (ref, present))

    def test_gate_job_runs_no_code_next_to_the_secrets(self):
        gate = self.jobs()["release-secrets"]
        self.assertTrue(gate["if"].startswith("startsWith(github.ref, 'refs/tags/v')"))
        self.assertEqual({}, gate.get("permissions"))
        for step in gate["steps"]:
            self.assertNotIn("uses", step, "no checkout, no third-party action")
        env = gate["steps"][0]["env"]
        for name in self.NAMES:
            self.assertEqual(f"${{{{ secrets.BACKSTAGE_{name} }}}}", env[name])

    def run_gate(self, **values):
        script = self.jobs()["release-secrets"]["steps"][0]["run"]
        with tempfile.TemporaryDirectory() as tmp:
            out_file = os.path.join(tmp, "out")
            env = {k: v for k, v in os.environ.items() if k not in self.NAMES}
            env.update({"GITHUB_OUTPUT": out_file, "GITHUB_STEP_SUMMARY": os.path.join(tmp, "summary")})
            env.update({k: "" for k in self.NAMES})
            env.update(values)
            p = subprocess.run(["bash", "-e", "-c", script], env=env, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, timeout=30)
            outputs = read_text(out_file) if os.path.exists(out_file) else ""
        return p.returncode, p.stdout, outputs

    def test_no_secrets_skips_with_a_warning(self):
        rc, out, outputs = self.run_gate()
        self.assertEqual(0, rc, out)
        self.assertIn("present=false", outputs)
        self.assertIn("::warning::", out)

    def test_all_secrets_enable_the_release(self):
        rc, out, outputs = self.run_gate(**{k: "x" for k in self.NAMES})
        self.assertEqual(0, rc, out)
        self.assertIn("present=true", outputs)

    def test_a_partial_set_fails(self):
        rc, out, outputs = self.run_gate(KEYSTORE_BASE64="x", KEY_ALIAS="x")
        self.assertNotEqual(0, rc, out)
        self.assertIn("BACKSTAGE_KEYSTORE_PASSWORD", out)
        self.assertIn("BACKSTAGE_KEY_PASSWORD", out)
        self.assertNotIn("present=", outputs)


USES = re.compile(r"^\s*(?:-\s+)?uses:\s*(\S+)(.*)$")


class PinnedActionsTest(unittest.TestCase):
    """a re-pointed tag of a third-party action must not run next to caches or the release keys."""

    def files(self):
        return workflow_files() + [ACTION]

    def test_third_party_actions_are_pinned_to_a_commit_with_a_version_comment(self):
        third_party = 0
        for path in self.files():
            with open(path, encoding="utf-8") as f:
                for n, line in enumerate(f, 1):
                    m = USES.match(line)
                    if not m:
                        continue
                    ref, rest = m.group(1), m.group(2)
                    if ref.startswith("./") or ref.startswith("actions/"):
                        continue
                    third_party += 1
                    where = f"{os.path.basename(path)}:{n}: {ref}"
                    self.assertRegex(ref, r"^[\w.-]+/[\w./-]+@[0-9a-f]{40}$", where)
                    self.assertRegex(rest, r"#\s*v\d+\.\d+\.\d+", where)
        self.assertGreaterEqual(third_party, 4)



@unittest.skipIf(yaml is None, "PyYAML not installed")
class SdkManagerStepTest(unittest.TestCase):
    """the Android SDK step fails when every sdkmanager attempt fails."""

    def step(self):
        steps = load(ACTION)["runs"]["steps"]
        return next(s for s in steps if s.get("name") == "Android SDK packages")

    def run_step(self, failures):
        step = self.step()
        self.assertNotIn("${{", step["run"], "no expression interpolation inside the script")
        with tempfile.TemporaryDirectory() as tmp:
            sdk_bin = os.path.join(tmp, "sdk", "cmdline-tools", "latest", "bin")
            os.makedirs(sdk_bin)
            tools = os.path.join(tmp, "bin")
            os.makedirs(tools)
            count = os.path.join(tmp, "count")
            write_exe(os.path.join(sdk_bin, "sdkmanager"), f"""#!/bin/bash
[ "$1" = --licenses ] && exit 0
echo x >> "{count}"
[ "$(wc -l < "{count}")" -gt {failures} ]
""")
            write_exe(os.path.join(tools, "flutter"), f'#!/bin/sh\necho "$*" >> "{tmp}/flutter.log"\n')
            write_exe(os.path.join(tools, "sleep"), "#!/bin/sh\nexit 0\n")
            env = dict(os.environ, ANDROID_HOME=os.path.join(tmp, "sdk"), CMAKE_VERSION="3.22.1",
                       PATH=tools + os.pathsep + os.environ.get("PATH", ""))
            p = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", step["run"]], env=env,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=60)
            attempts = len(read_text(count).splitlines()) if os.path.exists(count) else 0
            configured = os.path.exists(os.path.join(tmp, "flutter.log"))
        return p.returncode, p.stdout, attempts, configured

    def test_fails_after_three_failed_attempts(self):
        rc, out, attempts, configured = self.run_step(failures=3)
        self.assertNotEqual(0, rc, out)
        self.assertEqual(3, attempts)
        self.assertIn("::error::", out)
        self.assertFalse(configured)

    def test_retries_until_an_attempt_succeeds(self):
        rc, out, attempts, configured = self.run_step(failures=2)
        self.assertEqual(0, rc, out)
        self.assertEqual(3, attempts)
        self.assertTrue(configured)


if __name__ == "__main__":
    unittest.main()
