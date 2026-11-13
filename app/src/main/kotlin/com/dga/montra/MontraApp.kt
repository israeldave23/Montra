package com.dga.montra

import android.app.Application
import com.dga.montra.ads.Ads
import com.dga.montra.data.Settings
import com.dga.montra.data.Storage

/**
 * Runs the storage rename before any screen touches the database or its preferences, so an install
 * that predates the Montra rename keeps its ledger, profile and settings.
 */
class MontraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.migrateLegacyNames(this)
        // Before the first window exists: the delegate owns the scheme for the whole process, so
        // dialogs and popups inherit it too.
        AppActivity.applyThemeMode(Settings(this).themeMode)
        Ads.init(this)
    }
}
