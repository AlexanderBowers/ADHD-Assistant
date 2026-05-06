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
 *   OnOpen          — the user opened an app that has an immediate intention prompt configured.
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
     * @param routineId         ID of the routine whose rule matched.
     * @param routineName       Name of the routine whose rule matched (denormalised for display).
     * @param inheritedFrom     If the threshold was inherited from a parent routine,
     *                          this holds the ancestor's name. Null if defined on the
     *                          routine itself.
     */
    @Serializable
    data class ContinuousUsage(
        val thresholdMinutes: Int,
        val routineId: Long,
        val routineName: String,
        val inheritedFrom: String? = null
    ) : TriggerClause() {
        override fun describe(): String {
            val source = if (inheritedFrom != null) {
                "$routineName (inherited from $inheritedFrom)"
            } else {
                routineName
            }
            return "${thresholdMinutes}min continuous use · $source"
        }
    }

    /**
     * Fired the moment a watched app appears in the foreground, before
     * any usage threshold is reached. Prompts the user about their intention.
     *
     * @param routineId    ID of the routine that has this app in onOpenPromptPackages.
     * @param routineName  Name of that routine (denormalised for display).
     */
    @Serializable
    data class OnOpen(
        val routineId: Long,
        val routineName: String
    ) : TriggerClause() {
        override fun describe(): String = "Opened app · $routineName"
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    fun toJson(): String = Json.encodeToString(this)

    companion object {
        fun fromJson(json: String): TriggerClause? =
            runCatching { Json.decodeFromString<TriggerClause>(json) }.getOrNull()

        /**
         * Given a list of resolved routines that are all currently active,
         * determine which routine's threshold is responsible for the alert
         * and return the appropriate clause.
         *
         * The triggering routine is the one with the lowest threshold (strictest),
         * since that's the one whose rule the merged routine used.
         * In a tie, the routine that appears first in the list is used.
         *
         * Also detects whether the winning threshold was inherited or defined
         * directly on the routine, for display in the log.
         */
        fun identify(
            activeRoutines: List<com.example.adhdassistant.config.ResolvedRoutine>,
            allRawRoutines: List<com.example.adhdassistant.config.Routine>
        ): ContinuousUsage? {
            val triggering = activeRoutines.minByOrNull { it.alertThresholdMinutes } ?: return null

            // Determine whether the threshold was inherited or set directly
            val rawRoutine = allRawRoutines.firstOrNull { it.id == triggering.id }
            val inheritedFrom = if (rawRoutine?.alertThresholdMinutes == null) {
                // Null on the raw routine → inherited from somewhere in the chain
                triggering.inheritanceChain
                    .dropLast(1)  // Remove the routine itself
                    .lastOrNull() // The nearest ancestor that set it
            } else null

            return ContinuousUsage(
                thresholdMinutes = triggering.alertThresholdMinutes,
                routineId        = triggering.id,
                routineName      = triggering.name,
                inheritedFrom    = inheritedFrom
            )
        }
    }
}