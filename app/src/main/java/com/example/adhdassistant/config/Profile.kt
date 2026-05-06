package com.example.adhdassistant.config

import kotlinx.serialization.Serializable

// ─── Excluded App Delta ───────────────────────────────────────────────────────

/**
 * Describes how a profile modifies its inherited excluded-app list.
 *
 * Both root and child profiles use this. For a root, [remove] is a no-op
 * since there's nothing inherited to remove — the resolver starts from an
 * empty set and applies [add], giving the root its base list.
 *
 * For a child, the resolver takes the parent's resolved list and applies:
 *   result = (inherited + add) - remove
 *
 * null on the Profile means "inherit unchanged, no modifications."
 */
@Serializable
data class ExcludedAppOverrides(
    val add: Set<String> = emptySet(),
    val remove: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = add.isEmpty() && remove.isEmpty()

    companion object {
        fun adding(vararg packages: String) = ExcludedAppOverrides(add = setOf(*packages))
        fun removing(vararg packages: String) = ExcludedAppOverrides(remove = setOf(*packages))
    }
}

// ─── Profile ──────────────────────────────────────────────────────────────────

/**
 * A named tracking configuration. Can inherit from a parent profile.
 *
 * All setting fields are nullable. null = "inherit from parent."
 * Root profiles (parentId == null) must have all fields non-null — the UI enforces this.
 *
 * The parent is a template: a child can override any field, including
 * adding and removing individual excluded apps from the inherited list.
 *
 * Inheritance is resolved by ProfileResolver into a ResolvedProfile (no nulls)
 * before the service ever sees it.
 */
@Serializable
data class Profile(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val emoji: String = "🧠",
    val parentId: Long? = null,

    // ── Nullable — null means inherit from parent ────────────────────────────
    val startHour: Int? = null,
    val endHour: Int? = null,
    val alertThresholdMinutes: Int? = null,
    val schedule: ProfileSchedule? = null,

    /**
     * null  → inherit parent's list unchanged
     * non-null → apply delta: (inherited + add) - remove
     *
     * On a root profile, [remove] is ignored (nothing to remove).
     * A child that wants to clear ALL inherited exclusions can set
     * remove = <all parent packages>, or more practically, the UI
     * offers a "clear all inherited" toggle that populates [remove]
     * with the parent's full resolved list at save time.
     */
    val excludedAppOverrides: ExcludedAppOverrides? = null,

    /**
     * Apps that show an immediate intention check-in the moment they appear
     * in the foreground (fires at session start, not after the usage threshold).
     *
     * null  → inherit parent's list unchanged
     * non-null → apply delta: (inherited + add) - remove
     *
     * Example: add "com.chess" so the user is asked about their intention
     * the instant Chess opens, before they get absorbed.
     */
    val onOpenPromptOverrides: ExcludedAppOverrides? = null,

    // ── Not inherited — each profile's manual toggle is independent ──────────
    val isManuallyActive: Boolean = false
) {
    val isRoot: Boolean get() = parentId == null

    fun overrides(field: ProfileField): Boolean = when (field) {
        ProfileField.START_HOUR      -> startHour != null
        ProfileField.END_HOUR        -> endHour != null
        ProfileField.THRESHOLD       -> alertThresholdMinutes != null
        ProfileField.SCHEDULE        -> schedule != null
        ProfileField.EXCLUDED_APPS   -> excludedAppOverrides != null && !excludedAppOverrides.isEmpty
        ProfileField.ON_OPEN_PROMPTS -> onOpenPromptOverrides != null && !onOpenPromptOverrides.isEmpty
    }
}

enum class ProfileField { START_HOUR, END_HOUR, THRESHOLD, EXCLUDED_APPS, SCHEDULE, ON_OPEN_PROMPTS }

// ─── Resolved Profile ─────────────────────────────────────────────────────────

/**
 * A Profile after its full inheritance chain has been applied — no nulls.
 * This is what the service, stats screen, and merge logic work with.
 */
