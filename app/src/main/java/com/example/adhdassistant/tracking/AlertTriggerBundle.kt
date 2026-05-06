package com.example.adhdassistant.tracking

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class AlertTriggerBundle(
    val routineId: Long = -1L,
    val routineName: String,
    val locationName: String,
    val accumulatedTimeMs: Long,
    val thresholdMs: Long,
    val distractingApp: String,
    val requiresMovement: Boolean,
    val userWasStill: Boolean,
    val allowedAppWasOpen: Boolean
) : Parcelable {

    fun toReasons(): List<AlertReason> = buildList {
        // Card 1: Location context
        add(AlertReason(
            emoji = "📍",
            label = "You were at $locationName",
            detail = "Your \"$routineName\" routine activated here."
        ))

        // Card 2: Time calculation
        val totalSeconds = accumulatedTimeMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val timeLabel = when {
            minutes > 0 && seconds > 0 -> "${minutes}m ${seconds}s"
            minutes > 0               -> "${minutes}m"
            else                      -> "${seconds}s"
        }
        val limitLabel = "${thresholdMs / 1000 / 60}m"

        add(AlertReason(
            emoji = "⏱️",
            label = "Phone open for $timeLabel",
            detail = "The limit for this routine is $limitLabel."
        ))

        // Card 3: Stillness
        if (requiresMovement && userWasStill) {
            add(AlertReason(
                emoji = "🧍",
                label = "It looks like you've been still for a while",
                detail = "This routine expects movement while you're here."
            ))
        }

        // Card 4: App context
        if (!allowedAppWasOpen && distractingApp.isNotBlank()) {
            val appLabel = distractingApp
                .substringAfterLast(".")
                .replaceFirstChar { it.uppercaseChar() }
            add(AlertReason(
                emoji = "📱",
                label = "You were in $appLabel",
                detail = "This app doesn't pause the timer here."
            ))
        }
    }
}