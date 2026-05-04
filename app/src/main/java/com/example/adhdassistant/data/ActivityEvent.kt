package com.example.adhdassistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "activity_events")
data class ActivityEvent(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val profileId: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val actionTaken: String,
    val delayReason: String? = null
)

@Dao
interface ActivityEventDao {
    @Insert
    suspend fun insertEvent(event: ActivityEvent)

    @Query("SELECT * FROM activity_events WHERE profileId = :profileId AND timestamp >= :startOfWeekTimestamp ORDER BY timestamp DESC")
    fun getEventsForCurrentWeek(profileId: Int, startOfWeekTimestamp: Long): Flow<List<ActivityEvent>>
}