package com.dga.montra

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

abstract class MontraActivity : android.app.Activity() {
    private var appliedAppearance = 0

    override fun attachBaseContext(newBase: Context) {
        appliedAppearance = appearance(newBase)
        if (appliedAppearance == 0) {
            super.attachBaseContext(newBase)
            return
        }
        val config = Configuration(newBase.resources.configuration)
        val night = if (appliedAppearance == 2) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onResume() {
        super.onResume()
        if (appearance(this) != appliedAppearance) recreate()
    }

    private fun appearance(context: Context) = context.getSharedPreferences("lumen_settings", Context.MODE_PRIVATE)
        .getInt("theme_mode", 0).coerceIn(0, 2)
}

abstract class FinanceFormsActivity : MontraActivity() {
    protected val settings by lazy { getSharedPreferences("lumen_settings", MODE_PRIVATE) }
    protected val repository by lazy { RoomFinanceRepository.get(applicationContext) }
    protected val scope = MainScope()
    private val transactionFormState = TransactionFormState()
    private var activeEntry: Dialog? = null

    override fun onDestroy() {
        scope.cancel()
        activeEntry?.dismiss()
        super.onDestroy()
    }

    protected fun showAddAccountDialog() {
        val content = layoutInflater.inflate(R.layout.dialog_add_account, null)
        val name = content.findViewById<EditText>(R.id.account_name)
        val amount = content.findViewById<EditText>(R.id.opening_balance)
        val currency = content.findViewById<Spinner>(R.id.currency_picker)
        currency.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Money.currencies)
        settings.getString("last_currency", null)?.let { code -> currency.setSelection(Money.currencies.indexOf(code).coerceAtLeast(0)) }
        val dialog = entryDialog(R.string.add_account, R.string.save, content)
        dialog.setOnShowListener {
            dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
                val trimmedName = name.text.toString().trim()
                val code = currency.selectedItem.toString()
                val opening = amount.text.toString().trim().ifBlank { "0" }
                val parsed = runCatching { Money.parse(opening, code) }.getOrNull()
                when {
                    trimmedName.isEmpty() -> name.error = getString(R.string.account_name_hint)
                    parsed == null -> amount.error = getString(R.string.opening_balance_invalid)
                    else -> saveOnce(dialog, R.string.save_error) {
                        repository.addAccount(Account(UUID.randomUUID().toString(), trimmedName, parsed))
                        settings.edit().putString("last_currency", code).apply()
                        feedback(R.string.account_added)
                    }
                }
            }
        }
        dialog.show()
    }

    protected fun showTransferDialog() {
        scope.launch {
            val accounts = runCatching { repository.allAccounts().filterNot { it.isArchived } }.getOrElse { toast(R.string.storage_error); return@launch }
            val compatible = accounts.groupBy { it.openingBalance.currencyCode }.values
                .filter { it.size >= 2 }.flatten()
            if (compatible.isEmpty()) { toast(R.string.montra_compatible_accounts); return@launch }
            showTransferDialogWithAccounts(compatible)
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
        val lastTransfer = settings.getString("last_transfer_account", null)
        val initialSource = accounts.indexOfFirst { it.id == lastTransfer }.coerceAtLeast(0)
        fromSpinner.setSelection(initialSource)
        var targets = emptyList<Account>()
        fun updateTargets(source: Account) {
            targets = accounts.filter { it.id != source.id && it.openingBalance.currencyCode == source.openingBalance.currencyCode }
            toSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                targets.map { "${it.name} (${it.openingBalance.currencyCode})" })
            content.findViewById<TextView>(R.id.entry_currency).text = source.openingBalance.currencyCode
        }
        updateTargets(accounts[initialSource])
        fromSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                accounts.getOrNull(position)?.let(::updateTargets)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        val dialog = entryDialog(R.string.add_transfer, R.string.montra_save_transfer, content)
        dialog.setOnShowListener {
            dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
                val from = accounts.getOrNull(fromSpinner.selectedItemPosition)
                val to = targets.getOrNull(toSpinner.selectedItemPosition)
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
                val memo = note.text.toString().trim()
                saveOnce(dialog, R.string.transfer_error) {
                    repository.transfer(from.id, to.id, parsed, memo)
                    settings.edit().putString("last_transfer_account", from.id).apply()
                    feedback(R.string.save_feedback_transfer)
                }
            }
        }
        dialog.show()
    }

    protected fun showAddTransactionDialog() {
        scope.launch {
            val accounts = runCatching { repository.allAccounts().filterNot { it.isArchived } }.getOrElse { toast(R.string.storage_error); return@launch }
            if (accounts.isEmpty()) { showAddAccountDialog(); return@launch }
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
        val selectedDate = Calendar.getInstance()
        datePickerBtn.text = fmt.format(Date(selectedDate.timeInMillis))

        account.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            accounts.map { "${it.name} (${it.openingBalance.currencyCode})" })

        val initialAccount = accounts.indexOfFirst { it.id == settings.getString("last_account", null) }.coerceAtLeast(0)
        account.setSelection(initialAccount)
        val currencyLabel = content.findViewById<TextView>(R.id.entry_currency)
        currencyLabel.text = accounts[initialAccount].openingBalance.currencyCode
        account.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                currencyLabel.text = accounts.getOrNull(position)?.openingBalance?.currencyCode.orEmpty()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
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
        val chips = content.findViewById<com.google.android.material.chip.ChipGroup>(R.id.entry_types)
        type.visibility = android.view.View.GONE
        content.findViewById<TextView>(R.id.entry_type_label).visibility = android.view.View.GONE
        chips.visibility = android.view.View.VISIBLE
        chips.check(when (transactionFormState.type) {
            TransactionType.INCOME -> R.id.entry_type_income
            TransactionType.REFUND -> R.id.entry_type_refund
            else -> R.id.entry_type_expense
        })
        chips.setOnCheckedStateChangeListener { _, checked ->
            val selectedType = when (checked.firstOrNull()) {
                R.id.entry_type_income -> TransactionType.INCOME
                R.id.entry_type_refund -> TransactionType.REFUND
                else -> TransactionType.EXPENSE
            }
            if (transactionFormState.selectType(selectedType)) {
                category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, transactionFormState.categories)
            }
        }

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

        val dialog = entryDialog(R.string.add_transaction, R.string.montra_save_transaction, content)
        dialog.setOnShowListener {
            dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
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
                val entry = Transaction(accountId = selected.id, amount = parsed, type = transactionType,
                    categoryId = catId, note = note.text.toString().trim(), timestampMillis = selectedDate.timeInMillis)
                saveOnce(dialog, R.string.transaction_error) {
                    repository.addTransaction(entry)
                    settings.edit().putString("last_account", selected.id).putString("last_category", catId)
                        .putString("last_type", transactionType.name).apply()
                    transactionFormState.reset()
                    feedback(R.string.transaction_saved)
                }
            }
        }
        dialog.show()
    }


    protected fun entryDialog(title: Int, saveLabel: Int, content: android.view.View): Dialog {
        activeEntry?.dismiss()
        val dialog = Dialog(this, R.style.MontraEntryDialog)
        activeEntry = dialog
        dialog.setContentView(R.layout.view_entry_shell)
        dialog.findViewById<TextView>(R.id.entry_title).setText(title)
        dialog.findViewById<Button>(R.id.entry_save).setText(saveLabel)
        dialog.findViewById<android.widget.ImageButton>(R.id.entry_back).setOnClickListener { dialog.dismiss() }
        dialog.findViewById<android.widget.FrameLayout>(R.id.entry_content).addView(content)
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        return dialog
    }

    protected fun saveOnce(dialog: Dialog, error: Int, save: suspend () -> Unit) {
        val button = dialog.findViewById<Button>(R.id.entry_save)
        if (!button.isEnabled) return
        button.isEnabled = false
        scope.launch {
            try {
                save()
                dialog.dismiss()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(error)
            } finally {
                button.isEnabled = true
            }
        }
    }

    private fun feedback(message: Int) {
        com.google.android.material.snackbar.Snackbar.make(findViewById<android.view.View>(android.R.id.content), message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT).show()
    }

    protected fun toast(message: Int) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
