package com.example.adhdassistant.ui.location

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.adhdassistant.R
import com.example.adhdassistant.utils.PermissionManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.Circle
import com.google.android.gms.maps.model.CircleOptions
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_RADIUS = "extra_radius"

        const val EXTRA_INIT_LAT = "extra_init_lat"
        const val EXTRA_INIT_LNG = "extra_init_lng"
        const val EXTRA_INIT_RADIUS = "extra_init_radius"

        private const val DEFAULT_ZOOM = 15f
        private const val DEFAULT_RADIUS = 200.0
    }

    private lateinit var permissionManager: PermissionManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var etLocationName: TextInputEditText
    private lateinit var tvHint: TextView
    private lateinit var cardBottom: MaterialCardView
    private lateinit var tvRadiusLabel: TextView
    private lateinit var sliderRadius: Slider
    private lateinit var btnConfirm: MaterialButton

    private var googleMap: GoogleMap? = null
    private var currentMarker: Marker? = null
    private var currentCircle: Circle? = null
    private var pinnedLatLng: LatLng? = null

    private var initLat: Double? = null
    private var initLng: Double? = null
    private var initRadius: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_picker)

        permissionManager = PermissionManager(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        etLocationName = findViewById(R.id.etLocationName)
        tvHint = findViewById(R.id.tvHint)
        cardBottom = findViewById(R.id.cardBottom)
        tvRadiusLabel = findViewById(R.id.tvRadiusLabel)
        sliderRadius = findViewById(R.id.sliderRadius)
        btnConfirm = findViewById(R.id.btnConfirm)

        initLat = intent.getDoubleExtra(EXTRA_INIT_LAT, Double.NaN).takeIf { !it.isNaN() }
        initLng = intent.getDoubleExtra(EXTRA_INIT_LNG, Double.NaN).takeIf { !it.isNaN() }
        initRadius = intent.getIntExtra(EXTRA_INIT_RADIUS, -1).takeIf { it != -1 }

        initRadius?.let { sliderRadius.value = it.toFloat().coerceIn(50f, 2000f) }
        updateRadiusLabel(sliderRadius.value.toInt())

        sliderRadius.addOnChangeListener { _, value, _ ->
            updateRadiusLabel(value.toInt())
            pinnedLatLng?.let { redrawCircle(it, value.toDouble()) }
        }

        btnConfirm.setOnClickListener { returnResult() }

        if (permissionManager.hasLocationPermission()) {
            initMap()
        } else {
            permissionManager.requestLocationPermission(this)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionManager.REQUEST_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initMap()
        } else {
            Toast.makeText(this, "Location permission needed to pick a location", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun initMap() {
        val mapFragment = supportFragmentManager.findFragmentById(R.id.mapFragment) as SupportMapFragment
        mapFragment.getMapAsync(this)
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map

        if (permissionManager.hasLocationPermission()) {
            @Suppress("MissingPermission")
            map.isMyLocationEnabled = true
        }

        map.setOnMapClickListener { latLng -> placePin(latLng) }

        val lat = initLat
        val lng = initLng
        if (lat != null && lng != null) {
            val initLatLng = LatLng(lat, lng)
            placePin(initLatLng)
            map.moveCamera(CameraUpdateFactory.newLatLngZoom(initLatLng, DEFAULT_ZOOM))
        } else {
            moveToCurrentLocation(map)
        }
    }

    @Suppress("MissingPermission")
    private fun moveToCurrentLocation(map: GoogleMap) {
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                map.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(
                        LatLng(location.latitude, location.longitude),
                        DEFAULT_ZOOM
                    )
                )
            }
        }
    }

    private fun placePin(latLng: LatLng) {
        pinnedLatLng = latLng
        val map = googleMap ?: return

        currentMarker?.remove()
        currentMarker = map.addMarker(MarkerOptions().position(latLng))

        redrawCircle(latLng, sliderRadius.value.toDouble())

        tvHint.visibility = View.GONE
        cardBottom.visibility = View.VISIBLE

        reverseGeocode(latLng)
    }

    private fun redrawCircle(latLng: LatLng, radiusMeters: Double) {
        currentCircle?.remove()
        currentCircle = googleMap?.addCircle(
            CircleOptions()
                .center(latLng)
                .radius(radiusMeters)
                .strokeWidth(3f)
                .strokeColor(0xFF1976D2.toInt())
                .fillColor(0x221976D2)
        )
    }

    private fun reverseGeocode(latLng: LatLng) {
        CoroutineScope(Dispatchers.IO).launch {
            val name = try {
                val geocoder = Geocoder(this@MapPickerActivity, Locale.getDefault())
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(latLng.latitude, latLng.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val addr = addresses[0]
                    addr.featureName
                        ?: addr.thoroughfare
                        ?: addr.subLocality
                        ?: addr.locality
                        ?: "Picked Location"
                } else {
                    "Picked Location"
                }
            } catch (e: Exception) {
                "Picked Location"
            }
            withContext(Dispatchers.Main) {
                if (etLocationName.text.isNullOrEmpty()) {
                    etLocationName.setText(name)
                }
            }
        }
    }

    private fun returnResult() {
        val latLng = pinnedLatLng
        if (latLng == null) {
            Toast.makeText(this, "Please tap the map to select a location", Toast.LENGTH_SHORT).show()
            return
        }
        val name = etLocationName.text?.toString()?.trim().takeIf { !it.isNullOrEmpty() } ?: "Picked Location"
        val result = Intent().apply {
            putExtra(EXTRA_LAT, latLng.latitude)
            putExtra(EXTRA_LNG, latLng.longitude)
            putExtra(EXTRA_RADIUS, sliderRadius.value.toInt())
            putExtra(EXTRA_NAME, name)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun updateRadiusLabel(meters: Int) {
        tvRadiusLabel.text = "Radius: $meters m"
    }
}