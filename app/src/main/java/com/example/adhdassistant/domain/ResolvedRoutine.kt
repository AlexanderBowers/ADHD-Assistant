package com.example.adhdassistant.domain

import com.example.adhdassistant.config.RoutineSchedule

/**
 * A fully resolved Routine where all inheritance has been applied.
 * No fields are nullable here because the RoutineResolver has guaranteed
 * that any missing values were pulled from a parent.
 */
data class ResolvedRoutine(
    val id: Long,
    val name: String,
    val emoji: String,
    val startHour: Int,
    val endHour: Int,
    val alertThresholdMinutes: Int,
    val excludedApps: Set<String>,
    val onOpenPromptPackages: Set<String>,
    val schedule: RoutineSchedule,
    val isManuallyActive: Boolean,
    val inheritanceChain: List<String>
)