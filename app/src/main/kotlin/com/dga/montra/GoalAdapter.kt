package com.dga.montra

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.dga.montra.databinding.ItemGoalRowBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.domain.Goal
import com.dga.montra.domain.GoalStatus
import java.text.DateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** Card row for the goals list: name, status, amount, progress and a per-row action menu. */
class GoalAdapter(
    private val onOpen: (Goal) -> Unit,
    private val onMenu: (Goal, View) -> Unit
) : ListAdapter<Goal, GoalAdapter.Holder>(GoalDiff) {

    class Holder(val binding: ItemGoalRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemGoalRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val goal = getItem(position)
        val context = holder.itemView.context
        val zone = ZoneId.systemDefault()
        val status = goal.status(LocalDate.now(), zone)
        val percent = goal.progressPercent()
        val meta = metaText(context, goal, status, zone)

        holder.binding.goalRowName.text = goal.name
        holder.binding.goalRowStatus.text = context.getString(status.labelRes())
        holder.binding.goalRowStatus.backgroundTintList = ColorStateList.valueOf(context.getColor(status.colorRes()))
        holder.binding.goalRowStatus.setTextColor(context.getColor(status.textColorRes()))

        holder.binding.goalRowAmount.text = context.getString(
            R.string.goal_amount_progress,
            goal.saved.format(),
            goal.target.format()
        )
        holder.binding.goalRowProgress.progressTintList = ColorStateList.valueOf(context.getColor(status.colorRes()))
        holder.binding.goalRowProgress.max = 100
        holder.binding.goalRowProgress.progress = percent
        holder.binding.goalRowProgress.contentDescription = context.getString(
            R.string.goal_amount_progress,
            goal.saved.format(),
            goal.target.format()
        )
        holder.binding.goalRowMeta.text = meta

        holder.itemView.contentDescription = context.getString(
            R.string.goal_row_description,
            goal.name,
            goal.saved.format(),
            goal.target.format(),
            percent,
            meta
        )
        holder.itemView.setOnClickListener { onOpen(goal) }
        holder.binding.goalRowMenu.setOnClickListener { onMenu(goal, holder.binding.goalRowMenu) }
    }
}

/** A goal is identified by its id; its saved amount and status are content. */
private object GoalDiff : DiffUtil.ItemCallback<Goal>() {
    override fun areItemsTheSame(oldItem: Goal, newItem: Goal): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Goal, newItem: Goal): Boolean = oldItem == newItem
}

private fun metaText(context: Context, goal: Goal, status: GoalStatus, zone: ZoneId): String {
    val parts = mutableListOf(context.getString(R.string.goal_percent, goal.progressPercent()))
    val due = goal.dueDate(zone)
    when {
        due == null -> if (!goal.isComplete) parts += context.getString(R.string.goal_no_date)
        status == GoalStatus.OVERDUE ->
            parts += context.getString(R.string.goal_overdue_since, formatGoalDate(due))
        else -> parts += context.getString(R.string.goal_due_date, formatGoalDate(due))
    }
    return parts.joinToString("  ·  ")
}

internal fun formatGoalDate(date: LocalDate): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(date.atStartOfDay(ZoneId.systemDefault()).toInstant()))

internal fun formatGoalDate(millis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

internal fun GoalStatus.labelRes(): Int = when (this) {
    GoalStatus.COMPLETED -> R.string.goal_status_completed
    GoalStatus.OVERDUE -> R.string.goal_status_overdue
    GoalStatus.DUE_SOON -> R.string.goal_status_due_soon
    GoalStatus.TRACKING -> R.string.goal_status_tracking
    GoalStatus.OPEN_ENDED -> R.string.goal_status_open_ended
}

internal fun GoalStatus.colorRes(): Int = when (this) {
    GoalStatus.COMPLETED -> R.color.m3_positive
    GoalStatus.OVERDUE -> R.color.m3_negative
    GoalStatus.DUE_SOON -> R.color.m3_highlight
    GoalStatus.TRACKING -> R.color.m3_primary
    GoalStatus.OPEN_ENDED -> R.color.m3_on_surface_variant
}

/** Highlights are light in both themes, so they need dark text rather than on_primary. */
internal fun GoalStatus.textColorRes(): Int = when (this) {
    GoalStatus.DUE_SOON -> R.color.m3_on_highlight
    else -> R.color.m3_on_primary
}
