package com.dga.montra

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.databinding.ItemAccountRowBinding

/**
 * Display-ready content for one account row.
 *
 * The row is assembled by the screen (`AccountBalance` -> these strings) rather than by the adapter,
 * because "hidden balance" is a display decision the screen already owns: masking here would mean
 * the adapter reading user settings, and the diff would not notice the toggle because the underlying
 * account did not change. As data, the toggle changes the content and rebinds.
 */
internal data class AccountRowContent(
    val id: String,
    val initials: String,
    val name: String,
    val meta: String,
    val balance: String,
    val archived: Boolean,
    val description: String
)

/** One account per row on the home feed; tapping it opens that account, never an arbitrary one. */
internal class AccountRowAdapter(
    private val onOpen: (String) -> Unit
) : ListAdapter<AccountRowContent, AccountRowAdapter.Holder>(AccountRowDiff) {

    inner class Holder(
        private val binding: ItemAccountRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: AccountRowContent) {
            binding.accountRowInitial.text = row.initials
            binding.accountRowName.text = row.name
            binding.accountRowMeta.text = row.meta
            binding.accountRowBalance.text = row.balance
            binding.accountRowBadge.visibility = if (row.archived) View.VISIBLE else View.GONE
            binding.root.contentDescription = row.description
            binding.root.setOnClickListener { onOpen(row.id) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAccountRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))
}

/** An account is identified by its id; masked and unmasked amounts are different content. */
private object AccountRowDiff : DiffUtil.ItemCallback<AccountRowContent>() {
    override fun areItemsTheSame(oldItem: AccountRowContent, newItem: AccountRowContent): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: AccountRowContent, newItem: AccountRowContent): Boolean =
        oldItem == newItem
}
