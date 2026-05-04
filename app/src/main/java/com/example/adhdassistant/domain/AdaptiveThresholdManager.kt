package com.example.adhdassistant.domain

import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.config.Profile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Watches how the user responds to check-ins over time and surfaces a gentle
 * suggestion to adjust the threshold if they're consistently continuing past it.
 *
 * Philosophy:
 * - Never frame this as "you failed" or "you ignored X alerts."
 * - The suggestion is opt-in and easy to dismiss.
 * - The goal is to find a rhythm that actually works — not to enforce discipline.
 *
 * Suggestion triggers:
 * - 5+ consecutive "keep going" responses on the same profile
 * - Evaluated once per day per profile (not on every check-in)
 *
 * Suggestion cadence:
 * - Won't re-suggest on the same profile for 7 days after the user dismisses.
 * - Resets if the user accepts the suggestion.
 */
class AdaptiveThresholdManager(private val configRepository: ConfigRepository) {

    companion object {
        private const val CONSECUTIVE_THRESHOLD = 5    // "keep going" responses before suggesting
        private const val STEP_MINUTES = 2             // Suggest adding this many minutes
        private const val MAX_THRESHOLD_MINUTES = 30   // Never suggest above this
        private const val SNOOZE_DAYS = 7              // Days before re-suggesting after dismiss
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Call after every user action on an alert.
     * @param action    "MOVED", "SNOOZED", "IGNORED", "USEFUL" (kept going usefully)
     * @param profileId The triggering profile's ID
     */
    suspend fun recordAction(action: String, profileId: Long) {
        val state = loadState()
        val profileState = state.profiles[profileId] ?: ProfileAdaptState()

        val updated = when (action) {
            "MOVED", "SNOOZED" -> profileState.copy(consecutiveContinues = 0)
            "IGNORED", "USEFUL" -> profileState.copy(consecutiveContinues = profileState.consecutiveContinues + 1)
            else -> profileState
        }

        val newState = state.copy(profiles = state.profiles + (profileId to updated))
        saveState(newState)
    }

    /**
     * Returns a suggestion if the user has been consistently continuing past check-ins.
     * Returns null if no suggestion is warranted right now.
     *
     * Call once when the app opens, or after a session ends.
     */
    suspend fun checkForSuggestion(profile: Profile): AdjustmentSuggestion? {
        val currentThreshold = profile.alertThresholdMinutes ?: return null
        if (currentThreshold >= MAX_THRESHOLD_MINUTES) return null

        val state = loadState()
        val profileState = state.profiles[profile.id] ?: return null

        // Too soon since last suggestion was dismissed?
        val snoozedUntil = profileState.suggestionSnoozedUntilMs
        if (snoozedUntil != null && System.currentTimeMillis() < snoozedUntil) return null

        // Not enough consecutive continues yet?
        if (profileState.consecutiveContinues < CONSECUTIVE_THRESHOLD) return null

        val suggested = (currentThreshold + STEP_MINUTES).coerceAtMost(MAX_THRESHOLD_MINUTES)
        return AdjustmentSuggestion(
            profileId          = profile.id,
            profileName        = profile.name ?: "this routine",
            currentMinutes     = currentThreshold,
            suggestedMinutes   = suggested,
            consecutiveContinues = profileState.consecutiveContinues
        )
    }

    /**
     * User accepted the suggestion — update their profile threshold and reset the counter.
     */
    suspend fun acceptSuggestion(suggestion: AdjustmentSuggestion, allProfiles: List<Profile>): List<Profile> {
        // Reset counter
        val state = loadState()
        val updated = state.profiles.toMutableMap()
        updated[suggestion.profileId] = ProfileAdaptState() // full reset
        saveState(state.copy(profiles = updated))

        // Update the profile's threshold
        return allProfiles.map { p ->
            if (p.id == suggestion.profileId) {
                p.copy(alertThresholdMinutes = suggestion.suggestedMinutes)
            } else p
        }
    }

    /**
     * User dismissed the suggestion — snooze for 7 days.
     */
    suspend fun dismissSuggestion(profileId: Long) {
        val state = loadState()
        val profileState = state.profiles[profileId] ?: ProfileAdaptState()
        val snoozedUntil = System.currentTimeMillis() + (SNOOZE_DAYS * 24 * 60 * 60 * 1000L)
        val updated = profileState.copy(
            consecutiveContinues    = 0,
            suggestionSnoozedUntilMs = snoozedUntil
        )
        val newState = state.copy(profiles = state.profiles + (profileId to updated))
        saveState(newState)
    }

    // ─── Data model ──────────────────────────────────────────────────────────

    data class AdjustmentSuggestion(
        val profileId:            Long,
        val profileName:          String,
        val currentMinutes:       Int,
        val suggestedMinutes:     Int,
        val consecutiveContinues: Int
    ) {
        fun title() = "Want to adjust your timing? 🤔"

        fun message() =
            "You've been continuing past your check-ins a lot lately — and that's totally okay. " +
                    "Want to try checking in every $suggestedMinutes minutes instead of $currentMinutes? " +
                    "We can always change it back."
    }

    @Serializable
    private data class AdaptState(
        val profiles: Map<Long, ProfileAdaptState> = emptyMap()
    )

    @Serializable
    private data class ProfileAdaptState(
        val consecutiveContinues:     Int  = 0,
        val suggestionSnoozedUntilMs: Long? = null
    )

    // ─── Persistence ──────────────────────────────────────────────────────────

    private suspend fun loadState(): AdaptState {
        val json = configRepository.getAdaptiveThresholdStateJson()
        return if (json.isNullOrEmpty()) AdaptState()
        else runCatching { Json.decodeFromString<AdaptState>(json) }.getOrDefault(AdaptState())
    }

    private suspend fun saveState(state: AdaptState) {
        configRepository.setAdaptiveThresholdStateJson(Json.encodeToString(state))
    }
}