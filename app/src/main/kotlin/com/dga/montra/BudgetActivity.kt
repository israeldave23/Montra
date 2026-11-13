package com.dga.montra

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Budget
import com.dga.montra.domain.BudgetOrdering
import com.dga.montra.domain.BudgetProgress
import com.dga.montra.domain.BudgetSummary
import com.dga.montra.domain.Money
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.daysLeft
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import androidx.lifecycle.lifecycleScope

/**
 * Monthly spending plan: one limit per category, with the money actually spent counted from
 * transactions. The screen answers "what is left this month" rather than only "what did I spend".
 *
 * Budgets never move money. Spending is derived, so there is nothing to reconcile by hand.
 */
class BudgetActivity : SectionActivity(2) {
    private val repo by lazy { RoomFinanceRepository.get(this) }

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var summaryCard: View
    private lateinit var summaryAmount: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var monthLabel: TextView
    private lateinit var actionButton: Button
    private lateinit var adapter: BudgetAdapter

    private var month: YearMonth = YearMonth.now()
    private var budgets: List<BudgetProgress> = emptyList()
    private var observation: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_budget)
        findViewById<TextView>(R.id.budget_title).setText(R.string.budget_title)
        findViewById<TextView>(R.id.budget_subtitle).setText(R.string.budget_subtitle)

        list = findViewById(R.id.budget_list)
        emptyView = findViewById(R.id.budget_empty)
        summaryCard = findViewById(R.id.budget_summary)
        summaryAmount = findViewById(R.id.budget_summary_amount)
        summaryDetail = findViewById(R.id.budget_summary_detail)
        monthLabel = findViewById(R.id.budget_month)
        actionButton = findViewById(R.id.budget_action)

        list.layoutManager = LinearLayoutManager(this)
        adapter = BudgetAdapter(onMenu = { item, anchor -> showBudgetMenu(item, anchor) })
        list.adapter = adapter
        actionButton.setOnClickListener { showEditor(null) }
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
        render(emptyList())
    }

    override fun onStart() {
        super.onStart()
        month = YearMonth.now()
        renderMonthLabel()
        observation = lifecycleScope.launch {
            repo.observeBudgets(month)
                .catch { snackbar(R.string.storage_error) }
                .collect { render(it) }
        }
    }

    override fun onStop() {
        observation?.cancel()
        super.onStop()
    }

    private fun render(items: List<BudgetProgress>) {
        budgets = BudgetOrdering.sorted(items)
        adapter.submitList(budgets)
        val empty = budgets.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        summaryCard.visibility = if (empty) View.GONE else View.VISIBLE
        actionButton.setText(if (empty) R.string.budget_create_first else R.string.budget_new_action)
        if (!empty) renderSummary(budgets)
    }

    private fun renderSummary(items: List<BudgetProgress>) {
        val summary = BudgetSummary.of(items)
        summaryAmount.text = summary.limitByCurrency.entries
            .sortedBy { it.key }
            .joinToString("  ·  ") { (currency, minor) -> Money(minor, currency).format() }

        val spent = summary.spentByCurrency.entries
            .sortedBy { it.key }
            .joinToString("  ·  ") { (currency, minor) -> Money(minor, currency).format() }
        val counts = if (summary.overCount > 0) {
            getString(R.string.budget_summary_detail, summary.count, summary.overCount)
        } else {
            getString(R.string.budget_summary_detail_ok, summary.count)
        }
        summaryDetail.text = listOf(getString(R.string.budget_summary_spent, spent), counts)
            .joinToString("  ·  ")
    }

    private fun renderMonthLabel() {
        val label = "${month.month.getDisplayName(TextStyle.FULL_STANDALONE, Locale.getDefault())} ${month.year}"
        val today = LocalDate.now()
        val days = month.daysLeft(today)
        monthLabel.text = when {
            month != YearMonth.from(today) -> label
            days <= 0 -> getString(R.string.budget_month_last_day, label)
            else -> getString(R.string.budget_month_label, label, days)
        }
    }

    private fun showBudgetMenu(item: BudgetProgress, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_EDIT, 0, R.string.edit)
            menu.add(0, MENU_DELETE, 1, R.string.delete)
            setOnMenuItemClickListener { entry ->
                when (entry.itemId) {
                    MENU_EDIT -> showEditor(item.budget)
                    MENU_DELETE -> confirmDelete(item.budget)
                }
                true
            }
            show()
        }
    }

    private fun showEditor(existing: Budget?) {
        if (existing == null && budgets.size >= TransactionCategories.expense.size) {
            snackbar(R.string.budget_all_categories)
            return
        }
        val content = layoutInflater.inflate(R.layout.dialog_budget_edit, null)
        val categorySpinner = content.findViewById<Spinner>(R.id.budget_edit_category)
        val categoryLabelView = content.findViewById<TextView>(R.id.budget_edit_category_label)
        val amountInput = content.findViewById<EditText>(R.id.budget_edit_amount)
        val contextView = content.findViewById<TextView>(R.id.budget_edit_context)

        val currency = existing?.currencyCode ?: defaultCurrency()
        val categories = TransactionCategories.expense
        val budgetedCategories = budgets.map { it.budget.categoryId }.toSet()

        val labels = categories.map { category ->
            if (existing == null && category.id in budgetedCategories) {
                "${category.name} · ${getString(R.string.budget_duplicate_short)}"
            } else {
                category.name
            }
        }
        categorySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        if (existing == null) {
            val firstFree = categories.indexOfFirst { it.id !in budgetedCategories }
            if (firstFree > 0) categorySpinner.setSelection(firstFree)
        } else {
            categorySpinner.visibility = View.GONE
            categoryLabelView.visibility = View.GONE
            amountInput.setText(existing.limit.plainString())
            val progress = budgets.firstOrNull { it.budget.id == existing.id }
            contextView.visibility = if (progress == null) View.GONE else View.VISIBLE
            if (progress != null) {
                contextView.text = getString(
                    R.string.budget_spent_of_limit,
                    progress.spent.format(),
                    progress.limit.format()
                )
            }
        }
        if (existing == null) contextView.visibility = View.GONE

        amountInput.hint = getString(R.string.budget_limit_hint, currency)

        val titleRes = if (existing == null) R.string.budget_new_title else R.string.budget_edit_title
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val categoryId = when {
                    existing != null -> existing.categoryId
                    else -> categories.getOrNull(categorySpinner.selectedItemPosition)?.id
                }
                if (categoryId == null) {
                    snackbar(R.string.planning_error)
                    return@setOnClickListener
                }
                if (existing == null && categoryId in budgetedCategories) {
                    snackbar(R.string.budget_duplicate)
                    return@setOnClickListener
                }
                val limit = runCatching { Money.parse(amountInput.text.toString().trim(), currency) }
                    .getOrNull()
                if (limit == null) {
                    amountInput.error = getString(R.string.budget_amount_invalid)
                    return@setOnClickListener
                }
                if (limit.minorUnits <= 0L) {
                    amountInput.error = getString(R.string.amount_required)
                    return@setOnClickListener
                }
                confirm.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        if (existing == null) repo.createBudget(month, categoryId, limit)
                        else repo.updateBudget(existing.id, limit)
                    }.onSuccess {
                        dialog.dismiss()
                        snackbar(if (existing == null) R.string.budget_created else R.string.budget_updated)
                    }.onFailure { failure ->
                        confirm.isEnabled = true
                        val duplicate = failure is IllegalStateException &&
                            failure.message?.contains("already exists", ignoreCase = true) == true
                        if (duplicate) {
                            amountInput.error = getString(R.string.budget_duplicate)
                        } else {
                            amountInput.error = getString(R.string.planning_error)
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(budget: Budget) {
        AlertDialog.Builder(this)
            .setTitle(R.string.budget_delete_title)
            .setMessage(
                getString(
                    R.string.budget_delete_message,
                    categoryLabel(budget.categoryId),
                    budget.limit.format()
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteBudget(budget) }
            .show()
    }

    private fun deleteBudget(budget: Budget) {
        lifecycleScope.launch {
            runCatching { repo.deleteBudget(budget.id) }
                .onSuccess {
                    snackbarWithUndo(getString(R.string.budget_deleted)) {
                        lifecycleScope.launch {
                            runCatching { repo.restoreBudget(budget) }
                                .onFailure { snackbar(R.string.planning_error) }
                        }
                    }
                }
                .onFailure { snackbar(R.string.planning_error) }
        }
    }

    private fun defaultCurrency(): String {
        val preferred = settings.defaultCurrency
        return if (preferred != null && preferred in Money.currencies) preferred else Money.currencies.first()
    }

    private fun snackbar(message: Int) = snackbar(getString(message))

    private fun snackbar(message: String) {
        Snackbar.make(findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun snackbarWithUndo(message: String, undo: () -> Unit) {
        Snackbar.make(findViewById<View>(android.R.id.content), message, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) { undo() }
            .show()
    }

    private companion object {
        const val MENU_EDIT = 1
        const val MENU_DELETE = 2
    }
}
