# Tools

Both scripts are plain Python 3 (stdlib only) and exit non-zero on failure, so they can be dropped
into CI unchanged.

| Script | What it enforces |
| --- | --- |
| `check_ui_tokens.py` | No raw colours, dp or sp outside `dimens.xml` / `colors.xml` |
| `check_resources.py` | Every `@type/name` and `R.type.name` resolves **in its own namespace**; `getString` argument counts match the format string; no duplicate resource names; no unbalanced Kotlin braces; no CJK characters; light/night colour parity; unused resources reported as warnings |

```bash
python3 tools/check_ui_tokens.py
python3 tools/check_resources.py            # errors only
python3 tools/check_resources.py --verbose  # plus unused strings/drawables
```

`check_resources.py` is namespace-strict on purpose. Resolving `@color/foo` against any resource
named `foo` once hid a launcher drawable that referenced a colour the theming pass had deleted — it
looked fine to a loose scan and would have failed the build.
