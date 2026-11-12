package com.dga.montra

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.dga.montra.domain.Money
import com.dga.montra.domain.TransactionType
import java.text.DateFormat
import java.util.Date
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import androidx.recyclerview.widget.RecyclerView
import com.dga.montra.domain.Transaction

class TransactionListAdapter(
    val transactions: List<Transaction>,
    private val onClick: ((Transaction) -> Unit)? = null,
    private val onDelete: ((Transaction) -> Unit)? = null
) : RecyclerView.Adapter<TransactionListAdapter.Holder>() {
    class Holder(view: View) : RecyclerView.ViewHolder(view)
    override fun getItemCount() = transactions.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val container = android.widget.LinearLayout(parent.context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val header = TextView(parent.context).apply {
            setTextColor(context.getColor(R.color.lumen_muted))
            setPadding(0, context.resources.getDimensionPixelSize(R.dimen.space_16), 0, context.resources.getDimensionPixelSize(R.dimen.space_8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, context.resources.getDimension(R.dimen.text_14))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        container.addView(header)
        android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_transaction_row, container, true)
        return Holder(container)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val tx = transactions[position]
        val expense = tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER_OUT
        val date = Instant.ofEpochMilli(tx.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        holder.itemView.findViewById<TextView>(R.id.transaction_category).text = tx.categoryId.replaceFirstChar { it.uppercase() }
        holder.itemView.findViewById<android.widget.ImageView>(R.id.transaction_category_icon).setImageResource(categoryIconRes(tx.categoryId))
        val previousDate = transactions.getOrNull(position - 1)?.let { Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
        val header = (holder.itemView as android.widget.LinearLayout).getChildAt(0) as TextView
        header.visibility = if (previousDate == date) View.GONE else View.VISIBLE
        header.text = when (date) {
            today -> holder.itemView.context.getString(R.string.date_today)
            today.minusDays(1) -> holder.itemView.context.getString(R.string.date_yesterday)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tx.timestampMillis))
        }
        holder.itemView.findViewById<TextView>(R.id.transaction_note).text = tx.note.ifBlank { tx.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }
        holder.itemView.findViewById<TextView>(R.id.transaction_date).visibility = View.GONE
        holder.itemView.findViewById<TextView>(R.id.transaction_amount).apply {
            text = (if (expense) "−" else "+") + Money(tx.amount.minorUnits, tx.amount.currencyCode).format()
            setTextColor(context.getColor(if (expense) R.color.lumen_negative else R.color.lumen_positive))
        }
        holder.itemView.setOnClickListener { onClick?.invoke(tx) }
        holder.itemView.setOnLongClickListener {
            onDelete?.invoke(tx)
            onDelete != null
        }
    }
}
