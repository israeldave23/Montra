package com.dga.montra.data

import android.content.Context
import android.content.SharedPreferences
import com.dga.montra.domain.Money
import com.dga.montra.domain.TransactionType

/**
 * Typed access to everything the app remembers between launches.
 *
 * Keys used to be string literals at 20+ call sites across six files, which made a rename a
 * find-and-replace and a default value a guess. They live here now, and the two files behind them
 * (settings and profile) stay an implementation detail.
 */
class Settings(context: Context) {
    private val app: Context = context.applicationContext
    private val settings: SharedPreferences =
        app.getSharedPreferences(Storage.PREFS_SETTINGS, Context.MODE_PRIVATE)
    private val profile: SharedPreferences =
        app.getSharedPreferences(Storage.PREFS_PROFILE, Context.MODE_PRIVATE)

    /** [THEME_SYSTEM], [THEME_LIGHT] or [THEME_DARK]. */
    var themeMode: Int
        get() = settings.getInt(KEY_THEME_MODE, THEME_SYSTEM).coerceIn(THEME_SYSTEM, THEME_DARK)
        set(value) {
            settings.edit().putInt(KEY_THEME_MODE, value.coerceIn(THEME_SYSTEM, THEME_DARK)).apply()
        }

    /** Whether money is masked across every screen. */
    var balanceHidden: Boolean
        get() = settings.getBoolean(KEY_BALANCE_HIDDEN, false)
        set(value) = settings.edit().putBoolean(KEY_BALANCE_HIDDEN, value).apply()

    var displayName: String
        get() = profile.getString(KEY_DISPLAY_NAME, "").orEmpty().trim()
        set(value) = profile.edit().putString(KEY_DISPLAY_NAME, value.trim()).apply()

    /**
     * Writes the display name and reports whether it reached disk, so the profile dialog can show a
     * save error instead of pretending.
     */
    fun tryDisplayName(value: String): Boolean =
        profile.edit().putString(KEY_DISPLAY_NAME, value.trim()).commit()

    /** Currency preselected for new accounts and budgets. */
    var defaultCurrency: String
        get() = settings.getString(KEY_DEFAULT_CURRENCY, Money.currencies.first())
            ?: Money.currencies.first()
        set(value) = settings.edit().putString(KEY_DEFAULT_CURRENCY, value).apply()

    // ------------------------------------------------------- remembered form state
    // The add-transaction and transfer dialogs reopen on last month's choices; that is a feature,
    // and these are the fields it depends on.

    var lastCurrency: String?
        get() = settings.getString(KEY_LAST_CURRENCY, null)
        set(value) = settings.edit().putString(KEY_LAST_CURRENCY, value).apply()

    var lastAccountId: String?
        get() = settings.getString(KEY_LAST_ACCOUNT, null)
        set(value) = settings.edit().putString(KEY_LAST_ACCOUNT, value).apply()

    var lastCategoryId: String?
        get() = settings.getString(KEY_LAST_CATEGORY, null)
        set(value) = settings.edit().putString(KEY_LAST_CATEGORY, value).apply()

    var lastTransactionType: String
        get() = settings.getString(KEY_LAST_TYPE, TransactionType.EXPENSE.name)
            ?: TransactionType.EXPENSE.name
        set(value) = settings.edit().putString(KEY_LAST_TYPE, value).apply()

    var lastTransferAccountId: String?
        get() = settings.getString(KEY_LAST_TRANSFER_ACCOUNT, null)
        set(value) = settings.edit().putString(KEY_LAST_TRANSFER_ACCOUNT, value).apply()

    /** Remembers the currency for the next new account, and keeps the default in step. */
    fun rememberCurrency(code: String) {
        settings.edit()
            .putString(KEY_LAST_CURRENCY, code)
            .putString(KEY_DEFAULT_CURRENCY, code)
            .apply()
    }

    /** One write for the three fields the transaction dialog reopens with. */
    fun rememberTransactionForm(accountId: String, categoryId: String, type: String) {
        settings.edit()
            .putString(KEY_LAST_ACCOUNT, accountId)
            .putString(KEY_LAST_CATEGORY, categoryId)
            .putString(KEY_LAST_TYPE, type)
            .apply()
    }

    companion object {
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2

        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_BALANCE_HIDDEN = "balance_hidden"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_DEFAULT_CURRENCY = "default_currency"
        private const val KEY_LAST_CURRENCY = "last_currency"
        private const val KEY_LAST_ACCOUNT = "last_account"
        private const val KEY_LAST_CATEGORY = "last_category"
        private const val KEY_LAST_TYPE = "last_type"
        private const val KEY_LAST_TRANSFER_ACCOUNT = "last_transfer_account"
    }
}
