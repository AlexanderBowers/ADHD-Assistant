package com.example.adhdassistant.tracking

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adhdassistant.R
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.data.ActivityEvent
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.domain.TriggerClause
import com.example.adhdassistant.ui.alert.AlertActivity
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.Calendar

/**
 * Foreground service that tracks foreground app usage and triggers focus alerts.
 *
 * Lifecycle:
 *  - Started by MainActivity when user has granted UsageStats permission.
 *  - Started on boot by BootReceiver (if Pro + background toggle enabled).
 *  - Survives app close when in foreground service mode.
 *  - Returns START_STICKY so Android restarts it if killed.
 */
class UsageTrackingService : Service() {

    companion object {
        const val CHANNEL_ID = "adhd_tracking_channel"
        const val NOTIFICATION_ID = 1001
        private const val CHECK_INTERVAL_MS = 30_000L   // 30 seconds — battery-friendly
        private const val SNOOZE_EXTRA = "snooze_minutes"
        private const val TAG = "UsageTrackingService"
        /**
         * If the user leaves an app and returns within this window, the usage
         * timer resumes rather than restarting.  Covers brief interruptions like
         * Chess.com ads, notification glances, or system dialogs without letting
         * a genuine switch-away reset a nearly-full threshold timer.
         */
        private const val GRACE_PERIOD_MS = 90_000L     // 90 seconds

        fun snoozeIntent(context: android.content.Context, minutes: Int): Intent =
            Intent(context, UsageTrackingService::class.java).apply {
                putExtra(SNOOZE_EXTRA, minutes)
            }
    }

    private val configRepository get() = (applicationContext as ADHDApplication).configRepository
    private lateinit var database: AppDatabase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── State ──────────────────────────────────────────────────────────────
    private var continuousUsageStartTime = 0L   // adjusted so (now - start) == accumulated time
    private var isCurrentlyUsingPhone = false
    private var lastForegroundPackage: String? = null
    private var snoozedUntilMs = 0L
    private var trackingJob: Job? = null

    // Grace-period resumption: remember the last session so a brief interruption
    // (ad, notification, launcher) doesn't reset a nearly-full threshold timer.
    private var savedPackage: String? = null
    private var savedUsageStartTime: Long = 0L  // same adjusted start for the saved session
    private var savedSessionEndMs: Long = 0L    // wall-clock time the saved session ended

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getDatabase(applicationContext)
        createNotificationChannel()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle snooze command from AlertActivity
        val snoozeMinutes = intent?.getIntExtra(SNOOZE_EXTRA, 0) ?: 0
        if (snoozeMinutes > 0) {
            snoozedUntilMs = System.currentTimeMillis() + (snoozeMinutes * 60_000L)
            Log.d(TAG, "Snoozed for $snoozeMinutes minutes")
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
        Log.d(TAG, "Service destroyed")
    }

    // ─── Foreground Notification ─────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
                CHANNEL_ID,
                "Focus Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps focus tracking active in the background"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    // ─── Tracking Loop ────────────────────────────────────────────────────────

