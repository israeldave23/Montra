package com.dga.montra

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.dga.montra.domain.DashboardState
import com.dga.montra.domain.Money
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainActivity : FinanceFormsActivity() {
    private var latestDashboard: DashboardState? = null
    private var activeCurrency: String? = null
    private var dashboardJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 0)
        findViewById<ImageButton>(R.id.avatar).setOnClickListener { startActivity(Intent(this, ProfileActivity::class.java)) }
        findViewById<Button>(R.id.view_activity_button).setOnClickListener { startActivity(Intent(this, TransactionsActivity::class.java)) }
        findViewById<Button>(R.id.add_transaction_button).setOnClickListener { showAddTransactionDialog() }
        findViewById<Button>(R.id.add_transfer_button).setOnClickListener { showTransferDialog() }
        findViewById<Button>(R.id.view_accounts_button).setOnClickListener { openAccounts() }
        findViewById<TextView>(R.id.balance).setOnClickListener { openAccounts() }
        findViewById<TextView>(R.id.home_month).text = monthLabel()
        findViewById<ImageButton>(R.id.toggle_balance).setOnClickListener {
            settings.edit().putBoolean("balance_hidden", !settings.getBoolean("balance_hidden", false)).apply()
            latestDashboard?.let(::render)
        }
        findViewById<Button>(R.id.home_currency).setOnClickListener {
            val state = latestDashboard ?: return@setOnClickListener
            val currencies = state.balances.keys.sorted()
            if (currencies.size > 1) {
                activeCurrency = currencies[(currencies.indexOf(activeCurrency) + 1) % currencies.size]
                render(state)
            }
        }
        activeCurrency = savedInstanceState?.getString("active_currency")
        if (savedInstanceState == null && intent.getBooleanExtra("open_add_transaction", false)) showAddTransactionDialog()
    }

    private fun openAccounts() = startActivity(Intent(this, AccountsActivity::class.java))

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("active_currency", activeCurrency)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
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
            } catch (cancelled: CancellationException) {
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
        val preferred = settings.getString("default_currency", "PHP") ?: "PHP"
        val code = activeCurrency?.takeIf { it in state.balances }
            ?: preferred.takeIf { it in state.balances }
            ?: state.balances.keys.sorted().firstOrNull() ?: preferred
        activeCurrency = code
        val hidden = settings.getBoolean("balance_hidden", false)
        val masked = getString(R.string.masked_balance)
        val totals = state.monthlyTotals[code]
        findViewById<TextView>(R.id.monthly_spent).text = if (hidden) masked else Money(totals?.second ?: 0L, code).format()
        findViewById<TextView>(R.id.monthly_income).text = if (hidden) masked else Money(totals?.first ?: 0L, code).format()
        findViewById<TextView>(R.id.balance).text = if (hidden) masked else state.balances[code]?.let { Money(it, code).format() } ?: "—"
        findViewById<TextView>(R.id.balance_summary).text = if (state.accountCount == 0) getString(R.string.empty_balance)
            else resources.getQuantityString(R.plurals.account_count, state.accountCount, state.accountCount)
        findViewById<Button>(R.id.home_currency).apply {
            text = code
            isClickable = state.balances.size > 1
            contentDescription = if (isClickable) getString(R.string.montra_currency_next, code) else code
        }
        findViewById<ImageButton>(R.id.toggle_balance).apply {
            setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_montra_eye)
            contentDescription = getString(if (hidden) R.string.show_balance else R.string.hide_balance)
        }
        val recent = findViewById<LinearLayout>(R.id.recent_transactions)
        val items = state.recentTransactions.take(3)
        findViewById<TextView>(R.id.recent_empty).visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recent.removeAllViews()
        val adapter = TransactionAdapter(this, items, hideAmounts = hidden)
        items.indices.forEach { index -> recent.addView(adapter.getView(index, null, recent)) }
    }
}
