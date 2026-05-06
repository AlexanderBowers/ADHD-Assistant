package com.example.adhdassistant.tracking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.data.AppDatabase
import kotlinx.coroutines.*
import java.util.Calendar

/**
 * Listens for screen-off events and sends a brief, warm summary notification.
 *
 * Design principle: the notification is a friendly end-of-session note, not a
 * report card. It never uses words like "failed", "ignored", or "alerts".
 * The framing is always positive: check-ins are a sign of self-awareness.
 *
 * Register in AndroidManifest.xml:
 *   <receiver android:name=".tracking.SessionSummaryReceiver" android:exported="false">
 *     <intent-filter>
 *       <action android:name="android.intent.action.SCREEN_OFF" />
 *     </intent-filter>
 *   </receiver>
 *
 * Note: ACTION_SCREEN_OFF can't be declared in the manifest on modern Android
 * for battery reasons. Register this receiver dynamically in UsageTrackingService:
 *
 *   // In UsageTrackingService.onCreate():
 *   val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
 *   registerReceiver(sessionSummaryReceiver, filter)
 *
 *   // In UsageTrackingService.onDestroy():
 *   unregisterReceiver(sessionSummaryReceiver)
 */
class SessionSummaryReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID  = "adhd_session_summary"
        private const val NOTIF_ID    = 2001
        private const val TAG         = "SessionSummaryReceiver"
        private const val MIN_CHECKINS_TO_NOTIFY = 1  // Always notify if at least one check-in
        private const val MIN_INTERVAL_MS = 30 * 60 * 1000L  // Don't notify more than once per 30 min
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_OFF) return

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                val config = (context.applicationContext as ADHDApplication).configRepository
                val lastNotifMs = config.getLastSummaryNotificationMs()
                val now = System.currentTimeMillis()

                // Rate-limit: don't fire on every screen-off, just meaningful sessions
                if (now - lastNotifMs < MIN_INTERVAL_MS) return@launch

                val database = AppDatabase.getDatabase(context)
                val dao = database.activityEventDao()

                val todayStart = startOfDayMs()
                val count = dao.getTotalCount(todayStart, now)

                if (count < MIN_CHECKINS_TO_NOTIFY) return@launch

                val message = buildMessage(context, count)
                sendNotification(context, message)
                config.setLastSummaryNotificationMs(now)

            } catch (e: Exception) {
                Log.e(TAG, "Error in SessionSummaryReceiver", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun buildMessage(context: Context, count: Int): String = when {
        count == 0  -> context.getString(R.string.summary_zero)
        count == 1  -> context.getString(R.string.summary_one)
        count <= 5  -> context.getString(R.string.summary_few, count)
        else        -> context.getString(R.string.summary_many, count)
    }

    private fun sendNotification(context: Context, message: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.summary_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.summary_channel_desc)
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.summary_title))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSilent(true)   // No sound — this is a quiet, closing note
            .build()

        nm.notify(NOTIF_ID, notification)
    }

    private fun startOfDayMs(): Long {
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    // configRepository is accessed via ADHDApplication singleton — see call sites above
}

// ─── Add to ConfigRepository.kt ──────────────────────────────────────────────
/*
    private object Keys {
        // ... existing keys ...
        val LAST_SUMMARY_NOTIF_MS = longPreferencesKey("last_summary_notif_ms")
    }

    suspend fun getLastSummaryNotificationMs(): Long =
        context.dataStore.data.first()[Keys.LAST_SUMMARY_NOTIF_MS] ?: 0L

    suspend fun setLastSummaryNotificationMs(ms: Long) {
        context.dataStore.edit { it[Keys.LAST_SUMMARY_NOTIF_MS] = ms }
    }
*/

// ─── Register in UsageTrackingService ────────────────────────────────────────
/*
    // Add as a field:
    private val sessionSummaryReceiver = SessionSummaryReceiver()

    // In onCreate():
    registerReceiver(sessionSummaryReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))

    // In onDestroy():
    try { unregisterReceiver(sessionSummaryReceiver) } catch (e: Exception) { }
*/


// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

/**
 * Manages the "keep going" count for re-alert language escalation.
 *
 * Controls which greeting the AlertActivity shows:
 *   0 continues → "Quick check-in!" / 👋
 *   1–3 →         "Hey again!" / 😊
 *   4+ →          "We keep meeting like this!" / 😄
 *
 * Resets when the user taps "wrapping up" or the session ends (screen off).
 * Stored in memory only — resets on service restart, which is the right behaviour.
 */
class ReAlertTracker {

    private var continuesThisSession = 0

    fun recordContinue() { continuesThisSession++ }

    fun recordDone() { continuesThisSession = 0 }

    fun currentAlertLevel(): AlertLevel = when {
        continuesThisSession == 0  -> AlertLevel.FIRST
        continuesThisSession <= 3  -> AlertLevel.REPEAT
        else                       -> AlertLevel.MANY
    }

    fun reset() { continuesThisSession = 0 }

    enum class AlertLevel { FIRST, REPEAT, MANY }
}

// ─── Use in UsageTrackingService ─────────────────────────────────────────────
/*
    // Add as a field:
    private val reAlertTracker = ReAlertTracker()

    // In triggerAlert(), pass the level to AlertActivity:
    putExtra(AlertActivity.EXTRA_ALERT_LEVEL, reAlertTracker.currentAlertLevel().name)

    // When service processes the user's action response:
    when (action) {
        "MOVED", "SNOOZED" -> reAlertTracker.recordDone()
        "IGNORED", "USEFUL" -> reAlertTracker.recordContinue()
    }

    // In SessionSummaryReceiver (screen off):
    reAlertTracker.reset()  // Can't do this cross-process — handle in service instead
                             // by registering the receiver inside the service.
*/