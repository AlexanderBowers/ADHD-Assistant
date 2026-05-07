package com.example.adhdassistant.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

// ─── Entity ───────────────────────────────────────────────────────────────────

@Entity(tableName = "activity_events")
data class ActivityEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val activeRoutineIds: String,
    val activeRoutineNames: String,

    val triggeringRoutineId: Long,
    val triggeringRoutineName: String,

    val triggerClause: String,

    val timestamp: Long,
    val appPackage: String,
    val durationMs: Long,
    val actionTaken: String,
    val groundingChoice: String? = null
) {
    fun parsedClause() =
        com.example.adhdassistant.domain.TriggerClause.fromJson(triggerClause)

    fun triggerSummary(): String =
        parsedClause()?.describe() ?: triggerClause

    fun activeRoutineNameList(): List<String> = runCatching {
        Json.decodeFromString<List<String>>(activeRoutineNames)
    }.getOrDefault(listOf(triggeringRoutineName))
}

// ─── Query result types ───────────────────────────────────────────────────────

data class ActionCount(val action: String, val count: Int)
data class AppUsageStat(val appPackage: String, val totalAlerts: Int, val totalDurationMs: Long)
data class RoutineTriggerStat(val triggeringRoutineName: String, val triggerCount: Int)

// ─── DAO ──────────────────────────────────────────────────────────────────────

@Dao
interface ActivityEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ActivityEvent): Long

    @Query("""
        UPDATE activity_events 
        SET actionTaken = :action 
        WHERE id = (
            SELECT MAX(id) FROM activity_events 
            WHERE triggeringRoutineId = :triggeringRoutineId 
            AND actionTaken = 'TRIGGERED'
        )
    """)
    suspend fun resolveLastEvent(triggeringRoutineId: Long, action: String)

    @Query("""
        UPDATE activity_events 
        SET groundingChoice = :choice 
        WHERE id = (
            SELECT MAX(id) FROM activity_events 
            WHERE triggeringRoutineId = :triggeringRoutineId 
        )
    """)
    suspend fun updateLastEventGroundingChoice(triggeringRoutineId: Long, choice: String)

    @Query("""
        SELECT * FROM activity_events 
        WHERE triggeringRoutineId = :routineId 
        AND timestamp BETWEEN :startMs AND :endMs 
        ORDER BY timestamp ASC
    """)
    fun getEventsForPeriod(routineId: Long, startMs: Long, endMs: Long): Flow<List<ActivityEvent>>

    @Query("SELECT * FROM activity_events WHERE timestamp >= :weekStartMs ORDER BY timestamp DESC")
    fun getAllEventsForWeek(weekStartMs: Long): Flow<List<ActivityEvent>>

    @Query("""
        SELECT actionTaken as `action`, COUNT(*) as count 
        FROM activity_events 
        WHERE triggeringRoutineId = :routineId 
        AND timestamp BETWEEN :startMs AND :endMs
        AND actionTaken != 'TRIGGERED'
        GROUP BY actionTaken
    """)
    suspend fun getActionCounts(routineId: Long, startMs: Long, endMs: Long): List<ActionCount>

    @Query("""
        SELECT appPackage, COUNT(*) as totalAlerts, SUM(durationMs) as totalDurationMs
        FROM activity_events
        WHERE triggeringRoutineId = :routineId
        AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY appPackage
        ORDER BY totalAlerts DESC
        LIMIT 10
    """)
    suspend fun getTopApps(routineId: Long, startMs: Long, endMs: Long): List<AppUsageStat>

    @Query("""
        SELECT triggeringRoutineName, COUNT(*) as triggerCount
        FROM activity_events
        WHERE timestamp BETWEEN :startMs AND :endMs
        AND actionTaken != 'TRIGGERED'
        GROUP BY triggeringRoutineName
        ORDER BY triggerCount DESC
    """)
    suspend fun getAlertsByRoutine(startMs: Long, endMs: Long): List<RoutineTriggerStat>

    @Query("""
        SELECT * FROM activity_events
        WHERE triggeringRoutineId = :routineId
        AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp DESC
    """)
    fun getEventsByTriggeringRoutine(
        routineId: Long, startMs: Long, endMs: Long
    ): Flow<List<ActivityEvent>>

    @Query("DELETE FROM activity_events WHERE timestamp < :beforeMs")
    suspend fun pruneOldEvents(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM activity_events WHERE timestamp BETWEEN :startMs AND :endMs")
    suspend fun getTotalCount(startMs: Long, endMs: Long): Int
}

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(entities = [ActivityEvent::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun activityEventDao(): ActivityEventDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "adhd_assistant_db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}