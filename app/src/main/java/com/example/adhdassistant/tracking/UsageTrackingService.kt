package com.example.adhdassistant.tracking

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adhdassistant.R
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.data.ActivityEvent
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.domain.TriggerClause
import com.example.adhdassistant.domain.ResolvedRoutine
import com.example.adhdassistant.ui.alert.AlertActivity
import com.example.adhdassistant.ui.AlertExplanationActivity
import com.google.android.gms.location.ActivityRecognition
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.Calendar

class UsageTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "adhd_tracking_channel"
        const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val SNOOZE_EXTRA = "snooze_minutes"
        private const val TAG = "UsageTrackingService"
        private const val GRACE_PERIOD_MS = 90_000L

        const val ACTION_UPDATE_ACTIVITY_STATE = "com.example.adhdassistant.UPDATE_ACTIVITY_STATE"
        const val EXTRA_ACTIVITY_TYPE = "activity_type"
        const val EXTRA_ACTIVITY_CONFIDENCE = "activity_confidence"

        fun snoozeIntent(context: android.content.Context, minutes: Int): Intent =
            Intent(context, UsageTrackingService::class.java).apply {
                putExtra(SNOOZE_EXTRA, minutes)
            }
    }

    private val configRepository get() = (applicationContext as ADHDApplication).configRepository
    private lateinit var database: AppDatabase
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val heuristicsEngine = MovementHeuristicsEngine()
    private var isUserMoving = true
    private var activityRecognitionPendingIntent: PendingIntent? = null

    private var continuousUsageStartTime = 0L
    private var isCurrentlyUsingPhone = false
    private var lastForegroundPackage: String? = null
    private var snoozedUntilMs = 0L
    private var trackingJob: Job? = null
    private var savedPackage: String? = null
    private var savedUsageStartTime: Long = 0L
    private var savedSessionEndMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()

        val intent = Intent(this, ActivityTransitionReceiver::class.java)
        activityRecognitionPendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_UPDATE_ACTIVITY_STATE) {
            val type = intent.getIntExtra(EXTRA_ACTIVITY_TYPE, -1)
            val conf = intent.getIntExtra(EXTRA_ACTIVITY_CONFIDENCE, 0)
            if (type != -1) {
                val trulyStill = heuristicsEngine.processNewState(type, conf)
                isUserMoving = !trulyStill
            }
            return START_STICKY
        }

        val snoozeMinutes = intent?.getIntExtra(SNOOZE_EXTRA, 0) ?: 0
        if (snoozeMinutes > 0) {
            snoozedUntilMs = System.currentTimeMillis() + (snoozeMinutes * 60_000L)
            return START_STICKY
        }

        startForegroundCompat()
        startTracking()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        trackingJob?.cancel()
        serviceScope.cancel()
        activityRecognitionPendingIntent?.let {
            try {
                ActivityRecognition.getClient(this).removeActivityUpdates(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove activity updates", e)
            }
        }
    }

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_active))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Focus Tracking", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps focus tracking active in the background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun startTracking() {
        trackingJob?.cancel()

        try {
            ActivityRecognition.getClient(this)
                .requestActivityUpdates(10_000L, activityRecognitionPendingIntent!!)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Activity Recognition permission", e)
        }

        trackingJob = serviceScope.launch {
            while (isActive) {
                try { tick() } catch (e: Exception) { Log.e(TAG, "Error", e) }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        if (System.currentTimeMillis() < snoozedUntilMs) return

        val routine = configRepository.getActiveResolvedRoutine() ?: return

        if (!isWithinActiveHours(routine.startHour, routine.endHour)) {
            resetTracking()
            heuristicsEngine.resetBuffer()
            return
        }

        val foregroundPackage = getForegroundPackage()

        if (foregroundPackage == null) {
            resetTracking(clearSaved = false)
            return
        }

        if (foregroundPackage == packageName) return

        if (configRepository.isProVersion() && routine.excludedApps.contains(foregroundPackage)) {
            resetTracking()
            return
        }

        val now = System.currentTimeMillis()

        if (!isCurrentlyUsingPhone || foregroundPackage != lastForegroundPackage) {
            if (isCurrentlyUsingPhone && lastForegroundPackage != null) {
                savedPackage        = lastForegroundPackage
                savedUsageStartTime = continuousUsageStartTime
                savedSessionEndMs   = now
            }

            isCurrentlyUsingPhone = true
            lastForegroundPackage = foregroundPackage

            val resuming = foregroundPackage == savedPackage && (now - savedSessionEndMs) < GRACE_PERIOD_MS
            if (resuming) {
                val accumulatedMs        = savedSessionEndMs - savedUsageStartTime
                continuousUsageStartTime = now - accumulatedMs
            } else {
                continuousUsageStartTime = now
            }

            if (!resuming && foregroundPackage in routine.onOpenPromptPackages) {
                triggerAlert(foregroundPackage, 0L, routine, AlertActivity.TRIGGER_TYPE_ON_OPEN)
            }
        } else {
            val durationMs  = now - continuousUsageStartTime
            val thresholdMs = routine.alertThresholdMinutes * 60_000L

            if (durationMs >= thresholdMs) {
                triggerAlert(foregroundPackage, durationMs, routine, AlertActivity.TRIGGER_TYPE_THRESHOLD)
                resetTracking()
            }
        }
    }

    private fun resetTracking(clearSaved: Boolean = true) {
        isCurrentlyUsingPhone = false
        continuousUsageStartTime = 0L
        lastForegroundPackage = null
        if (clearSaved) {
            savedPackage        = null
            savedUsageStartTime = 0L
            savedSessionEndMs   = 0L
        }
    }

    private fun getForegroundPackage(): String? {
        return try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now)
            stats?.filter { it.lastTimeUsed > 0 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun triggerAlert(
        packageName: String,
        durationMs: Long,
        routine: ResolvedRoutine,
        triggerType: String = AlertActivity.TRIGGER_TYPE_THRESHOLD
    ) {
        val clause = if (triggerType == AlertActivity.TRIGGER_TYPE_ON_OPEN) {
            TriggerClause.OnOpen(routineId = routine.id, routineName = routine.name)
        } else {
            TriggerClause.ContinuousUsage(thresholdMinutes = routine.alertThresholdMinutes, routineId = routine.id, routineName = routine.name)
        }

        val event = ActivityEvent(
            activeRoutineIds = Json.encodeToString(listOf(routine.id)),
            activeRoutineNames = Json.encodeToString(listOf(routine.name)),
            triggeringRoutineId = routine.id,
            triggeringRoutineName = routine.name,
            triggerClause = clause.toJson(),
            timestamp = System.currentTimeMillis(),
            appPackage = packageName,
            durationMs = durationMs,
            actionTaken = "TRIGGERED"
        )
        database.activityEventDao().insertEvent(event)

        val bundle = AlertTriggerBundle(
            routineId         = routine.id,
            routineName       = routine.name,
            locationName      = routine.locationName ?: routine.name,
            accumulatedTimeMs = durationMs,
            thresholdMs       = routine.alertThresholdMinutes * 60_000L,
            distractingApp    = packageName,
            requiresMovement  = true,
            userWasStill      = !isUserMoving,
            allowedAppWasOpen = false
        )

        val alertIntent = Intent(this, AlertExplanationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlertExplanationActivity.EXTRA_BUNDLE, bundle)
        }
        startActivity(alertIntent)
    }

    private fun isWithinActiveHours(startHour: Int, endHour: Int): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            hour >= startHour || hour < endHour
        }
    }
}