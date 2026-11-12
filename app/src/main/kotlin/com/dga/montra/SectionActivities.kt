package com.dga.montra

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.TransactionEntity
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.data.toDomain
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.TransactionType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

abstract class SectionActivity(
    val tab: Int
) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
    }
}


class BudgetActivity : SectionActivity(2) {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()
    private lateinit var budgetsRow: ListView
    private lateinit var budgetsCreate: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_budget)
        findViewById<TextView>(R.id.budget_title).setText(R.string.budget_title)
        findViewById<TextView>(R.id.budget_subtitle).setText(R.string.budget_subtitle)
        budgetsRow = findViewById(R.id.budgets_list)
        budgetsCreate = findViewById(R.id.budget_action)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
        observe()
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            try {
                val budgets = repo.budgets()
                val month = YearMonth.now().toString()
                val spent = budgets.associateBy({ it.categoryId }) { repo.spentForCategoryMonth(it.categoryId, month) }.toMap()
                budgetsRow.adapter = BudgetAdapter(this@BudgetActivity, budgets, spent)
                budgetsCreate.visibility = View.VISIBLE
                budgetsCreate.setText(R.string.create_budget)
                budgetsCreate.setOnClickListener { showCreateBudget() }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this@BudgetActivity, R.string.planning_error, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showCreateBudget() {
        val categories = TransactionCategories.expense
        val names = categories.map { it.name }
        var selectedCategory = categories.firstOrNull()?.id ?: ""
        AlertDialog.Builder(this)
            .setTitle(R.string.create_budget)
            .setSingleChoiceItems(names.toTypedArray(), 0) { _, selected ->
                selectedCategory = categories[selected].id
            }
            .setPositiveButton(R.string.save) { _, _ ->
                val input = EditText(this@BudgetActivity)
                input.hint = getString(R.string.budget_limit)
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                AlertDialog.Builder(this@BudgetActivity)
                    .setTitle(R.string.budget_limit)
                    .setView(input)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val value = input.text.toString().toFloatOrNullOrZero()
                        if (value <= 0f) {
                            input.setError(getString(R.string.amount_required))
                            return@setPositiveButton
                        }
                        scope.launch {
                            try {
                                repo.createBudget(selectedCategory, Money((value * 100).toLong(), "PHP"))
                                observe()
                            } catch (e: Exception) {
                                input.setError(getString(R.string.planning_error))
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class BudgetAdapter(val activity: Activity, val budgets: List<BudgetEntity>, val spent: Map<String, Long>) : BaseAdapter() {
        override fun getCount() = budgets.size
        override fun getItem(position: Int) = budgets[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = budgets[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_budget_row, parent, false)
            val categoryView = row.findViewById<TextView>(R.id.budget_category)
            val amountView = row.findViewById<TextView>(R.id.budget_amount)
            val bar = row.findViewById<ProgressBar>(R.id.budget_progress)
            categoryView.text = TransactionCategories.expense.firstOrNull { it.id == b.categoryId }?.name ?: b.categoryId.replaceFirstChar { it.uppercase() }
            val limitMoney = Money(b.limitMinorUnits, b.currencyCode)
            val limitMinor = b.limitMinorUnits
            val categorySpent = spent[b.categoryId] ?: 0L
            amountView.text = activity.getString(R.string.progress_amount, Money(categorySpent, b.currencyCode).format(), limitMoney.format())
            bar.max = 100
            bar.progress = if (limitMinor == 0L) 0 else (minOf(categorySpent, limitMinor) * 100 / limitMinor).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(if (categorySpent > limitMinor) R.color.lumen_error else R.color.lumen_accent))
            return row
        }
    }
}

class AccountDetailActivity : Activity() {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()
    private lateinit var accountId: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_account_detail)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 0)
        scope.launch {
            val accounts = repo.allAccounts()
            val id = intent.getStringExtra("ACCOUNT_ID") ?: accounts.firstOrNull()?.id
            if (id == null) {
                runOnUiThread { android.widget.Toast.makeText(this@AccountDetailActivity, R.string.account_not_found, android.widget.Toast.LENGTH_LONG).show() }
                return@launch
            }
            accountId = id
            val account = accounts.firstOrNull { it.id == id } ?: run {
                runOnUiThread { android.widget.Toast.makeText(this@AccountDetailActivity, R.string.account_not_found, android.widget.Toast.LENGTH_LONG).show() }
                return@launch
            }
            findViewById<TextView>(R.id.account_name).text = account.name
            findViewById<TextView>(R.id.account_balance).text = account.openingBalance.format()
            val list = findViewById<ListView>(R.id.account_transactions)
            repo.observeAccountTransactions(accountId).collect { txs ->
                list.adapter = AccountTransactionAdapter(this@AccountDetailActivity, txs.map { it.toDomain() })
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private inner class AccountTransactionAdapter(val activity: Activity, val items: List<Transaction>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = items[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_transaction_row, parent, false)
            row.findViewById<TextView>(R.id.transaction_category).text = TransactionCategories.expense.firstOrNull { it.id == t.categoryId }?.name
                ?: TransactionCategories.income.firstOrNull { it.id == t.categoryId }?.name
                ?: t.categoryId.replaceFirstChar { it.uppercase() }
            val date = java.time.Instant.ofEpochMilli(t.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            row.findViewById<TextView>(R.id.transaction_date).text = date
            row.findViewById<TextView>(R.id.transaction_note).text = t.note.takeIf { it.isNotEmpty() } ?: date
            val amountView = row.findViewById<TextView>(R.id.transaction_amount)
            amountView.text = when {
                t.type == TransactionType.INCOME || t.type == TransactionType.REFUND -> "+${t.amount.format()}"
                else -> "-${t.amount.format()}"
            }
            amountView.setTextColor(activity.getColor(if (t.type == TransactionType.INCOME || t.type == TransactionType.REFUND) R.color.lumen_success else R.color.lumen_error))
            return row
        }
    }
}

class InsightsActivity : SectionActivity(4) {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()
    private lateinit var insightsRow: ListView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_insights)
        findViewById<TextView>(R.id.insights_title).setText(R.string.insights_title)
        findViewById<TextView>(R.id.insights_subtitle).setText(R.string.insights_subtitle)
        insightsRow = findViewById(R.id.insights_list)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
        observe()
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            try {
                val ym = YearMonth.now()
                val start = ym.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = ym.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val txs = repo.transactions().map { it.toDomain() }.filter {
                    it.type == TransactionType.EXPENSE && it.timestampMillis in start..end
                }
                val byCategory = txs.groupBy { it.categoryId }
                val spentByCategory = byCategory.mapValues { (_, items) -> items.fold(0L) { acc, item -> acc + item.amount.minorUnits } }
                val maxSpent = spentByCategory.values.maxOrNull() ?: 0L
                insightsRow.adapter = InsightsAdapter(this@InsightsActivity, spentByCategory, maxSpent)
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this@InsightsActivity, R.string.planning_error, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private inner class InsightsAdapter(val activity: Activity, val spentByCategory: Map<String, Long>, val maxSpent: Long) : BaseAdapter() {
        private val entries = spentByCategory.entries.sortedByDescending { it.value }

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val (categoryId, spent) = entries[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_insight_row, parent, false)
            row.findViewById<TextView>(R.id.insight_category).text = TransactionCategories.expense.firstOrNull { it.id == categoryId }?.name
                ?: categoryId.replaceFirstChar { it.uppercase() }
            row.findViewById<TextView>(R.id.insight_amount).text = Money(spent, "PHP").format()
            val bar = row.findViewById<ProgressBar>(R.id.insight_progress)
            bar.max = 100
            bar.progress = if (maxSpent == 0L) 0 else (spent * 100 / maxSpent).coerceIn(0, 100).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.lumen_accent))
            return row
        }
    }
}

fun String.toFloatOrNullOrZero(): Float = toFloatOrNull() ?: 0f
