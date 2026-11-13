# Montra design system

The palette lives in [THEMING.md](THEMING.md). This document is the rest of the system: type, space,
shape, components, icons — and the linters that make each of them enforceable rather than aspirational.

## Principles

1. **One name per decision.** A colour, size or radius is declared exactly once. If a screen needs a
   new one, it changes the system, not the screen.
2. **Semantics before pixels.** Screens ask for `TextAppearance.App.Headline`, never "24sp bold".
3. **Two-tone, one tint.** Icons are drawn once in black and coloured by `tint`, so light and dark
   come from the role, not from a second asset.
4. **Enforced, not documented.** `tools/check_ui_tokens.py` fails the build attempt on a raw size,
   weight or hex colour. A rule that only lives in this file does not exist.

## Type scale — `values/type.xml`

Layouts carry `android:textAppearance="@style/TextAppearance.App.*"` and nothing else about type.
Size comes from `@dimen/text_*`; weight and tracking come from the style.

| Style | Size | Weight | Used for |
| --- | --- | --- | --- |
| `Display` | 36 | bold | the balance figure on the hero |
| `Headline` | 24 | bold | screen titles, hero titles |
| `Title` | 20 | bold | dialog titles |
| `SectionTitle` | 18 | bold | section headers inside a screen |
| `Subtitle` | 16 | bold | row titles: account, category, goal |
| `BodyLarge` | 16 | regular | dialog body copy, long values |
| `Body` | 14 | regular | default text |
| `Label` | 14 | bold | buttons, inline actions |
| `Meta` | 12 | regular | secondary lines, timestamps, counts |
| `LabelSmall` | 12 | bold | pills, badges, nav labels |
| `Initial` | 20 | bold | avatar initials (48dp circle) |
| `InitialLarge` | 30 | bold | profile avatar (92dp circle) |

Colour is deliberately **not** part of the type scale: the same role appears on `surface` and on the
hero, so each usage still sets `android:textColor` from an `m3_*` role.

## Spacing, size, shape — `values/dimens.xml`

* Spacing: `space_4` → `space_96`, doubling-ish (4, 8, 12, 16, 24, 32, 48, 64, 96). Screen gutters are
  `screen_h_padding` (24dp); card interiors are `card_padding` (24dp).
* Targets: `touch_target` (48dp) for anything tappable, `icon_size` (24dp) for glyphs,
  `icon_badge_size` (40dp) for the tonal circle/square behind a category icon.
* Shape: `card_radius` (16dp) for cards and tiles, `hero_radius` (aliases the card radius so the hero
  can diverge later), `control_radius` (28dp) for pills — buttons, search field, hero CTA.
* Lines: `stroke_hairline` (1dp) for card outlines inside `action_tile_background`.
* Elevation: none. Depth is expressed with tonal surfaces and hairlines, which is the M3 direction and
  keeps the flat, financial feel.

## Components — `values/widgets.xml`

| Style | Purpose |
| --- | --- |
| `Widget.App.Icon` | 24dp, `fitCenter`, tinted `colorOnSurfaceVariant` — the default for any icon |
| `Widget.App.Icon.Primary` | same, tinted `colorPrimary` (month chevrons) |
| `Widget.App.Icon.OnBadge` | tinted `colorOnSecondaryContainer` for icons on tonal badges |
| `Widget.App.Icon.OnAccentBadge` | tinted `colorOnTertiaryContainer` (the single amber accent badge) |
| `BottomNavigationItem` | nav label, inherits `TextAppearance.App.LabelSmall` + touch target |
| `TextAppearance.App.*` | the type scale above |
| `layout/view_ad_slot.xml` | the one ad slot: `@dimen/ad_banner_height` tall, `screen_h_padding` gutter, `GONE` until `Ads` fills it — see `ADS.md` |

`Base.Theme.App` holds every theme role once. `Theme.App` exists twice — `values/` and `values-night/`
— and carries **only** the four window items that genuinely differ, so the two files cannot drift.

## Screen foundation — `AppActivity`

Every screen extends `AppActivity`, which owns the three things that used to be copy-pasted:

| Concern | Before | Now |
| --- | --- | --- |
| Night mode | a private `applyTheme()` per activity that mutated `resources.configuration` | `AppCompatDelegate.setDefaultNightMode` — one process-wide mode, applied before the first window, so dialogs and popups inherit it |
| Edge-to-edge | `android:fitsSystemWindows="true"` on 8 roots | real insets: status/cutout as screen padding, the bottom inset as padding on `bottom_navigation` so its surface still reaches the edge |
| Bar icon polarity | theme items only | `WindowInsetsControllerCompat` follows the resolved scheme |
| Lifecycle | `MainScope()` per screen, cancelled by hand in `onDestroy` | `lifecycleScope` — cancelled by the framework, and the same scope the dialogs borrow |
| Toasts | 4 private helpers | one `toast(message, short = false)` |

`settings` is a typed facade (`Settings` in `data/`), not a `SharedPreferences` lookup at the call site —
`balanceHidden`, `themeMode`, `displayName`, `defaultCurrency` and the remembered form fields all have
named accessors and documented defaults.

## Icons

24dp vectors, viewport 24, two tones (a second path at ~0.5 alpha for depth), tinted at the call site.

| Group | Files |
| --- | --- |
| Navigation | `ic_nav_home`, `ic_nav_activity`, `ic_nav_budget`, `ic_nav_goals`, `ic_nav_insights` |
| Chrome | `ic_back`, `ic_chevron_left`, `ic_chevron_right`, `ic_more_vert`, `ic_visibility`, `ic_visibility_off` |
| Actions | `ic_action_add` |
| Categories | `ic_cat_{dining,groceries,transport,utilities,rent,health,fun,salary,freelance,coin,transfer,other}` |

Category icons are mapped by `CategoryIcons.categoryIconRes(id)` with `ic_cat_other` as the fallback,
so an unknown category still renders. No screen contains a text glyph as a control any more.

## Adding a screen — checklist

1. Root layout: `@color/m3_background`, `@dimen/screen_h_padding`, no hardcoded sizes.
2. Every text view: one `TextAppearance.App.*` plus an `m3_*` `textColor`.
3. Every icon: `style="@style/Widget.App.Icon*"`, `android:contentDescription` unless decorative.
4. Every card: `@drawable/action_tile_background`; every pill: `@dimen/control_radius` shape.
5. Run both gates:
   ```bash
   python3 tools/check_ui_tokens.py
   python3 tools/check_resources.py
   ```
6. Anything visual that appears twice belongs in `values/widgets.xml` or `type.xml`, not in a layout.
7. If the screen shows a banner, `<include layout="@layout/view_ad_slot" />` and call
   `Ads.attachBanner(findViewById(R.id.ad_slot))` — never build an `AdView` by hand (`ADS.md`).

## What the linters reject

`check_ui_tokens.py` fails on: `android:textSize` / `android:textStyle` in layouts, any raw `dp`/`sp`
except `0dp`, hex colours outside `colors.xml`, `@color/` names that are not `m3_*`, raw dimens outside
`dimens.xml`, and a `TextAppearance.App.*` whose size does not come from `@dimen/text_*`.

`check_resources.py` fails on: unresolved `@type/name` or `R.type.name` (namespace-strict),
`getString` argument mismatches, duplicate resources, unbalanced Kotlin, CJK characters, and
light/night colour parity. Unused resources are reported as warnings.
