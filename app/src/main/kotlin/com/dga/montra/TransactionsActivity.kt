package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import com.google.android.material.snackbar.Snackbar
import android.widget.Spinner
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch

class TransactionsActivity : Activity() {
    private val scope = MainScope()
    private lateinit var repository: RoomFinanceRepository
    private lateinit var list: RecyclerView
    private lateinit var emptyView: android.widget.TextView
    private var currentItems = listOf<Transaction>()
    private var activeTouchHelper: ItemTouchHelper? = null
    private lateinit var query: EditText
    private var selectedFilter = com.dga.montra.domain.TransactionFilter.ALL
    private var requestVersion = 0
    private var observation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transactions)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 1)
        repository = RoomFinanceRepository.get(this)
        findViewById<View>(R.id.transactions_back).setOnClickListener { finish() }
        list = findViewById(R.id.transaction_list)
        list.layoutManager = LinearLayoutManager(this)
        emptyView = findViewById(R.id.transaction_empty)
        activeTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val tx = currentItems.getOrNull(viewHolder.bindingAdapterPosition) ?: return 0
                return if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                currentItems.getOrNull(position)?.let { deleteWithUndo(it) }
            }
        }).also { it.attachToRecyclerView(list) }
        query = findViewById(R.id.transaction_search)
        findViewById<com.google.android.material.chip.ChipGroup>(R.id.transaction_filters).setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFilter = when (checkedIds.firstOrNull()) {
                R.id.filter_income -> com.dga.montra.domain.TransactionFilter.INCOME
                R.id.filter_expense -> com.dga.montra.domain.TransactionFilter.EXPENSE
                R.id.filter_transfer -> com.dga.montra.domain.TransactionFilter.TRANSFER
                else -> com.dga.montra.domain.TransactionFilter.ALL
            }
            load(query.text.toString())
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { load(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        load("")
    }

    override fun onStart() {
        super.onStart()
        observation = scope.launch {
            try {
                repository.prepareForObservation()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Toast.makeText(this@TransactionsActivity, R.string.storage_error, Toast.LENGTH_LONG).show()
                return@launch
            }
            repository.observeTransactions().catch { Toast.makeText(this@TransactionsActivity, R.string.storage_error, Toast.LENGTH_LONG).show() }.collect { load(query.text.toString()) }
        }
    }

    override fun onStop() {
        observation?.cancel()
        super.onStop()
    }

    private fun load(text: String) {
        val version = ++requestVersion
        val filter = selectedFilter
        scope.launch {
            runCatching { repository.searchTransactions(text).filter { filter.matches(it.type) } }
                .onSuccess { transactions ->
                    if (version != requestVersion) return@onSuccess
                    currentItems = transactions
                    emptyView.visibility = if (transactions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
                    list.visibility = if (transactions.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    list.adapter = TransactionListAdapter(transactions, { tx ->
                        if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) showTransactionDetails(tx)
                        else showEditTransactionDialog(tx)
                    }, { tx -> confirmDelete(tx) })
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
            .plus(com.dga.montra.domain.TransactionCategories.income.asSequence())
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
        account.isEnabled = false

        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, arrayOf(getString(R.string.type_expense), getString(R.string.type_income), getString(R.string.type_refund)))
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
        var initialTypeSelection = true
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (initialTypeSelection) { initialTypeSelection = false; return }
                val c = when (position) { 1 -> com.dga.montra.domain.TransactionCategories.income; else -> com.dga.montra.domain.TransactionCategories.expense }
                category.adapter = ArrayAdapter(this@TransactionsActivity, android.R.layout.simple_spinner_dropdown_item, c.map { it.name })
                category.setSelection(0)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        amount.setText(java.math.BigDecimal.valueOf(tx.amount.minorUnits, java.util.Currency.getInstance(tx.amount.currencyCode).defaultFractionDigits).toPlainString())
        amount.isEnabled = false
        amount.alpha = 0.6f
        amount.hint = getString(R.string.transaction_amount_unchanged)
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
                val newType = when (type.selectedItemPosition) {
                    1 -> com.dga.montra.domain.TransactionType.INCOME
                    2 -> com.dga.montra.domain.TransactionType.REFUND
                    else -> com.dga.montra.domain.TransactionType.EXPENSE
                }
                val newCats = when (newType) {
                    com.dga.montra.domain.TransactionType.INCOME -> com.dga.montra.domain.TransactionCategories.income
                    else -> com.dga.montra.domain.TransactionCategories.expense
                }
                val catId = newCats[category.selectedItemPosition].id
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

    private fun deleteWithUndo(tx: Transaction) {
        scope.launch {
            runCatching { repository.deleteTransaction(tx.id) }
                .onSuccess {
                    load(query.text.toString())
                    Snackbar.make(findViewById<android.view.View>(android.R.id.content), R.string.transaction_deleted, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            scope.launch {
                                runCatching { repository.addTransaction(tx) }
                                    .onSuccess { load(query.text.toString()) }
                                    .onFailure { toast(R.string.transaction_error) }
                            }
                        }.show()
                }
                .onFailure { toast(R.string.storage_error); load(query.text.toString()) }
        }
    }

    private fun confirmDelete(tx: Transaction) {
        if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) {
            toast(R.string.transfer_delete_unavailable)
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_transaction_title)
            .setMessage(R.string.delete_transaction_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteWithUndo(tx)
            }.show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
