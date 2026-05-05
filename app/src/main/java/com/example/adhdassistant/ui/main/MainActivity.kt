package com.example.adhdassistant.ui.main

import android.Manifest
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.context.GeofenceBroadcastReceiver
import com.example.adhdassistant.tracking.UsageTrackingService
import com.example.adhdassistant.utils.PermissionManager
import kotlinx.coroutines.runBlocking

class MainActivity : AppCompatActivity() {

    private lateinit var permissionManager: PermissionManager
    private lateinit var geofencingClient: GeofencingClient
    private lateinit var configRepository: ConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionManager = PermissionManager(this)
        geofencingClient = LocationServices.getGeofencingClient(this)
        configRepository = ConfigRepository(this)

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            checkPermissionsAndStart()
        }
    }

    private fun checkPermissionsAndStart() {
        if (!permissionManager.hasUsageStatsPermission()) {
            showUsageDisclosure()
            return
        }
        if (!permissionManager.hasOverlayPermission()) {
            permissionManager.requestOverlayPermission(this)
            return
        }
        registerHomeGeofence()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            registerHomeGeofence()
        } else {
            Toast.makeText(this, getString(R.string.msg_location_required), Toast.LENGTH_LONG).show()
        }
    }

    private fun registerHomeGeofence() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                100
            )
            return
        }

        val geofence = Geofence.Builder()
            .setRequestId("HOME")
            .setCircularRegion(configRepository.homeLat, configRepository.homeLng, 150f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val intent = Intent(this, GeofenceBroadcastReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                startForegroundService(Intent(this, UsageTrackingService::class.java))
                Toast.makeText(this, getString(R.string.msg_tracker_active), Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, getString(R.string.msg_geofence_failed, e.message), Toast.LENGTH_LONG).show()
            }
    }

    private fun showUsageDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_usage_title))
            .setMessage(getString(R.string.dialog_usage_message))
            .setPositiveButton(getString(R.string.btn_grant)) { _, _ ->
                permissionManager.requestUsageStatsPermission(this)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
