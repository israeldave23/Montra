# Ads — placements, caps, and the policy floor

AdMob is wired in three formats: **adaptive banner**, **interstitial**, and **app-open**. Everything
that decides *whether* an ad appears lives in `ads/AdConfig.kt`; everything that decides *how* it
appears lives in `ads/Ads.kt`. No screen decides either.

## The rule the whole file follows

> An ad may never be closer to a decision than the content that decision is about.

Impressions are cheap and trust is not. Every slot below either sits where the user has finished
reading or where they have just completed something. None sits in front of a form, a hero number, or
a settings control.

## Placement map

| Screen | Format | Position in the layout | Why |
| --- | --- | --- | --- |
| Home (`activity_main`) | Adaptive banner | Last child of the scroll body, after recent activity | High dwell, zero interference with the balance hero or quick actions. The user has finished scrolling — the ad is at the natural end of the page, not in the middle of it |
| Transactions (`activity_transactions`) | Adaptive banner | Between the list and the bottom navigation | The screen's own footer. Search and filter chips stay above it, so no tap aimed at a filter can land on an ad |
| Insights (`activity_insights`) | Adaptive banner | Between the content and the bottom navigation | Long dwell, reading posture rather than tapping posture |
| Account detail (`activity_account_detail`) | Adaptive banner | Between the content and the bottom navigation | Same reasoning as the transaction feed |
| Anywhere a transaction is **saved** | Interstitial | After the success toast, before the dialog is dismissed from view | The one honest moment: the task is complete. Never before a save, never on a tap, never on launch |
| Returning to the app | App-open | On the first resume after the app was backgrounded | The format's entire intended use. Suppressed for system pickers via `Ads.suppressNextAppOpen()` — a photo choice must not be answered with a full-screen ad |

**Deliberately absent**, and why:

* **Profile / settings** — tap-dense, and accidental clicks there are invalid traffic.
* **Dialogs** — an ad next to a form is the classic policy violation.
* **Empty and error states** — nothing to pair with; reads as desperation.
* **The balance hero** — the one element that carries trust in this app.
* **Cold start** — an app-open ad covering the launcher tap is what users uninstall over. The ad
  appears when the user *comes back*, not when they arrive.

## Frequency caps (`AdConfig`)

| Constant | Value | Meaning |
| --- | --- | --- |
| `INTERSTITIAL_EVERY` | 3 | Three saved transactions between interstitials |
| `INTERSTITIAL_MIN_GAP_MS` | 3 min | Wall-clock floor regardless of saves |
| `MAX_INTERSTITIALS_PER_SESSION` | 3 | Worst case for a heavy user in one foreground stretch |
| `APP_OPEN_MIN_GAP_MS` | 4 h | At most a few app-open ads per day |

Timestamps and counters persist in `montra_ads` (`data/AdPrefs.kt`). The interstitial counter resets
on a successful show, so the ad is *earned* by use rather than reached by waiting.

## Consent — no request before an answer

`ads/Consent.kt` wraps Google's UMP SDK:

1. `requestConsentInfoUpdate` on the first activity resume.
2. `loadAndShowConsentFormIfRequired` — shows a form only where one is required.
3. Ads are initialised **only** if `canRequestAds()` is true. Otherwise the app runs with no ads at
   all: the ledger is fully usable and nobody is served a personalised ad without a legal basis.

The profile screen shows **Ad privacy options** — but only when a form exists for that user's region
(`privacyOptionsAvailable`). A settings row that does nothing is worse than no row.

## Keeping test ads out of production

`AdConfig.liveOrTest` returns Google's sample unit when the build is debuggable **or** when the live
ID has not been filled in. Test units exist so that development taps are never charged as invalid
traffic.

### Filling in your IDs

1. **AdMob app ID** → `app/src/main/res/values/strings.xml`, `admob_application_id`. It is read by
   the manifest entry `com.google.android.gms.ads.APPLICATION_ID`; without it `MobileAds.initialize`
   throws.
2. **Unit IDs** → `app/src/main/kotlin/com/dga/montra/ads/AdConfig.kt`:
   ```kotlin
   private const val LIVE_BANNER = "ca-app-pub-XXXX/YYYY"
   private const val LIVE_INTERSTITIAL = "ca-app-pub-XXXX/ZZZZ"
   private const val LIVE_APP_OPEN = "ca-app-pub-XXXX/WWWW"
   ```
Nothing else changes. Empty IDs fall back to test units, so a partially configured build still
renders the slots in QA.

## Design-system contract

* The slot is one layout, `view_ad_slot.xml`, `<include>`d by four screens. Its height is
  `@dimen/ad_banner_height` and its gutter is `@dimen/screen_h_padding`, so it lines up with the cards
  above it.
* **Space is reserved before the request.** `Ads.attachBanner` sets the container height from the
  `AdSize` it is about to request and keeps the container `INVISIBLE` until the creative loads. A
  late fill never pushes content; a no-fill sets `GONE` and leaves no hole.
* The slot adapts to width (`getCurrentOrientationAnchoredAdaptiveBannerAdSize`) rather than the
  fixed 320×50, which is both better paying and better fitting.
* No ad sits inside 8dp of a tappable control, and none overlaps the bottom navigation — the bottom
  inset padding on `bottom_navigation` keeps it above the gesture area.

## Before you publish

- [ ] Replace `admob_application_id` and the three `LIVE_*` unit IDs.
- [ ] Publish `app-ads.txt` for the developer website listed on the Play listing.
- [ ] Update the Play **Data safety** form: device or advertising ID, approximate location, ad
      interaction data, collected by Google.
- [ ] Set the content rating to include ads; keep `MAX_AD_CONTENT_RATING_PG` (already set).
- [ ] Confirm the app is not directed at children (Play's Families policy would forbid these
      placements entirely).
- [ ] Add a category blocklist in AdMob for loans, gambling, crypto and "get rich" offers. One
      predatory ad beside someone's overdraft costs more trust than a thousand impressions earn.
- [ ] Decide the **ad-free tier**. `AdPrefs.adsRemoved` already short-circuits every format, so the
      purchase flow only has to flip it.

## Where the revenue actually is

Banner eCPM in the PH market is roughly $0.2–1.0; native $1–3; interstitial $2–5; rewarded $5–12.
At 1,000 DAU × 2.5 impressions, banner-only is a couple of dollars a day. Order of leverage:
**format mix → mediation (Meta/AppLovin) → retention → an ad-free tier**. Ads are the texture of the
free tier, not the business.

Not implemented on purpose: native ads (needs a real creative container and an "Ad" label, worth
doing only once there is traffic to A/B), rewarded ads (needs a reward the app can actually grant —
exports or deeper reports), mediation, and an ad-free purchase flow.

## Verification status — read this before shipping

The code is statically checked only: `check_ui_tokens.py`, `check_resources.py`, XML parsing and
Kotlin bracket balance all pass. **This repository has no JDK or Gradle in the working environment,
so nothing here has been compiled or run.** The first real build must confirm:

1. `play-services-ads:24.6.0` and `user-messaging-platform:4.0.0` resolve (both are current stable;
   bump to 25.x once a build environment exists to test the upgrade).
2. Test banners render in debug on a device.
3. The interstitial appears on the fourth saved transaction, not before.
4. The app-open ad appears when returning from background — never on a cold start, never wrapped
   around a rotation.
5. Consent form appears for an EEA test device (`ConsentDebugSettings` with `DEBUG_GEOGRAPHY_EEA`
   during QA only).
