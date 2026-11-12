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
        findViewById<TextView>(R.id.avatar).setOnClickListener {
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
        balanceView.text = when (state.balances.size) {
            0 -> "—"
            1 -> state.balances.entries.first().let { Money(it.value, it.key).format() }
            else -> state.balances.entries.joinToString("  •  ") { Money(it.value, it.key).format() }
        }
        val balanceHidden = settings.getBoolean("balance_hidden", false)
        if (hidden) {
            balanceView.setText(R.string.masked_balance)
            findViewById<TextView>(R.id.monthly_summary).setText(R.string.monthly_summary_hidden)
        }
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).contentDescription = getString(if (hidden) R.string.show_balance else R.string.hide_balance)
        findViewById<Button>(R.id.add_account_button).setText(if (state.accounts.isEmpty()) R.string.add_first_account else R.string.add_account)
    }


    private fun refreshProfileHeader() {
        val name = getSharedPreferences("lumen_profile", MODE_PRIVATE).getString("display_name", "")?.trim().orEmpty()
        findViewById<TextView>(R.id.avatar).text = if (name.isEmpty()) "L" else name.substring(0, name.offsetByCodePoints(0, minOf(2, name.codePointCount(0, name.length)))).uppercase()
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

    private fun avatarInitials(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return "•"
        val count = minOf(2, trimmed.codePointCount(0, trimmed.length))
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, count)).uppercase()
    }

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
