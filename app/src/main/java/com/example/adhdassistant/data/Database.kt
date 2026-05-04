package com.example.adhdassistant.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

// ─── Entity ───────────────────────────────────────────────────────────────────

@Entity(tableName = "activity_events")
data class ActivityEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val activeProfileIds: String,
    val activeProfileNames: String,

    val triggeringProfileId: Long,
    val triggeringProfileName: String,

    val triggerClause: String,

    val timestamp: Long,
    val appPackage: String,
    val durationMs: Long,
    val actionTaken: String
) {
    fun parsedClause() =
        com.example.adhdassistant.domain.TriggerClause.fromJson(triggerClause)

    fun triggerSummary(): String =
        parsedClause()?.describe() ?: triggerClause

    fun activeProfileNameList(): List<String> = runCatching {
        Json.decodeFromString<List<String>>(activeProfileNames)
    }.getOrDefault(listOf(triggeringProfileName))
}

// ─── Query result types ───────────────────────────────────────────────────────

data class ActionCount(val action: String, val count: Int)
data class AppUsageStat(val appPackage: String, val totalAlerts: Int, val totalDurationMs: Long)
data class ProfileTriggerStat(val triggeringProfileName: String, val triggerCount: Int)

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
            WHERE triggeringProfileId = :triggeringProfileId 
            AND actionTaken = 'TRIGGERED'
        )
    """)
    suspend fun resolveLastEvent(triggeringProfileId: Long, action: String)

    @Query("""
        SELECT * FROM activity_events 
        WHERE triggeringProfileId = :profileId 
        AND timestamp BETWEEN :startMs AND :endMs 
        ORDER BY timestamp ASC
    """)
    fun getEventsForPeriod(profileId: Long, startMs: Long, endMs: Long): Flow<List<ActivityEvent>>

    @Query("SELECT * FROM activity_events WHERE timestamp >= :weekStartMs ORDER BY timestamp DESC")
    fun getAllEventsForWeek(weekStartMs: Long): Flow<List<ActivityEvent>>

    // FIXED: Wrapped 'action' in backticks to prevent SQLite keyword clash
    @Query("""
        SELECT actionTaken as `action`, COUNT(*) as count 
        FROM activity_events 
        WHERE triggeringProfileId = :profileId 
        AND timestamp BETWEEN :startMs AND :endMs
        AND actionTaken != 'TRIGGERED'
        GROUP BY actionTaken
    """)
    suspend fun getActionCounts(profileId: Long, startMs: Long, endMs: Long): List<ActionCount>

    @Query("""
        SELECT appPackage, COUNT(*) as totalAlerts, SUM(durationMs) as totalDurationMs
        FROM activity_events
        WHERE triggeringProfileId = :profileId
        AND timestamp BETWEEN :startMs AND :endMs
        GROUP BY appPackage
        ORDER BY totalAlerts DESC
        LIMIT 10
    """)
    suspend fun getTopApps(profileId: Long, startMs: Long, endMs: Long): List<AppUsageStat>

    @Query("""
        SELECT triggeringProfileName, COUNT(*) as triggerCount
        FROM activity_events
        WHERE timestamp BETWEEN :startMs AND :endMs
        AND actionTaken != 'TRIGGERED'
        GROUP BY triggeringProfileName
        ORDER BY triggerCount DESC
    """)
    suspend fun getAlertsByProfile(startMs: Long, endMs: Long): List<ProfileTriggerStat>

    @Query("""
        SELECT * FROM activity_events
        WHERE triggeringProfileId = :profileId
        AND timestamp BETWEEN :startMs AND :endMs
        ORDER BY timestamp DESC
    """)
    fun getEventsByTriggeringProfile(
        profileId: Long, startMs: Long, endMs: Long
    ): Flow<List<ActivityEvent>>

    @Query("DELETE FROM activity_events WHERE timestamp < :beforeMs")
    suspend fun pruneOldEvents(beforeMs: Long)

    @Query("SELECT COUNT(*) FROM activity_events WHERE timestamp BETWEEN :startMs AND :endMs")
    suspend fun getTotalCount(startMs: Long, endMs: Long): Int
}

// ─── Database ─────────────────────────────────────────────────────────────────

// FIXED: Set exportSchema = false to resolve the KSP missing directory error
@Database(entities = [ActivityEvent::class], version = 2, exportSchema = false)
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
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_events ADD COLUMN activeProfileIds TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN activeProfileNames TEXT NOT NULL DEFAULT '[]'")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN triggeringProfileId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN triggeringProfileName TEXT NOT NULL DEFAULT 'Legacy'")
                db.execSQL("ALTER TABLE activity_events ADD COLUMN triggerClause TEXT NOT NULL DEFAULT '{\"type\":\"com.example.adhdassistant.domain.TriggerClause.ContinuousUsage\",\"thresholdMinutes\":5,\"profileId\":0,\"profileName\":\"Legacy\"}'")

                db.execSQL("""
                    UPDATE activity_events 
                    SET activeProfileIds = '[' || profileId || ']',
                        triggeringProfileId = profileId
                    WHERE profileId IS NOT NULL AND profileId != 0
                """)
            }
        }
    }
}