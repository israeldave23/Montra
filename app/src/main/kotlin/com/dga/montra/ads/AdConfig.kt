package com.dga.montra.ads

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * Every ad identifier and every number that limits how often an ad can appear.
 *
 * Two rules live here and nowhere else:
 *
 *  * **Test units in debug, live units in release.** A live unit ID committed to a debug build is
 *    the fastest way to get an AdMob account suspended for invalid traffic — the taps are real
 *    traffic, but they are not real users. [liveOrTest] makes that impossible to get wrong.
 *  * **The caps are constants, not call-site judgement.** Frequency is the lever that decides
 *    whether ads cost 5% of retention, so it is reviewable in one diff instead of scattered
 *    through six files.
 */
object AdConfig {

    /** Master switch. `false` disables every format in the app without touching any call site. */
    const val ENABLED = true

    // ------------------------------------------------ live units — fill these in from AdMob
    //
    // Empty strings fall back to the test units below, so an unfilled build still renders the
    // slots (with test creatives) instead of showing nothing in QA. Replace before release.

    private const val LIVE_BANNER = ""
    private const val LIVE_INTERSTITIAL = ""
    private const val LIVE_APP_OPEN = ""

    // ------------------------------------------------ Google's sample units (never earn)
    private const val TEST_BANNER = "ca-app-pub-3940256099942544/9214589741"
    private const val TEST_INTERSTITIAL = "ca-app-pub-3940256099942544/1033173712"
    private const val TEST_APP_OPEN = "ca-app-pub-3940256099942544/9257395921"

    // ------------------------------------------------ frequency caps

    /** Saved transactions between two interstitials. The save is the completion; the ad follows it. */
    const val INTERSTITIAL_EVERY = 3

    /** Minimum wall-clock gap between interstitials, regardless of how many saves happen. */
    const val INTERSTITIAL_MIN_GAP_MS = 3 * 60 * 1000L

    /** A session is one foreground stretch; this caps the worst case a heavy user can hit. */
    const val MAX_INTERSTITIALS_PER_SESSION = 3

    /** App-open ads are a returning-visitor format. Four hours makes them a daily habit at most. */
    const val APP_OPEN_MIN_GAP_MS = 4 * 60 * 60 * 1000L

    fun banner(context: Context): String = liveOrTest(context, LIVE_BANNER, TEST_BANNER)

    fun interstitial(context: Context): String = liveOrTest(context, LIVE_INTERSTITIAL, TEST_INTERSTITIAL)

    fun appOpen(context: Context): String = liveOrTest(context, LIVE_APP_OPEN, TEST_APP_OPEN)

    private fun liveOrTest(context: Context, live: String, test: String): String {
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return if (debuggable || live.isBlank()) test else live
    }
}
