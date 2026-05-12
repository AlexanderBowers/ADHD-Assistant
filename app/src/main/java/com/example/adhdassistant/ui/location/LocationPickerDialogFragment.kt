package com.example.adhdassistant.ui.location

import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.R
import com.example.adhdassistant.utils.PermissionManager
import com.google.android.gms.location.LocationServices
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationPickerDialogFragment(
    private val onLocationPicked: (PickedLocation?) -> Unit
) : BottomSheetDialogFragment() {

    private lateinit var resultsAdapter: LocationResultAdapter
    private var searchJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_location_picker_dialog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val etSearch = view.findViewById<TextInputEditText>(R.id.etSearch)
        val rvResults = view.findViewById<RecyclerView>(R.id.rvResults)
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)

        resultsAdapter = LocationResultAdapter { picked ->
            onLocationPicked(picked)
            dismiss()
        }
        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = resultsAdapter

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim() ?: ""
                searchJob?.cancel()
                if (query.length < 3) {
                    rvResults.visibility = View.GONE
                    tvStatus.visibility = View.GONE
                    return
                }
                searchJob = viewLifecycleOwner.lifecycleScope.launch {
                    tvStatus.text = "Searching…"
                    tvStatus.visibility = View.VISIBLE
                    rvResults.visibility = View.GONE
                    try {
                        val results = withContext(Dispatchers.IO) { geocodeAddress(query) }
                        if (results.isEmpty()) {
                            tvStatus.text = "No results found"
                        } else {
                            tvStatus.visibility = View.GONE
                            rvResults.visibility = View.VISIBLE
                            resultsAdapter.submitList(results)
                        }
                    } catch (e: Exception) {
                        tvStatus.text = "Search unavailable"
                    }
                }
            }
        })

        view.findViewById<MaterialButton>(R.id.btnUseCurrentLocation).setOnClickListener {
            val permMgr = PermissionManager(requireActivity())
            if (!permMgr.hasLocationPermission()) {
                permMgr.requestLocationPermission(requireActivity())
            } else {
                fetchCurrentLocation(view)
            }
        }

        view.findViewById<MaterialButton>(R.id.btnClearLocation).setOnClickListener {
            onLocationPicked(null)
            dismiss()
        }
    }

    private fun fetchCurrentLocation(view: View) {
        val tvStatus = view.findViewById<TextView>(R.id.tvStatus)
        tvStatus.text = "Getting your location…"
        tvStatus.visibility = View.VISIBLE

        val fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedClient.lastLocation.addOnSuccessListener { location ->
            if (location == null) {
                tvStatus.text = "Could not get location. Try searching instead."
                return@addOnSuccessListener
            }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val geocoder = Geocoder(requireContext(), Locale.getDefault())
                    val addresses = withContext(Dispatchers.IO) {
                        if (!Geocoder.isPresent()) return@withContext emptyList()
                        @Suppress("DEPRECATION")
                        geocoder.getFromLocation(location.latitude, location.longitude, 1) ?: emptyList()
                    }
                    val addr = addresses.firstOrNull()
                    val name = addr?.getAddressLine(0) ?: "${location.latitude}, ${location.longitude}"
                    onLocationPicked(PickedLocation(name, name, location.latitude, location.longitude))
                    dismiss()
                } catch (e: Exception) {
                    tvStatus.text = "Could not determine address."
                }
            }
        }
    }

    private fun geocodeAddress(query: String): List<PickedLocation> {
        if (!Geocoder.isPresent()) return emptyList()
        val geocoder = Geocoder(requireContext(), Locale.getDefault())
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocationName(query, 5) ?: return emptyList()
        return addresses.map { addr ->
            val featureName = addr.featureName?.takeIf { it.isNotBlank() }
            val fullAddress = addr.getAddressLine(0) ?: featureName ?: ""
            val name = if (featureName != null && featureName != fullAddress) featureName else fullAddress
            PickedLocation(name, fullAddress, addr.latitude, addr.longitude)
        }
    }

    companion object {
        const val TAG = "LocationPickerDialogFragment"
    }
}
