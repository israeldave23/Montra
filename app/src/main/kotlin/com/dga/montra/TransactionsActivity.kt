package com.dga.montra

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.ads.Ads
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.databinding.ActivityTransactionsBinding
import com.dga.montra.databinding.DialogAddTransactionBinding
import com.dga.montra.ui.TransactionsViewModel
import com.dga.montra.ui.UiState
import com.dga.montra.ui.viewModelFactory
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionFilter
import com.dga.montra.domain.TransactionType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class TransactionsActivity : AppActivity() {
    private lateinit var binding: ActivityTransactionsBinding

    private lateinit var repository: RoomFinanceRepository
    private lateinit var list: RecyclerView
    private lateinit var emptyView: android.widget.TextView
    private lateinit var adapter: TransactionListAdapter
    private var activeTouchHelper: ItemTouchHelper? = null
    private lateinit var query: EditText
    private val viewModel: TransactionsViewModel by viewModels {
        viewModelFactory { TransactionsViewModel(RoomFinanceRepository.get(applicationContext)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransactionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ads.attachBanner(binding.root)
        bindBottomNavigation(binding.root, 1)
        repository = RoomFinanceRepository.get(this)
        binding.transactionsBack.setOnClickListener { finish() }
        list = binding.transactionList
        list.layoutManager = LinearLayoutManager(this)
        adapter = TransactionListAdapter(
            onClick = { tx ->
                if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) {
                    showTransactionDetails(tx)
                } else {
                    showEditTransactionDialog(tx)
                }
            },
            onDelete = { tx -> confirmDelete(tx) }
        )
        list.adapter = adapter
        emptyView = binding.transactionEmpty
        activeTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                val tx = adapter.transactionAt(viewHolder.bindingAdapterPosition) ?: return 0
                return if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) 0 else super.getSwipeDirs(recyclerView, viewHolder)
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                adapter.transactionAt(position)?.let { deleteWithUndo(it) }
            }
        }).also { it.attachToRecyclerView(list) }
        query = binding.transactionSearch
        binding.transactionFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            viewModel.selectFilter(when (checkedIds.firstOrNull()) {
                R.id.filter_income -> TransactionFilter.INCOME
                R.id.filter_expense -> TransactionFilter.EXPENSE
                R.id.filter_transfer -> TransactionFilter.TRANSFER
                else -> TransactionFilter.ALL
            })
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { viewModel.search(s.toString()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        collectState()
    }

    /**
     * Renders what the view model holds. Collection stops when the screen is not started, so nothing
     * queries the database behind a screen the user cannot see.
     */
    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is UiState.Ready -> renderTransactions(state.value)
                        UiState.Failed -> toast(R.string.storage_error)
                        UiState.Loading -> Unit
                    }
                }
            }
        }
    }

    private fun renderTransactions(transactions: List<Transaction>) {
        emptyView.visibility = if (transactions.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (transactions.isEmpty()) View.GONE else View.VISIBLE
        adapter.submitList(TransactionListAdapter.itemsFor(transactions))
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
        val builder = MaterialAlertDialogBuilder(this).setTitle(R.string.transaction_details).setMessage(lines)
            .setPositiveButton(android.R.string.ok, null)
        if (tx.type != com.dga.montra.domain.TransactionType.TRANSFER_OUT && tx.type != com.dga.montra.domain.TransactionType.TRANSFER_IN) {
            builder.setNeutralButton(R.string.edit) { _, _ -> showEditTransactionDialog(tx) }
        }
        builder.show()
    }

    private fun showEditTransactionDialog(tx: com.dga.montra.domain.Transaction) {
        lifecycleScope.launch {
            val accounts = runCatching { repository.allAccounts() }.getOrElse { toast(R.string.storage_error); return@launch }
            if (accounts.isEmpty()) { toast(R.string.empty_balance); return@launch }
            showEditTransactionDialogWithAccounts(tx, accounts)
        }
    }

    private fun showEditTransactionDialogWithAccounts(tx: com.dga.montra.domain.Transaction, accounts: List<com.dga.montra.domain.Account>) {
        val content = DialogAddTransactionBinding.inflate(layoutInflater)
        val account = content.transactionAccount
        val type = content.transactionType
        val category = content.transactionCategory
        val datePickerBtn = content.transactionDatePicker
        val amount = content.transactionAmount
        val note = content.transactionNote

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

        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.edit_transaction).setView(content.root)
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
                lifecycleScope.launch {
                    runCatching {
                        repository.updateTransaction(tx.id, catId, note.text.toString().trim(), newType, cal.timeInMillis)
                    }.onSuccess {
                        dialog.dismiss()
                        toast(R.string.transaction_updated, short = true)
                    }.onFailure { toast(R.string.transaction_error) }
                }
            }
        }
        dialog.show()
    }

    private fun deleteWithUndo(tx: Transaction) {
        lifecycleScope.launch {
            runCatching { repository.deleteTransaction(tx.id) }
                .onSuccess {
                    Snackbar.make(binding.root, R.string.transaction_deleted, Snackbar.LENGTH_LONG)
                        .setAction(R.string.undo) {
                            lifecycleScope.launch {
                                runCatching { repository.addTransaction(tx) }
                                    .onFailure { toast(R.string.transaction_error) }
                            }
                        }.show()
                }
                .onFailure { toast(R.string.storage_error) }
        }
    }

    private fun confirmDelete(tx: Transaction) {
        if (tx.type == TransactionType.TRANSFER_IN || tx.type == TransactionType.TRANSFER_OUT) {
            toast(R.string.transfer_delete_unavailable)
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_transaction_title)
            .setMessage(R.string.delete_transaction_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleteWithUndo(tx)
            }.show()
    }

}
