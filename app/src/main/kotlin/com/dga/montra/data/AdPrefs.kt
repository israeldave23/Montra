package com.dga.montra.data

import android.content.Context

/**
 * The little state ad serving needs to remember: when the last app-open and interstitial ran, and
 * how long the rewarded video's ad-free window lasts.
 *
 * It lives under `data/` on purpose — no activity should know that ad frequency is stored in
 * `SharedPreferences`, and the timestamp comparisons belong with the rest of the stored state.
 */
internal class AdPrefs(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(Storage.PREFS_ADS, Context.MODE_PRIVATE)

    /**
     * Epoch millis until which ads are suppressed because the user watched a rewarded video.
     * `0` means not ad-free.
     */
    var adFreeUntil: Long
        get() = prefs.getLong(KEY_AD_FREE_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_AD_FREE_UNTIL, value).apply()

    /** Epoch millis of the last app-open ad; `0` means never. */
    var lastAppOpenShownAt: Long
        get() = prefs.getLong(KEY_APP_OPEN_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_APP_OPEN_AT, value).apply()

    /** Epoch millis of the last interstitial; `0` means never. */
    var lastInterstitialShownAt: Long
        get() = prefs.getLong(KEY_INTERSTITIAL_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_INTERSTITIAL_AT, value).apply()

    /** Completed writes since the last interstitial, so the ad is earned rather than timed. */
    var transactionsSinceInterstitial: Int
        get() = prefs.getInt(KEY_TRANSACTIONS_SINCE, 0)
        set(value) = prefs.edit().putInt(KEY_TRANSACTIONS_SINCE, value).apply()

    private companion object {
        const val KEY_AD_FREE_UNTIL = "ad_free_until"
        const val KEY_APP_OPEN_AT = "app_open_at"
        const val KEY_INTERSTITIAL_AT = "interstitial_at"
        const val KEY_TRANSACTIONS_SINCE = "transactions_since_interstitial"
    }
}
