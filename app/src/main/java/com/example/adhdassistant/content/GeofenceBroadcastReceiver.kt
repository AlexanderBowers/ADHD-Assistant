package com.example.adhdassistant.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.example.adhdassistant.tracking.StartTrackerWorker
import com.example.adhdassistant.tracking.UsageTrackingService

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent?.hasError() == true) return

        val transition = geofencingEvent?.geofenceTransition

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d("GeofenceReceiver", "Arrived Home. Enqueueing Tracker Start.")
            val work = OneTimeWorkRequestBuilder<StartTrackerWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(work)
        } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("GeofenceReceiver", "Left Home. Disabling Trackers.")
            val serviceIntent = Intent(context, UsageTrackingService::class.java)
            context.stopService(serviceIntent)
        }
    }
}