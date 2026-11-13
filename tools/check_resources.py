#!/usr/bin/env python3
"""Static resource audit for this Android app.

This exists because the project has no Gradle build or CI to catch broken
references, and because a broken `@color/` reference is invisible until the app
is compiled. Every check is namespace-aware: an `@color/foo` is only resolved by
a `<color name="foo">`, never by a drawable that happens to share the name. That
exact false positive once hid a launcher icon that referenced a deleted colour.

Usage:
    python3 tools/check_resources.py            # errors only
    python3 tools/check_resources.py --verbose  # also list unused resources

Exit code is 0 when there are no errors. Unused resources are warnings.
"""

from __future__ import annotations

import collections
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
KOTLIN = ROOT / "app/src/main/kotlin"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"

XML_DIRS = ("layout", "drawable", "mipmap", "menu", "anim", "xml", "color")
VALUE_TAGS = ("string", "color", "dimen", "style", "plurals", "integer", "bool", "array", "attr")

# Launcher art is referenced by the manifest through mipmap aliases and by other
# adaptive-icon layers, so "unused" would be a lie here.
LAUNCHER = {"ic_launcher", "ic_launcher_round", "ic_launcher_background", "ic_launcher_foreground"}

errors: list[str] = []
warnings: list[str] = []


def fail(message: str) -> None:
    errors.append(message)


def rel(path: Path) -> str:
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def parse(path: Path) -> ET.Element | None:
    try:
        return ET.parse(path).getroot()
    except ET.ParseError as exc:
        fail(f"{rel(path)}: XML is not well formed ({exc})")
        return None


def collect_values() -> dict[str, set[str]]:
    """type -> names declared in any values*/ folder."""
    declared: dict[str, set[str]] = collections.defaultdict(set)
    per_folder: dict[tuple[str, str, str], int] = collections.Counter()
    for folder in sorted(RES.glob("values*")):
        if not folder.is_dir():
            continue
        for path in sorted(folder.glob("*.xml")):
            root = parse(path)
            if root is None:
                continue
            for element in root:
                if element.tag not in VALUE_TAGS:
                    continue
                name = element.get("name")
                if not name:
                    fail(f"{rel(path)}: <{element.tag}> without a name")
                    continue
                declared[element.tag].add(name)
                per_folder[(str(folder), element.tag, name)] += 1
    for (folder, tag, name), count in per_folder.items():
        if count > 1:
            fail(f"{folder}: duplicate <{tag} name=\"{name}\"> declared {count} times")
    return declared


def collect_files() -> dict[str, set[str]]:
    """type -> names of file-based resources (layout/drawable/mipmap/menu/anim/xml)."""
    files: dict[str, set[str]] = collections.defaultdict(set)
    for directory in XML_DIRS:
        for path in sorted((RES / directory).glob("*")):
            if path.is_file():
                files[directory].add(path.stem)
    return files


def reference_targets(declared: dict[str, set[str]], files: dict[str, set[str]]) -> dict[str, set[str]]:
    targets = {
        "color": set(declared.get("color", ())) | set(files.get("color", ())),
        "drawable": set(files.get("drawable", ())),
        "mipmap": set(files.get("mipmap", ())),
        "layout": set(files.get("layout", ())),
        "menu": set(files.get("menu", ())),
        "anim": set(files.get("anim", ())),
        "xml": set(files.get("xml", ())),
    }
    for tag in ("string", "dimen", "style", "plurals", "integer", "bool", "array", "attr"):
        targets[tag] = set(declared.get(tag, ()))
    return targets


AT_REF = re.compile(r"@(\+)?(?!android:)([\w]+)/([\w.]+)")

# Styles owned by Material Components / AppCompat, not by this project.
LIBRARY_STYLE_PREFIXES = (
    "Widget.Material",
    "Widget.AppCompat",
    "Widget.Support",
    "Theme.Material",
    "Theme.AppCompat",
    "ThemeOverlay.Material",
    "ThemeOverlay.AppCompat",
    "TextAppearance.Material",
    "TextAppearance.AppCompat",
    "Base.Widget",
    "Base.Theme",
    "Platform.",
    "CardView.",
)


def is_library_style(name: str) -> bool:
    return name.startswith(LIBRARY_STYLE_PREFIXES)


def check_references(targets: dict[str, set[str]]) -> set[str]:
    """Validates every @type/name reference; returns the ids that are declared."""
    ids: set[str] = set()
    sources = [p for d in XML_DIRS for p in RES.glob(f"{d}/*.xml")]
    sources += [p for p in RES.glob("values*/*.xml")]
    sources.append(MANIFEST)
    for path in sorted(sources):
        if path.suffix != ".xml":
            continue
        root = parse(path)
        if root is None:
            continue
        text = path.read_text()
        for plus, kind, name in AT_REF.findall(text):
            if plus:
                ids.add(name)
                continue
            if kind in ("id", "attr"):
                continue
            if kind == "style" and is_library_style(name):
                continue
            known = targets.get(kind)
            if known is None:
                continue  # @null/@empty and other literals
            if name not in known:
                fail(f"{rel(path)}: @{kind}/{name} is not defined")
    return ids


