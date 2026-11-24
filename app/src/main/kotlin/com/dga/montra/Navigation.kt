package com.dga.montra

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Activity.bindBottomNavigation(root: View, selected: Int) {
    val destinations = listOf(
        Triple(R.id.nav_home, MainActivity::class.java, 0),
        Triple(R.id.nav_activity, TransactionsActivity::class.java, 1),
        Triple(R.id.nav_budget, BudgetActivity::class.java, 2),
        Triple(R.id.nav_insights, InsightsActivity::class.java, 4)
    )
    val active = if (selected == 3) 2 else selected
    destinations.forEach { (id, destination, index) ->
        root.findViewById<TextView>(id).apply {
            isSelected = index == active
            setTextColor(getColor(if (isSelected) R.color.on_primary else R.color.lumen_muted))
            compoundDrawableTintList = ColorStateList.valueOf(currentTextColor)
            setOnClickListener {
                if (index != active) startActivity(Intent(this@bindBottomNavigation, destination)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }
    }
}

fun Activity.bindPlanSections(goalsSelected: Boolean) {
    listOf(R.id.plan_budgets to false, R.id.plan_goals to true).forEach { (id, goals) ->
        findViewById<Button>(id).apply {
            isSelected = goals == goalsSelected
            setBackgroundResource(if (isSelected) R.drawable.nav_item_selected else R.drawable.montra_soft)
            setTextColor(getColor(if (isSelected) R.color.on_primary else R.color.lumen_ink))
            setOnClickListener {
                if (!isSelected) {
                    val next = if (goals) GoalsActivity::class.java else BudgetActivity::class.java
                    startActivity(Intent(this@bindPlanSections, next).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                    finish()
                }
            }
        }
    }
}

internal fun monthLabel(month: YearMonth = YearMonth.now()): String =
    month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault()))
