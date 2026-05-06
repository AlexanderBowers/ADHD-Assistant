package com.example.adhdassistant.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*

@RunWith(AndroidJUnit4::class)
class ActivityEventDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ActivityEventDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = database.activityEventDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    // Helper to build a minimal valid ActivityEvent for testing
    private fun makeEvent(
        actionTaken: String = "TRIGGERED",
        appPackage: String = "com.test.app",
        durationMs: Long = 60_000L,
        timestamp: Long = System.currentTimeMillis()
    ) = ActivityEvent(
        activeRoutineIds   = "[1]",
        activeRoutineNames = "[\"Default\"]",
        triggeringRoutineId   = 1L,
        triggeringRoutineName = "Default",
        triggerClause = "{\"type\":\"TimeThreshold\",\"minutes\":5}",
        timestamp  = timestamp,
        appPackage = appPackage,
        durationMs = durationMs,
        actionTaken = actionTaken
    )

    @Test
    fun insertAndRetrieveEvent() = runBlocking {
        val event = makeEvent(actionTaken = "MOVED")
        dao.insertEvent(event)

        val weekStartMs = 0L
        val events = dao.getAllEventsForWeek(weekStartMs).first()
        assertEquals(1, events.size)
        assertEquals("MOVED", events[0].actionTaken)
    }

    @Test
    fun insertMultipleEventsAndCountThem() = runBlocking {
        dao.insertEvent(makeEvent(actionTaken = "TRIGGERED"))
        dao.insertEvent(makeEvent(actionTaken = "DISMISSED"))
        dao.insertEvent(makeEvent(actionTaken = "SNOOZED"))

        val count = dao.getTotalCount(0L, Long.MAX_VALUE)
        assertEquals(3, count)
    }

    @Test
    fun resolveLastEventUpdatesAction() = runBlocking {
        dao.insertEvent(makeEvent(actionTaken = "TRIGGERED"))

        dao.resolveLastEvent(triggeringRoutineId = 1L, action = "DISMISSED")

        val events = dao.getAllEventsForWeek(0L).first()
        assertEquals(1, events.size)
        assertEquals("DISMISSED", events[0].actionTaken)
    }

    @Test
    fun pruneOldEventsRemovesStaleRows() = runBlocking {
        val old  = makeEvent(timestamp = 1_000L)
        val recent = makeEvent(timestamp = System.currentTimeMillis())
        dao.insertEvent(old)
        dao.insertEvent(recent)

        // Prune everything older than 1 hour ago
        val cutoff = System.currentTimeMillis() - 3_600_000L
        dao.pruneOldEvents(cutoff)

        val remaining = dao.getAllEventsForWeek(0L).first()
        assertEquals(1, remaining.size)
        assertTrue(remaining[0].timestamp >= cutoff)
    }

    @Test
    fun groundingChoiceCanBeUpdated() = runBlocking {
        dao.insertEvent(makeEvent(actionTaken = "TRIGGERED"))
        dao.updateLastEventGroundingChoice(triggeringRoutineId = 1L, choice = "breathing")

        val events = dao.getAllEventsForWeek(0L).first()
        assertEquals("breathing", events[0].groundingChoice)
    }
}
