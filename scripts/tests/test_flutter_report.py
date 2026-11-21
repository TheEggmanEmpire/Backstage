#!/usr/bin/env python3
"""Unit tests of scripts/lib/flutter_test_report.py (the flutter test gate) on synthetic reports.

    python3 -m unittest discover -s scripts/tests -p 'test_*.py'
"""
import sys
sys.dont_write_bytecode = True  # keep scripts/ free of __pycache__

import contextlib
import importlib.util
import io
import json
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPTS = os.path.dirname(HERE)
spec = importlib.util.spec_from_file_location("flutter_test_report", os.path.join(SCRIPTS, "lib", "flutter_test_report.py"))
ftr = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ftr)

ROOT = "/work"
KNOWN = "test/known_test.dart: known broken"


def suite(sid, path):
    return {"type": "suite", "suite": {"id": sid, "path": f"{ROOT}/{path}"}}


def start(tid, sid, name):
    return {"type": "testStart", "test": {"id": tid, "suiteID": sid, "name": name}}


def done_test(tid, result="success", hidden=False, skipped=False):
    return {"type": "testDone", "testID": tid, "result": result, "hidden": hidden, "skipped": skipped}


def full_run(*, a_result="success"):
    """Two suites: a loading test (hidden) plus one real test each; suite 2 holds the known failure."""
    return [
        suite(0, "test/a_test.dart"), start(1, 0, "loading /work/test/a_test.dart"), done_test(1, hidden=True),
        start(2, 0, "a works"), done_test(2, a_result),
        suite(3, "test/known_test.dart"), start(4, 3, "loading /work/test/known_test.dart"), done_test(4, hidden=True),
        start(5, 3, "known broken"), {"type": "error", "testID": 5, "error": "boom"}, done_test(5, "error"),
        start(6, 3, "skipped one"), done_test(6, skipped=True),
        {"type": "done", "success": False},
    ]


class FlutterReportTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp()
        self.known = os.path.join(self.dir, "known.txt")
        with open(self.known, "w") as f:
            f.write(f"# comment\n{KNOWN}\n")

    def run_report(self, events, rc=None, raw_tail=""):
        path = os.path.join(self.dir, "report.json")
        with open(path, "w") as f:
            f.write("\n".join(json.dumps(e) for e in events) + "\n" + raw_tail)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = ftr.main(path, self.known, ROOT, rc)
        return status, out.getvalue()

    def test_complete_run_with_only_known_failures_passes(self):
        status, out = self.run_report(full_run(), rc=1)
        self.assertEqual(0, status, out)
        self.assertIn("1 passed, 1 failed", out)

    def test_unexpected_failure_fails(self):
        status, out = self.run_report(full_run(a_result="failure"), rc=1)
        self.assertEqual(1, status, out)
        self.assertIn("UNEXPECTED    : test/a_test.dart: a works", out)

    def test_killed_run_without_done_event_fails(self):
        # One test passed, the next started and never finished, no final event: an OOM kill mid-run.
        events = [suite(0, "test/a_test.dart"), start(1, 0, "a works"), done_test(1),
                  start(2, 0, "a second")]
        status, out = self.run_report(events, rc=137, raw_tail='{"type":"testDo')
        self.assertEqual(1, status, out)
        self.assertIn("UNFINISHED    : test/a_test.dart: a second", out)
        self.assertIn("no final 'done' event", out)

    def test_missing_done_event_alone_fails(self):
        status, out = self.run_report(full_run()[:-1], rc=0)
        self.assertEqual(1, status, out)
        self.assertIn("no final 'done' event", out)

    def test_suite_that_never_finished_loading_fails(self):
        events = full_run()
        events.insert(-1, suite(7, "test/c_test.dart"))
        events.insert(-1, start(8, 7, "loading /work/test/c_test.dart"))
        status, out = self.run_report(events, rc=1)
        self.assertEqual(1, status, out)
        self.assertIn("UNFINISHED    : test/c_test.dart: loading", out)

    def test_nonzero_exit_without_any_reported_failure_fails(self):
        events = full_run()
        events[10] = done_test(5)  # the known test passes this time...
        del events[9]              #... and raises no error event
        status, out = self.run_report(events, rc=255)
        self.assertEqual(1, status, out)
        self.assertIn("exited with status 255", out)
        self.assertIn("now passing", out)

    def test_missing_report_fails(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = ftr.main(os.path.join(self.dir, "absent.json"), self.known, ROOT, "1")
        self.assertEqual(1, status)
        self.assertIn("no report", out.getvalue())


if __name__ == "__main__":
    unittest.main()
