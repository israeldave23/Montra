package com.dga.montra

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Account
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import java.util.UUID
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class MainActivity : Activity() {
    private lateinit var repository: RoomFinanceRepository
    private val scope = MainScope()
    private lateinit var balanceView: TextView
    private lateinit var summaryView: TextView

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 0)
        findViewById<TextView>(R.id.avatar).setOnClickListener { startActivity(android.content.Intent(this, ProfileActivity::class.java)) }
        repository = RoomFinanceRepository.get(this)
        balanceView = findViewById(R.id.balance)
        summaryView = findViewById(R.id.balance_summary)
        findViewById<Button>(R.id.add_account_button).setOnClickListener { showAddAccountDialog() }
        findViewById<Button>(R.id.view_activity_button).setOnClickListener {
            startActivity(android.content.Intent(this, TransactionsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.add_transaction_button).setOnClickListener { showAddTransactionDialog() }
        findViewById<android.view.View>(R.id.add_transfer_button).setOnClickListener { showTransferDialog() }
        summaryView.setOnClickListener { render() }
        refreshProfileHeader()
        render()
    }

    override fun onResume() { super.onResume(); refreshProfileHeader() }

    private fun refreshProfileHeader() {
        val name = getSharedPreferences("lumen_profile", MODE_PRIVATE).getString("display_name", "")?.trim().orEmpty()
        findViewById<TextView>(R.id.avatar).text = if (name.isEmpty()) "L" else name.substring(0, name.offsetByCodePoints(0, minOf(2, name.codePointCount(0, name.length)))).uppercase()
        findViewById<TextView>(R.id.greeting_title).text = if (name.isEmpty()) getString(R.string.overview) else name
    }

    private fun showAddAccountDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val name = content.findViewById<EditText>(R.id.account_name)
        val amount = content.findViewById<EditText>(R.id.opening_balance)
        val currency = content.findViewById<Spinner>(R.id.currency_picker)
        currency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Money.currencies)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_account).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val trimmedName = name.text.toString().trim()
                val parsed = runCatching { Money.parse(amount.text.toString(), currency.selectedItem.toString()) }.getOrNull()
                when {
                    trimmedName.isEmpty() -> name.error = getString(R.string.account_name_hint)
                    parsed == null -> amount.error = getString(R.string.opening_balance_hint)
                    else -> {
                        try {
                            scope.launch {
                                runCatching { repository.addAccount(Account(UUID.randomUUID().toString(), trimmedName, parsed)) }
                                    .onSuccess { dialog.dismiss(); render() }
                                    .onFailure { toast(R.string.save_error) }
                            }
                        } catch (_: Exception) {
                            toast(R.string.save_error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showTransferDialog() {
        scope.launch {
            val accounts = runCatching { repository.allAccounts() }.getOrElse { toast(R.string.storage_error); return@launch }
            if (accounts.size < 2) { toast(R.string.empty_balance); return@launch }
            showTransferDialogWithAccounts(accounts)
        }
    }

    private fun showTransferDialogWithAccounts(accounts: List<Account>) {
        val content = layoutInflater.inflate(R.layout.dialog_add_transfer, null)
        val fromSpinner = content.findViewById<Spinner>(R.id.transfer_from_account)
        val toSpinner = content.findViewById<Spinner>(R.id.transfer_to_account)
        val amount = content.findViewById<EditText>(R.id.transfer_amount)
        val note = content.findViewById<EditText>(R.id.transfer_note)
        val labels = accounts.map { "${it.name} (${it.openingBalance.currencyCode})" }
        fromSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        toSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (labels.size > 1) toSpinner.setSelection(1)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_transfer).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = accounts[fromSpinner.selectedItemPosition]
                val to = accounts[toSpinner.selectedItemPosition]
                if (from.id == to.id) {
                    toast(R.string.transfer_error)
                    return@setOnClickListener
                }
                if (from.openingBalance.currencyCode != to.openingBalance.currencyCode) {
                    toast(R.string.transfer_error)
                    return@setOnClickListener
                }
                val parsed = runCatching { Money.parse(amount.text.toString(), from.openingBalance.currencyCode) }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = getString(R.string.transfer_amount)
                    return@setOnClickListener
                }
                scope.launch {
                    runCatching { repository.transfer(from.id, to.id, parsed, note.text.toString().trim()) }
                        .onSuccess { dialog.dismiss(); render() }
                        .onFailure { toast(R.string.transfer_error) }
                }
            }
        }
        dialog.show()
    }

    private fun showAddTransactionDialog() {
        scope.launch {
            val accounts = runCatching { repository.allAccounts() }.getOrElse { toast(R.string.storage_error); return@launch }
            if (accounts.isEmpty()) { toast(R.string.empty_balance); return@launch }
            showTransactionDialogWithAccounts(accounts)
        }
    }

    private fun showTransactionDialogWithAccounts(accounts: List<Account>) {
        val content = layoutInflater.inflate(R.layout.dialog_add_transaction, null)
        val account = content.findViewById<Spinner>(R.id.transaction_account)
        val type = content.findViewById<Spinner>(R.id.transaction_type)
        val amount = content.findViewById<EditText>(R.id.transaction_amount)
        val note = content.findViewById<EditText>(R.id.transaction_note)
        account.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            accounts.map { "${it.name} (${it.openingBalance.currencyCode})" })
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Expense", "Income", "Refund"))
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_transaction).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = accounts[account.selectedItemPosition]
                val parsed = runCatching { Money.parse(amount.text.toString(), selected.openingBalance.currencyCode) }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = getString(R.string.transaction_amount)
                    return@setOnClickListener
                }
                val transactionType = when (type.selectedItemPosition) {
                    1 -> TransactionType.INCOME
                    2 -> TransactionType.REFUND
                    else -> TransactionType.EXPENSE
                }
                scope.launch {
                    runCatching {
                        repository.addTransaction(Transaction(accountId = selected.id, amount = parsed,
                            type = transactionType, categoryId = if (transactionType == TransactionType.INCOME) "income_uncategorized" else "uncategorized",
                            note = note.text.toString().trim()))
                    }.onSuccess { dialog.dismiss(); render() }.onFailure { toast(R.string.transaction_error) }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun render() {
        scope.launch {
            runCatching { repository.dashboard() }
                .onSuccess { state ->
                    summaryView.text = if (state.accountCount == 0) getString(R.string.empty_balance)
                    else resources.getQuantityString(R.plurals.account_count, state.accountCount, state.accountCount)
                    balanceView.text = when (state.balances.size) {
                        0 -> "—"
                        1 -> state.balances.entries.first().let { Money(it.value, it.key).format() }
                        else -> state.balances.entries.joinToString("  •  ") { Money(it.value, it.key).format() }
                    }
                }
                .onFailure { toast(R.string.storage_error) }
        }
    }
}
