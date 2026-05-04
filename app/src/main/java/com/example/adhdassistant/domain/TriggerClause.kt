package com.example.adhdassistant.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Describes exactly which rule caused a focus alert to fire.
 *
 * Stored as a JSON string in the database so the schema doesn't need to change
 * as new clause types are added in future versions.
 *
 * Current clause types:
 *   ContinuousUsage — the user used a single app continuously beyond a threshold.
 *
 * Future clause types (not yet implemented, but the architecture supports them):
 *   TotalDailyUsage — cumulative screen time across all apps exceeded a limit.
 *   SessionCount    — too many individual sessions within a time window.
 *   TimeOfDay       — a scheduled check-in at a specific hour.
 */
@Serializable
sealed class TriggerClause {

    /** Returns a short, human-readable description for the event log UI. */
    abstract fun describe(): String

    /**
     * The only current trigger: continuous use of a single app beyond a threshold.
     *
     * @param thresholdMinutes  The threshold that fired, in minutes.
     * @param profileId         ID of the profile whose rule matched.
     * @param profileName       Name of the profile whose rule matched (denormalised for display).
     * @param inheritedFrom     If the threshold was inherited from a parent profile,
     *                          this holds the ancestor's name. Null if defined on the
     *                          profile itself.
     */
    @Serializable
    data class ContinuousUsage(
        val thresholdMinutes: Int,
        val profileId: Long,
        val profileName: String,
        val inheritedFrom: String? = null
    ) : TriggerClause() {
        override fun describe(): String {
            val source = if (inheritedFrom != null) {
                "$profileName (inherited from $inheritedFrom)"
            } else {
                profileName
            }
            return "${thresholdMinutes}min continuous use · $source"
        }
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): TriggerClause? =
            runCatching { Json.decodeFromString<TriggerClause>(json) }.getOrNull()

        /**
         * Given a list of resolved profiles that are all currently active,
         * determine which profile's threshold is responsible for the alert
         * and return the appropriate clause.
         *
         * The triggering profile is the one with the lowest threshold (strictest),
         * since that's the one whose rule the merged profile used.
         * In a tie, the profile that appears first in the list is used.
         *
         * Also detects whether the winning threshold was inherited or defined
         * directly on the profile, for display in the log.
         */
        fun identify(
            activeProfiles: List<com.example.adhdassistant.config.ResolvedProfile>,
            allRawProfiles: List<com.example.adhdassistant.config.Profile>
        ): ContinuousUsage? {
            val triggering = activeProfiles.minByOrNull { it.alertThresholdMinutes } ?: return null

            // Determine whether the threshold was inherited or set directly
            val rawProfile = allRawProfiles.firstOrNull { it.id == triggering.id }
            val inheritedFrom = if (rawProfile?.alertThresholdMinutes == null) {
                // Null on the raw profile → inherited from somewhere in the chain
                triggering.inheritanceChain
                    .dropLast(1)  // Remove the profile itself
                    .lastOrNull() // The nearest ancestor that set it
            } else null

            return ContinuousUsage(
                thresholdMinutes = triggering.alertThresholdMinutes,
                profileId        = triggering.id,
                profileName      = triggering.name,
                inheritedFrom    = inheritedFrom
            )
        }
    }
}