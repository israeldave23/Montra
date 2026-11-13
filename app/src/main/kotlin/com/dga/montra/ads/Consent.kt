package com.dga.montra.ads

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform

/**
 * Google's User Messaging Platform, wrapped so the rest of the app never has to think about it.
 *
 * No ad request goes out before this resolves. That is not politeness — serving a personalised ad
 * without a consent signal is a GDPR/ePrivacy violation in the EEA and the UK, and Google requires a
 * certified CMP (this one) for EEA traffic. When consent cannot be established we simply do not
 * serve ads: the ledger works, the app looks free, nobody is fined.
 */
internal object Consent {
    private const val TAG = "Ads"

    /** Resolves once per process: the answer is cached by the SDK, not by us. */
    @Volatile
    private var requested = false

    /**
     * Calls [onResolved] with `true` only when ads may be requested. The callback can arrive after
     * the calling activity is gone, so callers must not hold a view in it.
     */
    fun resolve(activity: Activity, onResolved: (Boolean) -> Unit) {
        if (requested) return
        requested = true

        val information = UserMessagingPlatform.getConsentInformation(activity)
        val parameters = ConsentRequestParameters.Builder().setTagForUnderAgeOfConsent(false).build()

        // Consent from a previous session is enough to start ads immediately; the refresh below runs
        // in parallel, so a changed decision is picked up on the next launch rather than gating this
        // one behind a network round trip.
        if (information.canRequestAds()) onResolved(true)

        information.requestConsentInfoUpdate(
            activity,
            parameters,
            object : ConsentInformation.OnConsentInfoUpdateSuccessListener {
                override fun onConsentInfoUpdateSuccess() {
                    // Shows a form only where one is required; everywhere else it returns at once.
                    UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { formError ->
                        if (formError != null) {
                            Log.w(TAG, "consent form failed: ${formError.message} (${formError.errorCode})")
                        }
                        onResolved(information.canRequestAds())
                    }
                }
            },
            object : ConsentInformation.OnConsentInfoUpdateFailureListener {
                override fun onConsentInfoUpdateFailure(requestError: FormError) {
                    Log.w(TAG, "consent update failed: ${requestError.message} (${requestError.errorCode})")
                    onResolved(false)
                }
            }
        )
    }

    /**
     * Whether this user can meaningfully change their mind — i.e. a privacy-options form exists for
     * their region. Used to hide a settings row that would otherwise do nothing.
     */
    fun privacyOptionsAvailable(context: Context): Boolean {
        val information = UserMessagingPlatform.getConsentInformation(context)
        return information.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    }

    /**
     * The "privacy options" entry point Play requires once a consent form exists. Call it from a
     * settings row; it no-ops where no form is required.
     */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { formError ->
            if (formError != null) {
                Log.w(TAG, "privacy options failed: ${formError.message} (${formError.errorCode})")
            }
        }
    }
}
