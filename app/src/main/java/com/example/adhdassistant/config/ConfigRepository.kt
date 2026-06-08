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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import com.example.adhdassistant.domain.ResolvedRoutine

// ─── DataStore singleton ──────────────────────────────────────────────────────
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "adhd_settings_v2")

class ConfigRepository(private val context: Context) {

    private object Keys {
        val IS_PRO = booleanPreferencesKey("is_pro")
        val IS_ONBOARDING_COMPLETE = booleanPreferencesKey("is_onboarding_complete")
        val IS_RUN_IN_BACKGROUND = booleanPreferencesKey("is_run_in_background")
        val LAST_SUMMARY_NOTIFICATION_MS = longPreferencesKey("last_summary_notification_ms")
        val ADAPTIVE_THRESHOLD_STATE = stringPreferencesKey("adaptive_threshold_state")
        val EXCLUDED_APPS = stringSetPreferencesKey("excluded_apps")
        val SNOOZED_UNTIL_MS = longPreferencesKey("snoozed_until_ms")
        val PER_APP_SNOOZE_JSON = stringPreferencesKey("per_app_snooze_json")

        val ROUTINE_LIST = stringPreferencesKey("profile_list")
        val INTENTION_LIST = stringPreferencesKey("chore_list")
    }

    private val dataStore = context.dataStore

    // ─── Legacy Settings ──────────────────────────────────────────────────────
    fun isProVersion(): Boolean = runBlocking { dataStore.data.map { it[Keys.IS_PRO] ?: false }.first() }
    suspend fun setProVersion(isPro: Boolean) { dataStore.edit { it[Keys.IS_PRO] = isPro } }

    fun isOnboardingComplete(): Boolean = runBlocking { dataStore.data.map { it[Keys.IS_ONBOARDING_COMPLETE] ?: false }.first() }
    suspend fun setOnboardingComplete(complete: Boolean) { dataStore.edit { it[Keys.IS_ONBOARDING_COMPLETE] = complete } }

    fun isRunInBackground(): Boolean = runBlocking { dataStore.data.map { it[Keys.IS_RUN_IN_BACKGROUND] ?: false }.first() }
    suspend fun setRunInBackground(run: Boolean) { dataStore.edit { it[Keys.IS_RUN_IN_BACKGROUND] = run } }

    fun getLastSummaryNotificationMs(): Long = runBlocking { dataStore.data.map { it[Keys.LAST_SUMMARY_NOTIFICATION_MS] ?: 0L }.first() }
    suspend fun setLastSummaryNotificationMs(ms: Long) { dataStore.edit { it[Keys.LAST_SUMMARY_NOTIFICATION_MS] = ms } }

    suspend fun getAdaptiveThresholdStateJson(): String? = dataStore.data.map { it[Keys.ADAPTIVE_THRESHOLD_STATE] }.first()
    suspend fun setAdaptiveThresholdStateJson(json: String) { dataStore.edit { it[Keys.ADAPTIVE_THRESHOLD_STATE] = json } }

    suspend fun getExcludedApps(): Set<String> = dataStore.data.map { it[Keys.EXCLUDED_APPS] ?: emptySet() }.first()
    suspend fun setExcludedApps(apps: Set<String>) { dataStore.edit { it[Keys.EXCLUDED_APPS] = apps } }

    suspend fun getSnoozedUntilMs(): Long = dataStore.data.first()[Keys.SNOOZED_UNTIL_MS] ?: 0L
    suspend fun setSnoozedUntilMs(untilMs: Long) { dataStore.edit { it[Keys.SNOOZED_UNTIL_MS] = untilMs } }

    /** Returns the per-app snooze map (packageName -> expiryMs). Expired entries are not filtered here. */
    private suspend fun getPerAppSnoozeMap(): MutableMap<String, Long> {
        val json = dataStore.data.first()[Keys.PER_APP_SNOOZE_JSON] ?: return mutableMapOf()
        return runCatching { Json.decodeFromString<Map<String, Long>>(json).toMutableMap() }
            .getOrDefault(mutableMapOf())
    }

    /** Returns the snooze expiry for a specific package, or 0 if not snoozed. */
    suspend fun getAppSnoozedUntil(packageName: String): Long =
        getPerAppSnoozeMap()[packageName] ?: 0L

