package com.dga.montra

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dga.montra.domain.Money
import com.dga.montra.domain.TransactionType
import java.text.DateFormat
import java.util.Date
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.domain.Transaction

class TransactionListAdapter(
    val transactions: List<Transaction>,
    private val onClick: (Transaction) -> Unit,
    private val onDelete: (Transaction) -> Unit
) : RecyclerView.Adapter<TransactionListAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun getItemCount() = transactions.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_transaction_row, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tx = transactions[position]
        val expense = tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER_OUT
        holder.itemView.findViewById<TextView>(R.id.transaction_category).text = tx.categoryId.replaceFirstChar { it.uppercase() }
        holder.itemView.findViewById<TextView>(R.id.transaction_note).text = tx.note.ifBlank { tx.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }
        holder.itemView.findViewById<TextView>(R.id.transaction_date).text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tx.timestampMillis))
        holder.itemView.findViewById<TextView>(R.id.transaction_amount).apply {
            text = (if (expense) "−" else "+") + Money(tx.amount.minorUnits, tx.amount.currencyCode).format()
            setTextColor(context.getColor(if (expense) R.color.lumen_negative else R.color.lumen_positive))
        }
        holder.itemView.setOnClickListener { onClick(tx) }
        holder.itemView.setOnLongClickListener { onDelete(tx); true }
    }
}
