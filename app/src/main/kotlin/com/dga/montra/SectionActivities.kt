package com.dga.montra

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.TransactionEntity
import com.dga.montra.db.GoalEntity
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.data.toDomain
import com.dga.montra.domain.Account
import com.dga.montra.domain.FinancialCalculations
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.TransactionType
import com.dga.montra.domain.signedMinorUnits
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId

abstract class SectionActivity(
    val tab: Int
) : FinanceFormsActivity()


class BudgetActivity : SectionActivity(2) {
    private lateinit var budgetsRow: ListView
    private lateinit var budgetsCreate: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_budget)
        findViewById<TextView>(R.id.budget_subtitle).text = monthLabel()
        bindPlanSections(goalsSelected = false)
        findViewById<View>(R.id.plan_add).setOnClickListener { showCreateBudget() }
        budgetsRow = findViewById(R.id.budgets_list)
        budgetsCreate = findViewById(R.id.budget_action)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    private fun observe() {
        scope.launch {
            try {
                val budgets = repository.budgets()
                val month = YearMonth.now().toString()
                val spent = budgets.associateBy({ it.id }) { repository.spentForCategoryMonth(it.categoryId, it.currencyCode, month) }.toMap()
                findViewById<View>(R.id.planning_empty).visibility = if (budgets.isEmpty()) View.VISIBLE else View.GONE
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
        val code = settings.getString("default_currency", "PHP") ?: "PHP"
        val fields = layoutInflater.inflate(R.layout.dialog_plan_budget, null)
        val category = fields.findViewById<android.widget.Spinner>(R.id.plan_category)
        category.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, categories)
        val amount = fields.findViewById<EditText>(R.id.plan_amount)
        fields.findViewById<TextView>(R.id.plan_form_currency).text = code
        val dialog = entryDialog(R.string.create_budget, R.string.save, fields)
        dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
            val limit = runCatching { Money.parse(amount.text.toString().trim(), code) }.getOrNull()
            if (limit == null || limit.minorUnits <= 0) {
                amount.error = getString(R.string.amount_required)
                return@setOnClickListener
            }
            val selected = categories.getOrNull(category.selectedItemPosition) ?: return@setOnClickListener
            saveOnce(dialog, R.string.planning_error) {
                repository.createBudget(selected.id, limit)
                observe()
            }
        }
        dialog.show()
    }

    private inner class BudgetAdapter(val activity: Activity, val budgets: List<BudgetEntity>, val spent: Map<String, Long>) : BaseAdapter() {
        override fun getCount() = budgets.size
        override fun getItem(position: Int) = budgets[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = budgets[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_budget_row, parent, false)
            val iconView = row.findViewById<android.widget.ImageView>(R.id.budget_icon)
            iconView?.setImageResource(categoryIconDrawable(b.categoryId))
            iconView?.imageTintList = android.content.res.ColorStateList.valueOf(categoryColor(b.categoryId))
            iconView?.backgroundTintList = android.content.res.ColorStateList.valueOf(categoryBgColor(b.categoryId))
            val categoryView = row.findViewById<TextView>(R.id.budget_category)
            val amountView = row.findViewById<TextView>(R.id.budget_amount)
            val percentView = row.findViewById<TextView>(R.id.budget_percent)
            val bar = row.findViewById<ProgressBar>(R.id.budget_progress)
            categoryView.text = TransactionCategories.expense.firstOrNull { it.id == b.categoryId }?.name ?: b.categoryId.replaceFirstChar { it.uppercase() }
            val limitMoney = Money(b.limitMinorUnits, b.currencyCode)
            val limitMinor = b.limitMinorUnits
            val categorySpent = spent[b.id] ?: 0L
            amountView.text = activity.getString(R.string.progress_amount, Money(categorySpent, b.currencyCode).format(), limitMoney.format())
            val pct = if (limitMinor == 0L) 0 else (categorySpent.toDouble() / limitMinor * 100).toInt()
            percentView?.text = if (categorySpent > limitMinor) activity.getString(R.string.over_budget) else "$pct%"
            percentView?.setTextColor(activity.getColor(if (categorySpent > limitMinor) R.color.lumen_error else R.color.lumen_muted))
            bar.max = 100
            bar.progress = pct.coerceIn(0, 100)
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(if (categorySpent > limitMinor) R.color.lumen_error else R.color.lumen_accent))
            return row
        }
    }
}