    /** Sets a per-app snooze expiry. Pass 0 to clear. */
    suspend fun setAppSnoozedUntil(packageName: String, untilMs: Long) {
        dataStore.edit { prefs ->
            val json = prefs[Keys.PER_APP_SNOOZE_JSON]
            val map = runCatching {
                Json.decodeFromString<Map<String, Long>>(json ?: "{}").toMutableMap()
            }.getOrDefault(mutableMapOf())
            if (untilMs == 0L) map.remove(packageName) else map[packageName] = untilMs
            prefs[Keys.PER_APP_SNOOZE_JSON] = Json.encodeToString(map)
        }
    }

    // ─── Routine & Intention Flows ────────────────────────────────────────────
    val routineListFlow: Flow<List<Routine>> = dataStore.data
        .catchIOException()
        .map { prefs ->
            val json = prefs[Keys.ROUTINE_LIST]
            if (json.isNullOrBlank()) listOf(buildDefaultRoutine()) else json.deserializeList<Routine>()
        }

    val routinesFlow get() = routineListFlow

    val intentionListFlow: Flow<List<IntentionItem>> = dataStore.data
        .catchIOException()
        .map { prefs -> prefs[Keys.INTENTION_LIST]?.deserializeList<IntentionItem>() ?: emptyList() }

    // ─── Active Resolvers ─────────────────────────────────────────────────────
    suspend fun getTopIntention(): IntentionItem? = intentionListFlow.first().firstOrNull()

    suspend fun getActiveRoutine(): Routine? {
        val routines = routineListFlow.first()
        return routines.firstOrNull { it.isManuallyActive } ?: routines.firstOrNull()
    }

    suspend fun getActiveResolvedRoutine(): ResolvedRoutine? {
        val activeRoutine = getActiveRoutine() ?: buildDefaultRoutine()
        return ResolvedRoutine(
            id = activeRoutine.id,
            name = activeRoutine.name,
            startHour = activeRoutine.startHour ?: 8,
            endHour = activeRoutine.endHour ?: 22,
            alertThresholdMinutes = activeRoutine.alertThresholdMinutes ?: 5,
            excludedApps = getExcludedApps(),
            onOpenPromptPackages = emptySet(),
            emoji = activeRoutine.emoji,
            schedule = activeRoutine.schedule ?: RoutineSchedule.DaysOfWeek(setOf(1, 2, 3, 4, 5, 6, 7)),
            isManuallyActive = activeRoutine.isManuallyActive,
            inheritanceChain = listOf(activeRoutine.name),
            locationName = activeRoutine.locationName
        )
    }

    // ─── CRUD Operations ──────────────────────────────────────────────────────
    suspend fun updateRoutineList(routines: List<Routine>) {
        dataStore.edit { it[Keys.ROUTINE_LIST] = Json.encodeToString(routines) }
    }

    suspend fun saveRoutine(routine: Routine) {
        val current = routineListFlow.first().toMutableList()
        val index = current.indexOfFirst { it.id == routine.id }
        if (index >= 0) current[index] = routine else current.add(routine)
        updateRoutineList(current)
    }

    suspend fun deleteRoutine(routine: Routine) {
        val current = routineListFlow.first().toMutableList()
        current.removeAll { it.id == routine.id }
        updateRoutineList(current)
    }

    suspend fun updateIntentionList(intentions: List<IntentionItem>) {
        dataStore.edit { it[Keys.INTENTION_LIST] = Json.encodeToString(intentions) }
    }

    companion object {
        fun buildDefaultRoutine() = Routine(
            id = 1L, name = "Default", emoji = "🧠", parentId = null,
            startHour = 8, endHour = 22, alertThresholdMinutes = 5,
            schedule = RoutineSchedule.DaysOfWeek(setOf(1, 2, 3, 4, 5, 6, 7)),
            excludedAppOverrides = null, isManuallyActive = false
        )
    }

    private inline fun <reified T> String.deserializeList(): List<T> =
        runCatching { Json.decodeFromString<List<T>>(this) }.getOrDefault(emptyList())

    private fun Flow<Preferences>.catchIOException(): Flow<Preferences> =
        catch { if (it is IOException) emit(emptyPreferences()) else throw it }
}