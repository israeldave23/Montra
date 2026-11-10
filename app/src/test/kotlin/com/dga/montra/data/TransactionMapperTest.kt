package com.dga.montra.data

import com.dga.montra.domain.Money
import com.dga.montra.domain.Transaction
import com.dga.montra.domain.TransactionType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransactionMapperTest {
    @Test
    fun `category survives persistence mapping round trip`() {
        val saved = Transaction(
            id = "transaction-id",
            accountId = "account-id",
            amount = Money(1250, "PHP"),
            type = TransactionType.EXPENSE,
            categoryId = "dining",
            note = "Dinner",
            timestampMillis = 1234L
        )

        val loaded = saved.toEntity().toDomain()

        assertEquals("dining", loaded.categoryId)
        assertEquals(saved, loaded)
    }
}
