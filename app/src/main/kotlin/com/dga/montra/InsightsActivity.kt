package com.dga.montra

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.ads.Ads
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.CurrencyInsight
import com.dga.montra.domain.MonthlyInsight
import com.dga.montra.domain.Money
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Monthly insight: where the money went, how it compares with last month, and what the pace implies
 * for the rest of the month.
 *
 * Every currency is shown on its own card and its own bars. Amounts from different currencies are
 * never summed, so the numbers stay exact and comparable.
 */
class InsightsActivity : SectionActivity(4) {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyTitle: TextView
    private lateinit var monthLabel: TextView
    private lateinit var previousButton: View
    private lateinit var nextButton: View
    private lateinit var adapter: InsightAdapter

    private var month: YearMonth = YearMonth.now()
    private var observation: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_insights)
        Ads.attachBanner(findViewById(R.id.ad_slot))
        findViewById<TextView>(R.id.insights_title).setText(R.string.insights_title)
        findViewById<TextView>(R.id.insights_subtitle).setText(R.string.insights_subtitle)

        list = findViewById(R.id.insights_list)
        emptyView = findViewById(R.id.insights_empty)
        emptyTitle = findViewById(R.id.insights_empty_title)
        monthLabel = findViewById(R.id.insights_month_label)
        previousButton = findViewById(R.id.insights_month_prev)
        nextButton = findViewById(R.id.insights_month_next)

        list.layoutManager = LinearLayoutManager(this)
        adapter = InsightAdapter()
        list.adapter = adapter

        previousButton.setOnClickListener { shiftMonth(-1) }
        nextButton.setOnClickListener { shiftMonth(1) }

        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
        renderMonthControls()
        render(MonthlyInsight(month, emptyList()))
    }

    override fun onStart() {
        super.onStart()
        observe()
    }

    override fun onStop() {
        observation?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun shiftMonth(months: Long) {
        val target = month.plusMonths(months)
        if (target > YearMonth.now()) return
        month = target
        renderMonthControls()
        observe()
    }

    private fun observe() {
        observation?.cancel()
        observation = scope.launch {
            repo.observeInsight(month)
                .catch { snackbar(R.string.storage_error) }
                .collect { render(it) }
        }
    }

    private fun renderMonthControls() {
        monthLabel.text = monthName(month)
        val canAdvance = month < YearMonth.now()
        nextButton.isEnabled = canAdvance
        nextButton.alpha = if (canAdvance) 1f else DISABLED_ALPHA
    }

    private fun render(insight: MonthlyInsight) {
        val rows = buildRows(insight)
        adapter.submit(rows)
        val empty = rows.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        emptyTitle.text = getString(R.string.insights_body, monthName(month))
    }

    private fun buildRows(insight: MonthlyInsight): List<Any> {
        val rows = mutableListOf<Any>()
        insight.currencies.forEach { currency ->
            rows += summaryRow(currency)
            currency.categories.forEach { spend ->
                val name = categoryLabel(spend.categoryId)
                val amount = Money(spend.minorUnits, currency.currencyCode).format()
                rows += InsightCategoryRow(
                    categoryId = spend.categoryId,
                    name = name,
                    amount = amount,
                    share = getString(R.string.insights_share_of_spending, spend.sharePercent),
                    percent = spend.sharePercent,
                    description = getString(
                        R.string.insights_row_description,
                        name,
                        amount,
                        spend.sharePercent
                    )
                )
            }
        }
        return rows
    }

    private fun summaryRow(insight: CurrencyInsight): InsightSummaryRow {
        val code = insight.currencyCode
        val expense = Money(insight.expenseMinorUnits, code).format()
        val flow = listOf(
            getString(R.string.insights_income_line, Money(insight.incomeMinorUnits, code).format()),
            getString(R.string.insights_net_line, Money(insight.netMinorUnits, code).format())
        ).joinToString("  ·  ")
        val change = changeText(insight)
        val average = getString(
            R.string.insights_daily_average,
            Money(insight.averageDailyExpenseMinorUnits, code).format()
        )
        val averageLine = if (month == YearMonth.now()) {
            listOf(
                average,
                getString(
                    R.string.insights_projected,
                    Money(insight.projectedExpenseMinorUnits, code).format()
                )
            ).joinToString("  ·  ")
        } else {
            average
        }
        val top = insight.topCategory?.let { biggest ->
            listOf(
                getString(R.string.insights_top_category, categoryLabel(biggest.categoryId)),
                resources.getQuantityString(
                    R.plurals.transaction_count,
                    insight.transactionCount,
                    insight.transactionCount
                )
            ).joinToString("  ·  ")
        } ?: ""

        return InsightSummaryRow(
            currencyCode = code,
            expense = expense,
            flow = flow,
            change = change,
            average = averageLine,
            top = top,
            description = "$code. $expense. $flow. $change"
        )
    }

    private fun changeText(insight: CurrencyInsight): String {
        val previous = monthName(month.minusMonths(1))
        val percent = insight.changePercent
            ?: return getString(R.string.insights_change_none, previous)
        val code = insight.currencyCode
        val current = insight.expenseMinorUnits
        val before = insight.previousExpenseMinorUnits
        return when {
            percent > 0 && current > before -> getString(
                R.string.insights_change_up,
                Money(current - before, code).format(),
                previous,
                percent
            )
            percent < 0 && current < before -> getString(
                R.string.insights_change_down,
                Money(before - current, code).format(),
                previous,
                -percent
            )
            else -> getString(R.string.insights_change_same, previous)
        }
    }

    private fun monthName(value: YearMonth): String =
        "${value.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${value.year}"

    private fun snackbar(message: Int) = snackbar(getString(message))

    private fun snackbar(message: String) {
        Snackbar.make(findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.35f
    }
}
