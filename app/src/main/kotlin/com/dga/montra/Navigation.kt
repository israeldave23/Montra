package com.dga.montra

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView

fun Activity.bindBottomNavigation(root: View, selected: Int) {
    val items = listOf(R.id.nav_home, R.id.nav_activity, R.id.nav_budget, R.id.nav_goals, R.id.nav_insights)
    val activities = listOf(MainActivity::class.java, TransactionsActivity::class.java, BudgetActivity::class.java, GoalsActivity::class.java, InsightsActivity::class.java)
    items.forEachIndexed { index, id ->
        root.findViewById<TextView>(id).apply {
            isPressed = false
            isSelected = index == selected
            setTextColor(getColor(if (isSelected) R.color.on_primary else R.color.lumen_muted))
            contentDescription = getString(listOf(R.string.nav_home_description, R.string.nav_activity_description, R.string.nav_budget_description, R.string.nav_goals_description, R.string.nav_insights_description)[index])
            setOnClickListener {
                if (index != selected) {
                    val intent = Intent(this@bindBottomNavigation, activities[index]).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    val options = android.app.ActivityOptions.makeCustomAnimation(this@bindBottomNavigation, R.anim.activity_enter, R.anim.activity_exit).toBundle()
                    startActivity(intent, options)
                }
            }
        }
    }
}
