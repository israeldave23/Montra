package com.dga.montra

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Goal
import com.dga.montra.domain.GoalOrdering
import com.dga.montra.domain.GoalStatus
import com.dga.montra.domain.GoalSummary
import com.dga.montra.domain.Money
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import androidx.lifecycle.lifecycleScope

/**
 * Savings goals: what you are putting money aside for, how far along you are, whether you are on
 * pace for the date you picked, and the quickest path to updating the tracked amount.
 *
 * Goals never move money: all copy says so, and the only writes are to the goal row.
 */
class GoalsActivity : SectionActivity(3) {
    private val repo by lazy { RoomFinanceRepository.get(this) }

    private lateinit var list: RecyclerView
    private lateinit var emptyView: View
    private lateinit var summaryCard: View
    private lateinit var summarySaved: TextView
    private lateinit var summaryDetail: TextView
    private lateinit var actionButton: Button
    private lateinit var adapter: GoalAdapter

    private var goals: List<Goal> = emptyList()
    private var observation: Job? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_goals)
        findViewById<TextView>(R.id.goal_title).setText(R.string.goals_title)
        findViewById<TextView>(R.id.goal_subtitle).setText(R.string.goals_subtitle)

        list = findViewById(R.id.goal_list)
        emptyView = findViewById(R.id.goal_empty)
        summaryCard = findViewById(R.id.goal_summary)
        summarySaved = findViewById(R.id.goal_summary_amount)
        summaryDetail = findViewById(R.id.goal_summary_detail)
        actionButton = findViewById(R.id.goal_action)

        list.layoutManager = LinearLayoutManager(this)
        adapter = GoalAdapter(
            onOpen = { goal -> showGoalDetail(goal) },
            onMenu = { goal, anchor -> showGoalMenu(goal, anchor) }
        )
        list.adapter = adapter
        actionButton.setOnClickListener { showGoalEditor(null) }
        bindBottomNavigation(findViewById(R.id.bottom_navigation), tab)
        render(emptyList())
    }

    override fun onStart() {
        super.onStart()
        observation = lifecycleScope.launch {
            repo.observeGoals()
                .catch { snackbar(R.string.storage_error) }
                .collect { render(it) }
        }
    }

    override fun onStop() {
        observation?.cancel()
        super.onStop()
    }

    private fun render(items: List<Goal>) {
        goals = GoalOrdering.sorted(items, LocalDate.now(), ZoneId.systemDefault())
        adapter.submitList(goals)
        val empty = goals.isEmpty()
        list.visibility = if (empty) View.GONE else View.VISIBLE
        emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        summaryCard.visibility = if (empty) View.GONE else View.VISIBLE
        actionButton.setText(if (empty) R.string.goal_create_first else R.string.new_goal)
        if (!empty) renderSummary(goals)
    }

    private fun renderSummary(items: List<Goal>) {
        val summary = GoalSummary.of(items)
        summarySaved.text = summary.savedByCurrency.entries.joinToString("  ·  ") { (currency, minor) ->
            Money(minor, currency).format()
        }
        val completed = getString(R.string.goal_summary_completed, summary.completedCount, summary.goalCount)
        val remaining = summary.remainingByCurrency.entries
            .filter { it.value > 0L }
            .joinToString("  ·  ") { (currency, minor) ->
                getString(R.string.goal_remaining_amount, Money(minor, currency).format())
            }
        summaryDetail.text = if (remaining.isEmpty()) completed
        else getString(R.string.goal_summary_detail, completed, remaining)
    }

    private fun showGoalDetail(goal: Goal) {
        val content = layoutInflater.inflate(R.layout.dialog_goal_detail, null)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val status = goal.status(today, zone)
        val percent = goal.progressPercent()

        content.findViewById<TextView>(R.id.goal_detail_name).text = goal.name
        content.findViewById<TextView>(R.id.goal_detail_status).apply {
            text = getString(status.labelRes())
            backgroundTintList = ColorStateList.valueOf(getColor(status.colorRes()))
            setTextColor(getColor(status.textColorRes()))
        }
        content.findViewById<TextView>(R.id.goal_detail_amounts).text =
            getString(R.string.goal_amount_progress, goal.saved.format(), goal.target.format())
        content.findViewById<ProgressBar>(R.id.goal_detail_progress).apply {
            progressTintList = ColorStateList.valueOf(getColor(status.colorRes()))
            max = 100
            progress = percent
        }
        content.findViewById<TextView>(R.id.goal_detail_meta).text = detailMeta(goal, status, percent, zone)
        content.findViewById<TextView>(R.id.goal_detail_plan).apply {
            val plan = planText(goal, today, zone)
            text = plan
            visibility = if (plan.isBlank()) View.GONE else View.VISIBLE
        }

        val dialog = AlertDialog.Builder(this)
            .setView(content)
            .setNegativeButton(R.string.close, null)
            .create()

        content.findViewById<Button>(R.id.goal_detail_add_funds).setOnClickListener {
            dialog.dismiss()
            showFundsDialog(goal, FundsMode.ADD)
        }
        content.findViewById<TextView>(R.id.goal_detail_withdraw).setOnClickListener {
            dialog.dismiss()
            showFundsDialog(goal, FundsMode.WITHDRAW)
        }
        content.findViewById<TextView>(R.id.goal_detail_edit).setOnClickListener {
            dialog.dismiss()
            showGoalEditor(goal)
        }
        content.findViewById<TextView>(R.id.goal_detail_delete).setOnClickListener {
            dialog.dismiss()
            confirmDelete(goal)
        }
        dialog.show()
    }

    private fun detailMeta(goal: Goal, status: GoalStatus, percent: Int, zone: ZoneId): String {
        val parts = mutableListOf(getString(R.string.goal_percent, percent))
        if (goal.remainingMinorUnits > 0L) {
            parts += getString(
                R.string.goal_remaining_amount,
                Money(goal.remainingMinorUnits, goal.currencyCode).format()
            )
        }
        val due = goal.dueDate(zone)
        when {
            due == null -> if (!goal.isComplete) parts += getString(R.string.goal_no_date)
            status == GoalStatus.OVERDUE ->
                parts += getString(R.string.goal_overdue_since, formatGoalDate(due))
            else -> parts += getString(R.string.goal_due_date, formatGoalDate(due))
        }
        return parts.joinToString("  ·  ")
    }

    /** The "what do I need to do" line: monthly pace, overdue nudge, or the completion note. */
    private fun planText(goal: Goal, today: LocalDate, zone: ZoneId): String {
        if (goal.isComplete) return getString(R.string.goal_completed_message)
        val due = goal.dueDate(zone) ?: return ""
        val monthly = goal.requiredMonthlyMinorUnits(today, zone)
            ?: return getString(R.string.goal_overdue_plan, formatGoalDate(due))
        return getString(
            R.string.goal_monthly_plan,
            Money(monthly, goal.currencyCode).format(),
            formatGoalDate(due)
        )
    }

    private fun showGoalMenu(goal: Goal, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_ADD, 0, R.string.add_funds)
            menu.add(0, MENU_WITHDRAW, 1, R.string.goal_withdraw)
            menu.add(0, MENU_EDIT, 2, R.string.edit)
            menu.add(0, MENU_DELETE, 3, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_ADD -> showFundsDialog(goal, FundsMode.ADD)
                    MENU_WITHDRAW -> showFundsDialog(goal, FundsMode.WITHDRAW)
                    MENU_EDIT -> showGoalEditor(goal)
                    MENU_DELETE -> confirmDelete(goal)
                }
                true
            }
            show()
        }
    }

    private fun showGoalEditor(existing: Goal?) {
        val content = layoutInflater.inflate(R.layout.dialog_goal_edit, null)
        val nameInput = content.findViewById<EditText>(R.id.goal_edit_name)
        val targetInput = content.findViewById<EditText>(R.id.goal_edit_target)
        val savedInput = content.findViewById<EditText>(R.id.goal_edit_saved)
        val savedRow = content.findViewById<View>(R.id.goal_edit_saved_row)
        val dateButton = content.findViewById<Button>(R.id.goal_edit_date)
        val clearDate = content.findViewById<TextView>(R.id.goal_edit_clear_date)

        val currency = existing?.currencyCode ?: defaultCurrency()
        targetInput.hint = getString(R.string.target_amount, currency)
        savedInput.hint = getString(R.string.saved_amount, currency)
        nameInput.setText(existing?.name.orEmpty())
        existing?.let {
            targetInput.setText(it.target.plainString())
            savedRow.visibility = View.GONE
        }

        var targetDate: Long? = existing?.targetDateMillis
        fun refreshDate() {
            dateButton.text = targetDate?.let { formatGoalDate(it) } ?: getString(R.string.goal_no_date)
            clearDate.visibility = if (targetDate == null) View.INVISIBLE else View.VISIBLE
        }
        refreshDate()

        dateButton.setOnClickListener {
            val start = Calendar.getInstance().apply { targetDate?.let { timeInMillis = it } }
            DatePickerDialog(this, { _, year, month, day ->
                targetDate = Calendar.getInstance().apply {
                    clear()
                    set(year, month, day, 12, 0, 0)
                }.timeInMillis
                refreshDate()
            }, start.get(Calendar.YEAR), start.get(Calendar.MONTH), start.get(Calendar.DAY_OF_MONTH)).show()
        }
        clearDate.setOnClickListener {
            targetDate = null
            refreshDate()
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.new_goal else R.string.goal_edit_title)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = getString(R.string.name_required)
                    return@setOnClickListener
                }
                val parsedTarget = runCatching {
                    Money.parse(targetInput.text.toString().trim(), currency)
                }.getOrNull()
                if (parsedTarget == null) {
                    targetInput.error = getString(R.string.goal_amount_invalid)
                    return@setOnClickListener
                }
                if (parsedTarget.minorUnits <= 0L) {
                    targetInput.error = getString(R.string.amount_required)
                    return@setOnClickListener
                }
                val parsedSaved = if (existing == null) {
                    val raw = savedInput.text.toString().trim()
                    if (raw.isEmpty()) Money(0L, currency)
                    else runCatching { Money.parse(raw, currency) }.getOrNull() ?: run {
                        savedInput.error = getString(R.string.goal_amount_invalid)
                        return@setOnClickListener
                    }
                } else null
                if (parsedSaved != null && parsedSaved.minorUnits < 0L) {
                    savedInput.error = getString(R.string.saved_invalid)
                    return@setOnClickListener
                }

                val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        if (existing == null) {
                            repo.createGoal(name, parsedTarget, parsedSaved ?: Money(0L, currency), targetDate)
                        } else {
                            repo.updateGoal(existing.id, name, parsedTarget, targetDate)
                        }
                    }.onSuccess {
                        dialog.dismiss()
                        snackbar(if (existing == null) R.string.goal_created else R.string.goal_updated)
                    }.onFailure {
                        saveButton.isEnabled = true
                        targetInput.error = getString(R.string.planning_error)
                    }
                }
            }
        }
        dialog.show()
    }

    private enum class FundsMode { ADD, WITHDRAW }

    private fun showFundsDialog(goal: Goal, mode: FundsMode) {
        val content = layoutInflater.inflate(R.layout.dialog_goal_funds, null)
        val amountInput = content.findViewById<EditText>(R.id.goal_funds_amount)
        val contextView = content.findViewById<TextView>(R.id.goal_funds_context)
        val preview = content.findViewById<TextView>(R.id.goal_funds_preview)
        val quickRow = content.findViewById<View>(R.id.goal_funds_quick_row)
        val quick1 = content.findViewById<TextView>(R.id.goal_funds_quick_1)
        val quick2 = content.findViewById<TextView>(R.id.goal_funds_quick_2)
        val quick3 = content.findViewById<TextView>(R.id.goal_funds_quick_3)

        contextView.text = getString(R.string.goal_funds_context, goal.saved.format(), goal.target.format())
        amountInput.hint = getString(R.string.goal_amount_label, goal.currencyCode)

        val basis = if (mode == FundsMode.ADD) goal.remainingMinorUnits else goal.saved.minorUnits
        val presets = listOf(basis / 4L, basis / 2L, basis)
        fun bindQuick(view: TextView, minorUnits: Long) {
            if (minorUnits <= 0L) {
                view.visibility = View.GONE
                return
            }
            view.visibility = View.VISIBLE
            view.text = Money(minorUnits, goal.currencyCode).format()
            view.setOnClickListener {
                amountInput.setText(Money(minorUnits, goal.currencyCode).plainString())
            }
        }
        bindQuick(quick1, presets[0])
        bindQuick(quick2, presets[1])
        bindQuick(quick3, presets[2])
        quickRow.visibility = if (presets.all { it <= 0L }) View.GONE else View.VISIBLE

        fun refreshPreview() {
            val entered = runCatching {
                Money.parse(amountInput.text.toString().trim(), goal.currencyCode)
            }.getOrNull()
            preview.text = when {
                entered == null || entered.minorUnits <= 0L ->
                    getString(R.string.goal_funds_context, goal.saved.format(), goal.target.format())
                mode == FundsMode.WITHDRAW && entered.minorUnits > goal.saved.minorUnits ->
                    getString(R.string.goal_withdraw_too_much, goal.saved.format())
                mode == FundsMode.ADD -> {
                    val next = runCatching {
                        Math.addExact(goal.saved.minorUnits, entered.minorUnits)
                    }.getOrNull()
                    if (next == null) getString(R.string.goal_amount_invalid)
                    else getString(
                        R.string.goal_funds_preview_after,
                        Money(next, goal.currencyCode).format(),
                        goal.target.format()
                    )
                }
                else -> getString(
                    R.string.goal_funds_preview_after,
                    Money(goal.saved.minorUnits - entered.minorUnits, goal.currencyCode).format(),
                    goal.target.format()
                )
            }
        }
        refreshPreview()
        amountInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val titleRes = if (mode == FundsMode.ADD) R.string.add_funds else R.string.goal_withdraw
        val dialog = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(content)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(titleRes, null)
            .create()

        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.setOnClickListener {
                val amount = runCatching {
                    Money.parse(amountInput.text.toString().trim(), goal.currencyCode)
                }.getOrNull()
                if (amount == null) {
                    amountInput.error = getString(R.string.goal_amount_invalid)
                    return@setOnClickListener
                }
                if (amount.minorUnits <= 0L) {
                    amountInput.error = getString(R.string.amount_required)
                    return@setOnClickListener
                }
                if (mode == FundsMode.WITHDRAW && amount.minorUnits > goal.saved.minorUnits) {
                    amountInput.error = getString(R.string.goal_not_enough_saved)
                    return@setOnClickListener
                }
                confirm.isEnabled = false
                lifecycleScope.launch {
                    runCatching {
                        if (mode == FundsMode.ADD) repo.addGoalFunds(goal.id, amount)
                        else repo.withdrawGoalFunds(goal.id, amount)
                    }.onSuccess {
                        dialog.dismiss()
                        val completedNow = mode == FundsMode.ADD && runCatching {
                            Math.addExact(goal.saved.minorUnits, amount.minorUnits) >= goal.target.minorUnits
                        }.getOrDefault(false)
                        val message = when {
                            completedNow -> getString(R.string.goal_completed_message)
                            mode == FundsMode.ADD -> getString(R.string.goal_funds_added, amount.format())
                            else -> getString(R.string.goal_funds_withdrawn, amount.format())
                        }
                        snackbarWithUndo(message) {
                            lifecycleScope.launch {
                                runCatching {
                                    if (mode == FundsMode.ADD) repo.withdrawGoalFunds(goal.id, amount)
                                    else repo.addGoalFunds(goal.id, amount)
                                }.onFailure { snackbar(R.string.planning_error) }
                            }
                        }
                    }.onFailure {
                        confirm.isEnabled = true
                        amountInput.error = getString(R.string.planning_error)
                    }
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(goal: Goal) {
        AlertDialog.Builder(this)
            .setTitle(R.string.goal_delete_title)
            .setMessage(getString(R.string.goal_delete_message, goal.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteGoal(goal) }
            .show()
    }

    private fun deleteGoal(goal: Goal) {
        lifecycleScope.launch {
            runCatching { repo.deleteGoal(goal.id) }
                .onSuccess {
                    snackbarWithUndo(getString(R.string.goal_deleted)) {
                        lifecycleScope.launch {
                            runCatching { repo.restoreGoal(goal) }
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
        const val MENU_ADD = 1
        const val MENU_WITHDRAW = 2
        const val MENU_EDIT = 3
        const val MENU_DELETE = 4
    }
}
