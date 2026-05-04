package com.example.adhdassistant.config

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("AdhdAppPrefs", Context.MODE_PRIVATE)

    var isProVersion: Boolean
        get() = prefs.getBoolean("PRO_VERSION", false)
        set(value) = prefs.edit().putBoolean("PRO_VERSION", value).apply()

    // Free = 5 minutes (300,000 ms) locked. Pro = Configurable.
    var timerDurationMs: Long
        get() = if (isProVersion) prefs.getLong("TIMER_DURATION", 5 * 60 * 1000L) else 5 * 60 * 1000L
        set(value) = prefs.edit().putLong("TIMER_DURATION", value).apply()

    var isGeofenceEnabled: Boolean
        get() = prefs.getBoolean("GEOFENCE_ENABLED", true)
        set(value) = prefs.edit().putBoolean("GEOFENCE_ENABLED", value).apply()

    var excludedApps: Set<String>
        get() = if (isProVersion) prefs.getStringSet("EXCLUDED_APPS", emptySet()) ?: emptySet() else emptySet()
        set(value) = prefs.edit().putStringSet("EXCLUDED_APPS", value).apply()

    var currentProfileId: Int
        get() = if (isProVersion) prefs.getInt("CURRENT_PROFILE_ID", 1) else 1
        set(value) = prefs.edit().putInt("CURRENT_PROFILE_ID", value).apply()

    var homeLat: Double
        get() = prefs.getFloat("HOME_LAT", 40.4949f).toDouble()
        set(value) = prefs.edit().putFloat("HOME_LAT", value.toFloat()).apply()

    var homeLng: Double
        get() = prefs.getFloat("HOME_LNG", -111.9391f).toDouble()
        set(value) = prefs.edit().putFloat("HOME_LNG", value.toFloat()).apply()
}