package com.dga.montra

import android.app.Application
import com.dga.montra.data.Storage

/**
 * Runs the storage rename before any screen touches the database or its preferences, so an install
 * that predates the Montra rename keeps its ledger, profile and settings.
 */
class MontraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Storage.migrateLegacyNames(this)
    }
}
