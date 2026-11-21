#!/usr/bin/env python3
"""Compare a `flutter test --file-reporter json:FILE` report with a list of known failures.

usage: flutter_test_report.py REPORT.json KNOWN_FAILURES.txt WORK_ROOT [FLUTTER_EXIT_CODE]

Known-failure lines look like "test/foo_test.dart: full test name" ('#' comments allowed).
Exit 0 when every failing test is listed, 1 otherwise. Listed tests that now pass are reported so
the list can be pruned.

A run that did not finish is a failure, whatever the tests it did report: the report must end with the
reporter's {"type":"done"} event, and every test that started must have a testDone (a crashed or
OOM-killed `flutter test` leaves a report of passes only). With FLUTTER_EXIT_CODE, a non-zero exit of
`flutter test` that no reported failure explains is a failure too.
"""
import json
import os
import sys


def main(report_path, known_path, root, flutter_rc=None):
    suites, tests, results = {}, {}, {}
    started, finished, done = set(), set(), None
    if not os.path.exists(report_path):
        print(f"flutter test: no report at {report_path} (flutter test did not start or crashed early)")
        return 1
    with open(report_path, encoding="utf-8") as report:
        for line in report:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                event = json.loads(line)
            except ValueError:  # the last line of a killed run can be cut off
                continue
            kind = event.get("type")
            if kind == "suite":
                path = event["suite"].get("path") or ""
                suites[event["suite"]["id"]] = os.path.relpath(path, root) if os.path.isabs(path) else path
            elif kind == "testStart":
                test = event["test"]
                tests[test["id"]] = (test.get("suiteID"), test.get("name", ""))
                started.add(test["id"])
            elif kind == "testDone":
                finished.add(event["testID"])
                if event.get("hidden") or event.get("skipped"):
                    continue
                results[event["testID"]] = event.get("result", "error")
            elif kind == "error":
                results[event["testID"]] = "error"
            elif kind == "done":
                done = event

    known = set()
    if os.path.exists(known_path):
        with open(known_path, encoding="utf-8") as listing:
            known = {l.strip() for l in listing if l.strip() and not l.lstrip().startswith("#")}

    failed, passed = set(), set()
    for test_id, result in results.items():
        suite_id, name = tests.get(test_id, (None, f"<test {test_id}>"))
        key = f"{suites.get(suite_id, '?')}: {name}"
        (passed if result == "success" else failed).add(key)
    failed -= passed  # a test reported as error and later success (retries) counts as passed

    unexpected = sorted(failed - known)
    known_failed = sorted(failed & known)
    fixed = sorted(known & passed)
    # Started but never finished: the run is cut short while this test ran (an unfinished hidden "loading" test is
    # a whole suite that never ran). Never excused by the known list: a known failure fails, it does not vanish.
    unfinished = sorted(f"{suites.get(tests[t][0], '?')}: {tests[t][1]}" for t in started - finished)
    print(f"flutter test: {len(passed)} passed, {len(failed)} failed "
          f"({len(known_failed)} known upstream failure(s), {len(unexpected)} unexpected)")
    for key in known_failed:
        print(f"  known failure : {key}")
    for key in fixed:
        print(f"  now passing   : {key} (remove it from {os.path.basename(known_path)})")
    for key in unexpected:
        print(f"  UNEXPECTED    : {key}")
    for key in unfinished:
        print(f"  UNFINISHED    : {key}")
    incomplete = bool(unfinished)
    if not results:
        print("  no test results found in the report")
        incomplete = True
    if done is None:
        print("  the report has no final 'done' event: flutter test was killed or crashed before the end")
        incomplete = True
    if str(flutter_rc or 0) != "0" and not failed:
        print(f"  flutter test exited with status {flutter_rc} but the report shows no failing test")
        incomplete = True
    return 1 if unexpected or incomplete else 0


if __name__ == "__main__":
    if len(sys.argv) not in (4, 5):
        sys.exit(__doc__)
    sys.exit(main(*sys.argv[1:]))
