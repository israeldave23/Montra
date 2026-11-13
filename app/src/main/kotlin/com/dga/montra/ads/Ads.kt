package com.dga.montra.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.dga.montra.R
import com.dga.montra.data.AdPrefs
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.appopen.AppOpenAd
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.google.android.gms.ads.rewarded.RewardItem
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The whole ad surface of the app: four formats, one entry point each.
 *
 * Design rules this class enforces so no caller has to remember them:
 *
 *  * **Nothing loads before consent.** [ready] only becomes true after [Consent] allows requests.
 *  * **Banners reserve their space before they fill.** A late creative never pushes the content
 *    below it, and a no-fill collapses to nothing rather than leaving a hole.
 *  * **Interstitials are earned, not timed.** They follow a *saved* transaction, never a tap, never
 *    a launch, and they are capped by count and by wall clock.
 *  * **App-open ads appear when the user comes back**, not when they arrive: an ad that covers the
 *    launcher tap on a cold start is the one users uninstall over. They also never cover an intent
 *    the user explicitly asked for (deep link, shortcut).
 *  * **Rewarded video is a trade, and the app honours it.** Earning the reward writes an
 *    ad-free-until timestamp that every other format checks, so the 24 hours are real rather than
 *    a banner swap.
 *  * **Everything is destroyed with its screen.** An [AdView] that outlives its activity keeps the
 *    radio awake and leaks the window.
 */
object Ads {
    private const val TAG = "Ads"
    private const val ATTACHED = "montra.ad.attached"

    private var consentStarted = false
    private var starting = false
    private var ready = false

    private var startedActivities = 0
    private var returningToForeground = false
    private var suppressNextAppOpen = false

    private var appOpenAd: AppOpenAd? = null
    private var appOpenLoading = false

    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false
    private var interstitialsThisSession = 0

    private var rewardedAd: RewardedAd? = null
    private var rewardedLoading = false

    private val attached = WeakHashMap<Activity, MutableList<AdView>>()
    private val waiting = mutableListOf<WeakReference<ViewGroup>>()

    // ------------------------------------------------------------------------------ lifecycle

