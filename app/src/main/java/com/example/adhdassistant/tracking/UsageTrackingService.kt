package com.example.adhdassistant.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigManager
import com.example.adhdassistant.ui.MovementEnforcerActivity
import kotlinx.coroutines.*

class UsageTrackingService : Service() {

    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var configManager: ConfigManager
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var trackingJob: Job? = null

    private var continuousUsageStartTime = 0L
    private var isCurrentlyUsingPhone = false

    companion object {
        private const val CHANNEL_ID = "FocusTrackerChannel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        configManager = ConfigManager(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        startTrackingLoop()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Focus Tracker",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Actively monitoring focus metrics."
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Focus Tracker Active")
            .setContentText("Monitoring phone usage while at home.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun startTrackingLoop() {
        if (trackingJob?.isActive == true) return

        trackingJob = serviceScope.launch {
            while (isActive) {
                checkUsageStatus()
                delay(30_000)
            }
        }
    }

    private fun checkUsageStatus() {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 1000 * 60

        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var currentForegroundApp: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentForegroundApp = event.packageName
            } else if (event.eventType == UsageEvents.Event.ACTIVITY_PAUSED ||
                event.eventType == UsageEvents.Event.DEVICE_SHUTDOWN ||
                event.eventType == UsageEvents.Event.SCREEN_NON_INTERACTIVE) {
                currentForegroundApp = null
            }
        }

        evaluateUsage(currentForegroundApp)
    }

    private fun evaluateUsage(foregroundApp: String?) {
        val currentTime = System.currentTimeMillis()

        if (foregroundApp == null || isLauncherApp(foregroundApp)) {
            resetTimer()
            return
        }

        if (configManager.isProVersion && configManager.excludedApps.contains(foregroundApp)) {
            resetTimer()
            return
        }

        if (!isCurrentlyUsingPhone) {
            isCurrentlyUsingPhone = true
            continuousUsageStartTime = currentTime
        } else {
            val durationMs = currentTime - continuousUsageStartTime
            if (durationMs >= configManager.timerDurationMs) {
                triggerMovementPrompt()
                resetTimer()
            }
        }
    }

    private fun resetTimer() {
        isCurrentlyUsingPhone = false
        continuousUsageStartTime = 0L
    }

    private fun triggerMovementPrompt() {
        val intent = Intent(this, MovementEnforcerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)
    }

    private fun isLauncherApp(packageName: String): Boolean {
        return packageName.contains("launcher", ignoreCase = true) ||
                packageName.contains("nexuslauncher", ignoreCase = true) ||
                packageName.contains("trebuchet", ignoreCase = true)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}