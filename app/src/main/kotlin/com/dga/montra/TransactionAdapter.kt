package com.dga.montra

import android.content.Context
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import java.text.DateFormat
import java.util.Date

class TransactionAdapter(context: Context, private val data: List<Transaction>) : ArrayAdapter<Transaction>(context, R.layout.item_transaction_row, data) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_transaction_row, parent, false)
        val tx = data[position]
        val expense = tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER_OUT
        row.findViewById<TextView>(R.id.transaction_category).text = tx.categoryId.replaceFirstChar { it.uppercase() }
        row.findViewById<TextView>(R.id.transaction_category_icon).text = categoryIcon(tx.categoryId)
        row.findViewById<TextView>(R.id.transaction_note).text = tx.note.ifBlank { tx.type.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() } }
        row.findViewById<TextView>(R.id.transaction_date).text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tx.timestampMillis))
        row.findViewById<TextView>(R.id.transaction_amount).apply {
            text = (if (expense) "−" else "+") + Money(tx.amount.minorUnits, tx.amount.currencyCode).format()
            setTextColor(context.getColor(if (expense) R.color.lumen_negative else R.color.lumen_positive))
            setTypeface(typeface, Typeface.BOLD)
        }
        return row
    }
}
