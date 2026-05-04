package com.example.adhdassistant.domain

import com.example.adhdassistant.config.Profile
import org.junit.Test
import org.junit.Assert.*

class ProfileResolverTest {

    private val profiles = listOf(
        Profile(id = 1, name = "Default"),
        Profile(id = 2, name = "Focus", timerDurationMs = 25 * 60 * 1000L)
    )
    private val resolver = ProfileResolver(profiles)

    @Test
    fun `resolves existing profile by id`() {
        val result = resolver.resolve(1)
        assertTrue(result.isSuccess)
        assertEquals("Default", result.getOrNull()?.name)
    }

    @Test
    fun `resolves focus profile by id`() {
        val result = resolver.resolve(2)
        assertTrue(result.isSuccess)
        assertEquals(25 * 60 * 1000L, result.getOrNull()?.timerDurationMs)
    }

    @Test
    fun `returns failure for missing profile id`() {
        val result = resolver.resolve(99)
        assertTrue(result.isFailure)
    }

    @Test
    fun `merger preserves base id and applies override settings`() {
        val base = Profile(id = 1, name = "Default")
        val override = Profile(id = 2, name = "Override", timerDurationMs = 10 * 60 * 1000L)
        val merged = ProfileMerger.merge(base, override)
        assertEquals(10 * 60 * 1000L, merged.timerDurationMs)
    }
}
