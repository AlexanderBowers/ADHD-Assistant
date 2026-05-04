package com.example.adhdassistant.domain

import com.example.adhdassistant.config.*
import java.util.Calendar

// ─── Errors ───────────────────────────────────────────────────────────────────

sealed class ProfileError {
    data class CircularInheritance(val cyclePath: String) : ProfileError()
    data class MissingParent(val profileName: String, val missingParentId: Long) : ProfileError()
    data class IncompleteRoot(val profileName: String, val missingFields: List<ProfileField>) : ProfileError()
}

// ─── Resolver ─────────────────────────────────────────────────────────────────

/**
 * Resolves a Profile through its full inheritance chain into a concrete ResolvedProfile.
 * Pure Kotlin — no Android dependencies, fully unit-testable.
 *
 * Field resolution (for all fields except excludedApps):
 *   Walk root → child. First non-null value encountered wins.
 *   A child's non-null value always overrides any ancestor.
 *
 * excludedAppOverrides resolution:
 *   Walk root → child, accumulating a running Set<String>:
 *     result = (result + level.add) - level.remove
 *   A level with null overrides passes the set through unchanged.
 *   This lets a child add apps, remove apps, do both, or leave the list alone.
 */
object ProfileResolver {

    // ─── Primary API ─────────────────────────────────────────────────────────

    fun resolve(
        profile: Profile,
        allProfiles: List<Profile>,
        errors: MutableList<ProfileError> = mutableListOf()
    ): ResolvedProfile? {
        val chain = buildChain(profile, allProfiles, errors) ?: return null
        return applyChain(chain, errors)
    }

    fun validate(allProfiles: List<Profile>): List<ProfileError> {
        val errors = mutableListOf<ProfileError>()
        for (profile in allProfiles) {
            buildChain(profile, allProfiles, errors)
            if (profile.isRoot) checkRoot(profile, errors)
        }
        return errors
    }

    fun resolveCurrentlyActive(
        allProfiles: List<Profile>,
        currentDayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): List<Profile> = allProfiles.filter { isActiveNow(it, currentDayOfWeek, currentHour) }

    fun childrenOf(parentId: Long, allProfiles: List<Profile>): List<Profile> =
        allProfiles.filter { it.parentId == parentId }

    fun ancestorsOf(profile: Profile, allProfiles: List<Profile>): List<Profile>? {
        val errors = mutableListOf<ProfileError>()
        return buildChain(profile, allProfiles, errors)?.dropLast(1)
    }

    // ─── Chain Building ───────────────────────────────────────────────────────

    private fun buildChain(
        profile: Profile,
        allProfiles: List<Profile>,
        errors: MutableList<ProfileError>
    ): List<Profile>? {
        val chain = mutableListOf<Profile>()
        val visited = mutableSetOf<Long>()
        var current = profile

        while (true) {
            if (current.id in visited) {
                val path = (chain.map { it.name } + current.name).joinToString(" → ")
                errors.add(ProfileError.CircularInheritance(path))
                return null
            }
            visited.add(current.id)
            chain.add(0, current)

            val parentId = current.parentId ?: break
            val parent = allProfiles.firstOrNull { it.id == parentId }
            if (parent == null) {
                errors.add(ProfileError.MissingParent(current.name, parentId))
                return null
            }
            current = parent
        }
        return chain
    }

    // ─── Chain Application ────────────────────────────────────────────────────

