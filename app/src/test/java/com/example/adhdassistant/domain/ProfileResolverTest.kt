package com.example.adhdassistant.domain

import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.config.RoutineSchedule
import org.junit.Test
import org.junit.Assert.*

class RoutineResolverTest {

    // A fully-specified root routine — RoutineResolver requires these four fields on the root.
    private fun rootRoutine(
        id: Long = 1L,
        name: String = "Default",
        startHour: Int = 8,
        endHour: Int = 22,
        alertThresholdMinutes: Int = 5,
        schedule: RoutineSchedule = RoutineSchedule.DaysOfWeek(setOf(1, 2, 3, 4, 5, 6, 7))
    ) = Routine(
        id                    = id,
        name                  = name,
        startHour             = startHour,
        endHour               = endHour,
        alertThresholdMinutes = alertThresholdMinutes,
        schedule              = schedule
    )

    // A child routine that inherits from a root — only fields to override need to be set.
    private fun childRoutine(
        id: Long,
        name: String,
        parentId: Long,
        alertThresholdMinutes: Int? = null
    ) = Routine(
        id                    = id,
        name                  = name,
        parentId              = parentId,
        alertThresholdMinutes = alertThresholdMinutes
    )

    // ─── RoutineResolver tests ─────────────────────────────────────────────────

    @Test
    fun `resolves root routine successfully`() {
        val routine = rootRoutine(id = 1L, name = "Default")
        val result = RoutineResolver.resolve(routine, listOf(routine))
        assertNotNull(result)
        assertEquals("Default", result!!.name)
    }

    @Test
    fun `resolved routine carries correct threshold`() {
        val routine = rootRoutine(id = 1L, alertThresholdMinutes = 10)
        val result = RoutineResolver.resolve(routine, listOf(routine))
        assertNotNull(result)
        assertEquals(10, result!!.alertThresholdMinutes)
    }

    @Test
    fun `returns null for incomplete root missing required fields`() {
        // Routine without startHour/endHour/alertThresholdMinutes/schedule is incomplete
        val incomplete = Routine(id = 1L, name = "Incomplete")
        val errors = mutableListOf<RoutineError>()
        val result = RoutineResolver.resolve(incomplete, listOf(incomplete), errors)
        assertNull(result)
        assertTrue("Expected IncompleteRoot error", errors.any { it is RoutineError.IncompleteRoot })
    }

    @Test
    fun `child inherits fields from root`() {
        val root  = rootRoutine(id = 1L, name = "Root", alertThresholdMinutes = 5)
        val child = childRoutine(id = 2L, name = "Child", parentId = 1L)
        val allRoutines = listOf(root, child)

        val result = RoutineResolver.resolve(child, allRoutines)
        assertNotNull(result)
        // Child didn't override threshold — should inherit root's value
        assertEquals(5, result!!.alertThresholdMinutes)
        assertEquals("Child", result.name)
    }

    @Test
    fun `child threshold overrides root threshold`() {
        val root  = rootRoutine(id = 1L, alertThresholdMinutes = 5)
        val child = childRoutine(id = 2L, name = "Focused Child", parentId = 1L, alertThresholdMinutes = 2)
        val allRoutines = listOf(root, child)

        val result = RoutineResolver.resolve(child, allRoutines)
        assertNotNull(result)
        assertEquals(2, result!!.alertThresholdMinutes)
    }

    @Test
    fun `returns null and error when parent is missing`() {
        val orphan = childRoutine(id = 2L, name = "Orphan", parentId = 99L)
        val errors = mutableListOf<RoutineError>()
        val result = RoutineResolver.resolve(orphan, listOf(orphan), errors)
        assertNull(result)
        assertTrue("Expected MissingParent error", errors.any { it is RoutineError.MissingParent })
    }

    @Test
    fun `validate detects circular inheritance`() {
        val a = Routine(id = 1L, name = "A", parentId = 2L)
        val b = Routine(id = 2L, name = "B", parentId = 1L)
        val errors = RoutineResolver.validate(listOf(a, b))
        assertTrue("Expected CircularInheritance error", errors.any { it is RoutineError.CircularInheritance })
    }

    // ─── RoutineMerger tests ───────────────────────────────────────────────────

    @Test
    fun `merge returns null for empty list`() {
        val result = RoutineMerger.merge(emptyList())
        assertNull(result)
    }

    @Test
    fun `merge single routine produces correct merged routine`() {
        val routine = rootRoutine(id = 1L, name = "Default", alertThresholdMinutes = 5)
        val resolved = RoutineResolver.resolve(routine, listOf(routine))!!
        val merged = RoutineMerger.merge(listOf(resolved))
        assertNotNull(merged)
        assertEquals(listOf("Default"), merged!!.activeRoutineNames)
        assertEquals(5, merged.alertThresholdMinutes)
    }

    @Test
    fun `merge uses minimum threshold across routines`() {
        val p1 = rootRoutine(id = 1L, name = "Relaxed", alertThresholdMinutes = 15)
        val p2 = rootRoutine(id = 2L, name = "Strict",  alertThresholdMinutes = 3)
        val allRoutines = listOf(p1, p2)
        val resolved = allRoutines.mapNotNull { RoutineResolver.resolve(it, allRoutines) }
        val merged = RoutineMerger.merge(resolved)
        assertNotNull(merged)
        // Strictest (lowest) threshold wins
        assertEquals(3, merged!!.alertThresholdMinutes)
    }

    @Test
    fun `merge combines routine names`() {
        val p1 = rootRoutine(id = 1L, name = "Work")
        val p2 = rootRoutine(id = 2L, name = "Evening")
        val allRoutines = listOf(p1, p2)
        val resolved = allRoutines.mapNotNull { RoutineResolver.resolve(it, allRoutines) }
        val merged = RoutineMerger.merge(resolved)
        assertNotNull(merged)
        assertTrue(merged!!.activeRoutineNames.containsAll(listOf("Work", "Evening")))
    }
}