KOTLIN_R = re.compile(r"(?<!android\.)\bR\.(\w+)\.(\w+)")


def check_kotlin(targets: dict[str, set[str]], ids: set[str], declared: dict[str, set[str]]) -> None:
    style_names = {n for n in targets["style"] if "." not in n} | targets["style"]
    for path in sorted(KOTLIN.rglob("*.kt")):
        text = path.read_text()
        for kind, name in KOTLIN_R.findall(text):
            if kind == "id":
                if name not in ids:
                    fail(f"{rel(path)}: R.id.{name} is not declared in any layout")
            elif kind in targets:
                if name not in targets[kind]:
                    fail(f"{rel(path)}: R.{kind}.{name} is not defined")
            elif kind != "styleable":
                warnings.append(f"{rel(path)}: R.{kind}.{name} uses an unchecked resource type")

        for match in re.finditer(r"getString\(\s*R\.string\.(\w+)\s*,?", text):
            key = match.group(1)
            if key not in targets["string"]:
                continue
            expected = len(re.findall(r"%(?:\d+\$)?[sdf]", declared_text("string", key)))
            actual = len(call_arguments(text, match.end()))
            if expected != actual:
                fail(f"{rel(path)}: R.string.{key} takes {expected} argument(s), call passes {actual}")

        stripped = re.sub(r'"""(?:.|\n)*?"""', '""', text)
        stripped = re.sub(r"//.*", "", stripped)
        if stripped.count("{") != stripped.count("}"):
            fail(f"{rel(path)}: unbalanced braces ({stripped.count('{')} open, {stripped.count('}')} close)")
        if stripped.count("(") != stripped.count(")"):
            fail(f"{rel(path)}: unbalanced parentheses")
        if re.search(r"[\u4e00-\u9fff\u3040-\u30ff]", text):
            fail(f"{rel(path)}: contains CJK characters (writing tool glitch?)")


_string_cache: dict[str, str] = {}


def declared_text(tag: str, name: str) -> str:
    key = f"{tag}/{name}"
    if key not in _string_cache:
        for folder in sorted(RES.glob("values*")):
            for path in sorted(folder.glob("*.xml")):
                root = parse(path)
                if root is None:
                    continue
                for element in root:
                    if element.tag == tag and element.get("name") == name:
                        _string_cache[key] = element.text or ""
                        return _string_cache[key]
        _string_cache[key] = ""
    return _string_cache[key]


def call_arguments(text: str, start: int) -> list[str]:
    depth, index, parts, current = 1, start, [], ""
    while index < len(text):
        char = text[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                parts.append(current)
                break
        elif char == "," and depth == 1:
            parts.append(current)
            current = ""
            index += 1
            continue
        current += char
        index += 1
    return [part for part in parts if part.strip()]


def check_colour_parity(declared: dict[str, set[str]]) -> None:
    light = set()
    night = set()
    for folder, tag, name in ((f, t, n) for (f, t, n) in _iter_declarations()):
        if tag != "color":
            continue
        (night if "night" in folder else light).add(name)
    if not night:
        warnings.append("no values-night colours found")
        return
    missing = sorted(light - night)
    extra = sorted(night - light)
    for name in missing:
        fail(f"colour m3 role '{name}' is missing from values-night/colors.xml")


def _iter_declarations():
    for folder in sorted(RES.glob("values*")):
        for path in sorted(folder.glob("*.xml")):
            root = parse(path)
            if root is None:
                continue
            for element in root:
                if element.tag in VALUE_TAGS and element.get("name"):
                    yield str(path.parent.name), element.tag, element.get("name")


def report_unused(targets: dict[str, set[str]], declared: dict[str, set[str]]) -> None:
    corpus = ""
    for path in list(KOTLIN.rglob("*.kt")) + [p for d in XML_DIRS for p in RES.glob(f"{d}/*.xml")]:
        corpus += path.read_text()
    corpus += MANIFEST.read_text()

    unused_strings = sorted(
        name for name in targets["string"]
        if f"@string/{name}" not in corpus and f"R.string.{name}" not in corpus
    )
    unused_drawables = sorted(
        name for name in targets["drawable"]
        if name not in LAUNCHER
        and f"@drawable/{name}" not in corpus and f"R.drawable.{name}" not in corpus
    )
    if unused_strings:
        warnings.append(f"{len(unused_strings)} unused string(s)")
        if VERBOSE:
            warnings.extend(f"  unused string: {name}" for name in unused_strings)
    if unused_drawables:
        warnings.append(f"{len(unused_drawables)} unused drawable(s): {', '.join(unused_drawables)}")


VERBOSE = "--verbose" in sys.argv


def main() -> int:
    declared = collect_values()
    files = collect_files()
    targets = reference_targets(declared, files)
    ids = check_references(targets)
    check_kotlin(targets, ids, declared)
    check_colour_parity(declared)
    report_unused(targets, declared)

    for warning in warnings:
        print(f"warning: {warning}")
    if errors:
        for error in errors:
            print(f"error: {error}")
        print(f"\n{len(errors)} error(s).")
        return 1
    print("Resource check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
