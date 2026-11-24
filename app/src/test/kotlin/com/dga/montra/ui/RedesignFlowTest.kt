package com.dga.montra.ui

import android.app.Activity
import android.app.Dialog
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import com.dga.montra.AccountsActivity
import com.dga.montra.BudgetActivity
import com.dga.montra.GoalsActivity
import com.dga.montra.InsightsActivity
import com.dga.montra.MainActivity
import com.dga.montra.ProfileActivity
import com.dga.montra.R
import com.dga.montra.TransactionsActivity
import com.dga.montra.data.RoomFinanceRepository
import com.dga.montra.domain.Account
import com.dga.montra.domain.Money
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w374dp-h822dp")
class RedesignFlowTest {
    @Test fun `entry saves against a real account and transfer only lists compatible destinations`() {
        val repository = RoomFinanceRepository.get(RuntimeEnvironment.getApplication())
        runBlocking {
            repository.addAccount(Account("cash", "Everyday cash", Money(125_000, "PHP")))
            repository.addAccount(Account("savings", "Savings", Money(850_000, "PHP")))
            repository.addAccount(Account("yen", "Yen", Money(2_000, "JPY")))
        }
        val home = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val screen = home.get()
            await { screen.findViewById<TextView>(R.id.home_currency).text.toString() == "PHP" }
            screen.findViewById<View>(R.id.add_transaction_button).performClick()
            val entry = awaitDialog(R.id.transaction_amount)
            entry.findViewById<EditText>(R.id.transaction_amount).setText("240.00")
            entry.findViewById<EditText>(R.id.transaction_note).setText("Lunch at the corner café")
            entry.findViewById<View>(R.id.entry_save).performClick()
            await { !entry.isShowing }
            val saved = runBlocking { repository.allTransactions().single() }
            assertEquals("cash", saved.accountId)
            assertEquals(Money(24_000, "PHP"), saved.amount)
            await { screen.findViewById<TextView>(R.id.balance).text.toString() == Money(951_000, "PHP").format() }

            screen.findViewById<View>(R.id.add_transfer_button).performClick()
            val transfer = awaitDialog(R.id.transfer_from_account)
            val from = transfer.findViewById<Spinner>(R.id.transfer_from_account)
            val to = transfer.findViewById<Spinner>(R.id.transfer_to_account)
            assertEquals(2, from.count)
            assertEquals(1, to.count)
            assertEquals("Savings (PHP)", to.selectedItem.toString())
            transfer.dismiss()
        } finally {
            home.pause().stop().destroy()
        }
    }

    @Test fun `redesigned destinations inflate their required controls`() {
        val screens = mapOf<Class<out Activity>, Int>(
            TransactionsActivity::class.java to R.id.transaction_list,
            AccountsActivity::class.java to R.id.accounts_rows,
            BudgetActivity::class.java to R.id.plan_goals,
            GoalsActivity::class.java to R.id.plan_budgets,
            InsightsActivity::class.java to R.id.insights_net,
            ProfileActivity::class.java to R.id.profile_restore
        )
        screens.forEach { (activity, control) ->
            val screen = Robolectric.buildActivity(activity).create()
            try {
                assertNotNull(activity.simpleName, screen.get().findViewById<View>(control))
            } finally {
                screen.destroy()
            }
        }
    }

    private fun awaitDialog(control: Int): Dialog {
        await { ShadowDialog.getLatestDialog()?.let { it.isShowing && it.findViewById<View>(control) != null } == true }
        return ShadowDialog.getLatestDialog()
    }

    private fun await(condition: () -> Boolean) {
        val timeout = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < timeout) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Expected screen state was not reached", condition())
    }
}
