# Montra theming: Material 3 palette and icons

The UI runs on a Material 3 colour system instead of ad-hoc constants. Every colour is a role in
`values/colors.xml`, mirrored for `values-night/colors.xml`, and every screen reads a role directly —
there are no aliases left, so a colour name means exactly one thing. `tools/check_ui_tokens.py` stays
green: no hex in layouts and no raw dp/sp in screens.

## The palette

Trusted blue is the brand, growth green means money moving the right way, neutral grays carry
structure, and **amber is the only accent** — it never collides with the green up / red down semantics
because it is only used for emphasis (attention, "due soon", the transfer tile).

| Role | Light | Dark | Used for |
| --- | --- | --- | --- |
| `m3_primary` | `#14508C` | `#A8C8FF` | brand, primary actions, links, selected nav label |
| `m3_on_primary` | `#FFFFFF` | `#002F63` | text on primary |
| `m3_primary_container` | `#D6E4FF` | `#17427A` | nav active indicator, tonal fills |
| `m3_on_primary_container` | `#001B3D` | `#D6E4FF` | text/icon on primary container |
| `m3_secondary` | `#1D6B4C` | `#7FD8AF` | growth green |
| `m3_secondary_container` | `#BCEBD5` | `#0F5138` | category/action icon badges |
| `m3_on_secondary_container` | `#002114` | `#BCEBD5` | icons inside those badges |
| `m3_tertiary` | `#7A5200` | `#F0C36A` | **the single accent** |
| `m3_tertiary_container` | `#FFDFA0` | `#5C3D00` | accent badge (transfer) |
| `m3_on_tertiary_container` | `#261A00` | `#FFDFA0` | icon on the accent badge |
| `m3_positive` | `#1D6B4C` | `#7FD8AF` | income / saved amounts, on-track pills |
| `m3_negative` | `#B3261E` | `#FFB4AB` | expenses, over-budget |
| `m3_highlight` | `#F5C86B` | `#F0C36A` | near-limit / due-soon pills |
| `m3_on_highlight` | `#2A1E00` | `#2A1E00` | text on highlight |
| `m3_error` | `#B3261E` | `#FFB4AB` | destructive actions, over-budget pill |
| `m3_background` | `#F4F6FB` | `#0E1218` | window and page background |
| `m3_surface` | `#FFFFFF` | `#151B23` | cards, bottom bar, sheets |
| `m3_surface_variant` | `#E3E8F1` | `#232B36` | search field, quiet fills |
| `m3_on_surface` | `#111726` | `#E4E8F0` | primary text |
| `m3_on_surface_variant` | `#4B5568` | `#AAB4C4` | secondary text, unselected nav |
| `m3_outline` | `#8A93A6` | `#6E788A` | strong separators |
| `m3_outline_variant` | `#D7DEEA` | `#2C3543` | card hairlines |
| `m3_on_hero` | `#FFFFFF` | `#FFFFFF` | text and icons on the hero gradient |
| `m3_hero_start` / `m3_hero_end` | `#0A3A6E` → `#14508C` | `#0A2440` → `#17527F` | balance hero gradient |
| `m3_ripple` | `#2614508C` | `#33A8C8FF` | state layer on tiles and nav |
| `m3_scrim` | `#000000` | `#000000` | dim behind dialogs |

The hero keeps white text in both schemes (`on_balance = #FFFFFF`), and the dark hero is deliberately
lighter than the dark background so the card still reads as a raised surface.

## Role mapping

`Theme.App` (in both `values/themes.xml` and `values-night/themes.xml`, parent
`Theme.Material3.DayNight.NoActionBar`) binds the full role set: `colorPrimary`, `colorOnPrimary`,
`colorPrimaryContainer`, `colorOnPrimaryContainer`, the secondary and tertiary families,
`colorError`/`colorOnError`/`colorErrorContainer`, `android:colorBackground`/`colorOnBackground`,
`colorSurface`/`colorOnSurface`, `colorSurfaceVariant`/`colorOnSurfaceVariant`, `colorOutline`,
`colorOutlineVariant`, `colorInverseSurface`/`colorInverseOnSurface`, `colorScrim`, plus
`android:colorPrimary`/`android:colorAccent` for platform widgets and
`android:windowLightStatusBar`/`windowLightNavigationBar` per scheme.

