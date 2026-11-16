package com.dga.montra

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.ads.Ads
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.databinding.ActivityInsightsBinding
import com.dga.montra.domain.CurrencyInsight
import com.dga.montra.ui.InsightsViewModel
import com.dga.montra.ui.UiState
import com.dga.montra.ui.viewModelFactory
import com.dga.montra.domain.MonthlyInsight
import com.dga.montra.domain.Money
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle

/**
 * Monthly insight: where the money went, how it compares with last month, and what the pace implies
 * for the rest of the month.
 *
 * Every currency is shown on its own card and its own bars. Amounts from different currencies are
 * never summed, so the numbers stay exact and comparable.
 */
class InsightsActivity : SectionActivity(4) {
    private lateinit var binding: ActivityInsightsBinding

    private val repo by lazy { RoomFinanceRepository.get(this) }

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyTitle: TextView
    private lateinit var monthLabel: TextView
    private lateinit var previousButton: View
    private lateinit var nextButton: View
    private lateinit var adapter: InsightAdapter

    private val viewModel: InsightsViewModel by viewModels { viewModelFactory { InsightsViewModel(repo) } }

    private val month: YearMonth get() = viewModel.currentMonth

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        binding = ActivityInsightsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ads.attachBanner(binding.root)
        binding.insightsTitle.setText(R.string.insights_title)
        binding.insightsSubtitle.setText(R.string.insights_subtitle)

        list = binding.insightsList
        emptyView = binding.insightsEmpty
        emptyTitle = binding.insightsEmptyTitle
        monthLabel = binding.insightsMonthLabel
        previousButton = binding.insightsMonthPrev
        nextButton = binding.insightsMonthNext

        list.layoutManager = LinearLayoutManager(this)
        adapter = InsightAdapter()
        list.adapter = adapter

        previousButton.setOnClickListener { shiftMonth(-1) }
        nextButton.setOnClickListener { shiftMonth(1) }

        bindBottomNavigation(binding.root, tab)
        renderMonthControls()
        render(MonthlyInsight(month, emptyList()))
        collectState()
    }

    override fun onStart() {
        super.onStart()
        renderMonthControls()
    }

    private fun shiftMonth(months: Long) {
        if (!viewModel.shiftMonth(months)) return
        renderMonthControls()
    }

    /**
     * Renders what the view model holds. Collection stops when the screen is not started, so nothing
     * queries the database behind a screen the user cannot see.
     */
    private fun collectState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is UiState.Ready -> render(state.value)
                        UiState.Failed -> snackbar(R.string.storage_error)
                        UiState.Loading -> Unit
                    }
                }
            }
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
        adapter.submitList(rows)
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
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
    }

    private companion object {
        const val DISABLED_ALPHA = 0.35f
    }
}
