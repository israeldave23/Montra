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
import com.dga.montra.data.AccountRepository
import com.dga.montra.domain.Account
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var repository: AccountRepository
    private lateinit var balanceView: TextView
    private lateinit var summaryView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repository = AccountRepository(this)
        balanceView = findViewById(R.id.balance)
        summaryView = findViewById(R.id.balance_summary)
        findViewById<Button>(R.id.add_account_button).setOnClickListener { showAddAccountDialog() }
        findViewById<Button>(R.id.add_transaction_button).setOnClickListener { showAddTransactionDialog() }
        summaryView.setOnClickListener { runCatching { render() }.onFailure { toast(R.string.storage_error) } }
        runCatching { render() }.onFailure { toast(R.string.storage_error) }
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
                            repository.add(Account(UUID.randomUUID().toString(), trimmedName, parsed))
                            dialog.dismiss()
                            render()
                        } catch (_: Exception) {
                            toast(R.string.save_error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showAddTransactionDialog() {
        val accounts = runCatching { repository.all() }.getOrElse { toast(R.string.storage_error); return }
        if (accounts.isEmpty()) { toast(R.string.empty_balance); return }
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
                try {
                    repository.addTransaction(Transaction(accountId = selected.id, amount = parsed,
                        type = transactionType, categoryId = if (transactionType == TransactionType.INCOME) "income_uncategorized" else "uncategorized",
                        note = note.text.toString().trim()))
                    dialog.dismiss()
                    render()
                } catch (_: Exception) { toast(R.string.transaction_error) }
            }
        }
        dialog.show()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun render() {
        val state = repository.dashboard()
        summaryView.text = if (state.accountCount == 0) getString(R.string.empty_balance)
        else resources.getQuantityString(R.plurals.account_count, state.accountCount, state.accountCount)
        balanceView.text = when (state.balances.size) {
            0 -> "—"
            1 -> state.balances.entries.first().let { Money(it.value, it.key).format() }
            else -> state.balances.entries.joinToString("  •  ") { Money(it.value, it.key).format() }
        }
    }
}
