package com.dga.montra.domain

import com.dga.montra.TransactionFormState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransactionFormStateTest {
    @Test
    fun `category selection is retained until type actually changes`() {
        val state = TransactionFormState()
        state.selectCategory("dining")
        assertEquals("dining", state.categoryId)
        assertEquals(false, state.selectType(TransactionType.EXPENSE))
        assertEquals("dining", state.categoryId)
        assertEquals(true, state.selectType(TransactionType.INCOME))
        assertEquals("salary", state.categoryId)
    }
}
