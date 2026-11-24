package com.dga.montra

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class AccountsActivity : FinanceFormsActivity() {
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        findViewById<View>(R.id.accounts_back).setOnClickListener { finish() }
        findViewById<View>(R.id.accounts_add_shortcut).setOnClickListener { showAddAccountDialog() }
        findViewById<View>(R.id.accounts_add).setOnClickListener { showAddAccountDialog() }
        findViewById<View>(R.id.accounts_transfer).setOnClickListener { showTransferDialog() }
    }

    override fun onStart() {
        super.onStart()
        job = scope.launch {
            try {
                repository.prepareForObservation()
                repository.observeAccountBalances().catch { toast(R.string.storage_error) }.collect { balances ->
                    val rows = findViewById<LinearLayout>(R.id.accounts_rows)
                    val hidden = settings.getBoolean("balance_hidden", false)
                    rows.removeAllViews()
                    findViewById<View>(R.id.accounts_empty).visibility = if (balances.isEmpty()) View.VISIBLE else View.GONE
                    findViewById<View>(R.id.accounts_transfer).visibility = if (balances.size < 2) View.GONE else View.VISIBLE
                    balances.forEach { (account, balance) ->
                        val card = layoutInflater.inflate(R.layout.item_account_card, rows, false)
                        card.findViewById<TextView>(R.id.account_card_name).text = account.name
                        card.findViewById<TextView>(R.id.account_card_balance).text = if (hidden) getString(R.string.masked_balance) else balance.format()
                        card.findViewById<TextView>(R.id.account_card_detail).text = getString(R.string.montra_view_account_activity, balance.currencyCode)
                        card.setOnClickListener {
                            startActivity(Intent(this@AccountsActivity, AccountDetailActivity::class.java).putExtra("ACCOUNT_ID", account.id))
                        }
                        rows.addView(card)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                toast(R.string.storage_error)
            }
        }
    }

    override fun onStop() {
        job?.cancel()
        super.onStop()
    }
}
