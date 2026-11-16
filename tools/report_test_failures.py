#!/usr/bin/env python3
"""Turns JUnit XML into GitHub annotations.

GitHub's run logs are behind a blob store that some environments cannot reach, and a failing test
task that says only "There were failing tests" is not actionable. Annotations ride on the check run
itself, so they can be read through the API (and appear inline on the PR diff).

Run after a failed `testDebugUnitTest`; prints one `::error` workflow command per failing test case
and a summary. Exits 0 when a report was parsed (the workflow decides whether the job fails), 1 when
there is no report to read.
"""

from pathlib import Path
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "app/build/test-results/testDebugUnitTest"


def escape(text: str) -> str:
    return (
        text.replace("%", "%25")
        .replace("\r", "")
        .replace("\n", "%0A")
    )


def main() -> int:
    files = sorted(REPORTS.glob("TEST-*.xml"))
    if not files:
        print(f"::error::no test report under {REPORTS.relative_to(ROOT)} - did the test task run?")
        return 1

    total = 0
    failed = 0
    for path in files:
        suite = ET.parse(path).getroot()
        for case in suite.iter("testcase"):
            total += 1
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is None:
                continue
            failed += 1
            name = case.get("name", "?")
            klass = case.get("classname", path.stem)
            message = (problem.get("message") or problem.text or "failed").strip()
            first = message.splitlines()[0][:300]
            print(
                f"::error title={klass}.{name}::"
                f"{escape(first)}"
            )

    summary = f"{failed} failing of {total} test(s) across {len(files)} class(es)"
    print(f"::notice::{summary}")
    print(summary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