That means Material widgets (dialogs, snackbars, buttons, ripples, text cursors) pick up the palette
without per-screen overrides, and `?attr/colorOnSurfaceVariant` style references stay correct in dark
mode.

## Component shapes

| Element | Drawable | Why |
| --- | --- | --- |
| Balance hero | `balance_card_background` | `hero_start → hero_end` linear gradient, `hero_radius` |
| Cards and tiles | `action_tile_background` | `surface` + 1dp `outline_variant`, `card_radius` |
| Bottom bar | `balance_surface_background` | same tonal surface, hairline separation |
| Quick-action tiles | `action_tile_ripple` | `m3_ripple` state layer over the tile |
| Primary button | `primary_button_ripple` | `primary` pill (`control_radius = 28dp`) |
| Button on the hero | `hero_button_ripple` | translucent white fill + outline, reads as "on primary" |
| Search field | `search_field_background` | `surface_variant` pill |
| Nav item | `nav_item_background` | `primary_container` indicator + ripple when idle |
| Icon badge | `icon_badge_background` / `icon_badge_accent_background` | `secondary_container` / `tertiary_container`, 12dp radius |
| Status pill | `goal_pill_background` | white base tinted at runtime by state colour |

## Icons

All icons are 24dp vectors with a 24 viewport, drawn with two tones (a second path at reduced alpha
gives depth without a second colour resource) and tinted at the call site. Nav icons are tinted through
`compoundDrawableTintList`, list icons through `android:tint` on the `ImageView`.

| Group | Files | Wired in |
| --- | --- | --- |
| Navigation | `ic_nav_home`, `ic_nav_activity`, `ic_nav_budget`, `ic_nav_goals`, `ic_nav_insights` | `view_bottom_navigation.xml` + `Navigation.kt` |
| Actions | `ic_action_add`, `ic_cat_transfer` | home quick-action tiles |
| Categories | `ic_cat_{dining,groceries,transport,utilities,rent,health,fun,salary,freelance,coin,transfer,other}` | `CategoryIcons.kt` → transaction rows, budget rows, insight rows |

`categoryIconRes(id)` maps stored category ids to drawables and falls back to `ic_cat_other`, so
unknown or future categories still render. The old emoji/"↔" text glyphs are gone.

## Contrast

Measured with the WCAG formula; every pairing used for text or an icon is ≥ 4.5:1.

| Pair | Light | Dark |
| --- | --- | --- |
| `on_surface` on `surface` | 17.9 | 14.1 |
| `on_surface_variant` on `surface` | 7.5 | 8.3 |
| `on_primary` on `primary` | 8.2 | 7.8 |
| `on_primary_container` on container | 13.4 | 7.8 |
| `on_secondary_container` on container | 13.0 | 7.1 |
| `on_tertiary_container` on container | 13.3 | 7.7 |
| `on_highlight` on `highlight` | 10.4 | 9.9 |
| `positive` on `surface` | 6.4 | 10.2 |
| `negative` on `surface` | 6.5 | 10.2 |
| white on hero gradient | 8.2–11.4 | 8.2–15.7 |

`outline_variant` is decorative only (1.35:1 by design) — never used for text.

## Rules for new UI

1. Add a colour role, not a hex. If a screen needs something new, add `m3_<role>` to **both**
   `values/colors.xml` and `values-night/colors.xml` (the night file must override every `m3_*` key).
2. Reference roles (`@color/m3_*`, `?attr/colorOnSurfaceVariant`) from layouts and code — never a
   second name for the same colour.
3. Use `@dimen/*` for every size and `@dimen/text_*` for every font size so the token linter passes.
4. Tint an icon instead of adding a coloured copy, and keep the amber accent for emphasis alone.
