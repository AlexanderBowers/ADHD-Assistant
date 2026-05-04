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

    @Test
    fun insertAndRetrieveEvent() = runBlocking {
        val event = ActivityEvent(actionTaken = "MOVED")
        dao.insertEvent(event)
        val events = dao.getEventsForCurrentWeek(1, 0L).first()
        assertEquals(1, events.size)
        assertEquals("MOVED", events[0].actionTaken)
    }

    @Test
    fun delayedEventIsStoredWithReason() = runBlocking {
        val event = ActivityEvent(actionTaken = "DELAYED", delayReason = "Needed to finish task")
        dao.insertEvent(event)
        val events = dao.getEventsForCurrentWeek(1, 0L).first()
        assertEquals("DELAYED", events[0].actionTaken)
        assertEquals("Needed to finish task", events[0].delayReason)
    }
}
