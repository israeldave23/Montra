package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import android.widget.TextView
import android.widget.Button
import android.widget.AdapterView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import android.app.AlertDialog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TransactionsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var repository: RoomFinanceRepository
    private lateinit var list: ListView
    private lateinit var query: EditText
    private var requestVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 1)
        repository = RoomFinanceRepository.get(this)
        findViewById<TextView>(R.id.transactions_back).setOnClickListener { finish() }
        list = findViewById(R.id.transaction_list)
        query = findViewById(R.id.transaction_search)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        load("")
    }

    private fun load(text: String) {
        val version = ++requestVersion
        scope.launch {
            runCatching { repository.searchTransactions(text) }
                .onSuccess { transactions ->
                    if (version != requestVersion) return@onSuccess
                    list.onItemLongClickListener = null
                    if (transactions.isEmpty()) {
                        list.onItemClickListener = null
                        list.adapter = ArrayAdapter(this@TransactionsActivity, android.R.layout.simple_list_item_1, 
                            listOf(getString(R.string.empty_transactions)))
                        return@onSuccess
                    }
                    list.adapter = TransactionAdapter(this@TransactionsActivity, transactions)
                    list.setOnItemClickListener { _, _, position, _ ->
                        showTransactionDetails(transactions[position])
                    }
                    list.setOnItemLongClickListener { _, _, position, _ ->
                        confirmDelete(transactions[position])
                        true
                    }
                }
                .onFailure { Toast.makeText(this@TransactionsActivity, R.string.storage_error, Toast.LENGTH_LONG).show() }
        }
    }

    private fun formattedLabel(tx: com.dga.montra.domain.Transaction): String {
        val sign = if (tx.type == com.dga.montra.domain.TransactionType.EXPENSE ||
            tx.type == com.dga.montra.domain.TransactionType.TRANSFER_OUT) "−" else "+"
        return "$sign${Money(tx.amount.minorUnits, tx.amount.currencyCode).format()}  ·  ${tx.categoryId}\n${tx.note.ifBlank { tx.type.name }}\n${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(tx.timestampMillis))}"
    }

    private fun showTransactionDetails(tx: Transaction) {
        val catName = com.dga.montra.domain.TransactionCategories.expense.asSequence()
            .or(com.dga.montra.domain.TransactionCategories.income.asSequence())
            .firstOrNull { it.id == tx.categoryId }?.name ?: tx.categoryId.replaceFirstChar { it.uppercase() }
        val lines = buildString {
            append("Date: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(tx.timestampMillis))}\n")
            append("Type: ${tx.type.name}\n")
            append("Amount: ${tx.amount.format()}\n")
            append("Category: ${catName}\n")
            if (tx.note.isNotBlank()) append("Note: ${tx.note}\n")
            append("Account: ${tx.accountId}")
        }
        val builder = AlertDialog.Builder(this).setTitle(R.string.transaction_details).setMessage(lines)
            .setPositiveButton(android.R.string.ok, null)
        if (tx.type != com.dga.montra.domain.TransactionType.TRANSFER_OUT && tx.type != com.dga.montra.domain.TransactionType.TRANSFER_IN) {
            builder.setNeutralButton(R.string.edit) { _, _ -> showEditTransactionDialog(tx) }
        }
        builder.show()
    }

    private fun showEditTransactionDialog(tx: com.dga.montra.domain.Transaction) {
        scope.launch {
            val accounts = runCatching { repository.allAccounts() }.getOrElse { toast(R.string.storage_error); return@launch }
            if (accounts.isEmpty()) { toast(R.string.empty_balance); return@launch }
            showEditTransactionDialogWithAccounts(tx, accounts)
        }
    }

    private fun showEditTransactionDialogWithAccounts(tx: com.dga.montra.domain.Transaction, accounts: List<com.dga.montra.domain.Account>) {
        val content = layoutInflater.inflate(R.layout.dialog_add_transaction, null)
        val account = content.findViewById<Spinner>(R.id.transaction_account)
        val type = content.findViewById<Spinner>(R.id.transaction_type)
        val category = content.findViewById<Spinner>(R.id.transaction_category)
        val datePickerBtn = content.findViewById<Button>(R.id.transaction_date_picker)
        val amount = content.findViewById<EditText>(R.id.transaction_amount)
        val note = content.findViewById<EditText>(R.id.transaction_note)

        val fmt = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = tx.timestampMillis
        }
        datePickerBtn.text = fmt.format(java.util.Date(tx.timestampMillis))

        val accountLabels = accounts.map { "${it.name} (${it.openingBalance.currencyCode})" }
        account.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, accountLabels)
        val accountIdx = accounts.indexOfFirst { it.id == tx.accountId }
        if (accountIdx >= 0) account.setSelection(accountIdx)

        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf("Expense", "Income", "Refund"))
        type.setSelection(when (tx.type) {
            com.dga.montra.domain.TransactionType.INCOME -> 1
            com.dga.montra.domain.TransactionType.REFUND -> 2
            else -> 0
        })

        val cats = when (tx.type) {
            com.dga.montra.domain.TransactionType.INCOME -> com.dga.montra.domain.TransactionCategories.income
            else -> com.dga.montra.domain.TransactionCategories.expense
        }
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cats.map { it.name })
        val catIdx = cats.indexOfFirst { it.id == tx.categoryId }
        if (catIdx >= 0) category.setSelection(catIdx)
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view?, position: Int, id: Long) {
                val c = when (position) { 1 -> com.dga.montra.domain.TransactionCategories.income; else -> com.dga.montra.domain.TransactionCategories.expense }
                category.adapter = ArrayAdapter(this@TransactionsActivity, android.R.layout.simple_spinner_dropdown_item, c.map { it.name })
                category.setSelection(0)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        amount.setText(tx.amount.format())
        note.setText(tx.note)

        datePickerBtn.setOnClickListener {
            android.app.DatePickerDialog(this,
                { _, y, m, d ->
                    cal.set(java.util.Calendar.YEAR, y)
                    cal.set(java.util.Calendar.MONTH, m)
                    cal.set(java.util.Calendar.DAY_OF_MONTH, d)
                    cal.set(java.util.Calendar.HOUR_OF_DAY, 12)
                    cal.set(java.util.Calendar.MINUTE, 0)
                    cal.set(java.util.Calendar.SECOND, 0)
                    datePickerBtn.text = fmt.format(java.util.Date(cal.timeInMillis))
                },
                cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH), cal.get(java.util.Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this).setTitle(R.string.edit_transaction).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = runCatching { com.dga.montra.domain.Money.parse(amount.text.toString(), tx.amount.currencyCode) }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = getString(R.string.transaction_amount)
                    return@setOnClickListener
                }
                val newType = when (type.selectedItemPosition) {
                    1 -> com.dga.montra.domain.TransactionType.INCOME
                    2 -> com.dga.montra.domain.TransactionType.REFUND
                    else -> com.dga.montra.domain.TransactionType.EXPENSE
                }
                val newCats = when (newType) {
                    com.dga.montra.domain.TransactionType.INCOME -> com.dga.montra.domain.TransactionCategories.income
                    else -> com.dga.montra.domain.TransactionCategories.expense
                }
                val catId = newCats.getOrElse(category.selectedItemPosition) { com.dga.montra.domain.TransactionCategories.UNCATEGORIZED_EXPENSE }.id
                scope.launch {
                    runCatching {
                        repository.updateTransaction(tx.id, catId, note.text.toString().trim(), newType, cal.timeInMillis)
                    }.onSuccess {
                        dialog.dismiss()
                        Toast.makeText(this@TransactionsActivity, R.string.transaction_updated, Toast.LENGTH_SHORT).show()
                        load(query.text.toString())
                    }.onFailure { toast(R.string.transaction_error) }
                }
            }
        }
        dialog.show()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun confirmDelete(tx: Transaction) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_transaction_title)
            .setMessage(R.string.delete_transaction_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                scope.launch {
                    runCatching { repository.deleteTransaction(tx.id) }
                        .onSuccess { Toast.makeText(this@TransactionsActivity, R.string.transaction_deleted, Toast.LENGTH_SHORT).show(); load(query.text.toString()) }
                        .onFailure { Toast.makeText(this@TransactionsActivity, R.string.storage_error, Toast.LENGTH_LONG).show() }
                }
            }.show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