    private fun startTracking() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    tick()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking tick", e)
                }
                delay(CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun tick() {
        // Check snooze
        if (System.currentTimeMillis() < snoozedUntilMs) return

        // Use the resolved profile so inherited fields (threshold, on-open packages, etc.)
        // are always present regardless of profile depth.
        val profile = configRepository.getActiveResolvedProfile() ?: return

        // Check if within active hours
        if (!isWithinActiveHours(profile.startHour, profile.endHour)) {
            resetTracking()
            return
        }

        val foregroundPackage = getForegroundPackage()

        if (foregroundPackage == null) {
            // Screen off or no app in foreground.
            // Keep saved session alive so resuming the same app within the grace
            // period still continues the timer (e.g. user briefly checks another
            // app or the screen dims, then picks back up where they left off).
            resetTracking(clearSaved = false)
            return
        }

        // Skip own app
        if (foregroundPackage == packageName) return

        // Check excluded apps (Pro feature only) — resolved profile has the merged set
        if (configRepository.isProVersion() && profile.excludedApps.contains(foregroundPackage)) {
            resetTracking()
            return
        }

        val now = System.currentTimeMillis()

        if (!isCurrentlyUsingPhone || foregroundPackage != lastForegroundPackage) {
            // App switched (or fresh start after screen-off / outside-hours reset).
            // Save the outgoing session so we can resume it if the user comes back.
            if (isCurrentlyUsingPhone && lastForegroundPackage != null) {
                savedPackage        = lastForegroundPackage
                savedUsageStartTime = continuousUsageStartTime
                savedSessionEndMs   = now
            }

            isCurrentlyUsingPhone = true
            lastForegroundPackage = foregroundPackage

            // Resume the timer if returning to the same app within the grace period.
            // Adjust the start time so accumulated time = (savedSessionEndMs - savedUsageStartTime),
            // i.e. we don't count the gap away.
            val resuming = foregroundPackage == savedPackage &&
                           (now - savedSessionEndMs) < GRACE_PERIOD_MS
            if (resuming) {
                val accumulatedMs        = savedSessionEndMs - savedUsageStartTime
                continuousUsageStartTime = now - accumulatedMs
                Log.d(TAG, "Resumed session: $foregroundPackage " +
                           "(gap ${(now - savedSessionEndMs) / 1_000}s, " +
                           "accumulated ${accumulatedMs / 60_000}min)")
            } else {
                continuousUsageStartTime = now
                Log.d(TAG, "Session started: $foregroundPackage")
            }

            // On-open intention prompt — fires immediately when the app opens
            // (not on resume — user already saw it for this session).
            if (!resuming && foregroundPackage in profile.onOpenPromptPackages) {
                triggerAlert(
                    packageName = foregroundPackage,
                    durationMs  = 0L,
                    profile     = profile,
                    triggerType = AlertActivity.TRIGGER_TYPE_ON_OPEN
                )
            }
        } else {
            val durationMs  = now - continuousUsageStartTime
            val thresholdMs = profile.alertThresholdMinutes * 60_000L

            if (durationMs >= thresholdMs) {
                Log.d(TAG, "Threshold reached: ${durationMs / 60_000}min on $foregroundPackage")
                triggerAlert(foregroundPackage, durationMs, profile, AlertActivity.TRIGGER_TYPE_THRESHOLD)
                resetTracking()
            }
        }
    }

    /**
     * Clears all tracking state.
     *
     * @param clearSaved  When true (default), also clears the grace-period saved
     *                    session so the next app-open starts a fresh timer.  Pass
     *                    false only when the reset is due to a brief screen-off so
     *                    that returning to the same app within the grace period can
     *                    still resume the timer.
     */
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

    // ─── Foreground App Detection ─────────────────────────────────────────────

    private fun getForegroundPackage(): String? {
        return try {
            val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // Query last 10 seconds for the most recent foreground app
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 10_000L, now)
            stats?.filter { it.lastTimeUsed > 0 }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying usage stats", e)
            null
        }
    }

    // ─── Alert ───────────────────────────────────────────────────────────────

    private suspend fun triggerAlert(
        packageName: String,
        durationMs: Long,
        profile: com.example.adhdassistant.config.ResolvedProfile,
        triggerType: String = AlertActivity.TRIGGER_TYPE_THRESHOLD
    ) {
        val clause = if (triggerType == AlertActivity.TRIGGER_TYPE_ON_OPEN) {
            TriggerClause.OnOpen(
                profileId   = profile.id,
                profileName = profile.name
            )
        } else {
            TriggerClause.ContinuousUsage(
                thresholdMinutes = profile.alertThresholdMinutes,
                profileId        = profile.id,
                profileName      = profile.name
            )
        }

        // Persist the event
        val event = ActivityEvent(
            activeProfileIds = Json.encodeToString(listOf(profile.id)),
            activeProfileNames = Json.encodeToString(listOf(profile.name)),
            triggeringProfileId = profile.id,
            triggeringProfileName = profile.name,
            triggerClause = clause.toJson(),
            timestamp = System.currentTimeMillis(),
            appPackage = packageName,
            durationMs = durationMs,
            actionTaken = "TRIGGERED"
        )
        database.activityEventDao().insertEvent(event)

        // Get top chore if any (shown on alert screen during off-hours periods)
        val topChore = configRepository.getTopChore()

        // Launch the full-screen alert Activity
        val alertIntent = Intent(this, AlertActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlertActivity.EXTRA_DURATION_MS, durationMs)
            putExtra(AlertActivity.EXTRA_APP_PACKAGE, packageName)
            putExtra(AlertActivity.EXTRA_CHORE_TEXT, topChore?.text)
            putExtra(AlertActivity.EXTRA_TRIGGER_TYPE, triggerType)
        }
        startActivity(alertIntent)
    }

    // ─── Time Helpers ─────────────────────────────────────────────────────────

    private fun isWithinActiveHours(startHour: Int, endHour: Int): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            // Handles overnight ranges (e.g. 22:00 → 06:00)
            hour >= startHour || hour < endHour
        }
    }
}