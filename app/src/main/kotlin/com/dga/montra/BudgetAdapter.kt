package com.dga.montra

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
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
) : RecyclerView.Adapter<BudgetAdapter.Holder>() {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: android.widget.ImageView = view.findViewById(R.id.budget_row_icon)
        val category: TextView = view.findViewById(R.id.budget_row_category)
        val state: TextView = view.findViewById(R.id.budget_row_state)
        val amount: TextView = view.findViewById(R.id.budget_row_amount)
        val meta: TextView = view.findViewById(R.id.budget_row_meta)
        val progress: ProgressBar = view.findViewById(R.id.budget_row_progress)
        val menu: TextView = view.findViewById(R.id.budget_row_menu)
    }

    private var items: List<BudgetProgress> = emptyList()

    fun submit(budgets: List<BudgetProgress>) {
        items = budgets
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_budget_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val state = item.state
        val stateLabel = context.getString(state.labelRes())
        val spent = item.spent.format()
        val limit = item.limit.format()
        val percent = item.usedPercent()

        holder.icon.setImageResource(categoryIconRes(item.budget.categoryId))
        holder.icon.contentDescription = categoryLabel(item.budget.categoryId)
        holder.category.text = categoryLabel(item.budget.categoryId)
        holder.state.text = stateLabel
        holder.state.backgroundTintList = ColorStateList.valueOf(context.getColor(state.colorRes()))
        holder.state.setTextColor(context.getColor(state.textColorRes()))

        holder.amount.text = context.getString(R.string.budget_spent_of_limit, spent, limit)
        holder.progress.progressTintList = ColorStateList.valueOf(context.getColor(state.colorRes()))
        holder.progress.max = 100
        holder.progress.progress = percent
        holder.progress.contentDescription = context.getString(
            R.string.budget_row_description,
            categoryLabel(item.budget.categoryId),
            spent,
            limit,
            stateLabel
        )
        holder.meta.text = metaText(context, item, percent)

        holder.itemView.contentDescription = context.getString(
            R.string.budget_row_description,
            categoryLabel(item.budget.categoryId),
            spent,
            limit,
            stateLabel
        )
        holder.itemView.setOnClickListener { onMenu(item, holder.menu) }
        holder.menu.setOnClickListener { onMenu(item, holder.menu) }
    }
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
