package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

abstract class SectionActivity(private val title: Int, private val subtitle: Int, private val body: Int) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_section)
        findViewById<TextView>(R.id.section_title).setText(title)
        findViewById<TextView>(R.id.section_subtitle).setText(subtitle)
        findViewById<TextView>(R.id.section_body).setText(body)
        bindBottomNavigation(findViewById(R.id.bottom_navigation))
    }
}
class BudgetActivity : SectionActivity(R.string.budget, R.string.budget_subtitle, R.string.budget_body)
class GoalsActivity : SectionActivity(R.string.goals, R.string.goals_subtitle, R.string.goals_body)
class InsightsActivity : SectionActivity(R.string.insights, R.string.insights_subtitle, R.string.insights_body)