    private fun applyChain(
        chain: List<Profile>,  // Root-first
        errors: MutableList<ProfileError>
    ): ResolvedProfile? {
        val root = chain.first()

        // Validate root completeness
        val missing = missingRootFields(root)
        if (missing.isNotEmpty()) {
            errors.add(ProfileError.IncompleteRoot(root.name, missing))
            return null
        }

        // Accumulate each field, applying child overrides as we walk down
        var startHour             = root.startHour!!
        var endHour               = root.endHour!!
        var alertThresholdMinutes = root.alertThresholdMinutes!!
        var schedule              = root.schedule!!

        // Excluded apps: start empty, apply each level's delta in order
        var excludedApps = emptySet<String>()
        root.excludedAppOverrides?.let { excludedApps = applyDelta(excludedApps, it) }

        for (profile in chain.drop(1)) {
            profile.startHour?.let             { startHour = it }
            profile.endHour?.let               { endHour = it }
            profile.alertThresholdMinutes?.let  { alertThresholdMinutes = it }
            profile.schedule?.let              { schedule = it }
            profile.excludedAppOverrides?.let  { excludedApps = applyDelta(excludedApps, it) }
        }

        val leaf = chain.last()
        return ResolvedProfile(
            id                    = leaf.id,
            name                  = leaf.name,
            emoji                 = leaf.emoji,
            startHour             = startHour,
            endHour               = endHour,
            alertThresholdMinutes = alertThresholdMinutes,
            excludedApps          = excludedApps,
            schedule              = schedule,
            isManuallyActive      = leaf.isManuallyActive,
            inheritanceChain      = chain.map { it.name }
        )
    }

    /**
     * Apply a single level's delta to the running excluded-apps set.
     * Add first, then remove — so a level can't accidentally re-add
     * something it also removes.
     */
    private fun applyDelta(
        current: Set<String>,
        overrides: ExcludedAppOverrides
    ): Set<String> = (current + overrides.add) - overrides.remove

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun checkRoot(root: Profile, errors: MutableList<ProfileError>) {
        val missing = missingRootFields(root)
        if (missing.isNotEmpty()) errors.add(ProfileError.IncompleteRoot(root.name, missing))
    }

    private fun missingRootFields(root: Profile): List<ProfileField> = buildList {
        if (root.startHour == null)            add(ProfileField.START_HOUR)
        if (root.endHour == null)              add(ProfileField.END_HOUR)
        if (root.alertThresholdMinutes == null) add(ProfileField.THRESHOLD)
        if (root.schedule == null)             add(ProfileField.SCHEDULE)
    }

    private fun isActiveNow(profile: Profile, dow: Int, hour: Int): Boolean =
        when (val s = profile.schedule ?: return false) {
            is ProfileSchedule.Manual     -> profile.isManuallyActive
            is ProfileSchedule.DaysOfWeek -> dow in s.days
            is ProfileSchedule.TimedDays  -> dow in s.days && hour >= s.activateHour
        }
}

// ─── Merger ───────────────────────────────────────────────────────────────────

object ProfileMerger {

    data class MergedProfile(
        val activeProfileNames: List<String>,
        val alertThresholdMinutes: Int,
        val excludedApps: Set<String>,
        val activeHourRanges: List<HourRange>
    ) {
        fun coversHour(hour: Int) = activeHourRanges.any { it.contains(hour) }
        fun label() = activeProfileNames.joinToString(" + ")
    }

    data class HourRange(val start: Int, val end: Int) {
        fun contains(hour: Int) = if (start <= end) hour in start until end
        else hour >= start || hour < end
    }

    fun merge(resolved: List<ResolvedProfile>): MergedProfile? {
        if (resolved.isEmpty()) return null
        return MergedProfile(
            activeProfileNames    = resolved.map { it.name },
            alertThresholdMinutes = resolved.minOf { it.alertThresholdMinutes },
            excludedApps          = resolved.flatMap { it.excludedApps }.toSet(),
            activeHourRanges      = resolved.map { HourRange(it.startHour, it.endHour) }
        )
    }

    fun resolveAndMerge(
        allProfiles: List<Profile>,
        currentDayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK),
        currentHour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    ): MergedProfile? {
        val errors = mutableListOf<ProfileError>()
        val resolved = ProfileResolver
            .resolveCurrentlyActive(allProfiles, currentDayOfWeek, currentHour)
            .mapNotNull { ProfileResolver.resolve(it, allProfiles, errors) }
        return merge(resolved)
    }
}