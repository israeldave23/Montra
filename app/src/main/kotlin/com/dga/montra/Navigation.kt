package com.dga.montra

import android.app.Activity
import android.content.Intent
import android.view.View

fun Activity.bindBottomNavigation(root: View) {
    root.findViewById<View>(R.id.nav_home).setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }
    root.findViewById<View>(R.id.nav_activity).setOnClickListener { startActivity(Intent(this, TransactionsActivity::class.java)) }
    root.findViewById<View>(R.id.nav_budget).setOnClickListener { startActivity(Intent(this, BudgetActivity::class.java)) }
    root.findViewById<View>(R.id.nav_goals).setOnClickListener { startActivity(Intent(this, GoalsActivity::class.java)) }
    root.findViewById<View>(R.id.nav_insights).setOnClickListener { startActivity(Intent(this, InsightsActivity::class.java)) }
}
