package com.example.adhdassistant.domain

import com.example.adhdassistant.config.RoutineSchedule

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
    val inheritanceChain: List<String>,
    val locationName: String? = null
)