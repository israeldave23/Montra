package com.dga.montra

import android.app.Activity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.catch
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
import com.dga.montra.domain.DashboardState
import java.util.Calendar
import java.util.Date
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


class MainActivity : Activity() {
    private var latestDashboard: DashboardState? = null
    private val settings by lazy { getSharedPreferences("lumen_settings", MODE_PRIVATE) }
    private val transactionFormState = TransactionFormState()
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
        findViewById<Button>(R.id.add_account_button).setOnClickListener { showAddAccountDialog() }
        findViewById<Button>(R.id.view_activity_button).setOnClickListener {
            startActivity(android.content.Intent(this, TransactionsActivity::class.java))
        }
        findViewById<android.view.View>(R.id.add_transaction_button).setOnClickListener { showAddTransactionDialog() }
        findViewById<android.view.View>(R.id.add_transfer_button).setOnClickListener { showTransferDialog() }
        if (intent.getBooleanExtra("open_add_transaction", false)) showAddTransactionDialog()
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).setOnClickListener {
            settings.edit().putBoolean("balance_hidden", !settings.getBoolean("balance_hidden", false)).apply()
            latestDashboard?.let { render(it) }
        }
        findViewById<View>(R.id.balance).setOnClickListener {
            startActivity(Intent(this, AccountDetailActivity::class.java))
        }
        summaryView.setOnClickListener { observeHome() }
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
        summaryView.text = if (state.accountCount == 0) getString(R.string.empty_balance) else resources.getQuantityString(R.plurals.account_count, state.accountCount, state.accountCount)
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
        val hidden = settings.getBoolean("balance_hidden", false)
        if (hidden) {
            balanceView.setText(R.string.masked_balance)
            findViewById<TextView>(R.id.monthly_summary).setText(R.string.monthly_summary_hidden)
        }
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).setImageResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        findViewById<android.widget.ImageButton>(R.id.toggle_balance).contentDescription = getString(if (hidden) R.string.show_balance else R.string.hide_balance)
        findViewById<Button>(R.id.add_account_button).setText(if (state.accountCount == 0) R.string.add_first_account else R.string.add_account)
    }


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
        settings.getString("last_currency", null)?.let { code -> currency.setSelection(Money.currencies.indexOf(code).coerceAtLeast(0)) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_account).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val trimmedName = name.text.toString().trim()
                val parsed = runCatching { Money.parse(amount.text.toString(), currency.selectedItem.toString()) }.getOrNull()
                when {
                    trimmedName.isEmpty() -> name.error = getString(R.string.account_name_hint)
                    parsed == null || parsed.minorUnits <= 0L -> amount.error = getString(R.string.amount_required)
                    else -> {
                        try {
                            scope.launch {
                                runCatching { repository.addAccount(Account(UUID.randomUUID().toString(), trimmedName, parsed)).also { settings.edit().putString("last_currency", currency.selectedItem.toString()).apply() } }
                                    .onSuccess { dialog.dismiss(); feedback(R.string.account_added) }
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
        val lastTransfer = settings.getString("last_transfer_account", null)
        fromSpinner.setSelection(accounts.indexOfFirst { it.id == lastTransfer }.coerceAtLeast(0))
        toSpinner.setSelection((fromSpinner.selectedItemPosition + 1) % accounts.size)
        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_transfer).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val from = accounts.getOrNull(fromSpinner.selectedItemPosition)
                val to = accounts.getOrNull(toSpinner.selectedItemPosition)
                if (from == null || to == null) {
                    (fromSpinner.selectedView as? TextView)?.error = getString(R.string.account_required)
                    (toSpinner.selectedView as? TextView)?.error = getString(R.string.account_required)
                    return@setOnClickListener
                }
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
                    amount.error = getString(R.string.amount_required)
                    return@setOnClickListener
                }
                scope.launch {
                    runCatching { repository.transfer(from.id, to.id, parsed, note.text.toString().trim()) }
                        .onSuccess { settings.edit().putString("last_transfer_account", from.id).apply(); dialog.dismiss(); feedback(R.string.save_feedback_transfer) }
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
        val category = content.findViewById<Spinner>(R.id.transaction_category)
        val datePickerBtn = content.findViewById<Button>(R.id.transaction_date_picker)
        val amount = content.findViewById<EditText>(R.id.transaction_amount)
        val note = content.findViewById<EditText>(R.id.transaction_note)

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        var selectedDate = Calendar.getInstance()
        datePickerBtn.text = fmt.format(Date(selectedDate.timeInMillis))

        // account spinner
        account.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            accounts.map { "${it.name} (${it.openingBalance.currencyCode})" })

        account.setSelection(accounts.indexOfFirst { it.id == settings.getString("last_account", null) }.coerceAtLeast(0))
        val lastType = settings.getString("last_type", TransactionType.EXPENSE.name)
        transactionFormState.selectType(when (lastType) {
            TransactionType.INCOME.name -> TransactionType.INCOME
            TransactionType.REFUND.name -> TransactionType.REFUND
            else -> TransactionType.EXPENSE
        })
        settings.getString("last_category", null)?.let { id ->
            if (transactionFormState.categories.any { it.id == id }) transactionFormState.selectCategory(id)
        }
        type.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            arrayOf(getString(R.string.type_expense), getString(R.string.type_income), getString(R.string.type_refund)))
        type.setSelection(when (transactionFormState.type) { TransactionType.INCOME -> 1; TransactionType.REFUND -> 2; else -> 0 })
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transactionFormState.categories)
        category.setSelection(transactionFormState.categories.indexOfFirst { it.id == transactionFormState.categoryId }.coerceAtLeast(0))
        category.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedCategory = parent?.selectedItem as? com.dga.montra.domain.Category ?: return
                if (transactionFormState.categories.any { it.id == selectedCategory.id }) {
                    transactionFormState.selectCategory(selectedCategory.id)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        type.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedType = when (position) {
                    1 -> TransactionType.INCOME
                    2 -> TransactionType.REFUND
                    else -> TransactionType.EXPENSE
                }
                val typeChanged = transactionFormState.selectType(selectedType)
                if (typeChanged) {
                    category.adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, transactionFormState.categories)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // date picker
        datePickerBtn.setOnClickListener {
            android.app.DatePickerDialog(this,
                { _, y, m, d ->
                    selectedDate.set(Calendar.YEAR, y)
                    selectedDate.set(Calendar.MONTH, m)
                    selectedDate.set(Calendar.DAY_OF_MONTH, d)
                    selectedDate.set(Calendar.HOUR_OF_DAY, 12)
                    selectedDate.set(Calendar.MINUTE, 0)
                    selectedDate.set(Calendar.SECOND, 0)
                    datePickerBtn.text = fmt.format(Date(selectedDate.timeInMillis))
                },
                selectedDate.get(Calendar.YEAR), selectedDate.get(Calendar.MONTH), selectedDate.get(Calendar.DAY_OF_MONTH)).show()
        }

        val dialog = AlertDialog.Builder(this).setTitle(R.string.add_transaction).setView(content)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = accounts.getOrNull(account.selectedItemPosition)
                if (selected == null) {
                    (account.selectedView as? TextView)?.error = getString(R.string.account_required)
                    return@setOnClickListener
                }
                val parsed = runCatching { Money.parse(amount.text.toString(), selected.openingBalance.currencyCode) }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = getString(R.string.amount_required)
                    return@setOnClickListener
                }
                val transactionType = transactionFormState.type
                val catId = transactionFormState.categoryId
                scope.launch {
                    runCatching {
                        repository.addTransaction(Transaction(accountId = selected.id, amount = parsed,
                            type = transactionType, categoryId = catId,
                            note = note.text.toString().trim(),
                            timestampMillis = selectedDate.timeInMillis))
                    }.onSuccess {
                        settings.edit().putString("last_account", selected.id).putString("last_category", catId).putString("last_type", transactionType.name).apply()
                        transactionFormState.reset()
                        dialog.dismiss()
                        feedback(R.string.transaction_saved)
                    }.onFailure { toast(R.string.transaction_error) }
                }
            }
        }
        dialog.show()
    }

    private fun feedback(message: Int) {
        com.google.android.material.snackbar.Snackbar.make(findViewById<android.view.View>(android.R.id.content), message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    private fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

}
