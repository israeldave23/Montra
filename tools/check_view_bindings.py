#!/usr/bin/env python3
"""Fails when a screen goes back to looking views up by id, or builds a list by hand.

View binding is only a guarantee while it is the only way in. This gate keeps the four legitimate
exceptions explicit — each one operates on a root the caller passes in, so no generated binding can
exist for it — and fails on everything else.

Rules:
  1. `findViewById` may only appear in the allowlist below.
  2. An `*Adapter.kt` file must not inflate a layout by resource id; it inflates the binding.
  3. `removeAllViews()` is how a list gets rebuilt by hand; a ListAdapter submits instead.
  4. A `private lateinit var` must be assigned somewhere. `lateinit var binding` is the reason this
     rule lives here: a field the screen forgot to initialise compiles clean and throws
     `UninitializedPropertyAccessException` on the first frame that reads it.
"""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/kotlin"

# file name -> why the lookup is legitimate
ALLOWED = {
    "AppActivity.kt": "the content view (android.R.id.content) and the nav bar under it",
    "Navigation.kt": "the nav items under whatever root the screen passes",
    "FinanceDialogs.kt": "the dialog anchor is android.R.id.content",
    "Ads.kt": "the ad slot inside whatever root the screen passes",
}


def main() -> int:
    problems: list[str] = []

    for path in sorted(SOURCE.rglob("*.kt")):
        text = path.read_text()

        if "findViewById" in text and path.name not in ALLOWED:
            for number, line in enumerate(text.splitlines(), start=1):
                if "findViewById" in line:
                    problems.append(
                        f"{path.relative_to(ROOT)}:{number}: findViewById outside the allowlist "
                        f"(inflate the binding instead)"
                    )

        if path.name.endswith("Adapter.kt") and ".inflate(R.layout." in text:
            problems.append(
                f"{path.relative_to(ROOT)}: adapter inflates a layout by id; inflate its binding"
            )

        for match in re.finditer(r"private lateinit var (\w+):", text):
            name = match.group(1)
            if not re.search(rf"^\s*{re.escape(name)} = ", text, re.M):
                problems.append(
                    f"{path.relative_to(ROOT)}: lateinit {name} is never assigned "
                    f"(compiles, then throws at runtime)"
                )

        if "removeAllViews()" in text and path.name != "Ads.kt":
            problems.append(
                f"{path.relative_to(ROOT)}: removeAllViews() rebuilds a list by hand; submit it"
            )

    if problems:
        for problem in problems:
            print(f"error: {problem}")
        print(f"\nView binding check failed ({len(problems)} problem(s)).")
        print("Allowed lookups:")
        for name, why in ALLOWED.items():
            print(f"  {name}: {why}")
        return 1

    print("View binding check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
