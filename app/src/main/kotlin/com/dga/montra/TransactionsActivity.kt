package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
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
        bindBottomNavigation(findViewById(R.id.bottom_navigation))
        repository = RoomFinanceRepository.get(this)
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
                        list.adapter = ArrayAdapter(this@TransactionsActivity, android.R.layout.simple_list_item_1, 
                            listOf(getString(R.string.empty_transactions)))
                        return@onSuccess
                    }
                    list.adapter = ArrayAdapter(this@TransactionsActivity, R.layout.item_transaction_row,
                        android.R.id.text1,
                        transactions.map { formattedLabel(it) })
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
        return "$sign${Money(tx.amount.minorUnits, tx.amount.currencyCode).format()}  ·  ${tx.categoryId}\n${tx.note.ifBlank { tx.type.name }}"
    }

    private fun showTransactionDetails(tx: Transaction) {
        val lines = buildString {
            append("Type: ${tx.type.name}\n")
            append("Amount: ${tx.amount.format()}\n")
            append("Category: ${tx.categoryId}\n")
            if (tx.note.isNotBlank()) append("Note: ${tx.note}\n")
            append("Account: ${tx.accountId}")
        }
        AlertDialog.Builder(this).setTitle(R.string.transaction_details).setMessage(lines)
            .setPositiveButton(android.R.string.ok, null).show()
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
