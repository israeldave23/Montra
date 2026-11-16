package com.dga.montra

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.databinding.ItemTransactionDayHeaderBinding
import com.dga.montra.databinding.ItemTransactionRowBinding
import com.dga.montra.domain.Transaction
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** A row in the activity list: a day header, or a transaction filed under the header above it. */
internal sealed interface TransactionListItem {
    data class Day(val date: LocalDate) : TransactionListItem
    data class Row(val transaction: Transaction) : TransactionListItem
}

/**
 * The activity list, grouped by day.
 *
 * The day divider is a row type of its own rather than a header view hidden inside each transaction
 * row: the previous version built its rows in code, allocated a `LinearLayout` and a header
 * `TextView` per row, and hid the header by comparing positions on every bind. A header that is
 * real data diffs correctly when a transaction is inserted, deleted or undone.
 */
internal class TransactionListAdapter(
    private val onClick: ((Transaction) -> Unit)? = null,
    private val onDelete: ((Transaction) -> Unit)? = null
) : ListAdapter<TransactionListItem, RecyclerView.ViewHolder>(TransactionItemDiff) {

    /** The transaction at [position], or null when that row is a day header. */
    fun transactionAt(position: Int): Transaction? =
        (currentList.getOrNull(position) as? TransactionListItem.Row)?.transaction

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is TransactionListItem.Day -> TYPE_DAY
        is TransactionListItem.Row -> TYPE_ROW
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DAY -> DayHolder(ItemTransactionDayHeaderBinding.inflate(inflater, parent, false))
            else -> RowHolder(ItemTransactionRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is TransactionListItem.Day -> (holder as DayHolder).bind(item)
            is TransactionListItem.Row -> (holder as RowHolder).bind(item.transaction)
        }
    }

    private class DayHolder(
        private val binding: ItemTransactionDayHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TransactionListItem.Day) {
            val context = binding.root.context
            val today = LocalDate.now()
            binding.transactionDayHeader.text = when (item.date) {
                today -> context.getString(R.string.date_today)
                today.minusDays(1) -> context.getString(R.string.date_yesterday)
                else -> DateFormat.getDateInstance(DateFormat.MEDIUM)
                    .format(Date.from(item.date.atStartOfDay(ZoneId.systemDefault()).toInstant()))
            }
        }
    }

    private inner class RowHolder(
        private val binding: ItemTransactionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tx: Transaction) {
            // The day header above the group already says the date.
            binding.bindTransaction(tx, showDate = false)
            binding.root.setOnClickListener { onClick?.invoke(tx) }
            binding.root.setOnLongClickListener {
                onDelete?.invoke(tx)
                onDelete != null
            }
        }
    }

    companion object {
        private const val TYPE_DAY = 0
        private const val TYPE_ROW = 1

        /**
         * Groups [transactions] into day-header / row items, keeping the order it is given: the
         * repository already sorts newest first, and re-sorting here would silently disagree with
         * whatever that query decides.
         */
        fun itemsFor(
            transactions: List<Transaction>,
            zone: ZoneId = ZoneId.systemDefault()
        ): List<TransactionListItem> {
            val items = ArrayList<TransactionListItem>(transactions.size + 4)
            var lastDay: LocalDate? = null
            transactions.forEach { tx ->
                val day = Instant.ofEpochMilli(tx.timestampMillis).atZone(zone).toLocalDate()
                if (day != lastDay) {
                    items += TransactionListItem.Day(day)
                    lastDay = day
                }
                items += TransactionListItem.Row(tx)
            }
            return items
        }
    }
}

/** A day header is identified by its date; a row by its transaction id. */
private object TransactionItemDiff : DiffUtil.ItemCallback<TransactionListItem>() {
    override fun areItemsTheSame(
        oldItem: TransactionListItem,
        newItem: TransactionListItem
    ): Boolean = when {
        oldItem is TransactionListItem.Day && newItem is TransactionListItem.Day ->
            oldItem.date == newItem.date
        oldItem is TransactionListItem.Row && newItem is TransactionListItem.Row ->
            oldItem.transaction.id == newItem.transaction.id
        else -> false
    }

    override fun areContentsTheSame(
        oldItem: TransactionListItem,
        newItem: TransactionListItem
    ): Boolean = oldItem == newItem
}
