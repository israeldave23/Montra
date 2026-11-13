# Tools

The gates exit non-zero on failure, so they can be dropped into CI unchanged. The two resource gates
are stdlib-only; the Kotlin gate needs `pip install --break-system-packages tree-sitter
tree-sitter-kotlin` and skips itself cleanly when the grammar is absent.

| Script | What it enforces |
| --- | --- |
| `check_ui_tokens.py` | The design system itself: no raw text sizes/weights in layouts, no raw dp/sp except `0dp`, no hex colours outside `colors.xml`, only `@color/m3_*` roles, raw dimens only in `dimens.xml`, type-scale sizes from `@dimen/text_*` |
| `check_resources.py` | Every `@type/name` and `R.type.name` resolves **in its own namespace**; `getString` argument counts match the format string; no duplicate resource names; no unbalanced Kotlin braces; no CJK characters; light/night colour parity; unused resources reported as warnings |
| `check_kotlin_syntax.py` | Every `.kt` file parses with a real Kotlin grammar — catches a duplicated `}` that balances but does not compile. Syntax only: unresolved references still need the SDK on a classpath |

```bash
python3 tools/check_ui_tokens.py
python3 tools/check_resources.py            # errors only
python3 tools/check_resources.py --verbose  # plus unused strings/drawables
python3 tools/check_kotlin_syntax.py        # Kotlin grammar, not a compiler
```

`check_resources.py` is namespace-strict on purpose. Resolving `@color/foo` against any resource
named `foo` once hid a launcher drawable that referenced a colour the theming pass had deleted — it
looked fine to a loose scan and would have failed the build.
