#!/usr/bin/env python3
"""The license tables behind the APK's notices asset and the release's source mirror.

    python3 -m unittest discover -s scripts/tests -p 'test_*.py'

licenses/native-libraries.tsv must name every native library the APK can contain, with license texts that exist;
licenses/third-party-sources.tsv drives scripts/fetch-third-party-sources.sh (pinned SHA-256, no release without it).
"""
import sys
sys.dont_write_bytecode = True  # keep scripts/ free of __pycache__

import glob
import hashlib
import os
import re
import subprocess
import tempfile
import unittest
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NATIVE = os.path.join(ROOT, "licenses", "native-libraries.tsv")
SOURCES = os.path.join(ROOT, "licenses", "third-party-sources.tsv")
FETCH = os.path.join(ROOT, "scripts", "fetch-third-party-sources.sh")
UPSTREAM = os.path.join(ROOT, "upstream", "dextop")


def rows(path):
    with open(path, encoding="utf-8") as f:
        return [line.rstrip("\n").split("\t") for line in f if line.strip() and not line.startswith("#")]


class NativeLibrariesTest(unittest.TestCase):
    def setUp(self):
        self.rows = rows(NATIVE)
        self.names = {r[0] for r in self.rows}

    def test_rows_are_well_formed_and_texts_exist(self):
        for r in self.rows:
            self.assertEqual(4, len(r), r)
            self.assertRegex(r[0], r"^[A-Za-z0-9._+-]+\.so$")
            self.assertTrue(r[1] and r[2], r)
            for text in r[3].split():
                if text != "-":
                    self.assertTrue(os.path.exists(os.path.join(ROOT, text)), f"{r[0]}: {text} does not exist")
        self.assertEqual(len(self.rows), len(self.names), "duplicate rows")

    def test_upstream_prebuilt_libraries_are_listed(self):
        prebuilt = {os.path.basename(p) for p in
                    glob.glob(os.path.join(UPSTREAM, "android/app/src/main/jniLibs/*/*.so")) +
                    glob.glob(os.path.join(UPSTREAM, "android/app/src/main/assets/**/*.so"), recursive=True)}
        self.assertTrue(prebuilt, "fixture: no prebuilt libraries found upstream")
        self.assertEqual(set(), prebuilt - self.names)

    def test_upstream_cmake_shared_libraries_are_listed(self):
        built = set()
        for cmake in glob.glob(os.path.join(UPSTREAM, "android/app/src/main/cpp/**/CMakeLists.txt"), recursive=True):
            with open(cmake, encoding="utf-8") as f:
                text = f.read()
            built |= {f"lib{m}.so" for m in re.findall(r"add_library\(\s*(\w+)\s+SHARED", text)}
        self.assertIn("libdextop_input.so", built)
        self.assertEqual(set(), built - self.names)

    def test_every_native_library_of_the_shipped_apks_is_listed(self):
        apks = glob.glob(os.path.join(ROOT, "dist", "backstage-[0-9]*.apk"))
        if not apks:
            self.skipTest("no APK in dist/")
        for apk in apks:
            with zipfile.ZipFile(apk) as z:
                sos = {os.path.basename(n) for n in z.namelist() if n.endswith(".so")}
            self.assertEqual(set(), sos - self.names, os.path.basename(apk))


class ThirdPartySourcesTest(unittest.TestCase):
    def test_rows_are_well_formed(self):
        listed = rows(SOURCES)
        self.assertTrue(listed)
        for r in listed:
            self.assertEqual(4, len(r), r)
            self.assertRegex(r[0], r"^[A-Za-z0-9][A-Za-z0-9._+-]*$")
            self.assertTrue(r[1].startswith("https://"), r)
            self.assertRegex(r[2], r"^(-|[0-9a-f]{64})$")
        text = " ".join(r[3] for r in listed)
        for binary in ("libvulkan_dextop_turnip.so", "libsndfile.so", "libltdl.so", "libpulse", "libffi.so"):
            self.assertIn(binary, text)

    def fetch(self, table_rows):
        with tempfile.TemporaryDirectory() as d:
            table = os.path.join(d, "t.tsv")
            with open(table, "w") as f:
                f.write("# comment\n" + "".join("\t".join(r) + "\n" for r in table_rows))
            out = os.path.join(d, "out")
            p = subprocess.run(["bash", FETCH, out, table], capture_output=True, text=True)
            files = sorted(os.listdir(out)) if os.path.isdir(out) else []
            sums = ""
            if "SOURCES-SHA256SUMS" in files:
                with open(os.path.join(out, "SOURCES-SHA256SUMS")) as f:
                    sums = f.read()
            return p.returncode, p.stderr, files, sums

    def source(self, d, name, data):
        path = os.path.join(d, name)
        with open(path, "wb") as f:
            f.write(data)
        return "file://" + path, hashlib.sha256(data).hexdigest()

    def test_fetch_checks_pins_and_writes_sums(self):
        with tempfile.TemporaryDirectory() as d:
            url_a, sha_a = self.source(d, "a.tar.gz", b"aaa")
            url_b, sha_b = self.source(d, "b.tar.xz", b"bbb")
            rc, err, files, sums = self.fetch([["a.tar.gz", url_a, sha_a, "liba.so"], ["b.tar.xz", url_b, "-", "libb.so"]])
            self.assertEqual(0, rc, err)
            self.assertEqual(["SOURCES-SHA256SUMS", "a.tar.gz", "b.tar.xz"], files)
            self.assertIn(f"{sha_a}  a.tar.gz", sums)
            self.assertIn(f"{sha_b}  b.tar.xz", sums)

    def test_fetch_refuses_a_changed_archive_and_a_missing_one(self):
        with tempfile.TemporaryDirectory() as d:
            url_a, _ = self.source(d, "a.tar.gz", b"aaa")
            rc, err, files, _ = self.fetch([["a.tar.gz", url_a, "0" * 64, "liba.so"]])
            self.assertEqual(1, rc)
            self.assertIn("SHA-256 mismatch", err)
            self.assertNotIn("a.tar.gz", files)
            rc, err, _, _ = self.fetch([["gone.tar.gz", "file://" + os.path.join(d, "gone"), "-", "libx.so"]])
            self.assertEqual(1, rc)
            self.assertIn("download failed", err)

    def test_fetch_refuses_unsafe_names(self):
        rc, err, _, _ = self.fetch([["../evil", "file:///dev/null", "-", "x"]])
        self.assertEqual(1, rc)
        self.assertIn("bad file name", err)


if __name__ == "__main__":
    unittest.main()
