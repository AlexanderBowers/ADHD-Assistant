package com.example.adhdassistant.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Single source of truth for all persistent app settings.
 *
 * Replaces ConfigManager (SharedPreferences).
 * Uses DataStore for coroutine-safe reads/writes and Flow-based observation.
 */
class ConfigRepository(private val context: Context) {

    // ─── DataStore instance (one per app, lazy) ───────────────────────────────
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = "adhd_settings"
    )

    // ─── Keys ─────────────────────────────────────────────────────────────────
    private object Keys {
        val IS_PRO                  = booleanPreferencesKey("is_pro")
        val RUN_IN_BACKGROUND       = booleanPreferencesKey("run_in_background")
        val EXCLUDED_APPS_JSON      = stringPreferencesKey("excluded_apps")
        val CHORE_LIST_JSON         = stringPreferencesKey("chore_list")
        val ACTIVE_PROFILE_ID       = longPreferencesKey("active_profile_id")
        val PROFILES_JSON           = stringPreferencesKey("profiles")
        val ONBOARDING_COMPLETE     = booleanPreferencesKey("onboarding_complete")
        val LAST_SUMMARY_NOTIF_MS   = longPreferencesKey("last_summary_notif_ms")
        val ADAPTIVE_THRESHOLD_JSON = stringPreferencesKey("adaptive_threshold_json")
        val HOME_LAT                = doublePreferencesKey("home_lat")
        val HOME_LNG                = doublePreferencesKey("home_lng")
    }

    // ─── Flows (for observing in ViewModels) ──────────────────────────────────

    val isProFlow: Flow<Boolean> = context.dataStore.data
        .catchIOException()
        .map { it[Keys.IS_PRO] ?: false }

    val runInBackgroundFlow: Flow<Boolean> = context.dataStore.data
        .catchIOException()
        .map { it[Keys.RUN_IN_BACKGROUND] ?: false }

    val choreListFlow: Flow<List<ChoreItem>> = context.dataStore.data
        .catchIOException()
        .map { prefs ->
            prefs[Keys.CHORE_LIST_JSON]?.deserializeList() ?: emptyList()
        }

    val profilesFlow: Flow<List<Profile>> = context.dataStore.data
        .catchIOException()
        .map { prefs ->
            val json = prefs[Keys.PROFILES_JSON]
            if (json.isNullOrEmpty()) listOf(defaultProfile())
            else runCatching { Json.decodeFromString<List<Profile>>(json) }
                .getOrDefault(listOf(defaultProfile()))
        }

    // ─── Suspend Reads ────────────────────────────────────────────────────────

    suspend fun isProVersion(): Boolean = isProFlow.first()
    suspend fun isRunInBackground(): Boolean = runInBackgroundFlow.first()

    suspend fun isOnboardingComplete(): Boolean =
        context.dataStore.data.first()[Keys.ONBOARDING_COMPLETE] ?: false

    suspend fun getExcludedApps(): Set<String> {
        val json = context.dataStore.data.first()[Keys.EXCLUDED_APPS_JSON] ?: return emptySet()
        return runCatching { Json.decodeFromString<Set<String>>(json) }.getOrDefault(emptySet())
    }

    suspend fun getChoreList(): List<ChoreItem> = choreListFlow.first()

    suspend fun getTopChore(): ChoreItem? = getChoreList().firstOrNull()

    suspend fun getActiveProfile(): Profile? {
        val id = context.dataStore.data.first()[Keys.ACTIVE_PROFILE_ID] ?: 1L
        val profiles = getProfiles()
        return profiles.firstOrNull { it.id == id } ?: profiles.firstOrNull()
    }

    // Delegates to profilesFlow so both stay in sync
    suspend fun getProfiles(): List<Profile> = profilesFlow.first()

    suspend fun getLastSummaryNotificationMs(): Long =
        context.dataStore.data.first()[Keys.LAST_SUMMARY_NOTIF_MS] ?: 0L

    suspend fun getAdaptiveThresholdStateJson(): String? =
        context.dataStore.data.first()[Keys.ADAPTIVE_THRESHOLD_JSON]

    val homeLat: Double
        get() = runBlocking {
            context.dataStore.data.first()[Keys.HOME_LAT] ?: 37.422
        }

    val homeLng: Double
        get() = runBlocking {
            context.dataStore.data.first()[Keys.HOME_LNG] ?: -122.084
        }

    suspend fun setHomeLocation(lat: Double, lng: Double) {
        context.dataStore.edit {
            it[Keys.HOME_LAT] = lat
            it[Keys.HOME_LNG] = lng
        }
    }

    // ─── Writes ───────────────────────────────────────────────────────────────

    suspend fun setProVersion(isPro: Boolean) {
        context.dataStore.edit { it[Keys.IS_PRO] = isPro }
    }

    suspend fun setRunInBackground(value: Boolean) {
        context.dataStore.edit { it[Keys.RUN_IN_BACKGROUND] = value }
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { it[Keys.ONBOARDING_COMPLETE] = true }
    }

    suspend fun setExcludedApps(apps: Set<String>) {
        context.dataStore.edit { it[Keys.EXCLUDED_APPS_JSON] = Json.encodeToString(apps) }
    }

    suspend fun setChoreList(chores: List<ChoreItem>) {
        context.dataStore.edit { it[Keys.CHORE_LIST_JSON] = Json.encodeToString(chores) }
    }

    suspend fun addChore(text: String) {
        val updated = getChoreList().toMutableList().also {
            it.add(ChoreItem(text = text))
        }
        setChoreList(updated)
    }

    suspend fun removeChore(id: String) {
        val updated = getChoreList().filter { it.id != id }
        setChoreList(updated)
    }

    suspend fun reorderChores(chores: List<ChoreItem>) {
        setChoreList(chores)
    }

    suspend fun setActiveProfile(profileId: Long) {
        context.dataStore.edit { it[Keys.ACTIVE_PROFILE_ID] = profileId }
    }

    suspend fun saveProfile(profile: Profile) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index >= 0) profiles[index] = profile else profiles.add(profile)
        context.dataStore.edit { it[Keys.PROFILES_JSON] = Json.encodeToString(profiles) }
    }

    suspend fun deleteProfile(profileId: Long) {
        val profiles = getProfiles().filter { it.id != profileId }
        context.dataStore.edit { it[Keys.PROFILES_JSON] = Json.encodeToString(profiles) }
    }

    suspend fun setLastSummaryNotificationMs(timeMs: Long) {
        context.dataStore.edit { it[Keys.LAST_SUMMARY_NOTIF_MS] = timeMs }
    }

    suspend fun setAdaptiveThresholdStateJson(json: String) {
        context.dataStore.edit { it[Keys.ADAPTIVE_THRESHOLD_JSON] = json }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun defaultProfile() = Profile(
        id                    = 1L,
        name                  = "Default",
        emoji                 = "🧠",
        parentId              = null,
        startHour             = 8,
        endHour               = 22,
        alertThresholdMinutes = 5,
        schedule              = ProfileSchedule.DaysOfWeek(setOf(1, 2, 3, 4, 5, 6, 7)),
        excludedAppOverrides  = null,
        isManuallyActive      = false
    )

    private inline fun <reified T> String.deserializeList(): List<T> =
        runCatching { Json.decodeFromString<List<T>>(this) }.getOrDefault(emptyList())

    private fun <T> Flow<T>.catchIOException(): Flow<T> =
        catch { if (it is IOException) emit(emptyPreferences() as T) else throw it }
}

// ─── Domain Models ────────────────────────────────────────────────────────────

@Serializable
data class ChoreItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String
)