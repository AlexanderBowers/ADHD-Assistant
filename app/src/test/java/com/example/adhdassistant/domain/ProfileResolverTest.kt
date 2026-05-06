package com.example.adhdassistant.domain

import com.example.adhdassistant.config.Profile
import com.example.adhdassistant.config.ProfileSchedule
import org.junit.Test
import org.junit.Assert.*

class ProfileResolverTest {

    // A fully-specified root profile — ProfileResolver requires these four fields on the root.
    private fun rootProfile(
        id: Long = 1L,
        name: String = "Default",
        startHour: Int = 8,
        endHour: Int = 22,
        alertThresholdMinutes: Int = 5,
        schedule: ProfileSchedule = ProfileSchedule.DaysOfWeek(setOf(1, 2, 3, 4, 5, 6, 7))
    ) = Profile(
        id                    = id,
        name                  = name,
        startHour             = startHour,
        endHour               = endHour,
        alertThresholdMinutes = alertThresholdMinutes,
        schedule              = schedule
    )

    // A child profile that inherits from a root — only fields to override need to be set.
    private fun childProfile(
        id: Long,
        name: String,
        parentId: Long,
        alertThresholdMinutes: Int? = null
    ) = Profile(
        id                    = id,
        name                  = name,
        parentId              = parentId,
        alertThresholdMinutes = alertThresholdMinutes
    )

    // ─── ProfileResolver tests ─────────────────────────────────────────────────

    @Test
    fun `resolves root profile successfully`() {
        val profile = rootProfile(id = 1L, name = "Default")
        val result = ProfileResolver.resolve(profile, listOf(profile))
        assertNotNull(result)
        assertEquals("Default", result!!.name)
    }

    @Test
    fun `resolved profile carries correct threshold`() {
        val profile = rootProfile(id = 1L, alertThresholdMinutes = 10)
        val result = ProfileResolver.resolve(profile, listOf(profile))
        assertNotNull(result)
        assertEquals(10, result!!.alertThresholdMinutes)
    }

    @Test
    fun `returns null for incomplete root missing required fields`() {
        // Profile without startHour/endHour/alertThresholdMinutes/schedule is incomplete
        val incomplete = Profile(id = 1L, name = "Incomplete")
        val errors = mutableListOf<ProfileError>()
        val result = ProfileResolver.resolve(incomplete, listOf(incomplete), errors)
        assertNull(result)
        assertTrue("Expected IncompleteRoot error", errors.any { it is ProfileError.IncompleteRoot })
    }

    @Test
    fun `child inherits fields from root`() {
        val root  = rootProfile(id = 1L, name = "Root", alertThresholdMinutes = 5)
        val child = childProfile(id = 2L, name = "Child", parentId = 1L)
        val allProfiles = listOf(root, child)

        val result = ProfileResolver.resolve(child, allProfiles)
        assertNotNull(result)
        // Child didn't override threshold — should inherit root's value
        assertEquals(5, result!!.alertThresholdMinutes)
        assertEquals("Child", result.name)
    }

    @Test
    fun `child threshold overrides root threshold`() {
        val root  = rootProfile(id = 1L, alertThresholdMinutes = 5)
        val child = childProfile(id = 2L, name = "Focused Child", parentId = 1L, alertThresholdMinutes = 2)
        val allProfiles = listOf(root, child)

        val result = ProfileResolver.resolve(child, allProfiles)
        assertNotNull(result)
        assertEquals(2, result!!.alertThresholdMinutes)
    }

    @Test
    fun `returns null and error when parent is missing`() {
        val orphan = childProfile(id = 2L, name = "Orphan", parentId = 99L)
        val errors = mutableListOf<ProfileError>()
        val result = ProfileResolver.resolve(orphan, listOf(orphan), errors)
        assertNull(result)
        assertTrue("Expected MissingParent error", errors.any { it is ProfileError.MissingParent })
    }

    @Test
    fun `validate detects circular inheritance`() {
        val a = Profile(id = 1L, name = "A", parentId = 2L)
        val b = Profile(id = 2L, name = "B", parentId = 1L)
        val errors = ProfileResolver.validate(listOf(a, b))
        assertTrue("Expected CircularInheritance error", errors.any { it is ProfileError.CircularInheritance })
    }

    // ─── ProfileMerger tests ───────────────────────────────────────────────────

    @Test
    fun `merge returns null for empty list`() {
        val result = ProfileMerger.merge(emptyList())
        assertNull(result)
    }

    @Test
    fun `merge single profile produces correct merged profile`() {
        val profile = rootProfile(id = 1L, name = "Default", alertThresholdMinutes = 5)
        val resolved = ProfileResolver.resolve(profile, listOf(profile))!!
        val merged = ProfileMerger.merge(listOf(resolved))
        assertNotNull(merged)
        assertEquals(listOf("Default"), merged!!.activeProfileNames)
        assertEquals(5, merged.alertThresholdMinutes)
    }

    @Test
    fun `merge uses minimum threshold across profiles`() {
        val p1 = rootProfile(id = 1L, name = "Relaxed", alertThresholdMinutes = 15)
        val p2 = rootProfile(id = 2L, name = "Strict",  alertThresholdMinutes = 3)
        val allProfiles = listOf(p1, p2)
        val resolved = allProfiles.mapNotNull { ProfileResolver.resolve(it, allProfiles) }
        val merged = ProfileMerger.merge(resolved)
        assertNotNull(merged)
        // Strictest (lowest) threshold wins
        assertEquals(3, merged!!.alertThresholdMinutes)
    }

    @Test
    fun `merge combines profile names`() {
        val p1 = rootProfile(id = 1L, name = "Work")
        val p2 = rootProfile(id = 2L, name = "Evening")
        val allProfiles = listOf(p1, p2)
        val resolved = allProfiles.mapNotNull { ProfileResolver.resolve(it, allProfiles) }
        val merged = ProfileMerger.merge(resolved)
        assertNotNull(merged)
        assertTrue(merged!!.activeProfileNames.containsAll(listOf("Work", "Evening")))
    }
}
