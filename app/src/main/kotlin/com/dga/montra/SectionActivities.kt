package com.dga.montra

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.data.toDomain
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionCategories
import com.dga.montra.domain.TransactionType
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.ZoneId

abstract class SectionActivity(
    val tab: Int
) : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
    }
}


class AccountDetailActivity : Activity() {
    private val repo by lazy { RoomFinanceRepository.get(this) }
    private val scope = MainScope()
    private lateinit var accountId: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_account_detail)
        bindBottomNavigation(findViewById(R.id.bottom_navigation), 0)
        scope.launch {
            val accounts = repo.allAccounts()
            val id = intent.getStringExtra("ACCOUNT_ID") ?: accounts.firstOrNull()?.id
            if (id == null) {
                runOnUiThread { android.widget.Toast.makeText(this@AccountDetailActivity, R.string.account_not_found, android.widget.Toast.LENGTH_LONG).show() }
                return@launch
            }
            accountId = id
            val account = accounts.firstOrNull { it.id == id } ?: run {
                runOnUiThread { android.widget.Toast.makeText(this@AccountDetailActivity, R.string.account_not_found, android.widget.Toast.LENGTH_LONG).show() }
                return@launch
            }
            findViewById<TextView>(R.id.account_name).text = account.name
            findViewById<TextView>(R.id.account_balance).text = account.openingBalance.format()
            val list = findViewById<ListView>(R.id.account_transactions)
            repo.observeAccountTransactions(accountId).collect { txs ->
                list.adapter = AccountTransactionAdapter(this@AccountDetailActivity, txs.map { it.toDomain() })
            }
        }
    }

    override fun onDestroy() {
        scope.coroutineContext.cancel()
        super.onDestroy()
    }

    private inner class AccountTransactionAdapter(val activity: Activity, val items: List<Transaction>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val t = items[position]
            val row = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_transaction_row, parent, false)
            row.findViewById<TextView>(R.id.transaction_category).text = TransactionCategories.expense.firstOrNull { it.id == t.categoryId }?.name
                ?: TransactionCategories.income.firstOrNull { it.id == t.categoryId }?.name
                ?: t.categoryId.replaceFirstChar { it.uppercase() }
            val date = java.time.Instant.ofEpochMilli(t.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            row.findViewById<TextView>(R.id.transaction_date).text = date
            row.findViewById<TextView>(R.id.transaction_note).text = t.note.takeIf { it.isNotEmpty() } ?: date
            val amountView = row.findViewById<TextView>(R.id.transaction_amount)
            amountView.text = when {
                t.type == TransactionType.INCOME || t.type == TransactionType.REFUND -> "+${t.amount.format()}"
                else -> "-${t.amount.format()}"
            }
            amountView.setTextColor(activity.getColor(if (t.type == TransactionType.INCOME || t.type == TransactionType.REFUND) R.color.lumen_success else R.color.lumen_error))
            return row
        }
    }
}

