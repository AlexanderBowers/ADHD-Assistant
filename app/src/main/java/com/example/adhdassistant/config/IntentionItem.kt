package com.example.adhdassistant.config

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Represents a single intention  in the user's list.
 */
@Serializable
data class IntentionItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String
)