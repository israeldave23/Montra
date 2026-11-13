package com.dga.montra

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/** Display-ready content for one currency card on the Insights screen. */
internal data class InsightSummaryRow(
    val currencyCode: String,
    val expense: String,
    val flow: String,
    val change: String,
    val average: String,
    val top: String,
    val description: String
)

/** Display-ready content for one category bar. */
internal data class InsightCategoryRow(
    val categoryId: String,
    val name: String,
    val amount: String,
    val share: String,
    val percent: Int,
    val description: String
)

/**
 * Renders the monthly insight as a currency card followed by that currency's category bars, so
 * amounts from different currencies are never added together or shown under one total.
 */
class InsightAdapter : ListAdapter<Any, RecyclerView.ViewHolder>(Diff) {

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is InsightSummaryRow) TYPE_SUMMARY else TYPE_CATEGORY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_SUMMARY) {
            SummaryHolder(inflater.inflate(R.layout.item_insight_summary, parent, false))
        } else {
            CategoryHolder(inflater.inflate(R.layout.item_insight_row, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is InsightSummaryRow -> (holder as SummaryHolder).bind(row)
            is InsightCategoryRow -> (holder as CategoryHolder).bind(row)
        }
    }

    private class SummaryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val currency: TextView = view.findViewById(R.id.insight_summary_currency)
        private val expense: TextView = view.findViewById(R.id.insight_summary_expense)
        private val flow: TextView = view.findViewById(R.id.insight_summary_flow)
        private val change: TextView = view.findViewById(R.id.insight_summary_change)
        private val average: TextView = view.findViewById(R.id.insight_summary_average)
        private val top: TextView = view.findViewById(R.id.insight_summary_top)

        fun bind(row: InsightSummaryRow) {
            val context = itemView.context
            currency.text = row.currencyCode
            expense.text = row.expense
            flow.text = row.flow
            change.text = row.change
            average.text = row.average
            top.text = row.top
            top.visibility = if (row.top.isBlank()) View.GONE else View.VISIBLE
            itemView.contentDescription = row.description
            expense.setTextColor(context.getColor(R.color.m3_on_surface))
        }
    }

    private class CategoryHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val icon: android.widget.ImageView = view.findViewById(R.id.insight_row_icon)
        private val name: TextView = view.findViewById(R.id.insight_category)
        private val amount: TextView = view.findViewById(R.id.insight_amount)
        private val share: TextView = view.findViewById(R.id.insight_share)
        private val progress: ProgressBar = view.findViewById(R.id.insight_progress)

        fun bind(row: InsightCategoryRow) {
            val context = itemView.context
            icon.setImageResource(categoryIconRes(row.categoryId))
            icon.contentDescription = row.name
            name.text = row.name
            amount.text = row.amount
            share.text = row.share
            progress.progressTintList =
                ColorStateList.valueOf(context.getColor(R.color.m3_primary))
            progress.max = 100
            progress.progress = row.percent
            progress.contentDescription = row.description
            itemView.contentDescription = row.description
        }
    }

    private companion object {
        const val TYPE_SUMMARY = 0
        const val TYPE_CATEGORY = 1
    }
}

/**
 * A currency card and its category bars are different rows with different identity, so the diff has
 * to look at the row type before comparing ids.
 */
private object Diff : DiffUtil.ItemCallback<Any>() {
    override fun areItemsTheSame(oldItem: Any, newItem: Any): Boolean = when {
        oldItem is InsightSummaryRow && newItem is InsightSummaryRow ->
            oldItem.currencyCode == newItem.currencyCode
        oldItem is InsightCategoryRow && newItem is InsightCategoryRow ->
            oldItem.categoryId == newItem.categoryId
        else -> false
    }

    override fun areContentsTheSame(oldItem: Any, newItem: Any): Boolean = oldItem == newItem
}
