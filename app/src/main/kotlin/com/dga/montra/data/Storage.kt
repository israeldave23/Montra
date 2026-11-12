package com.dga.montra.data

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Every name the app writes to disk, in one place.
 *
 * The app shipped as *Lumen Finance* before it was called Montra, so older installs have a database
 * and preference files under the old names. [migrateLegacyNames] renames them once, before anything
 * opens the database or reads a preference, so upgrading is invisible and no ledger is lost.
 */
internal object Storage {
    const val DATABASE = "montra.db"
    const val TEMP_RESTORE = "montra_restore.db"
    const val EXPORT_PREFIX = "montra_transactions"

    const val PREFS_SETTINGS = "montra_settings"
    const val PREFS_PROFILE = "montra_profile"
    const val PREFS_ACCOUNTS = "montra_accounts"
    const val PREFS_TRANSACTIONS = "montra_tx"
    const val PREFS_MIGRATION = "montra_migration"

    private const val LEGACY_DATABASE = "lumen_finance.db"

    /** Old preference file name → current one. */
    private val legacyPreferences = listOf(
        "lumen_settings" to PREFS_SETTINGS,
        "lumen_profile" to PREFS_PROFILE,
        "lumen_accounts" to PREFS_ACCOUNTS,
        "lumen_tx" to PREFS_TRANSACTIONS,
        "lumen_migration" to PREFS_MIGRATION,
    )

    @Volatile
    private var migrated = false

    /** Idempotent; safe to call on every process start. Failures never block the app. */
    fun migrateLegacyNames(context: Context) {
        if (migrated) return
        synchronized(this) {
            if (migrated) return
            runCatching { renameDatabase(context) }
            legacyPreferences.forEach { (oldName, newName) ->
                runCatching { copyPreferences(context, oldName, newName) }
            }
            migrated = true
        }
    }

    /**
     * Moves the database file, plus its `-wal` and `-shm` sidecars: those hold committed rows that
     * have not been checkpointed, so leaving them behind would silently drop recent transactions.
     */
    private fun renameDatabase(context: Context) {
        val target = context.getDatabasePath(DATABASE)
        if (target.exists()) return
        val legacy = context.getDatabasePath(LEGACY_DATABASE)
        if (!legacy.exists()) return
        target.parentFile?.mkdirs()
        listOf("", "-wal", "-shm").forEach { suffix ->
            val from = File(legacy.path + suffix)
            if (!from.exists()) return@forEach
            val to = File(target.path + suffix)
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
        }
    }

    /** Copies every entry, then clears the old file, so a rename can never be half-applied. */
    private fun copyPreferences(context: Context, legacyName: String, newName: String) {
        val legacy = context.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
        val values = legacy.all
        if (values.isEmpty()) return
        val target = context.getSharedPreferences(newName, Context.MODE_PRIVATE)
        // Never overwrite a file that already belongs to the renamed build.
        if (target.all.isNotEmpty()) return

        val editor = target.edit()
        values.forEach { (key, value) -> editor.putValue(key, value) }
        if (editor.commit()) legacy.edit().clear().commit()
    }

    private fun SharedPreferences.Editor.putValue(key: String, value: Any?): SharedPreferences.Editor =
        when (value) {
            is String -> putString(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
            else -> this
        }
}
