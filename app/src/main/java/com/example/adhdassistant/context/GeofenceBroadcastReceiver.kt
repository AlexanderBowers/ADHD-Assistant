package com.example.adhdassistant.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.example.adhdassistant.tracking.UsageTrackingService

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent)
        if (geofencingEvent?.hasError() == true) return

        val transition = geofencingEvent?.geofenceTransition
        val serviceIntent = Intent(context, UsageTrackingService::class.java)

        if (transition == Geofence.GEOFENCE_TRANSITION_ENTER) {
            Log.d("GeofenceReceiver", "Arrived Home. Starting Foreground Tracker.")
            // Start the Foreground Service instantly
            ContextCompat.startForegroundService(context, serviceIntent)

        } else if (transition == Geofence.GEOFENCE_TRANSITION_EXIT) {
            Log.d("GeofenceReceiver", "Left Home. Disabling Trackers.")
            // Stop the service when you leave
            context.stopService(serviceIntent)
        }
    }
}