data class ResolvedProfile(
    val id: Long,
    val name: String,
    val emoji: String,
    val startHour: Int,
    val endHour: Int,
    val alertThresholdMinutes: Int,
    val excludedApps: Set<String>,
    val onOpenPromptPackages: Set<String>,
    val schedule: ProfileSchedule,
    val isManuallyActive: Boolean,
    val inheritanceChain: List<String>   // Root-first, e.g. ["Weekday", "Monday"]
) {
    val inheritancePath: String get() = inheritanceChain.joinToString(" → ")

    fun scheduleSummary(): String {
        val time = "${fmt(startHour)}–${fmt(endHour)}"
        val days = when (val s = schedule) {
            is ProfileSchedule.Manual     -> "Manual"
            is ProfileSchedule.DaysOfWeek -> fmtDays(s.days)
            is ProfileSchedule.TimedDays  -> "${fmtDays(s.days)} from ${fmt(s.activateHour)}"
        }
        return "$days · $time · ${alertThresholdMinutes}min"
    }

    private fun fmt(h: Int) = "${(h % 12).let { if (it == 0) 12 else it }}${if (h < 12) "am" else "pm"}"
    private fun fmtDays(days: Set<Int>) = when (days.sorted().toSet()) {
        setOf(2,3,4,5,6)       -> "Mon–Fri"
        setOf(1,7)             -> "Weekends"
        setOf(1,2,3,4,5,6,7)  -> "Every day"
        else -> days.sorted().joinToString(",") {
            listOf("","Sun","Mon","Tue","Wed","Thu","Fri","Sat").getOrElse(it) { "?" }
        }
    }
}

// ─── Schedule ─────────────────────────────────────────────────────────────────

@Serializable
sealed class ProfileSchedule {
    @Serializable object Manual : ProfileSchedule()
    @Serializable data class DaysOfWeek(val days: Set<Int>) : ProfileSchedule()
    @Serializable data class TimedDays(val days: Set<Int>, val activateHour: Int) : ProfileSchedule()
}

// ─── Presets ──────────────────────────────────────────────────────────────────

object ProfilePresets {

    val WEEKDAY = Profile(
        id = 100L, name = "Weekday", emoji = "💼",
        startHour = 8, endHour = 22, alertThresholdMinutes = 5,
        schedule = ProfileSchedule.DaysOfWeek(setOf(2,3,4,5,6)),
        excludedAppOverrides = ExcludedAppOverrides.adding("com.netflix")
    )

    val WEEKEND = Profile(
        id = 101L, name = "Weekend", emoji = "🌅",
        startHour = 9, endHour = 23, alertThresholdMinutes = 10,
        schedule = ProfileSchedule.DaysOfWeek(setOf(1,7))
    )

    val EVERY_DAY = Profile(
        id = 102L, name = "Every Day", emoji = "📅",
        startHour = 8, endHour = 22, alertThresholdMinutes = 5,
        schedule = ProfileSchedule.DaysOfWeek(setOf(1,2,3,4,5,6,7))
    )

    val GYM = Profile(
        id = 103L, name = "Gym", emoji = "🏋️",
        startHour = 5, endHour = 9, alertThresholdMinutes = 3,
        schedule = ProfileSchedule.Manual,
        excludedAppOverrides = ExcludedAppOverrides.adding("com.spotify.music")
    )

    // Child: inherits Weekday, removes Netflix (no scrolling at work), adds Slack
    val WORK_HOURS = Profile(
        id = 200L, name = "Work Hours", emoji = "🖥️",
        parentId = WEEKDAY.id,
        startHour = 9, endHour = 17, alertThresholdMinutes = 3,
        schedule = ProfileSchedule.TimedDays(setOf(2,3,4,5,6), activateHour = 9),
        excludedAppOverrides = ExcludedAppOverrides(
            add    = setOf("com.slack", "com.microsoft.teams"),
            remove = setOf("com.netflix")   // Don't let Netflix slide during work
        )
    )

    // Child: inherits Weekday, just overrides threshold and day
    val MONDAY = Profile(
        id = 201L, name = "Monday", emoji = "😤",
        parentId = WEEKDAY.id,
        alertThresholdMinutes = 3,
        schedule = ProfileSchedule.DaysOfWeek(setOf(2))
    )

    // Child: inherits Weekday, relaxes end time and threshold, keeps all exclusions
    val FRIDAY = Profile(
        id = 202L, name = "Friday", emoji = "🎉",
        parentId = WEEKDAY.id,
        endHour = 20, alertThresholdMinutes = 8,
        schedule = ProfileSchedule.DaysOfWeek(setOf(6))
    )

    val all = listOf(WEEKDAY, WEEKEND, EVERY_DAY, GYM, WORK_HOURS, MONDAY, FRIDAY)

    /** Top-level presets shown on the onboarding routine picker (no parent). */
    val rootProfiles = listOf(WEEKDAY, WEEKEND, EVERY_DAY, GYM)
}