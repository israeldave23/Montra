package com.dga.montra

import android.app.Activity
import android.app.Application
import android.content.Context
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
import androidx.appcompat.app.AlertDialog
import com.dga.montra.db.BudgetEntity
import com.dga.montra.db.GoalEntity
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

abstract class SectionActivity(private val title: Int, private val subtitle: Int, private val body: Int, private val tab: Int, private val icon: Int) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_section)
        findViewById<TextView>(R.id.section_title).setText(title)
        findViewById<TextView>(R.id.section_subtitle).setText(subtitle)
        findViewById<TextView>(R.id.section_body).setText(body)
        findViewById<android.widget.ImageView>(R.id.section_icon).setImageResource(icon)
        if (tab == 4) {
            findViewById<Button>(R.id.section_action).apply {
                setText(R.string.add_transaction)
                setOnClickListener { startActivity(Intent(this@SectionActivity, MainActivity::class.java).putExtra("open_add_transaction", true)) }
            }
        }
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
    }
}

class BudgetActivity : SectionActivity(R.string.budget_title, R.string.budget_subtitle, R.string.budget_body, 2, R.drawable.ic_nav_budget) {
    private val repo = RoomFinanceRepository.get(applicationContext)
    private lateinit var budgetsRow: ListView
    private lateinit var budgetsCreate: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_budget)
        budgetsRow = findViewById(R.id.budgets_list)
        budgetsCreate = findViewById(R.id.budget_action)
        observe()
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            val budgets = repo.budgets()
            val month = YearMonth.now().toString()
            val spent = budgets.associateWith { repo.spentForCategoryMonth(it.categoryId, month) }.toMap()
            budgetsRow.adapter = BudgetAdapter(this@BudgetActivity, budgets, spent)
            budgetsCreate.isVisible = true
            budgetsCreate.setText(R.string.create_budget)
            budgetsCreate.setOnClickListener { showCreateBudget() }
        }
    }

    private fun showCreateBudget() {
        val categories = TransactionCategories.expense
        val names = categories.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.create_budget)
            .setSingleChoiceItems(names.toTypedArray(), 0) { _, selected -> selectedCategory = categories[selected].id }
            .setPositiveButton(R.string.next) { _, _ ->
                val input = EditText(this@BudgetActivity)
                input.hint = getString(R.string.budget_limit)
                input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                AlertDialog.Builder(this@BudgetActivity)
                    .setTitle(R.string.budget_limit)
                    .setView(input)
                    .setPositiveButton(R.string.save) { _, _ ->
                        val value = input.text.toString().toFloatOrNullOrZero()
                        if (value <= 0f) { input.setError(getString(R.string.amount_required)); return@setPositiveButton }
                        lifecycleScope.launch {
                            try {
                                repo.createBudget(selectedCategory, Money((value * 100).toLong(), "PHP"))
                                observe()
                            } catch (e: Exception) { input.setError(getString(R.string.planning_error)) }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private var selectedCategory: String by lazy { TransactionCategories.expense.first().id }

    private inner class BudgetAdapter(activity: Activity, val budgets: List<BudgetEntity>, val spent: Map<String,Long>) : BaseAdapter() {
        override fun getCount() = budgets.size
        override fun getItem(position: Int) = budgets[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val b = budgets[position]
            val row = if (convertView != null) convertView else LayoutInflater.from(activity).inflate(R.layout.item_budget_row, parent, false)
            val categoryView = row.findViewById<TextView>(R.id.budget_category)
            val amountView = row.findViewById<TextView>(R.id.budget_amount)
            val bar = row.findViewById<ProgressBar>(R.id.budget_progress)
            categoryView.text = TransactionCategories.byId(b.categoryId)?.name ?: b.categoryId.replaceFirstChar { it.uppercase() }
            val limit = Money(b.limitMinorUnits, b.currencyCode)
            amountView.text = activity.getString(R.string.progress_amount, Money(0L, b.currencyCode).format(), limit.format())
            bar.max = 100
            val spent = spent[b.categoryId] ?: 0L
            val limit = b.limitMinorUnits
            val progress = if (limit == 0L) 0L else minOf(spent, limit) * 100 / limit
            bar.progress = progress.coerceIn(0,100).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(if (spent > limit) R.color.lumen_error else R.color.lumen_accent))
            row.tag = b
            return row
        }
    }
}

class GoalsActivity : SectionActivity(R.string.goals_title, R.string.goals_subtitle, R.string.goals_body, 3, R.drawable.ic_nav_goals) {
    private val repo = RoomFinanceRepository.get(applicationContext)
    private lateinit var goalsRow: ListView
    private lateinit var goalsCreate: Button

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_goals)
        goalsRow = findViewById(R.id.goals_list)
        goalsCreate = findViewById(R.id.goal_action)
        observe()
    }

    override fun onResume() {
        super.onResume()
        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            val goals = repo.goals()
            goalsRow.adapter = GoalAdapter(this@GoalsActivity, goals)
            goalsCreate.isVisible = true
            goalsCreate.setText(R.string.new_goal)
            goalsCreate.setOnClickListener { showGoalForm() }
        }
    }

    private fun showGoalForm() {
        val nameInput = EditText(this).apply { hint = getString(R.string.goal_name) }
        val targetInput = EditText(this).apply {
            hint = getString(R.string.target_amount)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val savedInput = EditText(this).apply {
            hint = getString(R.string.saved_amount)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(nameInput)
            addView(targetInput)
            addView(savedInput)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_goal)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) { nameInput.setError(getString(R.string.name_required)); return@setPositiveButton }
                val target = targetInput.text.toString().toFloatOrNullOrZero()
                val saved = savedInput.text.toString().toFloatOrNullOrZero()
                if (target <= 0f || saved < 0f) { targetInput.setError(getString(R.string.amount_required)); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        repo.createGoal(name, Money((target * 100).toLong(), "PHP"), Money((saved * 100).toLong(), "PHP"))
                        observe()
                    } catch (e: Exception) { targetInput.setError(getString(R.string.planning_error)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class GoalAdapter(activity: Activity, val goals: List<GoalEntity>) : BaseAdapter() {
        override fun getCount() = goals.size
        override fun getItem(position: Int) = goals[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val g = goals[position]
            val row = if (convertView != null) convertView else LayoutInflater.from(activity).inflate(R.layout.item_goal_row, parent, false)
            row.findViewById<TextView>(R.id.goal_name).text = g.name
            val target = Money(g.targetMinorUnits, g.currencyCode)
            val saved = Money(g.savedMinorUnits, g.currencyCode)
            row.findViewById<TextView>(R.id.goal_amount).text = activity.getString(R.string.progress_amount, saved.format(), target.format())
            val progress = if (target.minorUnits == 0L) 0L else minOf(saved.minorUnits, target.minorUnits) * 100 / target.minorUnits
            val bar = row.findViewById<ProgressBar>(R.id.goal_progress)
            bar.max = 100
            bar.progress = progress.coerceIn(0, 100).toInt()
            bar.progressTintList = android.content.res.ColorStateList.valueOf(activity.getColor(R.color.lumen_accent))
            row.setOnClickListener { showAddFunds(g) }
            return row
        }
    }

    private fun showAddFunds(goal: GoalEntity) {
        val input = EditText(this).apply {
            hint = getString(R.string.add_funds)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        AlertDialog.Builder(this)
            .setTitle(goal.name)
            .setMessage(getString(R.string.add_funds_note))
            .setView(input)
            .setPositiveButton(R.string.add_funds) { _, _ ->
                val amount = input.text.toString().toFloatOrNullOrZero()
                if (amount <= 0f) { input.setError(getString(R.string.amount_required)); return@setPositiveButton }
                lifecycleScope.launch {
                    try {
                        repo.addGoalFunds(goal.id, Money((amount * 100).toLong(), "PHP"))
                        observe()
                    } catch (e: Exception) { input.setError(getString(R.string.planning_error)) }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

class AccountDetailActivity : Activity() {
    private val repo = RoomFinanceRepository.get(applicationContext)
    private lateinit var accountId: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_account_detail)
        accountId = intent.getStringExtra("ACCOUNT_ID") ?: repo.allAccounts().firstOrNull()?.id ?: return
        lifecycleScope.launch {
            val account = repo.allAccounts().first { it.id == accountId }
            findViewById<TextView>(R.id.account_name).text = account.name
            findViewById<TextView>(R.id.account_balance).text = account.openingBalance.format()
            val list = findViewById<ListView>(R.id.account_transactions)
            repo.observeAccountTransactions(accountId).collect { txs ->
                list.adapter = AccountTransactionAdapter(this@AccountDetailActivity, txs.map { it.toDomain() })
            }
        }
    }

    private inner class AccountTransactionAdapter(activity: Activity, val items: List<Transaction>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = items[position]
            val row = if (convertView != null) convertView else LayoutInflater.from(activity).inflate(R.layout.item_transaction_row, parent, false)
            row.findViewById<TextView>(R.id.transaction_category).text = TransactionCategories.byId(t.categoryId)?.name ?: t.categoryId.replaceFirstChar { it.uppercase() }
            val date = java.time.Instant.ofEpochMilli(t.timestampMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
            row.findViewById<TextView>(R.id.transaction_date).text = date
            row.findViewById<TextView>(R.id.transaction_note).text = t.note.takeIf { it.isNotEmpty() } ?: date
            val amountView = row.findViewById<TextView>(R.id.transaction_amount)
            amountView.text = when { t.type == TransactionType.INCOME || t.type == TransactionType.REFUND -> "+${t.amount.format()}"; else -> "-${t.amount.format()}" }
            amountView.setTextColor(activity.getColor(if (t.type == TransactionType.INCOME || t.type == TransactionType.REFUND) R.color.lumen_success else R.color.lumen_error))
            return row
        }
    }
}

fun String.toFloatOrNullOrZero(): Float = toFloatOrNull() ?: 0f
