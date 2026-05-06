package com.example.adhdassistant.utils

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Centralises all permission checks and system-settings launches.
 *
 * No permission is requested silently. Every request is triggered by a
 * user action (button tap) after an explanation has been shown.
 *
 * File location: app/src/main/java/com/example/adhdassistant/utils/PermissionManager.kt
 */
class PermissionManager(private val context: Context) {

    companion object {
        const val REQUEST_LOCATION    = 101
        const val REQUEST_BACKGROUND  = 102
        const val REQUEST_NOTIFICATION = 103
    }

    // ─── Usage Stats ──────────────────────────────────────────────────────────

    /**
     * Usage Stats is not a "dangerous" permission — there is no runtime dialog.
     * The user must manually grant it in Settings > Special app access > Usage access.
     * This check reads from AppOpsManager to see if it has been granted.
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                context.applicationInfo.uid,
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Opens the system Usage Access settings screen.
     * The user taps the toggle next to the app name.
     * We deep-link to this specific app's entry where possible.
     */
    fun requestUsageStatsPermission(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            // Some OEMs don't support the deep-link — fall back to the list
            activity.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    // ─── Location ─────────────────────────────────────────────────────────────

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return hasLocationPermission()
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Location is OPTIONAL. This is only called when the user taps
     * "Enable home detection" on the onboarding location page or in Settings.
     *
     * On Android 10+ we must ask for foreground location first, then background
     * separately — Android won't allow both in the same request.
     */
    fun requestLocationPermission(activity: Activity) {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQUEST_LOCATION
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                   !hasBackgroundLocationPermission()) {
            // Foreground already granted — now ask for background separately
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                REQUEST_BACKGROUND
            )
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Only requested when the user enables the background tracking toggle in Settings.
     * Never requested proactively.
     */
    fun requestNotificationPermission(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION
            )
        }
    }

    // ─── Overlay (draw over other apps) ───────────────────────────────────────

    fun hasOverlayPermission(): Boolean =
        Settings.canDrawOverlays(context)

    fun requestOverlayPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.fromParts("package", context.packageName, null)
        )
        activity.startActivity(intent)
    }
}
