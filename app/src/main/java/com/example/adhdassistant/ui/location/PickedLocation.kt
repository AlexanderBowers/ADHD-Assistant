package com.example.adhdassistant.ui.location

data class PickedLocation(
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusMeters: Int
)