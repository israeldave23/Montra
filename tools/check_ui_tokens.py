from pathlib import Path
import re
import xml.etree.ElementTree as ET

RESOURCE_ROOT = Path(__file__).parents[1] / "app/src/main/res"
ALLOWED_SPACING = {"4", "8", "12", "16", "24", "32", "48", "64", "96"}
ALLOWED_TYPE = {"12", "14", "16", "18", "20", "24", "30", "36", "48"}
errors = []
for path in RESOURCE_ROOT.rglob("*.xml"):
    ET.parse(path)
    if path.parent.name != "layout":
        continue
    text = path.read_text()
    for value in re.findall(r'android:textSize="(\d+)sp"', text):
        if value not in ALLOWED_TYPE:
            errors.append(f"{path}: off-scale text size {value}sp")
    for value in re.findall(r'android:(?:padding\w*|layout_margin\w*)="(\d+)dp"', text):
        if value not in ALLOWED_SPACING:
            errors.append(f"{path}: off-scale spacing {value}dp")
if errors:
    raise SystemExit("\n".join(errors))
print("UI token check passed")
