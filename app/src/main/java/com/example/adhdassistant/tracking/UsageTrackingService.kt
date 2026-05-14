package com.example.adhdassistant.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.ui.alert.AlertActivity
import kotlinx.coroutines.*

class UsageTrackingService : Service() {

    // Creates a scope for our background loop that we can easily cancel later
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val CHANNEL_ID = "FocusTrackingChannel"
    private val ALERT_CHANNEL_ID = "FocusAlertChannel"
    private val NOTIFICATION_ID = 1001

    // State tracking
    private var currentActivityType: Int = 4 // UNKNOWN default for ActivityRecognition
    private var isSnoozed: Boolean = false
    private var snoozeEndTime: Long = 0L

    // Lazy load the repository to check excluded apps
    private val configRepository by lazy {
        (application as ADHDApplication).configRepository
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        // 1. Intercept specific actions (like Snooze from AlertActivity or Activity Updates)
        when (intent?.action) {
            ACTION_SNOOZE -> {
                val minutes = intent.getIntExtra(EXTRA_SNOOZE_MINUTES, 15)
                handleSnooze(minutes)
                return START_STICKY
            }
            ACTION_UPDATE_ACTIVITY_STATE -> {
                currentActivityType = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, 4)
                return START_STICKY
            }
        }

        // 2. If it's a standard start, build the persistent notification
        createNotificationChannels()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ADHD Assistant Active")
            .setContentText("Monitoring your focus. You've got this!")
            .setSmallIcon(R.mipmap.ic_launcher) // Update if you have a specific notification icon
            .setOngoing(true)
            .build()

        // 3. Promote this service to a Foreground Service
        startForeground(NOTIFICATION_ID, notification)

        // 4. Start the exact 1-minute loop
        startTrackingLoop()

        return START_STICKY
    }

    private fun startTrackingLoop() {
        serviceScope.launch {
            while (isActive) {
                // Only check usage if we aren't currently snoozed
                if (isSnoozed && System.currentTimeMillis() < snoozeEndTime) {
                    Log.d("UsageTracking", "Tracker is snoozed. Skipping check.")
                } else {
                    isSnoozed = false // Snooze expired
                    performUsageCheck()
                }

                // Wait exactly 60 seconds before checking again
                delay(60_000L)
            }
        }
    }

    private suspend fun performUsageCheck() {
        Log.d("UsageTracking", "Performing 1-minute usage check...")

        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 60_000L // Look at the last minute of usage

        val events = usageStatsManager.queryEvents(startTime, endTime)
        var currentForegroundApp: String? = null
        val event = android.app.usage.UsageEvents.Event()

        // Find the most recent app the user opened
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                currentForegroundApp = event.packageName
            }
        }

        if (currentForegroundApp != null) {
            val excludedApps = configRepository.getExcludedApps()

            // If the app is NOT excluded, and it's NOT our own app, trigger the alert
            if (!excludedApps.contains(currentForegroundApp) && currentForegroundApp != packageName) {
                Log.d("UsageTracking", "Distracting app detected: $currentForegroundApp")
                triggerAlert(currentForegroundApp)
            }
        }
    }

    private fun triggerAlert(distractingApp: String) {
        val alertIntent = Intent(this, AlertActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(AlertActivity.EXTRA_APP_PACKAGE, distractingApp)
        }

        // Wrap it in a PendingIntent for the Full-Screen Notification
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            alertIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Fire a High-Priority Notification with a Full-Screen Intent
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Focus Check!")
            .setContentText("Are you still doing what you intended to do?")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(pendingIntent, true) // TRUE forces the UI to pop up
            .setAutoCancel(true)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1002, notification)
    }

    private fun handleSnooze(minutes: Int) {
        isSnoozed = true
        snoozeEndTime = System.currentTimeMillis() + (minutes * 60 * 1000L)
        Log.d("UsageTracking", "Tracker snoozed for $minutes minutes until $snoozeEndTime")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Channel 1: The quiet, persistent background tracker
            val trackingChannel = NotificationChannel(
                CHANNEL_ID,
                "Focus Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(trackingChannel)

            // Channel 2: The loud, screen-waking alert channel
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Focus Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to interrupt you when you are distracted"
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    companion object {
        const val ACTION_SNOOZE = "ACTION_SNOOZE"
        const val EXTRA_SNOOZE_MINUTES = "extra_snooze_minutes"

        const val ACTION_UPDATE_ACTIVITY_STATE = "com.example.adhdassistant.UPDATE_ACTIVITY_STATE"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_ACTIVITY_CONFIDENCE = "activity_confidence"

        fun snoozeIntent(context: Context, minutes: Int): Intent {
            return Intent(context, UsageTrackingService::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(EXTRA_SNOOZE_MINUTES, minutes)
            }
        }
    }
}