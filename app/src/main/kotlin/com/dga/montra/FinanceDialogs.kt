package com.dga.montra

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.data.Settings
import com.dga.montra.domain.Account
import com.dga.montra.domain.Category
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The three dialogs that create money: an account, a transaction, a transfer.
 *
 * The home screen and an account's detail screen both open them, so the validation, defaults and
 * the rule that archived accounts cannot receive new activity live here, once.
 */
internal object FinanceDialogs {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private fun settings(activity: Activity) = Settings(activity)

    private fun toast(activity: Activity, message: Int) =
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()

    private fun feedback(activity: Activity, message: Int) = Snackbar.make(
        activity.findViewById(android.R.id.content),
        message,
        Snackbar.LENGTH_SHORT
    ).show()

    // ---------------------------------------------------------------- accounts

    fun addAccount(activity: Activity, repository: RoomFinanceRepository, onSaved: () -> Unit = {}) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_add_account, null)
        val name = content.findViewById<EditText>(R.id.account_name)
        val amount = content.findViewById<EditText>(R.id.opening_balance)
        val currency = content.findViewById<Spinner>(R.id.currency_picker)
        val prefs = settings(activity)
        currency.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            Money.currencies
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        prefs.lastCurrency?.let { code ->
            currency.setSelection(Money.currencies.indexOf(code).coerceAtLeast(0))
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.add_account)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val trimmedName = name.text.toString().trim()
                val code = currency.selectedItem?.toString() ?: Money.currencies.first()
                val raw = amount.text.toString().trim()
                // A new account may legitimately start at zero, so blank means 0.00, not an error.
                val parsed = if (raw.isEmpty()) Money(0L, code)
                else runCatching { Money.parse(raw, code) }.getOrNull()
                when {
                    trimmedName.isEmpty() -> name.error = activity.getString(R.string.account_name_required)
                    trimmedName.length > ACCOUNT_NAME_LIMIT ->
                        name.error = activity.getString(R.string.account_name_too_long)
                    parsed == null || parsed.minorUnits < 0L ->
                        amount.error = activity.getString(R.string.opening_balance_invalid)
                    else -> scope().launch {
                        runCatching {
                            repository.addAccount(
                                Account(UUID.randomUUID().toString(), trimmedName, parsed)
                            )
                        }.onSuccess {
                            prefs.rememberCurrency(code)
                            dialog.dismiss()
                            feedback(activity, R.string.account_added)
                            onSaved()
                        }.onFailure {
                            toast(activity, R.string.save_error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    // ------------------------------------------------------------ transactions

    fun addTransaction(
        activity: Activity,
        repository: RoomFinanceRepository,
        preselectAccountId: String? = null,
        onSaved: () -> Unit = {}
    ) {
        scope().launch {
            val accounts = runCatching { repository.allAccounts() }
                .getOrElse { toast(activity, R.string.storage_error); return@launch }
                .filterNot { it.isArchived }
            if (accounts.isEmpty()) {
                toast(activity, R.string.transaction_needs_account)
                return@launch
            }
            showTransaction(activity, repository, accounts, preselectAccountId, onSaved)
        }
    }

    private fun showTransaction(
        activity: Activity,
        repository: RoomFinanceRepository,
        accounts: List<Account>,
        preselectAccountId: String?,
        onSaved: () -> Unit
    ) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_add_transaction, null)
        val account = content.findViewById<Spinner>(R.id.transaction_account)
        val type = content.findViewById<Spinner>(R.id.transaction_type)
        val category = content.findViewById<Spinner>(R.id.transaction_category)
        val datePickerBtn = content.findViewById<Button>(R.id.transaction_date_picker)
        val amount = content.findViewById<EditText>(R.id.transaction_amount)
        val note = content.findViewById<EditText>(R.id.transaction_note)
        val prefs = settings(activity)
        val form = TransactionFormState()

        val fmt = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        val selectedDate = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        datePickerBtn.text = fmt.format(Date(selectedDate.timeInMillis))

        account.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            accounts.map { "${it.name} (${it.openingBalance.currencyCode})" }
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        val preselected = preselectAccountId ?: prefs.lastAccountId
        account.setSelection(
            accounts.indexOfFirst { it.id == preselected }.coerceAtLeast(0)
        )

        val lastType = prefs.lastTransactionType
        form.selectType(
            when (lastType) {
                TransactionType.INCOME.name -> TransactionType.INCOME
                TransactionType.REFUND.name -> TransactionType.REFUND
                else -> TransactionType.EXPENSE
            }
        )
        prefs.lastCategoryId?.let { id ->
            if (form.categories.any { it.id == id }) form.selectCategory(id)
        }
        type.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            arrayOf(
                activity.getString(R.string.type_expense),
                activity.getString(R.string.type_income),
                activity.getString(R.string.type_refund)
            )
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        type.setSelection(
            when (form.type) {
                TransactionType.INCOME -> 1
                TransactionType.REFUND -> 2
                else -> 0
            }
        )
        category.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_item,
            form.categories
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        category.setSelection(
            form.categories.indexOfFirst { it.id == form.categoryId }.coerceAtLeast(0)
        )

        category.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = parent?.selectedItem as? Category ?: return
                if (form.categories.any { it.id == selected.id }) form.selectCategory(selected.id)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        type.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selectedType = when (position) {
                    1 -> TransactionType.INCOME
                    2 -> TransactionType.REFUND
                    else -> TransactionType.EXPENSE
                }
                if (form.selectType(selectedType)) {
                    category.adapter = ArrayAdapter(
                        activity,
                        android.R.layout.simple_spinner_item,
                        form.categories
                    ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        datePickerBtn.setOnClickListener {
            DatePickerDialog(
                activity,
                { _, year, month, day ->
                    selectedDate.set(Calendar.YEAR, year)
                    selectedDate.set(Calendar.MONTH, month)
                    selectedDate.set(Calendar.DAY_OF_MONTH, day)
                    selectedDate.set(Calendar.HOUR_OF_DAY, 12)
                    selectedDate.set(Calendar.MINUTE, 0)
                    selectedDate.set(Calendar.SECOND, 0)
                    datePickerBtn.text = fmt.format(Date(selectedDate.timeInMillis))
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.add_transaction)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val selected = accounts.getOrNull(account.selectedItemPosition)
                if (selected == null) {
                    (account.selectedView as? TextView)?.error =
                        activity.getString(R.string.account_required)
                    return@setOnClickListener
                }
                val parsed = runCatching {
                    Money.parse(amount.text.toString(), selected.openingBalance.currencyCode)
                }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = activity.getString(R.string.amount_required)
                    return@setOnClickListener
                }
                val transactionType = form.type
                val categoryId = form.categoryId
                confirm.isEnabled = false
                scope().launch {
                    runCatching {
                        repository.addTransaction(
                            Transaction(
                                accountId = selected.id,
                                amount = parsed,
                                type = transactionType,
                                categoryId = categoryId,
                                note = note.text.toString().trim(),
                                timestampMillis = selectedDate.timeInMillis
                            )
                        )
                    }.onSuccess {
                        prefs.rememberTransactionForm(selected.id, categoryId, transactionType.name)
                        form.reset()
                        dialog.dismiss()
                        feedback(activity, R.string.transaction_saved)
                        onSaved()
                    }.onFailure {
                        confirm.isEnabled = true
                        toast(activity, R.string.transaction_error)
                    }
                }
            }
        }
        dialog.show()
    }

    // --------------------------------------------------------------- transfers

    fun transfer(
        activity: Activity,
        repository: RoomFinanceRepository,
        preselectFromAccountId: String? = null,
        onSaved: () -> Unit = {}
    ) {
        scope().launch {
            val accounts = runCatching { repository.allAccounts() }
                .getOrElse { toast(activity, R.string.storage_error); return@launch }
                .filterNot { it.isArchived }
            if (accounts.size < 2) {
                toast(activity, R.string.transfer_needs_two_accounts)
                return@launch
            }
            showTransfer(activity, repository, accounts, preselectFromAccountId, onSaved)
        }
    }

    private fun showTransfer(
        activity: Activity,
        repository: RoomFinanceRepository,
        accounts: List<Account>,
        preselectFromAccountId: String?,
        onSaved: () -> Unit
    ) {
        val content = activity.layoutInflater.inflate(R.layout.dialog_add_transfer, null)
        val fromSpinner = content.findViewById<Spinner>(R.id.transfer_from_account)
        val toSpinner = content.findViewById<Spinner>(R.id.transfer_to_account)
        val amount = content.findViewById<EditText>(R.id.transfer_amount)
        val note = content.findViewById<EditText>(R.id.transfer_note)
        val prefs = settings(activity)
        val labels = accounts.map { "${it.name} (${it.openingBalance.currencyCode})" }

        fromSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        toSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val preferred = preselectFromAccountId ?: prefs.lastTransferAccountId
        fromSpinner.setSelection(accounts.indexOfFirst { it.id == preferred }.coerceAtLeast(0))
        toSpinner.setSelection((fromSpinner.selectedItemPosition + 1) % accounts.size)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.add_transfer)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val from = accounts.getOrNull(fromSpinner.selectedItemPosition)
                val to = accounts.getOrNull(toSpinner.selectedItemPosition)
                if (from == null || to == null) {
                    (fromSpinner.selectedView as? TextView)?.error =
                        activity.getString(R.string.account_required)
                    (toSpinner.selectedView as? TextView)?.error =
                        activity.getString(R.string.account_required)
                    return@setOnClickListener
                }
                if (from.id == to.id) {
                    toast(activity, R.string.transfer_same_account)
                    return@setOnClickListener
                }
                if (from.openingBalance.currencyCode != to.openingBalance.currencyCode) {
                    toast(activity, R.string.transfer_currency_mismatch)
                    return@setOnClickListener
                }
                val parsed = runCatching {
                    Money.parse(amount.text.toString(), from.openingBalance.currencyCode)
                }.getOrNull()
                if (parsed == null || parsed.minorUnits <= 0L) {
                    amount.error = activity.getString(R.string.amount_required)
                    return@setOnClickListener
                }
                confirm.isEnabled = false
                scope().launch {
                    runCatching {
                        repository.transfer(from.id, to.id, parsed, note.text.toString().trim())
                    }.onSuccess {
                        prefs.lastTransferAccountId = from.id
                        dialog.dismiss()
                        feedback(activity, R.string.save_feedback_transfer)
                        onSaved()
                    }.onFailure {
                        confirm.isEnabled = true
                        toast(activity, R.string.transfer_error)
                    }
                }
            }
        }
        dialog.show()
    }

    private const val ACCOUNT_NAME_LIMIT = 100
}
