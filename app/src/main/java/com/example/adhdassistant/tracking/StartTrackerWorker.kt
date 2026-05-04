package com.example.adhdassistant.tracking

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.adhdassistant.R

class StartTrackerWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        val intent = Intent(applicationContext, UsageTrackingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(intent)
        } else {
            applicationContext.startService(intent)
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, "FocusTrackerChannel")
            .setContentTitle("Starting Focus Tracker...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
        return ForegroundInfo(2, notification)
    }
}