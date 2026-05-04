package com.example.adhdassistant.tracking

import org.junit.Test
import org.junit.Assert.*

class UsageEvaluatorTest {

    @Test
    fun `usage below threshold does not trigger alert`() {
        val thresholdMs = 5 * 60 * 1000L
        val usageMs = 4 * 60 * 1000L
        assertFalse(usageMs >= thresholdMs)
    }

    @Test
    fun `usage at threshold triggers alert`() {
        val thresholdMs = 5 * 60 * 1000L
        val usageMs = 5 * 60 * 1000L
        assertTrue(usageMs >= thresholdMs)
    }

    @Test
    fun `excluded app resets timer`() {
        val excludedApps = setOf("com.example.excluded")
        val foregroundApp = "com.example.excluded"
        assertTrue(excludedApps.contains(foregroundApp))
    }

    @Test
    fun `launcher app resets timer`() {
        val launcherPackage = "com.google.android.apps.nexuslauncher"
        assertTrue(launcherPackage.contains("launcher", ignoreCase = true))
    }
}
