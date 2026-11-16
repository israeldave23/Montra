package com.dga.montra

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.ads.Ads
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.databinding.ActivityAccountDetailBinding
import com.dga.montra.databinding.DialogRenameAccountBinding
import com.dga.montra.domain.AccountBalance
import com.dga.montra.ui.AccountDetailViewModel
import com.dga.montra.ui.UiState
import com.dga.montra.ui.viewModelFactory
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * One account: its real current balance (opening balance plus every transaction), its own activity,
 * and the actions that belong to it — add a transaction, transfer out of it, rename, archive, delete.
 *
 * The balance here is the same number the home screen shows, because both read
 * [com.dga.montra.domain.accountBalances]; nothing is recomputed per screen.
 */
class AccountDetailActivity : AppActivity() {
    private lateinit var binding: ActivityAccountDetailBinding

    private val repo by lazy { RoomFinanceRepository.get(this) }

    private lateinit var list: RecyclerView
    private lateinit var activityAdapter: TransactionListAdapter
    private lateinit var emptyView: TextView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var archivedHint: TextView
    private lateinit var balanceView: TextView
    private lateinit var metaView: TextView
    private lateinit var countView: TextView
    private lateinit var addTransactionButton: View
    private lateinit var transferButton: View
    private lateinit var menuButton: View

    private var accountId: String? = null
    private var current: AccountBalance? = null
    private var reportedMissing = false
    private var viewModel: AccountDetailViewModel? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityAccountDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ads.attachBanner(binding.root)

        list = binding.accountTransactions
        emptyView = binding.accountActivityEmpty
        nameView = binding.accountName
        statusView = binding.accountStatus
        archivedHint = binding.accountArchivedHint
        balanceView = binding.accountBalance
        metaView = binding.accountMeta
        countView = binding.accountActivityCount
        addTransactionButton = binding.accountAddTransaction
        transferButton = binding.accountTransfer
        menuButton = binding.accountMenu

        list.layoutManager = LinearLayoutManager(this)
        activityAdapter = TransactionListAdapter()
        list.adapter = activityAdapter

        binding.accountBack.setOnClickListener { finish() }
        menuButton.setOnClickListener { showMenu(it) }
        addTransactionButton.setOnClickListener {
            FinanceDialogs.addTransaction(
                this,
                repo,
                preselectAccountId = accountId
            )
        }
        transferButton.setOnClickListener {
            FinanceDialogs.transfer(this, repo, preselectFromAccountId = accountId)
        }
        bindBottomNavigation(binding.root, 0)

        accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        if (accountId == null) {
            showMissingAccount()
        } else {
            startObservation()
        }
    }

    /**
     * Created once the account id is known: the view model is per account, and holds the two flows
     * the screen used to combine by hand on every start.
     */
    private fun startObservation() {
        val id = accountId ?: return
        if (viewModel != null) return
        val model: AccountDetailViewModel by viewModels {
            viewModelFactory { AccountDetailViewModel(repo, id) }
        }
        viewModel = model
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    when (state) {
                        is UiState.Ready -> render(state.value.first, state.value.second)
                        UiState.Failed -> snackbar(R.string.storage_error)
                        UiState.Loading -> Unit
                    }
                }
            }
        }
    }

    private fun render(balance: AccountBalance?, transactions: List<Transaction>) {
        if (balance == null) {
            showMissingAccount()
            return
        }
        current = balance
        val hidden = settings.balanceHidden
        val account = balance.account

        nameView.text = account.name
        statusView.visibility = if (balance.isArchived) View.VISIBLE else View.GONE
        archivedHint.visibility = if (balance.isArchived) View.VISIBLE else View.GONE

        balanceView.text = if (hidden) {
            getString(R.string.masked_balance)
        } else {
            balance.currentBalance.format()
        }
        countView.text = getString(R.string.account_row_activity, balance.transactionCount)
        metaView.text = if (hidden) {
            getString(R.string.account_row_activity, balance.transactionCount)
        } else {
            getString(
                R.string.account_meta,
                account.openingBalance.format(),
                signedMoney(balance.movementMinorUnits, balance.currencyCode)
            )
        }
        val statusLabel = if (balance.isArchived) {
            getString(R.string.account_archived)
        } else {
            getString(R.string.account_row_activity, balance.transactionCount)
        }
        nameView.contentDescription = getString(
            R.string.account_row_description,
            account.name,
            balance.currentBalance.format(),
            statusLabel
        )

        // Archived accounts keep their history but cannot receive new money movement.
        val actionable = !balance.isArchived
        addTransactionButton.isEnabled = actionable
        transferButton.isEnabled = actionable
        addTransactionButton.alpha = if (actionable) 1f else DISABLED_ALPHA
        transferButton.alpha = if (actionable) 1f else DISABLED_ALPHA

        val empty = transactions.isEmpty()
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        list.visibility = if (empty) View.GONE else View.VISIBLE
        activityAdapter.submitList(TransactionListAdapter.itemsFor(transactions))
    }

    private fun signedMoney(minorUnits: Long, currency: String): String = getString(
        if (minorUnits < 0L) R.string.amount_negative else R.string.amount_positive,
        Money(Math.abs(minorUnits), currency).format()
    )

    private fun showMenu(anchor: View) {
        val balance = current ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_RENAME, 0, R.string.rename_account)
            if (balance.isArchived) {
                menu.add(0, MENU_UNARCHIVE, 1, R.string.account_unarchive)
            } else {
                menu.add(0, MENU_ARCHIVE, 1, R.string.account_archive)
            }
            menu.add(0, MENU_DELETE, 2, R.string.delete_account)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_RENAME -> showRename(balance)
                    MENU_ARCHIVE -> confirmArchive(balance)
                    MENU_UNARCHIVE -> unarchive(balance)
                    MENU_DELETE -> confirmDelete(balance)
                }
                true
            }
            show()
        }
    }

    private fun showRename(balance: AccountBalance) {
        val content = DialogRenameAccountBinding.inflate(layoutInflater)
        val input = content.accountRenameInput
        input.setText(balance.account.name)
        input.setSelection(input.text.length)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_account)
            .setView(content.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = getString(R.string.account_name_required)
                    return@setOnClickListener
                }
                if (name.length > NAME_LIMIT) {
                    input.error = getString(R.string.account_name_too_long)
                    return@setOnClickListener
                }
                confirm.isEnabled = false
                lifecycleScope.launch {
                    runCatching { repo.renameAccount(balance.account.id, name) }
                        .onSuccess {
                            dialog.dismiss()
                            snackbar(R.string.account_renamed)
                        }
                        .onFailure {
                            confirm.isEnabled = true
                            input.error = getString(R.string.save_error)
                        }
                }
            }
        }
        dialog.show()
    }

    private fun confirmArchive(balance: AccountBalance) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_archive_title)
            .setMessage(getString(R.string.account_archive_message, balance.account.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.account_archive) { _, _ -> archive(balance) }
            .show()
    }

    private fun archive(balance: AccountBalance) {
        lifecycleScope.launch {
            runCatching { repo.archiveAccount(balance.account.id) }
                .onSuccess {
                    snackbarWithUndo(getString(R.string.account_archived_done)) {
                        lifecycleScope.launch {
                            runCatching { repo.unarchiveAccount(balance.account.id) }
                                .onFailure { snackbar(R.string.save_error) }
                        }
                    }
                }
                .onFailure { snackbar(R.string.save_error) }
        }
    }

    private fun unarchive(balance: AccountBalance) {
        lifecycleScope.launch {
            runCatching { repo.unarchiveAccount(balance.account.id) }
                .onSuccess { snackbar(R.string.account_unarchived) }
                .onFailure { snackbar(R.string.save_error) }
        }
    }

    private fun confirmDelete(balance: AccountBalance) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_account)
            .setMessage(R.string.delete_account_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> delete(balance) }
            .show()
    }

    private fun delete(balance: AccountBalance) {
        lifecycleScope.launch {
            runCatching { repo.deleteAccount(balance.account.id) }
                .onSuccess { finish() }
                .onFailure {
                    // Accounts that took part in a transfer must stay paired, so deletion is refused.
                    snackbar(R.string.delete_account_error)
                }
        }
    }

    private fun showMissingAccount() {
        if (reportedMissing || isFinishing || isDestroyed) return
        reportedMissing = true
        toast(R.string.account_not_found_body)
        finish()
    }

    private fun snackbar(message: Int) = snackbar(getString(message))

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun snackbarWithUndo(message: String, undo: () -> Unit) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .show()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "ACCOUNT_ID"
        private const val MENU_RENAME = 1
        private const val MENU_ARCHIVE = 2
        private const val MENU_UNARCHIVE = 3
        private const val MENU_DELETE = 4
        private const val NAME_LIMIT = 100
        private const val DISABLED_ALPHA = 0.45f
    }
}
