package com.dga.montra

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import com.dga.montra.databinding.ItemInsightRowBinding
import com.dga.montra.databinding.ItemInsightSummaryBinding
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
            SummaryHolder(ItemInsightSummaryBinding.inflate(inflater, parent, false))
        } else {
            CategoryHolder(ItemInsightRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is InsightSummaryRow -> (holder as SummaryHolder).bind(row)
            is InsightCategoryRow -> (holder as CategoryHolder).bind(row)
        }
    }

    private class SummaryHolder(
        private val binding: ItemInsightSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: InsightSummaryRow) {
            val context = itemView.context
            binding.insightSummaryCurrency.text = row.currencyCode
            binding.insightSummaryExpense.text = row.expense
            binding.insightSummaryFlow.text = row.flow
            binding.insightSummaryChange.text = row.change
            binding.insightSummaryAverage.text = row.average
            binding.insightSummaryTop.text = row.top
            binding.insightSummaryTop.visibility = if (row.top.isBlank()) View.GONE else View.VISIBLE
            itemView.contentDescription = row.description
            binding.insightSummaryExpense.setTextColor(context.getColor(R.color.m3_on_surface))
        }
    }

    private class CategoryHolder(
        private val binding: ItemInsightRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: InsightCategoryRow) {
            val context = itemView.context
            binding.insightRowIcon.setImageResource(categoryIconRes(row.categoryId))
            binding.insightRowIcon.contentDescription = row.name
            binding.insightCategory.text = row.name
            binding.insightAmount.text = row.amount
            binding.insightShare.text = row.share
            binding.insightProgress.progressTintList =
                ColorStateList.valueOf(context.getColor(R.color.m3_primary))
            binding.insightProgress.max = 100
            binding.insightProgress.progress = row.percent
            binding.insightProgress.contentDescription = row.description
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
