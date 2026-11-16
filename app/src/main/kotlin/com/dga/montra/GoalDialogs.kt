package com.dga.montra

import android.app.DatePickerDialog
import android.content.res.ColorStateList
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.dga.montra.databinding.DialogGoalDetailBinding
import com.dga.montra.databinding.DialogGoalEditBinding
import com.dga.montra.databinding.DialogGoalFundsBinding
import com.dga.montra.domain.Goal
import com.dga.montra.domain.GoalStatus
import com.dga.montra.domain.Money
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * Every dialog the goals screen opens: the detail card, the editor, adding or withdrawing funds, the
 * per-row menu and the delete confirmation.
 *
 * They are extensions on [GoalsActivity] rather than functions on an object so they can use the
 * activity's repository, snackbars and currency, and so the screen's call sites stay `showGoalEditor()`
 * rather than `GoalDialogs.showEditor(this, ...)`. The screen itself is left with rendering and state,
 * which is what a screen should be: these dialogs were 351 of its 505 lines.
 */
private object GoalMenu {
    const val ADD = 1
    const val WITHDRAW = 2
    const val EDIT = 3
    const val DELETE = 4
}

    internal fun GoalsActivity.showGoalDetail(goal: Goal) {
        val content = DialogGoalDetailBinding.inflate(layoutInflater)
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val status = goal.status(today, zone)
        val percent = goal.progressPercent()

        content.goalDetailName.text = goal.name
        content.goalDetailStatus.apply {
            text = getString(status.labelRes())
            backgroundTintList = ColorStateList.valueOf(getColor(status.colorRes()))
            setTextColor(getColor(status.textColorRes()))
        }
        content.goalDetailAmounts.text =
            getString(R.string.goal_amount_progress, goal.saved.format(), goal.target.format())
        content.goalDetailProgress.apply {
            progressTintList = ColorStateList.valueOf(getColor(status.colorRes()))
            max = 100
            progress = percent
        }
        content.goalDetailMeta.text = detailMeta(goal, status, percent, zone)
        content.goalDetailPlan.apply {
            val plan = planText(goal, today, zone)
            text = plan
            visibility = if (plan.isBlank()) View.GONE else View.VISIBLE
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(content.root)
            .setNegativeButton(R.string.close, null)
            .create()

        content.goalDetailAddFunds.setOnClickListener {
            dialog.dismiss()
            showFundsDialog(goal, FundsMode.ADD)
        }
        content.goalDetailWithdraw.setOnClickListener {
            dialog.dismiss()
            showFundsDialog(goal, FundsMode.WITHDRAW)
        }
        content.goalDetailEdit.setOnClickListener {
            dialog.dismiss()
            showGoalEditor(goal)
        }
        content.goalDetailDelete.setOnClickListener {
            dialog.dismiss()
            confirmDelete(goal)
        }
        dialog.show()
    }

    internal fun GoalsActivity.detailMeta(goal: Goal, status: GoalStatus, percent: Int, zone: ZoneId): String {
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
    internal fun GoalsActivity.planText(goal: Goal, today: LocalDate, zone: ZoneId): String {
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

    internal fun GoalsActivity.showGoalMenu(goal: Goal, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, GoalMenu.ADD, 0, R.string.add_funds)
            menu.add(0, GoalMenu.WITHDRAW, 1, R.string.goal_withdraw)
            menu.add(0, GoalMenu.EDIT, 2, R.string.edit)
            menu.add(0, GoalMenu.DELETE, 3, R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    GoalMenu.ADD -> showFundsDialog(goal, FundsMode.ADD)
                    GoalMenu.WITHDRAW -> showFundsDialog(goal, FundsMode.WITHDRAW)
                    GoalMenu.EDIT -> showGoalEditor(goal)
                    GoalMenu.DELETE -> confirmDelete(goal)
                }
                true
            }
            show()
        }
    }

    internal fun GoalsActivity.showGoalEditor(existing: Goal?) {
        val content = DialogGoalEditBinding.inflate(layoutInflater)
        val nameInput = content.goalEditName
        val targetInput = content.goalEditTarget
        val savedInput = content.goalEditSaved
        val savedRow = content.goalEditSavedRow
        val dateButton = content.goalEditDate
        val clearDate = content.goalEditClearDate

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

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.new_goal else R.string.goal_edit_title)
            .setView(content.root)
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

    internal enum class FundsMode { ADD, WITHDRAW }

    internal fun GoalsActivity.showFundsDialog(goal: Goal, mode: FundsMode) {
        val content = DialogGoalFundsBinding.inflate(layoutInflater)
        val amountInput = content.goalFundsAmount
        val contextView = content.goalFundsContext
        val preview = content.goalFundsPreview
        val quickRow = content.goalFundsQuickRow
        val quick1 = content.goalFundsQuick1
        val quick2 = content.goalFundsQuick2
        val quick3 = content.goalFundsQuick3

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
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(content.root)
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

    internal fun GoalsActivity.confirmDelete(goal: Goal) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.goal_delete_title)
            .setMessage(getString(R.string.goal_delete_message, goal.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ -> deleteGoal(goal) }
            .show()
    }

    internal fun GoalsActivity.deleteGoal(goal: Goal) {
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

