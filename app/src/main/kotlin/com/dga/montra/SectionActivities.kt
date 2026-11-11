package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Money
import com.dga.montra.domain.TransactionType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

abstract class SectionActivity(private val title: Int, private val subtitle: Int, private val body: Int, private val tab: Int, private val icon: Int) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_section)
        findViewById<TextView>(R.id.section_title).setText(title)
        findViewById<TextView>(R.id.section_subtitle).setText(subtitle)
        findViewById<TextView>(R.id.section_body).setText(body)
        findViewById<android.widget.ImageView>(R.id.section_icon).setImageResource(icon)
        if (tab == 4) {
            findViewById<android.widget.Button>(R.id.section_action).apply {
                setText(R.string.add_transaction)
                setOnClickListener { startActivity(android.content.Intent(this@SectionActivity, MainActivity::class.java).putExtra("open_add_transaction", true)) }
            }
        }
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
    }
}
class BudgetActivity : SectionActivity(R.string.budget_title, R.string.budget_subtitle, R.string.budget_body, 2, R.drawable.ic_nav_budget)
class GoalsActivity : SectionActivity(R.string.goals_title, R.string.goals_subtitle, R.string.goals_body, 3, R.drawable.ic_nav_goals)
class InsightsActivity : SectionActivity(R.string.insights_title, R.string.insights_subtitle, R.string.insights_body, 4, R.drawable.ic_nav_insights) {
    private val scope = MainScope()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        scope.launch {
            runCatching { RoomFinanceRepository.get(this@InsightsActivity).allTransactions() }.onSuccess { items ->
                val grouped = items.groupBy { it.amount.currencyCode }
                val lines = mutableListOf(resources.getQuantityString(R.plurals.transaction_count, items.size, items.size))
                grouped.forEach { (currency, txs) ->
                    val income = txs.filter { it.type == TransactionType.INCOME || it.type == TransactionType.REFUND }.fold(0L) { a, tx -> Math.addExact(a, tx.amount.minorUnits) }
                    val expenses = txs.filter { it.type == TransactionType.EXPENSE }.fold(0L) { a, tx -> Math.addExact(a, tx.amount.minorUnits) }
                    lines += "$currency  ·  ${getString(R.string.insight_income)} ${Money(income, currency).format()}"
                    lines += "$currency  ·  ${getString(R.string.insight_expenses)} ${Money(expenses, currency).format()}"
                }
                findViewById<TextView>(R.id.section_body).text = if (items.isEmpty()) getString(R.string.insight_empty) else lines.joinToString("\n\n")
                findViewById<android.widget.Button>(R.id.section_action).visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
