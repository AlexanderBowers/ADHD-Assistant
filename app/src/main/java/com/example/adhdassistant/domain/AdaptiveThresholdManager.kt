package com.example.adhdassistant.domain

import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.config.Routine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AdaptiveThresholdManager(private val configRepository: ConfigRepository) {

    companion object {
        private const val CONSECUTIVE_THRESHOLD = 5
        private const val STEP_MINUTES = 2
        private const val MAX_THRESHOLD_MINUTES = 30
        private const val SNOOZE_DAYS = 7
    }

    data class AdjustmentSuggestion(
        val routineId: Long,
        val routineName: String,
        val currentMinutes: Int,
        val suggestedMinutes: Int,
        val consecutiveContinues: Int
    ) {
        fun title() = "Want to adjust your timing? 🤔"
        fun message() = "You've been continuing past your check-ins a lot lately — and that's totally okay. " +
                "Want to try checking in every $suggestedMinutes minutes instead of $currentMinutes? We can always change it back."
    }

    @Serializable
    private data class AdaptState(
        val routines: Map<Long, RoutineAdaptState> = emptyMap()
    )

    @Serializable
    private data class RoutineAdaptState(
        val consecutiveContinues: Int = 0,
        val suggestionSnoozedUntilMs: Long? = null
    )

    private suspend fun loadState(): AdaptState {
        val json = configRepository.getAdaptiveThresholdStateJson()
        // Fixed String Elvis Warning
        return if (json.isNullOrEmpty()) AdaptState()
        else runCatching { Json.decodeFromString<AdaptState>(json) }.getOrDefault(AdaptState())
    }

    private suspend fun saveState(state: AdaptState) {
        configRepository.setAdaptiveThresholdStateJson(Json.encodeToString(state))
    }

    suspend fun recordAction(action: String, routineId: Long, routineName: String, currentThreshold: Int): AdjustmentSuggestion? {
        val state = loadState()
        val routineState = state.routines[routineId] ?: RoutineAdaptState()

        val newContinues = when (action) {
            "USEFUL", "IGNORED" -> routineState.consecutiveContinues + 1
            "MOVED", "SNOOZED" -> 0
            else -> routineState.consecutiveContinues
        }

        val updatedState = state.copy(
            routines = state.routines + (routineId to routineState.copy(consecutiveContinues = newContinues))
        )
        saveState(updatedState)

        if (newContinues >= CONSECUTIVE_THRESHOLD) {
            val snoozedUntil = routineState.suggestionSnoozedUntilMs ?: 0L
            if (System.currentTimeMillis() > snoozedUntil && currentThreshold < MAX_THRESHOLD_MINUTES) {
                return AdjustmentSuggestion(
                    routineId = routineId,
                    routineName = routineName, // Fixed Warning
                    currentMinutes = currentThreshold,
                    suggestedMinutes = minOf(currentThreshold + STEP_MINUTES, MAX_THRESHOLD_MINUTES),
                    consecutiveContinues = newContinues
                )
            }
        }
        return null
    }

    suspend fun acceptSuggestion(suggestion: AdjustmentSuggestion) {
        val state = loadState()
        val updatedState = state.copy(
            routines = state.routines + (suggestion.routineId to RoutineAdaptState(consecutiveContinues = 0))
        )
        saveState(updatedState)
    }

    suspend fun dismissSuggestion(routineId: Long) {
        val state = loadState()
        val routineState = state.routines[routineId] ?: RoutineAdaptState()
        val snoozeUntil = System.currentTimeMillis() + (SNOOZE_DAYS * 24 * 60 * 60 * 1000L)
        val updatedState = state.copy(
            routines = state.routines + (routineId to routineState.copy(suggestionSnoozedUntilMs = snoozeUntil))
        )
        saveState(updatedState)
    }
}