package com.dga.montra

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dga.montra.ads.Ads
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.databinding.ActivityMainBinding
import com.dga.montra.domain.AccountBalance
import com.dga.montra.domain.DashboardState
import com.dga.montra.domain.Money
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppActivity() {
    private lateinit var binding: ActivityMainBinding

    private var latestDashboard: DashboardState? = null
    private lateinit var repository: RoomFinanceRepository
    private var dashboardJob: Job? = null
    private lateinit var balanceView: TextView
    private lateinit var summaryView: TextView
    private lateinit var accountAdapter: AccountRowAdapter
    private lateinit var recentAdapter: RecentTransactionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ads.attachBanner(binding.root)
        bindBottomNavigation(binding.root, 0)
        binding.avatarContainer.setOnClickListener {
            startActivity(
                android.content.Intent(this, ProfileActivity::class.java),
                android.app.ActivityOptions.makeCustomAnimation(this, R.anim.activity_enter, R.anim.activity_exit).toBundle()
            )
        }
        repository = RoomFinanceRepository.get(this)
        balanceView = binding.balance
        summaryView = binding.balanceSummary
        binding.addAccountButton.setOnClickListener {
            FinanceDialogs.addAccount(this, repository)
        }
        binding.viewActivityButton.setOnClickListener {
            startActivity(android.content.Intent(this, TransactionsActivity::class.java))
        }
        binding.addTransactionButton.setOnClickListener {
            FinanceDialogs.addTransaction(this, repository)
        }
        binding.addTransferButton.setOnClickListener {
            FinanceDialogs.transfer(this, repository)
        }
        if (intent.getBooleanExtra("open_add_transaction", false)) {
            FinanceDialogs.addTransaction(this, repository)
        }
        binding.toggleBalance.setOnClickListener {
            settings.balanceHidden = !settings.balanceHidden
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
        dashboardJob = lifecycleScope.launch {
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
        val monthlyView = binding.monthlySummary
        val hidden = settings.balanceHidden
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
        val items = state.recentTransactions
        binding.recentEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        recentAdapter.submitList(items)
        // Hidden balances are masked before formatting, so the exact amount never reaches a view.
        balanceView.text = when {
            hidden -> getString(R.string.masked_balance)
            state.balances.isEmpty() -> getString(R.string.balance_placeholder)
            state.balances.size == 1 -> state.balances.entries.first().let { Money(it.value, it.key).format() }
            else -> state.balances.entries.joinToString("  •  ") { Money(it.value, it.key).format() }
        }
        binding.toggleBalance.setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        binding.toggleBalance.contentDescription = getString(if (hidden) R.string.show_balance else R.string.hide_balance)
        binding.addAccountButton.setText(if (state.accounts.isEmpty()) R.string.add_first_account else R.string.add_account)
    }


    private fun refreshProfileHeader() {
        val name = settings.displayName
        val initials = binding.avatar
        initials.text = ProfileAvatar.initialsFor(name, defaultInitial())
        // A chosen profile photo replaces the initials circle until it is removed.
        ProfileAvatar.bind(
            this,
            binding.avatarImage,
            initials,
            resources.getDimensionPixelSize(R.dimen.space_48)
        )
        binding.greetingTitle.text = if (name.isEmpty()) getString(R.string.overview) else name
    }

    /**
     * The home screen's account list. Each row is the account's real current balance, and tapping it
     * opens that account — never an arbitrary first account.
     */
    private fun renderAccounts(accounts: List<AccountBalance>) {
        val empty = accounts.isEmpty()
        binding.homeAccountsSection.visibility = if (empty) View.GONE else View.VISIBLE
        binding.homeAccountsEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        if (empty) {
            accountAdapter.submitList(emptyList())
            return
        }

        binding.homeAccountsCount.text =
            resources.getQuantityString(R.plurals.account_count, accounts.size, accounts.size)
        accountAdapter.submitList(accounts.map(::accountRow))
    }

    /** Masks the balance before formatting, so a hidden amount never reaches a view. */
    private fun accountRow(item: AccountBalance): AccountRowContent {
        val name = item.account.name
        val balanceText = if (settings.balanceHidden) {
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
        return AccountRowContent(
            id = item.account.id,
            initials = avatarInitials(name),
            name = name,
            meta = "${item.currencyCode}  ·  $meta",
            balance = balanceText,
            archived = item.isArchived,
            description = getString(R.string.account_row_description, name, balanceText, status)
        )
    }

    private fun avatarInitials(name: String): String = ProfileAvatar.initialsFor(name, defaultInitial())

    /** The app's own initial, so a nameless profile is never branded with a stale letter. */
    private fun defaultInitial(): String =
        getString(R.string.app_name).take(1).uppercase()

}
