package com.dga.montra

import android.graphics.Typeface
import android.view.View
import androidx.recyclerview.widget.DiffUtil
import com.dga.montra.databinding.ItemTransactionRowBinding
import com.dga.montra.domain.Money
import com.dga.montra.domain.PaymentMethod
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import java.text.DateFormat
import java.util.Date

/**
 * One transaction row, filled the same way on the home feed and in the activity list.
 *
 * The two screens differ only in the date: home shows it under the note, the activity list already
 * has a day header above the group and hides it. Everything else — the sign, the colour, the
 * fallback label for a blank note — is decided here once, so the same transaction cannot read
 * differently on two screens.
 */
internal fun ItemTransactionRowBinding.bindTransaction(tx: Transaction, showDate: Boolean) {
    val context = root.context
    val expense = tx.type == TransactionType.EXPENSE || tx.type == TransactionType.TRANSFER_OUT

    transactionCategory.text = tx.categoryId.replaceFirstChar { it.uppercase() }
    transactionCategoryIcon.setImageResource(categoryIconRes(tx.categoryId))
    transactionNote.text = tx.note.ifBlank { tx.type.defaultLabel() }

    transactionDate.text = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tx.timestampMillis))
    transactionDate.visibility = if (showDate) View.VISIBLE else View.GONE

    val paymentLabel = when (tx.paymentMethod) {
        PaymentMethod.CASH -> context.getString(R.string.payment_method_cash)
        PaymentMethod.DEBIT_CARD -> context.getString(R.string.payment_method_debit_card)
        PaymentMethod.CREDIT_CARD -> context.getString(R.string.payment_method_credit_card)
        PaymentMethod.OTHER -> context.getString(R.string.payment_method_other)
    }
    val billingInfo = if (tx.paymentMethod == PaymentMethod.CREDIT_CARD && tx.billingDateMillis != null) {
        " · ${context.getString(R.string.transaction_billing_date)}: ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(tx.billingDateMillis))}"
    } else ""
    transactionPaymentMethod.text = "$paymentLabel$billingInfo"
    transactionPaymentMethod.visibility = View.VISIBLE

    transactionAmount.apply {
        text = context.getString(
            if (expense) R.string.amount_negative else R.string.amount_positive,
            Money(tx.amount.minorUnits, tx.amount.currencyCode).format()
        )
        setTextColor(context.getColor(if (expense) R.color.m3_negative else R.color.m3_positive))
        setTypeface(typeface, Typeface.BOLD)
    }
}

/** "transfer_out" is not a sentence; the note falls back to this when the user typed nothing. */
internal fun TransactionType.defaultLabel(): String =
    name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

/** Identity is the transaction id; every displayed field is content. */
internal object TransactionDiff : DiffUtil.ItemCallback<Transaction>() {
    override fun areItemsTheSame(oldItem: Transaction, newItem: Transaction): Boolean =
        oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Transaction, newItem: Transaction): Boolean =
        oldItem == newItem
}
