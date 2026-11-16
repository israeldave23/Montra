package com.dga.montra

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.dga.montra.databinding.ItemBudgetRowBinding
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.domain.BudgetProgress
import com.dga.montra.domain.BudgetState
import com.dga.montra.domain.Money

/**
 * One card per budget: category, how the month is going, spent against the limit and what is left.
 * The bar and pill both use the state colour so "over" is obvious at a glance.
 */
class BudgetAdapter(
    private val onMenu: (BudgetProgress, View) -> Unit
) : ListAdapter<BudgetProgress, BudgetAdapter.Holder>(BudgetDiff) {

    class Holder(val binding: ItemBudgetRowBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBudgetRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val context = holder.itemView.context
        val state = item.state
        val stateLabel = context.getString(state.labelRes())
        val spent = item.spent.format()
        val limit = item.limit.format()
        val percent = item.usedPercent()

        holder.binding.budgetRowIcon.setImageResource(categoryIconRes(item.budget.categoryId))
        holder.binding.budgetRowIcon.contentDescription = categoryLabel(item.budget.categoryId)
        holder.binding.budgetRowCategory.text = categoryLabel(item.budget.categoryId)
        holder.binding.budgetRowState.text = stateLabel
        holder.binding.budgetRowState.backgroundTintList = ColorStateList.valueOf(context.getColor(state.colorRes()))
        holder.binding.budgetRowState.setTextColor(context.getColor(state.textColorRes()))

        holder.binding.budgetRowAmount.text = context.getString(R.string.budget_spent_of_limit, spent, limit)
        holder.binding.budgetRowProgress.progressTintList = ColorStateList.valueOf(context.getColor(state.colorRes()))
        holder.binding.budgetRowProgress.max = 100
        holder.binding.budgetRowProgress.progress = percent
        holder.binding.budgetRowProgress.contentDescription = context.getString(
            R.string.budget_row_description,
            categoryLabel(item.budget.categoryId),
            spent,
            limit,
            stateLabel
        )
        holder.binding.budgetRowMeta.text = metaText(context, item, percent)

        holder.itemView.contentDescription = context.getString(
            R.string.budget_row_description,
            categoryLabel(item.budget.categoryId),
            spent,
            limit,
            stateLabel
        )
        holder.itemView.setOnClickListener { onMenu(item, holder.binding.budgetRowMenu) }
        holder.binding.budgetRowMenu.setOnClickListener { onMenu(item, holder.binding.budgetRowMenu) }
    }
}

/** Identity is the budget row; contents cover the month's progress, so a new spend animates. */
private object BudgetDiff : DiffUtil.ItemCallback<BudgetProgress>() {
    override fun areItemsTheSame(oldItem: BudgetProgress, newItem: BudgetProgress): Boolean =
        oldItem.budget.id == newItem.budget.id

    override fun areContentsTheSame(oldItem: BudgetProgress, newItem: BudgetProgress): Boolean =
        oldItem == newItem
}

private fun metaText(context: Context, item: BudgetProgress, percent: Int): String {
    val remaining = item.remainingMinorUnits
    val balance = when {
        remaining > 0L -> context.getString(
            R.string.budget_remaining,
            Money(remaining, item.budget.currencyCode).format()
        )
        remaining < 0L -> context.getString(
            R.string.budget_over_by,
            Money(-remaining, item.budget.currencyCode).format()
        )
        else -> context.getString(
            R.string.budget_remaining,
            Money(0L, item.budget.currencyCode).format()
        )
    }
    return listOf(balance, context.getString(R.string.budget_used_percent, percent))
        .joinToString("  ·  ")
}

internal fun BudgetState.labelRes(): Int = when (this) {
    BudgetState.OVER -> R.string.budget_state_over
    BudgetState.AT_LIMIT -> R.string.budget_state_spent
    BudgetState.NEAR_LIMIT -> R.string.budget_state_near
    BudgetState.ON_TRACK -> R.string.budget_state_on_track
}

internal fun BudgetState.colorRes(): Int = when (this) {
    BudgetState.OVER -> R.color.m3_negative
    BudgetState.AT_LIMIT, BudgetState.NEAR_LIMIT -> R.color.m3_highlight
    BudgetState.ON_TRACK -> R.color.m3_primary
}

/** Highlights are light in both themes, so they need dark text rather than on_primary. */
internal fun BudgetState.textColorRes(): Int = when (this) {
    BudgetState.AT_LIMIT, BudgetState.NEAR_LIMIT -> R.color.m3_on_highlight
    else -> R.color.m3_on_primary
}