    /**
     * Called once from [com.dga.montra.MontraApp]. Everything else — consent, preloading, the
     * foreground bookkeeping — follows from here, so no activity has to opt in.
     */
    fun init(application: Application) {
        if (!AdConfig.ENABLED) return
        MobileAds.setRequestConfiguration(
            RequestConfiguration.Builder()
                .setMaxAdContentRating(RequestConfiguration.MAX_AD_CONTENT_RATING_PG)
                .build()
        )
        application.registerActivityLifecycleCallbacks(lifecycle)
    }

    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
        }

        override fun onActivityResumed(activity: Activity) {
            if (!consentStarted) {
                consentStarted = true
                Consent.resolve(activity) { granted ->
                    if (granted) startSdk(activity.applicationContext)
                }
                return
            }
            if (returningToForeground) {
                returningToForeground = false
                showAppOpen(activity)
            }
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            // A rotation is not a visit: it must not count as leaving and coming back.
            if (startedActivities == 0 && !activity.isChangingConfigurations) {
                returningToForeground = true
                loadAppOpen(activity.applicationContext)
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            releaseBanners(activity)
        }
    }

    /**
     * One question, asked everywhere: may this app show an ad right now?
     *
     * Two things can say no — the build switch, or the 24 hours a rewarded video bought. Keeping
     * the answer in one function is the reason a new format cannot accidentally bypass the ad-free
     * window.
     */
    private fun adsAllowed(context: Context): Boolean {
        if (!AdConfig.ENABLED) return false
        return AdPrefs(context).adFreeUntil <= System.currentTimeMillis()
    }

    /** Whether the profile screen should offer the rewarded card at all. */
    fun rewardsEnabled(context: Context): Boolean = AdConfig.ENABLED

    /** Epoch millis until which a rewarded video has suppressed ads; `0` when none is active. */
    fun adFreeUntil(context: Context): Long = AdPrefs(context).adFreeUntil

    private fun startSdk(context: Context) {
        if (ready || starting) return
        starting = true
        MobileAds.initialize(context) {
            ready = true
            loadInterstitial(context)
            loadAppOpen(context)
            loadRewarded(context)
            flushWaiting()
        }
    }

    // ---------------------------------------------------------------------------------- banner

    /**
     * Fills [container] with an adaptive banner, or hides it.
     *
     * Safe to call from `onCreate`: if consent has not resolved yet the container is queued and
     * filled when the SDK is ready, and if a no-fill arrives it is hidden for the rest of the
     * screen's life.
     */
    fun attachBanner(container: ViewGroup) {
        if (container.tag == ATTACHED) return
        val context = container.context

        if (!adsAllowed(context)) {
            container.visibility = View.GONE
            return
        }
        if (!ready) {
            container.visibility = View.GONE
            remember(container)
            return
        }

        container.tag = ATTACHED
        val metrics = context.resources.displayMetrics
        val gutter = context.resources.getDimensionPixelSize(R.dimen.screen_h_padding)
        val availableDp = ((metrics.widthPixels - gutter * 2) / metrics.density).toInt()
        val size = AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(context, availableDp)
            ?: AdSize.BANNER

        // Reserve the exact height we asked for, before asking: no shift when the creative lands.
        container.layoutParams?.let { params ->
            params.height = size.getHeightInPixels(context)
            container.layoutParams = params
        }
        container.visibility = View.INVISIBLE

        val adView = AdView(context)
        adView.adUnitId = AdConfig.banner(context)
        adView.setAdSize(size)
        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                container.visibility = View.VISIBLE
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Log.w(TAG, "banner no-fill (${error.code}): ${error.message}")
                container.visibility = View.GONE
                destroy(adView)
            }
        }

        container.addView(
            adView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL
            )
        )
        context.findActivity()?.let { owner ->
            attached.getOrPut(owner) { mutableListOf() }.add(adView)
        }
        adView.loadAd(AdRequest.Builder().build())
    }

    private fun remember(container: ViewGroup) {
        val known = waiting.any { it.get() === container }
        if (!known) waiting.add(WeakReference(container))
    }

    private fun flushWaiting() {
        val pending = waiting.toList()
        waiting.clear()
        pending.forEach { ref ->
            val container = ref.get()
            if (container != null && container.isAttachedToWindow) attachBanner(container)
        }
    }

    private fun releaseBanners(activity: Activity) {
        attached.remove(activity)?.forEach { adView ->
            (adView.parent as? ViewGroup)?.removeView(adView)
            destroy(adView)
        }
    }

    private fun destroy(adView: AdView) {
        runCatching { adView.destroy() }
        attached.values.forEach { it.remove(adView) }
    }

    // ---------------------------------------------------------------------------- interstitial

    /**
     * Call after a transaction is **saved**. Reaches for an ad only once the user has had three
     * successful saves and three minutes since the last one, and never more than three times in a
     * session.
     */
    fun onTransactionSaved(activity: Activity) {
        if (!ready || !adsAllowed(activity)) return
        val prefs = AdPrefs(activity)
        prefs.transactionsSinceInterstitial = prefs.transactionsSinceInterstitial + 1
        if (prefs.transactionsSinceInterstitial < AdConfig.INTERSTITIAL_EVERY) return
        if (interstitialsThisSession >= AdConfig.MAX_INTERSTITIALS_PER_SESSION) return
        if (System.currentTimeMillis() - prefs.lastInterstitialShownAt < AdConfig.INTERSTITIAL_MIN_GAP_MS) return

        val ad = interstitial
        if (ad == null) {
            loadInterstitial(activity.applicationContext)
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = reload(activity.applicationContext)

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "interstitial failed to show: ${error.message}")
                reload(activity.applicationContext)
            }
        }

        prefs.transactionsSinceInterstitial = 0
        prefs.lastInterstitialShownAt = System.currentTimeMillis()
        interstitialsThisSession++
        interstitial = null
        ad.show(activity)
    }

    private fun reload(context: Context) {
        interstitial = null
        loadInterstitial(context)
    }

    private fun loadInterstitial(context: Context) {
        if (!AdConfig.ENABLED || interstitial != null || interstitialLoading) return
        interstitialLoading = true
        InterstitialAd.load(
            context,
            AdConfig.interstitial(context),
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialLoading = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialLoading = false
                    Log.w(TAG, "interstitial load failed (${error.code}): ${error.message}")
                }
            }
        )
    }

    // ------------------------------------------------------------------------------ app open

    private fun showAppOpen(activity: Activity) {
        if (!ready || !adsAllowed(activity)) return
        // A system picker is not "leaving the app": coming back from the photo chooser with an
        // interstitial in your face is the kind of detail people uninstall over.
        if (suppressNextAppOpen) {
            suppressNextAppOpen = false
            return
        }
        val ad = appOpenAd ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (!launchedByUser(activity.intent)) return

        val prefs = AdPrefs(activity)
        if (System.currentTimeMillis() - prefs.lastAppOpenShownAt < AdConfig.APP_OPEN_MIN_GAP_MS) return

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "app-open failed to show: ${error.message}")
                appOpenAd = null
            }
        }
        prefs.lastAppOpenShownAt = System.currentTimeMillis()
        appOpenAd = null
        ad.show(activity)
    }

    /**
     * An app-open ad may not cover a launch the user asked for by name — a deep link, a shortcut, a
     * notification tap. Only a plain launcher start is eligible.
     */
    private fun launchedByUser(intent: Intent?): Boolean {
        if (intent == null) return true
        if (intent.data != null) return false
        val action = intent.action
        return action == null || action == Intent.ACTION_MAIN
    }

    private fun loadAppOpen(context: Context) {
        if (!AdConfig.ENABLED || appOpenAd != null || appOpenLoading) return
        appOpenLoading = true
        AppOpenAd.load(
            context,
            AdConfig.appOpen(context),
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenLoading = false
                    appOpenAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    appOpenLoading = false
                    Log.w(TAG, "app-open load failed (${error.code}): ${error.message}")
                }
            }
        )
    }

    // ------------------------------------------------------------------------------- rewarded

    /**
     * Shows a rewarded video and grants [onReward] only when the user actually earned it. The reward
     * is 24 hours without any ad — deliberately not something the app already gives away free, so
     * watching is a real trade rather than a toll on an existing feature.
     */
    fun showRewarded(activity: Activity, onReward: () -> Unit, onUnavailable: () -> Unit) {
        if (!ready || activity.isFinishing || activity.isDestroyed) {
            onUnavailable()
            return
        }
        val ad = rewardedAd
        if (ad == null) {
            loadRewarded(activity.applicationContext)
            onUnavailable()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() = reloadRewarded(activity.applicationContext)

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "rewarded failed to show: ${error.message}")
                reloadRewarded(activity.applicationContext)
            }
        }
        rewardedAd = null
        ad.show(activity) { reward ->
            Log.i(TAG, "reward earned: ${reward.amount} ${reward.type}")
            AdPrefs(activity).adFreeUntil = System.currentTimeMillis() + AdConfig.AD_FREE_MS
            // The promise starts now, not on the next screen: any banner already on screen goes.
            hideBanners()
            onReward()
        }
    }

    private fun hideBanners() {
        attached.values.toList().forEach { list ->
            list.toList().forEach { adView ->
                (adView.parent as? ViewGroup)?.visibility = View.GONE
                destroy(adView)
            }
        }
    }

    private fun reloadRewarded(context: Context) {
        rewardedAd = null
        loadRewarded(context)
    }

    private fun loadRewarded(context: Context) {
        if (!AdConfig.ENABLED || rewardedAd != null || rewardedLoading) return
        rewardedLoading = true
        RewardedAd.load(
            context,
            AdConfig.rewarded(context),
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedLoading = false
                    rewardedAd = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedLoading = false
                    Log.w(TAG, "rewarded load failed (${error.code}): ${error.message}")
                }
            }
        )
    }

    // -------------------------------------------------------------------------------- privacy

    /**
     * Marks the next return to the app as a continuation rather than a visit. Call it immediately
     * before `startActivityForResult` for a system picker (photo, document) so the user's own
     * document choice is not answered with a full-screen ad.
     */
    fun suppressNextAppOpen() {
        suppressNextAppOpen = true
    }

    /** Entry point for a "privacy options" settings row; no-ops where no form is required. */
    fun showPrivacyOptions(activity: Activity) = Consent.showPrivacyOptions(activity)

    /** Whether the profile screen should offer that row at all. */
    fun privacyOptionsAvailable(context: Context): Boolean {
        if (!AdConfig.ENABLED) return false
        return Consent.privacyOptionsAvailable(context)
    }

    private fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> this.baseContext.findActivity()
        else -> null
    }
}
