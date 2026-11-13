#!/usr/bin/env python3
"""Design-system linter.

The design system is only real if the build refuses to bypass it. This checks the rules the palette,
type scale and spacing scale depend on:

  layouts    no raw text sizes or weights (use android:textAppearance="@style/TextAppearance.App.*")
             no raw dp/sp except 0dp weights, no hex colours, only @color/m3_* roles
  drawables  no raw dp (use @dimen), no hex except pure black/white inside vector icons
  values     hex colours only in colors.xml / values-night/colors.xml,
             sp/dp literals only in dimens.xml,
             every TextAppearance.App.* size must come from @dimen/text_*

Exit code 1 on any violation.
"""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

RESOURCE_ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
COLOUR_FILES = {"colors.xml"}
RAW_SIZE = re.compile(r'android:(?:textSize|layout_\w+|padding\w*|margin\w*|minWidth|minHeight|height|width)="(\d+(?:\.\d+)?)(dp|sp)"')
HEX_COLOUR = re.compile(r'#[0-9a-fA-F]{3,8}\b')
COLOUR_REF = re.compile(r'@color/([\w.]+)')
ALLOWED_ICON_HEX = {"#FF000000", "#000000", "#FFFFFFFF", "#FFFFFF"}

errors: list[str] = []


def fail(path: Path, message: str) -> None:
    errors.append(f"{path.relative_to(RESOURCE_ROOT.parent.parent.parent)}: {message}")


def check_layout(path: Path) -> None:
    text = path.read_text()
    if "android:textSize=" in text:
        fail(path, "raw android:textSize — use android:textAppearance=\"@style/TextAppearance.App.*\"")
    if "android:textStyle=" in text:
        fail(path, "raw android:textStyle — weight belongs to the type scale")
    for value, unit in RAW_SIZE.findall(text):
        if unit == "dp" and value == "0":
            continue
        fail(path, f"raw {value}{unit} — every size and space comes from @dimen")
    if HEX_COLOUR.search(text):
        fail(path, "hex colour in a layout — use a @color/m3_* role")
    for name in COLOUR_REF.findall(text):
        if not name.startswith("m3_"):
            fail(path, f"@color/{name} is not a design-system role (expected m3_*)")


def check_drawable(path: Path) -> None:
    text = path.read_text()
    for value, unit in RAW_SIZE.findall(text):
        if unit == "dp" and value == "0":
            continue
        fail(path, f"raw {value}{unit} — use a @dimen token")
    for colour in HEX_COLOUR.findall(text):
        if path.name.startswith("ic_") and colour in ALLOWED_ICON_HEX:
            continue
        fail(path, f"hex colour {colour} — use a @color/m3_* role")
    for name in COLOUR_REF.findall(text):
        if not name.startswith("m3_") and name != "ic_launcher_background":
            fail(path, f"@color/{name} is not a design-system role (expected m3_*)")


def check_values(path: Path) -> None:
    text = path.read_text()
    ET.parse(path)
    if path.name not in COLOUR_FILES and not path.parent.name.startswith("values-night"):
        for colour in HEX_COLOUR.findall(text):
            fail(path, f"hex colour {colour} outside colors.xml")
    if path.name not in {"dimens.xml", "type.xml"}:
        for size in re.findall(r">\s*(\d+(?:\.\d+)?)(dp|sp)\s*<", text):
            value, unit = size
            if value == "0":
                continue  # explicit zero / weight
            fail(path, f"raw dimen {value}{unit} outside dimens.xml")
    if path.name == "type.xml":
        for style in ET.parse(path).getroot():
            size = style.find("item[@name='android:textSize']")
            if size is not None and not (size.text or "").startswith("@dimen/text_"):
                fail(path, f"{style.get('name')} must size from @dimen/text_*")


def main() -> int:
    for path in sorted(RESOURCE_ROOT.rglob("*.xml")):
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append(f"{path}: not well formed ({exc})")
            continue
        folder = path.parent.name
        if folder == "layout":
            check_layout(path)
        elif folder == "drawable":
            check_drawable(path)
        elif folder.startswith("values"):
            check_values(path)

    if errors:
        print("\n".join(errors))
        print(f"\n{len(errors)} design-system violation(s).")
        return 1
    print("UI token check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
