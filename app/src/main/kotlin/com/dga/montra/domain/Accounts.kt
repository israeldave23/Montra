package com.dga.montra.domain

/**
 * An account with its live balance: opening balance plus every signed transaction recorded against
 * it. The account row's number must match what the dashboard shows, so this is the only place the
 * number is computed.
 */
data class AccountBalance(
    val account: Account,
    val currentBalance: Money,
    val transactionCount: Int,
    val lastActivityMillis: Long? = null
) {
    init {
        require(currentBalance.currencyCode == account.openingBalance.currencyCode) {
            "Balance currency must match the account"
        }
        require(transactionCount >= 0) { "Transaction count must be non-negative" }
    }

    val currencyCode: String get() = account.openingBalance.currencyCode
    val isArchived: Boolean get() = account.isArchived

    /** Net movement since the account was opened; negative when more went out than came in. */
    val movementMinorUnits: Long get() = currentBalance.minorUnits - account.openingBalance.minorUnits

    val hasActivity: Boolean get() = transactionCount > 0
}

/** Totals for the active accounts only; archived accounts stay visible but out of the numbers. */
data class AccountSummary(
    val totalsByCurrency: Map<String, Long>,
    val activeCount: Int,
    val archivedCount: Int
) {
    val isEmpty: Boolean get() = activeCount == 0 && archivedCount == 0

    companion object {
        fun of(items: List<AccountBalance>): AccountSummary {
            val totals = mutableMapOf<String, Long>()
            items.filterNot { it.isArchived }.forEach { item ->
                totals[item.currencyCode] = Math.addExact(
                    totals[item.currencyCode] ?: 0L,
                    item.currentBalance.minorUnits
                )
            }
            return AccountSummary(
                totalsByCurrency = totals,
                activeCount = items.count { !it.isArchived },
                archivedCount = items.count { it.isArchived }
            )
        }
    }
}

object AccountOrdering {
    /** Active accounts first, then case-insensitive name; ties break on id so order is stable. */
    fun sorted(items: List<AccountBalance>): List<AccountBalance> = items.sortedWith(
        compareBy<AccountBalance> { it.isArchived }
            .thenBy { it.account.name.lowercase() }
            .thenBy { it.account.id }
    )
}

/**
 * Pairs [accounts] with the balance implied by [transactions].
 *
 * A transaction that references an unknown account, or one whose currency differs from its account,
 * is a data-integrity error rather than something to silently drop, so it fails loudly.
 */
fun accountBalances(
    accounts: List<Account>,
    transactions: List<Transaction>
): List<AccountBalance> {
    val byId = accounts.associateBy { it.id }
    val movement = mutableMapOf<String, Long>()
    val counts = mutableMapOf<String, Int>()
    val lastActivity = mutableMapOf<String, Long>()
    transactions.forEach { transaction ->
        val account = byId[transaction.accountId]
            ?: throw IllegalArgumentException("Transaction references a missing account")
        require(account.openingBalance.currencyCode == transaction.amount.currencyCode) {
            "Transaction currency mismatch"
        }
        movement[transaction.accountId] = Math.addExact(
            movement[transaction.accountId] ?: 0L,
            transaction.signedMinorUnits()
        )
        counts[transaction.accountId] = (counts[transaction.accountId] ?: 0) + 1
        lastActivity[transaction.accountId] = maxOf(
            lastActivity[transaction.accountId] ?: Long.MIN_VALUE,
            transaction.timestampMillis
        )
    }
    return accounts.map { account ->
        AccountBalance(
            account = account,
            currentBalance = Money(
                Math.addExact(account.openingBalance.minorUnits, movement[account.id] ?: 0L),
                account.openingBalance.currencyCode
            ),
            transactionCount = counts[account.id] ?: 0,
            lastActivityMillis = lastActivity[account.id]
        )
    }
}
