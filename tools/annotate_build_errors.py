#!/usr/bin/env python3
"""Turns Gradle output into GitHub annotations.

Same reason as report_test_failures.py: a red build's log lives in a blob store that some
environments cannot reach, so `Execution failed for task ':app:compileDebugKotlin'` arrives with no
detail. Annotations ride on the check run, which is always readable.

Usage: annotate_build_errors.py gradle-build.log [gradle-test.log ...]

Prints `::error` workflow commands (with file and line where the compiler gave them) plus a summary.
Duplicates are collapsed: one unresolved reference repeated in nine files is one cause, not nine.
"""

from collections import OrderedDict
from pathlib import Path
import re
import sys

MAX_ANNOTATIONS = 10

REPO_PREFIX = re.compile(r"^.*?/(?=app/src/|app/build\.gradle|build\.gradle|settings\.gradle|tools/)")

# Leading severity/prefix noise: "e: file://", "ERROR: /path", "error: ", "w: "
PREFIX = re.compile(r"^(?:e|error|ERROR|w|warning|WARNING):\s+(?P<rest>.+)$")
FILE_URL = re.compile(r"^file://(?P<path>.+)$")

# file:line:col message                     (Kotlin: e: file:///x/Y.kt:42:19 Unresolved reference: y)
WITH_COLUMN = re.compile(r"^(?P<file>.+?\.(?:kt|kts|java)):(?P<line>\d+):(?P<col>\d+)\s+(?P<message>.+)$")
# file:line: [AAPT: ]error: message         (AAPT and javac)
WITH_LINE = re.compile(
    r"^(?P<file>.+?\.(?:kt|kts|java|xml)):(?P<line>\d+):\s*(?:AAPT:\s*)?(?:error:)?\s*(?P<message>.+)$"
)
AAPT_MARKER = re.compile(r"^AAPT:\s*error:\s*(?P<message>.+)$")
BARE_ERROR = re.compile(r"^error:\s*(?P<message>.+)$")
# R8: "Missing class com.foo.Bar (referenced from: java.lang.Object[])" - a keep rule that stopped
# being enough, which only shows up in a minified build.
MISSING_CLASS = re.compile(r"^Missing class (?P<message>.+)$")
FAILED_TASK = re.compile(r"^Execution failed for task '(?P<task>.+?)'\.")
WHAT_WENT_WRONG = re.compile(r"^\* What went wrong:")


def normalise(path: str) -> str:
    """Path relative to the repository, so annotations land on a real file in the diff."""
    path = path.strip()
    if path.startswith("file://"):
        path = path[len("file://"):]
    end = REPO_PREFIX.match(path)
    return path[end.end():] if end else path


def parse(line: str) -> tuple[str, str, str] | None:
    """(file, line, message) for one output line, or None when it carries no error."""
    text = line.rstrip()
    prefix = PREFIX.match(text)
    if prefix:
        text = prefix.group("rest")
    if text.startswith("file://"):
        text = text[len("file://"):]

    for pattern in (WITH_COLUMN, WITH_LINE):
        match = pattern.match(text)
        if match:
            return norm(match)

    bare = AAPT_MARKER.match(text) or BARE_ERROR.match(text)
    if bare:
        return "", "", bare.group("message").strip()

    missing = MISSING_CLASS.match(text)
    if missing:
        return "", "", "Missing class " + missing.group("message").strip()
    return None


def norm(match: "re.Match[str]") -> tuple[str, str, str]:
    return normalise(match.group("file")), match.group("line"), match.group("message").strip()


def collect(paths: list[str]) -> tuple[list[tuple[str, str, str]], list[str]]:
    found: "OrderedDict[str, tuple[str, str, str]]" = OrderedDict()
    tasks: list[str] = []
    context: list[str] = []

    for path in paths:
        p = Path(path)
        if not p.is_file():
            continue
        lines = p.read_text(errors="replace").splitlines()
        for index, line in enumerate(lines):
            if WHAT_WENT_WRONG.match(line):
                context = [l.strip() for l in lines[index + 1:index + 6] if l.strip()]
                continue

            match = FAILED_TASK.match(line)
            if match:
                tasks.append(match.group("task"))
                continue

            parsed = parse(line)
            if parsed:
                file, line_no, message = parsed
                if message and message not in found:
                    found[message] = (file, line_no, message)

    errors = list(found.values())
    # Only mention failed tasks when the compiler said nothing more useful.
    if not errors:
        errors = [("", "", f"task {task} failed") for task in tasks]
    return errors, context


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def main(argv: list[str]) -> int:
    if not argv:
        print("usage: annotate_build_errors.py <gradle log> [more logs ...]")
        return 1

    errors, context = collect(argv)
    if not errors:
        tail = ""
        for path in argv:
            p = Path(path)
            if p.is_file():
                tail = "\n".join(p.read_text(errors="replace").splitlines()[-25:])
                break
        print("::error::the build failed but no compiler error was found in the log%0A" + escape(tail))
        return 0

    print(f"::notice::{len(errors)} distinct build error(s)")
    for file, line_no, message in errors[:MAX_ANNOTATIONS]:
        if file:
            print(f"::error file={file},line={line_no}::{escape(message)}")
        else:
            print(f"::error::{escape(message)}")
    if len(errors) > MAX_ANNOTATIONS:
        print(f"::notice::{len(errors) - MAX_ANNOTATIONS} further error(s) not shown")
    if context:
        print("::notice::" + escape(" / ".join(context)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
