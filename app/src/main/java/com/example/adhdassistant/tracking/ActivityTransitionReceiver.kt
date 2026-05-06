package com.example.adhdassistant.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityRecognitionResult

/**
 * Catches background updates from Google's Activity Recognition API and forwards 
 * them to the UsageTrackingService.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent) {
        if (ActivityRecognitionResult.hasResult(intent)) {
            val result = ActivityRecognitionResult.extractResult(intent)
            result?.let {
                // Get the most probable activity the user is doing right now
                val mostProbableActivity = it.mostProbableActivity
                
                // Send it directly to our running Service
                val serviceIntent = Intent(context, UsageTrackingService::class.java).apply {
                    action = UsageTrackingService.ACTION_UPDATE_ACTIVITY_STATE
                    putExtra(UsageTrackingService.EXTRA_ACTIVITY_TYPE, mostProbableActivity.type)
                    putExtra(UsageTrackingService.EXTRA_ACTIVITY_CONFIDENCE, mostProbableActivity.confidence)
                }
                context.startService(serviceIntent)
            }
        }
    }
}