#!/usr/bin/env python3
"""Kotlin syntax gate.

Braces can balance while a file is still unparseable — a duplicated `}` at the end of a class, for
instance, is exactly what a bracket counter calls "fine". This parses every Kotlin source file with a
real grammar and fails on any ERROR or missing node, which is the closest thing to a compiler this
environment has without a JDK.

It is a syntax check only: unresolved references need the Android SDK, Play Services and AppCompat on
a classpath, so type correctness is still the first real build's job.

Requires `tree-sitter` and `tree-sitter-kotlin` (pip). Exits 0 when clean, 1 on any problem.
"""

from pathlib import Path
import sys

try:
    from tree_sitter import Language, Parser
    import tree_sitter_kotlin
except ImportError:  # pragma: no cover - tooling guard
    print("skipped: pip install --break-system-packages tree-sitter tree-sitter-kotlin")
    sys.exit(0)

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin"


def problems(tree, source: bytes) -> list[tuple[int, int, str]]:
    found: list[tuple[int, int, str]] = []
    stack = [tree.root_node]
    while stack:
        node = stack.pop()
        if node.type == "ERROR" or node.is_missing:
            snippet = source[node.start_byte:node.end_byte][:100].decode("utf-8", "replace")
            found.append((node.start_point[0] + 1, node.start_point[1] + 1, snippet.replace("\n", " ⏎ ")))
        stack.extend(node.children)
    return sorted(found)


def main() -> int:
    parser = Parser(Language(tree_sitter_kotlin.language()))
    failures = 0
    files = sorted(SOURCE.rglob("*.kt"))
    for path in files:
        source = path.read_bytes()
        tree = parser.parse(source)
        if not tree.root_node.has_error:
            continue
        failures += 1
        for line, column, snippet in problems(tree, source):
            print(f"{path.relative_to(ROOT)}:{line}:{column}: {snippet}")
    if failures:
        print(f"\n{failures} file(s) do not parse.")
        return 1
    print(f"Kotlin syntax check passed ({len(files)} files).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
