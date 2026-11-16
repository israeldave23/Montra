#!/usr/bin/env python3
"""Turns an Android Lint XML report into GitHub annotations.

Same reason as annotate_build_errors.py and report_test_failures.py: a failing run's log lives in a
blob store that this environment cannot always reach, but annotations ride on the check run and are
always readable. Lint also prints its findings in a long, column-wrapped text report that is
unpleasant to read in a log tail; the XML is structured, so this parses that instead.

Usage: annotate_lint.py app/build/reports/lint-results-debug.xml [--max N]

Behaviour:
  * Fatal and Error issues become `::error` annotations - those are what fail the build.
  * Warnings become `::warning` annotations - they do not fail it, but they are real findings.
  * A grouped table is printed to stdout (issue id, severity, count, first location) so the log tail
    shows the shape of the problem even when the annotations are truncated.
  * The exit code is always 0: whether lint is allowed to fail the job is the workflow's decision,
    not this script's. A missing report is reported as a warning, not a crash.
"""

from __future__ import annotations

from collections import OrderedDict
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

DEFAULT_MAX_ANNOTATIONS = 40
SEVERITY_ORDER = {"Fatal": 0, "Error": 1, "Warning": 2, "Information": 3}


def escape_data(text: str) -> str:
    """Escape the message part of a workflow command."""
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def escape_property(text: str) -> str:
    """Escape a key=value property: commas and colons would end the property list."""
    return escape_data(text).replace(":", "%3A").replace(",", "%2C")


def repo_relative(path: str) -> str:
    """Path relative to the repository root so annotations land on a file that exists in the diff."""
    marker = "/app/"
    if not path:
        return ""
    if marker in path:
        return "app/" + path.split(marker, 1)[1]
    for prefix in ("app/", "./"):
        if path.startswith(prefix):
            return path[len(prefix):] if prefix == "./" else path
    return path


def read_issues(report: Path) -> list[dict[str, str]]:
    root = ET.parse(report).getroot()
    issues: list[dict[str, str]] = []

    for node in root.iter("issue"):
        location = node.find("location")
        issues.append(
            {
                "id": node.get("id", "Unknown"),
                "severity": node.get("severity", "Warning"),
                "message": (node.get("message") or "").strip(),
                "file": repo_relative(location.get("file", "")) if location is not None else "",
                "line": (location.get("line") or "") if location is not None else "",
                "column": (location.get("column") or "") if location is not None else "",
            }
        )

    # Errors first: if the annotation cap truncates anything, it must be the warnings.
    issues.sort(key=lambda i: SEVERITY_ORDER.get(i["severity"], 9))
    return issues


def summarise(issues: list[dict[str, str]]) -> None:
    counts: "OrderedDict[str, int]" = OrderedDict()
    for issue in issues:
        key = f"{issue['severity']} {issue['id']}"
        counts[key] = counts.get(key, 0) + 1

    by_severity: "OrderedDict[str, int]" = OrderedDict()
    for issue in issues:
        by_severity[issue["severity"]] = by_severity.get(issue["severity"], 0) + 1

    print("lint summary: " + (", ".join(f"{k} {v}" for k, v in by_severity.items()) or "no issues"))
    for key, count in sorted(counts.items(), key=lambda kv: (SEVERITY_ORDER.get(kv[0].split()[0], 9), -kv[1])):
        print(f"  {count:>4}  {key}")


def annotation(issue: dict[str, str]) -> str:
    level = "error" if issue["severity"] in ("Fatal", "Error") else "warning"
    body = escape_data(f"{issue['id']}: {issue['message']}")
    properties = []
    if issue["file"]:
        properties.append("file=" + escape_property(issue["file"]))
        if issue["line"]:
            properties.append("line=" + issue["line"])
        if issue["column"]:
            properties.append("col=" + issue["column"])
    if properties:
        return f"::{level} {','.join(properties)}::{body}"
    return f"::{level}::{body}"


def main(argv: list[str]) -> int:
    max_annotations = DEFAULT_MAX_ANNOTATIONS
    args: list[str] = []
    index = 0
    while index < len(argv):
        arg = argv[index]
        if arg == "--max" and index + 1 < len(argv):
            max_annotations = int(argv[index + 1])
            index += 2
            continue
        args.append(arg)
        index += 1

    if not args:
        print("usage: annotate_lint.py <lint-results.xml> [--max N]")
        return 0

    report = Path(args[0])
    if not report.is_file():
        print(f"::warning::lint report not found at {report} - lint may have failed before analysis")
        return 0

    try:
        issues = read_issues(report)
    except ET.ParseError as error:
        print(f"::warning::could not parse {report}: {error}")
        return 0

    if not issues:
        print("::notice::lint reported no issues")
        return 0

    summarise(issues)
    for issue in issues[:max_annotations]:
        print(annotation(issue))
    if len(issues) > max_annotations:
        print(f"::notice::{len(issues) - max_annotations} further lint finding(s) not annotated")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
