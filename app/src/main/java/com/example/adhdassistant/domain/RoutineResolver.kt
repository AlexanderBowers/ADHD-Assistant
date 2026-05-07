package com.example.adhdassistant.domain

import com.example.adhdassistant.config.*
import java.util.Calendar

// ─── Errors ───────────────────────────────────────────────────────────────────

sealed class RoutineError {
    data class CircularInheritance(val cyclePath: String) : RoutineError()
    data class MissingParent(val routineName: String, val missingParentId: Long) : RoutineError()
    data class IncompleteRoot(val routineName: String, val missingFields: List<RoutineField>) : RoutineError()
}

// ─── Resolver ─────────────────────────────────────────────────────────────────

object RoutineResolver {

    // ─── Primary API ─────────────────────────────────────────────────────────

    fun resolve(
        routine: Routine,
        allRoutines: List<Routine>,
        errors: MutableList<RoutineError> = mutableListOf()
    ): ResolvedRoutine? {
        val chain = buildChain(routine, allRoutines, errors) ?: return null
        return applyChain(chain, errors)
    }

    fun validate(allRoutines: List<Routine>): List<RoutineError> {
        val errors = mutableListOf<RoutineError>()
        for (routine in allRoutines) {
            buildChain(routine, allRoutines, errors)
            if (routine.isRoot) checkRoot(routine, errors)
        }
        return errors
    }

    fun resolveCurrentlyActive(
        allRoutines: List<Routine>,
        currentDayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): List<Routine> = allRoutines.filter { isActiveNow(it, currentDayOfWeek, currentHour) }

    fun childrenOf(parentId: Long, allRoutines: List<Routine>): List<Routine> =
        allRoutines.filter { it.parentId == parentId }

    fun ancestorsOf(routine: Routine, allRoutines: List<Routine>): List<Routine>? {
        val errors = mutableListOf<RoutineError>()
        return buildChain(routine, allRoutines, errors)?.dropLast(1)
    }

    // ─── Chain Building ───────────────────────────────────────────────────────

    private fun buildChain(
        routine: Routine,
        allRoutines: List<Routine>,
        errors: MutableList<RoutineError>
    ): List<Routine>? {
        val chain = mutableListOf<Routine>()
        val visited = mutableSetOf<Long>()
        var current = routine

        while (true) {
            if (current.id in visited) {
                val path = (chain.map { it.name } + current.name).joinToString(" → ")
                errors.add(RoutineError.CircularInheritance(path))
                return null
            }
            visited.add(current.id)
            chain.add(0, current)

            val parentId = current.parentId ?: break
            val parent = allRoutines.firstOrNull { it.id == parentId }
            if (parent == null) {
                errors.add(RoutineError.MissingParent(current.name, parentId))
                return null
            }
            current = parent
        }
        return chain
    }

    // ─── Chain Application ────────────────────────────────────────────────────

    private fun applyChain(
        chain: List<Routine>,  // Root-first
        errors: MutableList<RoutineError>
    ): ResolvedRoutine? {
        val root = chain.first()

        val missing = missingRootFields(root)
        if (missing.isNotEmpty()) {
            errors.add(RoutineError.IncompleteRoot(root.name, missing))
            return null
        }

        var startHour             = root.startHour!!
        var endHour               = root.endHour!!
        var alertThresholdMinutes = root.alertThresholdMinutes!!
        var schedule              = root.schedule!!

        var excludedApps = emptySet<String>()
        root.excludedAppOverrides?.let { excludedApps = applyDelta(excludedApps, it) }

        var onOpenPromptPackages = emptySet<String>()
        root.onOpenPromptOverrides?.let { onOpenPromptPackages = applyDelta(onOpenPromptPackages, it) }

        for (routine in chain.drop(1)) {
            routine.startHour?.let              { startHour = it }
            routine.endHour?.let                { endHour = it }
            routine.alertThresholdMinutes?.let  { alertThresholdMinutes = it }
            routine.schedule?.let               { schedule = it }
            routine.excludedAppOverrides?.let   { excludedApps = applyDelta(excludedApps, it) }
            routine.onOpenPromptOverrides?.let  { onOpenPromptPackages = applyDelta(onOpenPromptPackages, it) }
        }

        val leaf = chain.last()
        return ResolvedRoutine(
            id                    = leaf.id,
            name                  = leaf.name,
            emoji                 = leaf.emoji,
            startHour             = startHour,
            endHour               = endHour,
            alertThresholdMinutes = alertThresholdMinutes,
            excludedApps          = excludedApps,
            onOpenPromptPackages  = onOpenPromptPackages,
            schedule              = schedule,
            isManuallyActive      = leaf.isManuallyActive,
            inheritanceChain      = chain.map { it.name },
            locationName          = leaf.locationName
        )
    }

    private fun applyDelta(
        current: Set<String>,
        overrides: ExcludedAppOverrides
    ): Set<String> = (current + overrides.add) - overrides.remove

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun checkRoot(root: Routine, errors: MutableList<RoutineError>) {
        val missing = missingRootFields(root)
        if (missing.isNotEmpty()) errors.add(RoutineError.IncompleteRoot(root.name, missing))
    }

    private fun missingRootFields(root: Routine): List<RoutineField> = buildList {
        if (root.startHour == null)            add(RoutineField.START_HOUR)
        if (root.endHour == null)              add(RoutineField.END_HOUR)
        if (root.alertThresholdMinutes == null) add(RoutineField.THRESHOLD)
        if (root.schedule == null)             add(RoutineField.SCHEDULE)
    }

    private fun isActiveNow(routine: Routine, dow: Int, hour: Int): Boolean =
        when (val s = routine.schedule ?: return false) {
            is RoutineSchedule.Manual     -> routine.isManuallyActive
            is RoutineSchedule.DaysOfWeek -> dow in s.days
            is RoutineSchedule.TimedDays  -> dow in s.days && hour >= s.activateHour
        }
}

// ─── Merger ───────────────────────────────────────────────────────────────────

object RoutineMerger {

    data class MergedRoutine(
        val activeRoutineNames: List<String>,
        val alertThresholdMinutes: Int,
        val excludedApps: Set<String>,
        val onOpenPromptPackages: Set<String>,
        val activeHourRanges: List<HourRange>
    ) {
        fun coversHour(hour: Int) = activeHourRanges.any { it.contains(hour) }
        fun label() = activeRoutineNames.joinToString(" + ")
    }

    data class HourRange(val start: Int, val end: Int) {
        fun contains(hour: Int) = if (start <= end) hour in start until end
        else hour >= start || hour < end
    }

    fun merge(resolved: List<ResolvedRoutine>): MergedRoutine? {
        if (resolved.isEmpty()) return null
        return MergedRoutine(
            activeRoutineNames    = resolved.map { it.name },
            alertThresholdMinutes = resolved.minOf { it.alertThresholdMinutes },
            excludedApps          = resolved.flatMap { it.excludedApps }.toSet(),
            onOpenPromptPackages  = resolved.flatMap { it.onOpenPromptPackages }.toSet(),
            activeHourRanges      = resolved.map { HourRange(it.startHour, it.endHour) }
        )
    }

    fun resolveAndMerge(
        allRoutines: List<com.example.adhdassistant.config.Routine>,
        currentDayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): MergedRoutine? {
        val errors = mutableListOf<RoutineError>()
        val resolved = RoutineResolver
            .resolveCurrentlyActive(allRoutines, currentDayOfWeek, currentHour)
            .mapNotNull { RoutineResolver.resolve(it, allRoutines, errors) }
        return merge(resolved)
    }
}