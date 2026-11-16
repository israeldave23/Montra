package com.dga.montra

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.databinding.ItemTransactionRowBinding
import com.dga.montra.domain.Transaction

/**
 * The home feed's recent activity.
 *
 * No day headings here: the feed is a short, newest-first sample, and each row carries its own date
 * (see `bindTransaction(showDate = true)`). The activity screen is where grouping earns its space.
 */
internal class RecentTransactionAdapter :
    ListAdapter<Transaction, RecentTransactionAdapter.Holder>(TransactionDiff) {

    class Holder(
        private val binding: ItemTransactionRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(tx: Transaction) = binding.bindTransaction(tx, showDate = true)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemTransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
}
