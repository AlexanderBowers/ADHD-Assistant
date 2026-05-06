package com.example.adhdassistant.tracking

import android.util.Log
import com.google.android.gms.location.DetectedActivity

/**
 * Evaluates movement using a rolling buffer to prevent false-positive "STILL" readings.
 * Provides a "Grace Window" so momentarily pausing doesn't instantly trigger alerts.
 */
class MovementHeuristicsEngine {
    
    // We keep a rolling window of the last 5 activity readings.
    private val stateBuffer = ArrayDeque<Int>(5)
    private val REQUIRED_STILL_COUNT = 4

    /**
     * Pushes a new Activity Recognition state into the buffer and evaluates 
     * if the user is truly "STILL".
     */
    fun processNewState(detectedActivityType: Int, confidence: Int): Boolean {
        // Only trust readings with > 50% confidence from the OS
        if (confidence < 50) return isCurrentlyStill()

        // Maintain the 5-item rolling buffer
        if (stateBuffer.size >= 5) {
            stateBuffer.removeFirst()
        }
        stateBuffer.addLast(detectedActivityType)

        val trulyStill = isCurrentlyStill()
        
        Log.d("MovementEngine", "Buffer: $stateBuffer | Truly Still? $trulyStill")
        return trulyStill
    }

    /**
     * Evaluates the Grace Window:
     * If 4 out of the last 5 readings are STILL, we are truly still.
     * Otherwise, we assume they are pacing, paused momentarily, or moving.
     */
    fun isCurrentlyStill(): Boolean {
        val stillCount = stateBuffer.count { it == DetectedActivity.STILL }
        return stillCount >= REQUIRED_STILL_COUNT
    }

    /**
     * Call this when a routine ends or the screen turns off to prevent stale data.
     */
    fun resetBuffer() {
        stateBuffer.clear()
    }
}