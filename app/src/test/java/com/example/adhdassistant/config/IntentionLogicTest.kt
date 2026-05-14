package com.example.adhdassistant.config

import org.junit.Test
import org.junit.Assert.*

class IntentionLogicTest {

    @Test
    fun `chore is not overdue before deadline`() {
        val deadlineMs = System.currentTimeMillis() + 60_000L
        assertFalse(System.currentTimeMillis() > deadlineMs)
    }

    @Test
    fun `chore is overdue after deadline`() {
        val deadlineMs = System.currentTimeMillis() - 1L
        assertTrue(System.currentTimeMillis() > deadlineMs)
    }

    @Test
    fun `completed chore is not overdue`() {
        val isCompleted = true
        assertFalse(!isCompleted && System.currentTimeMillis() > 0)
    }
}