class GoalsActivity : SectionActivity(3) {
    private lateinit var goalsRow: ListView
    private lateinit var goalsCreate: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_goals)
        findViewById<TextView>(R.id.goal_subtitle).setText(R.string.goals_title)
        bindPlanSections(goalsSelected = true)
        findViewById<View>(R.id.plan_add).setOnClickListener { showGoalForm() }
        goalsRow = findViewById(R.id.goals_list)
        goalsCreate = findViewById(R.id.goal_action)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    private fun observe() {
        scope.launch {
            try {
                val goals = repository.goals()
                findViewById<View>(R.id.planning_empty).visibility = if (goals.isEmpty()) View.VISIBLE else View.GONE
                goalsRow.adapter = GoalAdapter(this@GoalsActivity, goals)
                goalsCreate.visibility = View.VISIBLE
                goalsCreate.setText(R.string.new_goal)
                goalsCreate.setOnClickListener { showGoalForm() }
            } catch (e: Exception) {
                runOnUiThread { android.widget.Toast.makeText(this@GoalsActivity, R.string.planning_error, android.widget.Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun showGoalForm() {
        val code = settings.getString("default_currency", "PHP") ?: "PHP"
        val fields = layoutInflater.inflate(R.layout.dialog_plan_goal, null)
        fields.findViewById<TextView>(R.id.plan_form_currency).text = code
        val nameField = fields.findViewById<EditText>(R.id.plan_goal_name)
        val targetField = fields.findViewById<EditText>(R.id.plan_amount)
        val savedField = fields.findViewById<EditText>(R.id.plan_already_saved)
        val dialog = entryDialog(R.string.new_goal, R.string.save, fields)
        dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
            val name = nameField.text.toString().trim()
            val target = runCatching { Money.parse(targetField.text.toString().trim(), code) }.getOrNull()
            val saved = runCatching { Money.parse(savedField.text.toString().trim().ifBlank { "0" }, code) }.getOrNull()
            when {
                name.isEmpty() || name.length > 100 -> nameField.error = getString(R.string.name_required)
                target == null || target.minorUnits <= 0 -> targetField.error = getString(R.string.amount_required)
                saved == null || saved.minorUnits < 0 -> savedField.error = getString(R.string.saved_invalid)
                else -> saveOnce(dialog, R.string.planning_error) {
                    repository.createGoal(name, target, saved)
                    observe()
                }
            }
        }
        dialog.show()
    }

    private inner class GoalAdapter(val activity: Activity, val goals: List<GoalEntity>) : BaseAdapter() {
        override fun getCount() = goals.size
        override fun getItem(position: Int) = goals[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val g = goals[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_goal_row, parent, false)
            val iconView = row.findViewById<android.widget.ImageView>(R.id.goal_icon)
            iconView?.setImageResource(R.drawable.ic_nav_goals)
            iconView?.imageTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.on_primary))
            iconView?.backgroundTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.lumen_accent))
            row.findViewById<TextView>(R.id.goal_name).text = g.name
            val target = Money(g.targetMinorUnits, g.currencyCode)
            val saved = Money(g.savedMinorUnits, g.currencyCode)
            row.findViewById<TextView>(R.id.goal_amount).text = activity.getString(R.string.progress_amount, saved.format(), target.format())
            val progress = if (target.minorUnits == 0L) 0 else (saved.minorUnits.toDouble() / target.minorUnits * 100).toInt()
            val percentView = row.findViewById<TextView>(R.id.goal_percent)
            percentView?.text = "${progress.coerceIn(0, 100)}%"
            val bar = row.findViewById<ProgressBar>(R.id.goal_progress)
            bar.max = 100
            bar.progress = progress.coerceIn(0, 100).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.lumen_accent))
            row.setOnClickListener { showAddFunds(g) }
            return row
        }
    }

    private fun showAddFunds(goal: GoalEntity) {
        val fields = layoutInflater.inflate(R.layout.dialog_plan_funds, null)
        fields.findViewById<TextView>(R.id.plan_form_currency).text = goal.currencyCode
        val amount = fields.findViewById<EditText>(R.id.plan_amount)
        val dialog = entryDialog(R.string.add_funds, R.string.add_funds, fields)
        dialog.findViewById<TextView>(R.id.entry_title).text = goal.name
        dialog.findViewById<Button>(R.id.entry_save).setOnClickListener {
            val parsed = runCatching { Money.parse(amount.text.toString().trim(), goal.currencyCode) }.getOrNull()
            if (parsed == null || parsed.minorUnits <= 0) {
                amount.error = getString(R.string.amount_required)
                return@setOnClickListener
            }
            saveOnce(dialog, R.string.planning_error) {
                repository.addGoalFunds(goal.id, parsed)
                observe()
            }
        }
        dialog.show()
    }
}

