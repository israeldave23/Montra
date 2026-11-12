package com.dga.montra.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AccountsTest {
    private fun account(
        id: String = "a",
        name: String = "Cash",
        opening: Long = 100_000L,
        currency: String = "PHP",
        archived: Boolean = false
    ) = Account(id, name, Money(opening, currency), archived)

    private fun tx(
        amount: Long,
        type: TransactionType = TransactionType.EXPENSE,
        account: String = "a",
        currency: String = "PHP",
        at: Long = 1_000L
    ) = Transaction(
        accountId = account,
        amount = Money(amount, currency),
        type = type,
        categoryId = "dining",
        timestampMillis = at
    )

    @Test
    fun `current balance is opening plus every signed transaction`() {
        val balances = accountBalances(
            listOf(account(opening = 100_000L)),
            listOf(
                tx(30_000L, TransactionType.EXPENSE),
                tx(5_000L, TransactionType.INCOME, at = 2_000L),
                tx(1_500L, TransactionType.REFUND, at = 3_000L),
                tx(20_000L, TransactionType.TRANSFER_OUT, at = 4_000L),
                tx(50_000L, TransactionType.TRANSFER_IN, at = 5_000L)
            )
        ).single()

        // 100,000 - 30,000 + 5,000 + 1,500 - 20,000 + 50,000
        assertEquals(106_500L, balances.currentBalance.minorUnits)
        assertEquals(6_500L, balances.movementMinorUnits)
        assertEquals(5, balances.transactionCount)
        assertEquals(5_000L, balances.lastActivityMillis)
        assertTrue(balances.hasActivity)
    }

    @Test
    fun `account with no transactions keeps its opening balance`() {
        val balances = accountBalances(listOf(account(opening = 0L)), emptyList()).single()
        assertEquals(0L, balances.currentBalance.minorUnits)
        assertEquals(0L, balances.movementMinorUnits)
        assertEquals(0, balances.transactionCount)
        assertNull(balances.lastActivityMillis)
        assertFalse(balances.hasActivity)
    }

    @Test
    fun `summary totals each currency separately and skips archived accounts`() {
        val balances = accountBalances(
            listOf(
                account(id = "a", name = "Cash", opening = 100_000L),
                account(id = "b", name = "Bank", opening = 50_000L),
                account(id = "c", name = "Old jar", opening = 9_999L, archived = true),
                account(id = "d", name = "Dollars", opening = 5_000L, currency = "USD")
            ),
            listOf(
                tx(20_000L, TransactionType.EXPENSE, account = "a"),
                tx(1_000L, TransactionType.INCOME, account = "d", currency = "USD")
            )
        )
        val summary = AccountSummary.of(balances)

        assertEquals(mapOf("PHP" to 130_000L, "USD" to 6_000L), summary.totalsByCurrency)
        assertEquals(3, summary.activeCount)
        assertEquals(1, summary.archivedCount)
        assertFalse(summary.isEmpty)
    }

    @Test
    fun `summary of nothing is empty`() {
        val summary = AccountSummary.of(emptyList())
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.activeCount)
        assertTrue(summary.totalsByCurrency.isEmpty())
    }

    @Test
    fun `ordering keeps active accounts first and names case-insensitive`() {
        val balances = accountBalances(
            listOf(
                account(id = "1", name = "wallet"),
                account(id = "2", name = "Bank"),
                account(id = "3", name = "Archived", archived = true),
                account(id = "4", name = "Cash")
            ),
            emptyList()
        )

        assertEquals(
            listOf("Bank", "Cash", "wallet", "Archived"),
            AccountOrdering.sorted(balances).map { it.account.name }
        )
    }

    @Test
    fun `transaction for an unknown account is rejected`() {
        assertTrue(
            runCatching {
                accountBalances(listOf(account()), listOf(tx(100L, account = "missing")))
            }.isFailure
        )
    }

    @Test
    fun `transaction in another currency is rejected`() {
        assertTrue(
            runCatching {
                accountBalances(listOf(account(currency = "PHP")), listOf(tx(100L, currency = "USD")))
            }.isFailure
        )
    }

    @Test
    fun `balance that would overflow fails instead of wrapping`() {
        assertTrue(
            runCatching {
                accountBalances(
                    listOf(account(opening = Long.MAX_VALUE)),
                    listOf(tx(1L, TransactionType.INCOME))
                )
            }.isFailure
        )
    }

    @Test
    fun `account balance rejects a currency mismatch`() {
        assertTrue(
            runCatching {
                AccountBalance(
                    account = account(currency = "PHP"),
                    currentBalance = Money(1L, "USD"),
                    transactionCount = 0
                )
            }.isFailure
        )
    }
}
