package com.dga.montra.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency

/** Exact currency minor units; JPY uses zero fractional digits. */
data class Money(val minorUnits: Long, val currencyCode: String) {
    fun format(): String {
        val curr = Currency.getInstance(currencyCode)
        return NumberFormat.getCurrencyInstance().apply {
            currency = curr
            minimumFractionDigits = curr?.defaultFractionDigits ?: 2
            maximumFractionDigits = curr?.defaultFractionDigits ?: 2
        }.format(BigDecimal.valueOf(minorUnits, curr?.defaultFractionDigits ?: 2))
    }

    companion object {
        val currencies = listOf("PHP", "USD", "CAD", "EUR", "GBP", "JPY")

        /** Input uses period decimal separator; no silent rounding. */
        fun parse(text: String, code: String): Money {
            require(code in currencies) { "Unsupported currency" }
            require(text.matches(Regex("-?[0-9]+(\\.[0-9]+)?"))) { "Invalid amount" }
            val digits = Currency.getInstance(code).defaultFractionDigits
            return Money(BigDecimal(text).setScale(digits, RoundingMode.UNNECESSARY)
                .movePointRight(digits).longValueExact(), code)
        }
    }
}

data class Account(val id: String, val name: String, val openingBalance: Money)
data class DashboardState(val balances: Map<String, Long>, val accountCount: Int)

fun accountSummary(accounts: List<Account>): DashboardState = DashboardState(
    accounts.groupBy { it.openingBalance.currencyCode }.mapValues { (_, items) ->
        items.fold(0L) { sum, account -> Math.addExact(sum, account.openingBalance.minorUnits) }
    }, accounts.size
)
