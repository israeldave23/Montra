package com.dga.montra

import android.app.Activity
import android.content.Intent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.content.res.Configuration
import android.widget.Toast
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Money
import com.dga.montra.domain.AccountBalance
import com.dga.montra.domain.DashboardState
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class MainActivity : Activity() {
    private var latestDashboard: DashboardState? = null
    private val settings by lazy { getSharedPreferences("lumen_settings", MODE_PRIVATE) }
    private lateinit var repository: RoomFinanceRepository
    private val scope = MainScope()
    private var dashboardJob: Job? = null
    private lateinit var balanceView: TextView
    private lateinit var summaryView: TextView

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTheme()
        setContentView(R.layout.activity_main)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 0)
        findViewById<android.view.View>(R.id.avatar_container).setOnClickListener {
            startActivity(
                android.content.Intent(this, ProfileActivity::class.java),
                android.app.ActivityOptions.makeCustomAnimation(this, R.anim.activity_enter, R.anim.activity_exit).toBundle()
            )
        }
        repository = RoomFinanceRepository.get(this)
        balanceView = findViewById(R.id.balance)
        summaryView = findViewById(R.id.balance_summary)
        findViewById<Button>(R.id.add_account_button).setOnClickListener {
            FinanceDialogs.addAccount(this, repository)
        }
        findViewById<Button>(R.id.view_activity_button).setOnClickListener {
            startActivity(android.content.Intent(this, TransactionsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.add_transaction_button).setOnClickListener {
            FinanceDialogs.addTransaction(this, repository)
        }
        findViewById<android.view.View>(R.id.add_transfer_button).setOnClickListener {
            FinanceDialogs.transfer(this, repository)
        }
        if (intent.getBooleanExtra("open_add_transaction", false)) {
            FinanceDialogs.addTransaction(this, repository)
        }
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).setOnClickListener {
            settings.edit().putBoolean("balance_hidden", !settings.getBoolean("balance_hidden", false)).apply()
            latestDashboard?.let { render(it) }
        }
        refreshProfileHeader()
    }

    override fun onResume() {
        super.onResume()
        refreshProfileHeader()
        observeHome()
    }

    override fun onStop() {
        dashboardJob?.cancel()
        super.onStop()
    }

    private fun observeHome() {
        dashboardJob?.cancel()
        dashboardJob = scope.launch {
            try {
                repository.prepareForObservation()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast(R.string.storage_error)
                return@launch
            }
            repository.observeDashboard().catch { toast(R.string.storage_error) }.collect { render(it) }
        }
    }

    private fun render(state: DashboardState) {
        latestDashboard = state
        val monthlyView = findViewById<TextView>(R.id.monthly_summary)
        val hidden = settings.getBoolean("balance_hidden", false)
        monthlyView.text = if (hidden) getString(R.string.monthly_summary_hidden) else state.monthlyTotals.entries.joinToString("\n") { (currency, totals) ->
            getString(R.string.monthly_summary, Money(totals.first, currency).format(), Money(totals.second, currency).format(), Money(Math.subtractExact(totals.first, totals.second), currency).format())
        }
        summaryView.text = when {
            state.accounts.isEmpty() -> getString(R.string.empty_balance)
            else -> {
                val active = resources.getQuantityString(
                    R.plurals.account_count,
                    state.accountCount,
                    state.accountCount
                )
                if (state.archivedCount == 0) active
                else "$active  ·  ${getString(R.string.account_archived_count, state.archivedCount)}"
            }
        }
        renderAccounts(state.accounts)
        val recent = findViewById<android.widget.LinearLayout>(R.id.recent_transactions)
        val items = state.recentTransactions
        findViewById<TextView>(R.id.recent_empty).visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        recent.removeAllViews()
        val adapter = TransactionAdapter(this, items)
        items.indices.forEach { index -> recent.addView(adapter.getView(index, null, recent)) }
        // Hidden balances are masked before formatting, so the exact amount never reaches a view.
        balanceView.text = when {
            hidden -> getString(R.string.masked_balance)
            state.balances.isEmpty() -> getString(R.string.balance_placeholder)
            state.balances.size == 1 -> state.balances.entries.first().let { Money(it.value, it.key).format() }
            else -> state.balances.entries.joinToString("  •  ") { Money(it.value, it.key).format() }
        }
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).contentDescription = getString(if (hidden) R.string.show_balance else R.string.hide_balance)
        findViewById<Button>(R.id.add_account_button).setText(if (state.accounts.isEmpty()) R.string.add_first_account else R.string.add_account)
    }


    private fun refreshProfileHeader() {
        val name = getSharedPreferences("lumen_profile", MODE_PRIVATE).getString("display_name", "")?.trim().orEmpty()
        val initials = findViewById<TextView>(R.id.avatar)
        initials.text = ProfileAvatar.initialsFor(name, defaultInitial())
        // A chosen profile photo replaces the initials circle until it is removed.
        ProfileAvatar.bind(
            this,
            findViewById(R.id.avatar_image),
            initials,
            resources.getDimensionPixelSize(R.dimen.space_48)
        )
        findViewById<TextView>(R.id.greeting_title).text = if (name.isEmpty()) getString(R.string.overview) else name
    }

    /**
     * The home screen's account list. Each row is the account's real current balance, and tapping it
     * opens that account — never an arbitrary first account.
     */
    private fun renderAccounts(accounts: List<AccountBalance>) {
        val section = findViewById<android.view.View>(R.id.home_accounts_section)
        val empty = findViewById<android.view.View>(R.id.home_accounts_empty)
        val container = findViewById<android.widget.LinearLayout>(R.id.home_accounts)
        val count = findViewById<TextView>(R.id.home_accounts_count)

        container.removeAllViews()
        section.visibility = if (accounts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        empty.visibility = if (accounts.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        if (accounts.isEmpty()) return

        count.text = resources.getQuantityString(R.plurals.account_count, accounts.size, accounts.size)
        accounts.forEach { item -> container.addView(accountRow(item, container)) }
    }

    private fun accountRow(item: AccountBalance, parent: android.view.ViewGroup): android.view.View {
        val row = layoutInflater.inflate(R.layout.item_account_row, parent, false)
        val hidden = settings.getBoolean("balance_hidden", false)
        val name = item.account.name
        val balanceText = if (hidden) {
            getString(R.string.account_masked_amount)
        } else {
            item.currentBalance.format()
        }
        val meta = if (item.hasActivity) {
            getString(R.string.account_row_activity, item.transactionCount)
        } else {
            getString(R.string.account_row_no_activity)
        }
        val status = if (item.isArchived) getString(R.string.account_archived) else meta

        row.findViewById<TextView>(R.id.account_row_initial).text = avatarInitials(name)
        row.findViewById<TextView>(R.id.account_row_name).text = name
        row.findViewById<TextView>(R.id.account_row_meta).text =
            "${item.currencyCode}  ·  $meta"
        row.findViewById<TextView>(R.id.account_row_balance).text = balanceText
        row.findViewById<TextView>(R.id.account_row_badge).visibility =
            if (item.isArchived) android.view.View.VISIBLE else android.view.View.GONE
        row.contentDescription = getString(
            R.string.account_row_description,
            name,
            balanceText,
            status
        )
        row.setOnClickListener {
            startActivity(
                Intent(this, AccountDetailActivity::class.java)
                    .putExtra(AccountDetailActivity.EXTRA_ACCOUNT_ID, item.account.id)
            )
        }
        return row
    }

    private fun avatarInitials(name: String): String = ProfileAvatar.initialsFor(name, defaultInitial())

    /** The app's own initial, so a nameless profile is never branded with a stale letter. */
    private fun defaultInitial(): String =
        getString(R.string.app_name).take(1).uppercase()

    private fun applyTheme() {
        val mode = settings.getInt("theme_mode", 0)
        val cur = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val target = when (mode) {
            1 -> Configuration.UI_MODE_NIGHT_NO
            2 -> Configuration.UI_MODE_NIGHT_YES
            else -> cur
        }
        if (target != cur) {
            val config = Configuration(resources.configuration)
            config.uiMode = target
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

}