class AccountDetailActivity : MontraActivity() {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()
    private lateinit var accountId: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_account_detail)
        findViewById<android.view.View>(R.id.account_detail_back)?.setOnClickListener { finish() }
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
            val list = findViewById<ListView>(R.id.account_transactions)
            repo.observeAccountTransactions(accountId).collect { txs ->
                val domainTxs = txs.map { it.toDomain() }
                val currentBalanceMinor = domainTxs.fold(account.openingBalance.minorUnits) { acc, tx ->
                    Math.addExact(acc, tx.signedMinorUnits())
                }
                findViewById<TextView>(R.id.account_balance).text = Money(currentBalanceMinor, account.openingBalance.currencyCode).format()
                list.adapter = AccountTransactionAdapter(this@AccountDetailActivity, domainTxs)
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
            val iconView = row.findViewById<android.widget.ImageView>(R.id.transaction_category_icon)
            iconView?.setImageResource(categoryIconDrawable(t.categoryId))
            iconView?.imageTintList = android.content.res.ColorStateList.valueOf(categoryColor(t.categoryId))
            iconView?.backgroundTintList = android.content.res.ColorStateList.valueOf(categoryBgColor(t.categoryId))
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
    private lateinit var insightsRow: ListView

    private var currency: String? = null
    private var monthlyTransactions = emptyList<Transaction>()
    private var availableCurrencies = emptyList<String>()

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_insights)
        insightsRow = findViewById(R.id.insights_list)
        currency = state?.getString("insights_currency")
        findViewById<View>(R.id.insights_activity).setOnClickListener {
            startActivity(Intent(this, TransactionsActivity::class.java))
        }
        findViewById<Button>(R.id.insights_currency).setOnClickListener {
            if (availableCurrencies.size > 1) {
                currency = availableCurrencies[(availableCurrencies.indexOf(currency) + 1) % availableCurrencies.size]
                renderMonth()
            }
        }
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("insights_currency", currency)
        super.onSaveInstanceState(outState)
    }

    private fun observe() {
        scope.launch {
            try {
                val ym = YearMonth.now()
                val zone = ZoneId.systemDefault()
                val start = ym.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end = ym.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                monthlyTransactions = repository.allTransactions().filter { it.timestampMillis in start until end }
                availableCurrencies = (repository.allAccounts().map { it.openingBalance.currencyCode } +
                    monthlyTransactions.map { it.amount.currencyCode }).distinct().sorted()
                findViewById<TextView>(R.id.insights_month).text = monthLabel(ym)
                renderMonth()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.widget.Toast.makeText(this@InsightsActivity, R.string.planning_error, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderMonth() {
        val prefs = getSharedPreferences("lumen_settings", MODE_PRIVATE)
        val preferred = prefs.getString("default_currency", "PHP") ?: "PHP"
        val code = currency?.takeIf { it in availableCurrencies }
            ?: preferred.takeIf { it in availableCurrencies } ?: availableCurrencies.firstOrNull() ?: preferred
        currency = code
        findViewById<Button>(R.id.insights_currency).apply {
            text = code
            isClickable = availableCurrencies.size > 1
        }
        val (income, spent) = FinancialCalculations.monthlySummary(monthlyTransactions, YearMonth.now(), ZoneId.systemDefault())[code] ?: (0L to 0L)
        val net = Math.subtractExact(income, spent)
        findViewById<TextView>(R.id.monthly_spent).text = Money(spent, code).format()
        findViewById<TextView>(R.id.monthly_income).text = Money(income, code).format()
        findViewById<TextView>(R.id.insights_net).apply {
            text = Money(net, code).format()
            setTextColor(getColor(if (net < 0) R.color.lumen_negative else R.color.lumen_positive))
        }
        val categories = FinancialCalculations.categorySpending(monthlyTransactions, code)
        findViewById<View>(R.id.insights_empty).visibility = if (categories.isEmpty()) View.VISIBLE else View.GONE
        insightsRow.adapter = InsightsAdapter(this, categories, spent, code)
    }

    private inner class InsightsAdapter(
        val activity: Activity,
        val spentByCategory: Map<String, Long>,
        val totalSpent: Long,
        val currencyCode: String
    ) : BaseAdapter() {
        private val entries = spentByCategory.entries.sortedByDescending { it.value }

        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val (categoryId, spent) = entries[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_insight_row, parent, false)
            val iconView = row.findViewById<android.widget.ImageView>(R.id.insight_icon)
            iconView?.setImageResource(categoryIconDrawable(categoryId))
            iconView?.imageTintList = android.content.res.ColorStateList.valueOf(categoryColor(categoryId))
            iconView?.backgroundTintList = android.content.res.ColorStateList.valueOf(categoryBgColor(categoryId))
            row.findViewById<TextView>(R.id.insight_category).text = TransactionCategories.expense.firstOrNull { it.id == categoryId }?.name
                ?: categoryId.replaceFirstChar { it.uppercase() }
            row.findViewById<TextView>(R.id.insight_amount).text = Money(spent, currencyCode).format()
            val bar = row.findViewById<ProgressBar>(R.id.insight_progress)
            bar.max = 100
            bar.progress = if (totalSpent == 0L) 0 else (spent.toDouble() / totalSpent * 100).toInt().coerceIn(0, 100)
            bar.progressTintList = android.content.res.ColorStateList.valueOf(categoryColor(categoryId))
            row.findViewById<TextView>(R.id.insight_share).text = getString(R.string.montra_percentage, bar.progress)
            return row
        }
    }
}

