package com.example.adhdassistant.ui.onboarding

import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.config.RoutinePresets
import com.example.adhdassistant.databinding.ActivityOnboardingBinding
import com.example.adhdassistant.ui.location.LocationResultAdapter
import com.example.adhdassistant.ui.location.PickedLocation
import com.example.adhdassistant.ui.main.MainActivity
import com.example.adhdassistant.utils.PermissionManager
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private var selectedPresetId: Long? = null
    private var selectedLocationName: String? = null
    private var selectedLocationLat: Double? = null
    private var selectedLocationLng: Double? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (configRepository.isOnboardingComplete()) {
            goToMain()
            return
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        setupViewPager()

        binding.btnNext.setOnClickListener {
            val currentItem = binding.viewPager.currentItem
            if (currentItem < 3) {
                binding.viewPager.currentItem = currentItem + 1
            } else {
                finishOnboarding()
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 4
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> Fragment(R.layout.fragment_onboarding_welcome)
                1 -> UsageAccessFragment()
                2 -> LocationFragment { picked ->
                    selectedLocationName = picked?.name
                    selectedLocationLat  = picked?.lat
                    selectedLocationLng  = picked?.lng
                }
                3 -> PickRoutineFragment { presetId ->
                    selectedPresetId = presetId
                }
                else -> throw IllegalArgumentException()
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.btnNext.text = if (position == 3) "Get Started" else "Next"
            }
        })
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            val selectedPreset = selectedPresetId
            val routine = if (selectedPreset != null) {
                RoutinePresets.all.firstOrNull { it.id == selectedPreset }
                    ?.copy(
                        locationName = selectedLocationName,
                        locationLat  = selectedLocationLat,
                        locationLng  = selectedLocationLng
                    )
            } else null
            configRepository.saveRoutine(
                routine ?: ConfigRepository.buildDefaultRoutine().copy(
                    locationName = selectedLocationName,
                    locationLat  = selectedLocationLat,
                    locationLng  = selectedLocationLng
                )
            )
            configRepository.setOnboardingComplete(true)
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    // ─── Fragments ─────────────────────────────────────────────────────────────

    class UsageAccessFragment : Fragment(R.layout.fragment_onboarding_usage) {
        private lateinit var permissionManager: PermissionManager

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            permissionManager = PermissionManager(requireActivity())

            view.findViewById<MaterialButton>(R.id.btnGrantUsage)?.setOnClickListener {
                permissionManager.requestUsageStatsPermission(requireActivity())
            }
        }
    }

    class LocationFragment(
        private val onLocationResult: (PickedLocation?) -> Unit
    ) : Fragment(R.layout.fragment_onboarding_location) {

        private lateinit var resultsAdapter: LocationResultAdapter
        private var searchJob: Job? = null

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val etSearch    = view.findViewById<TextInputEditText>(R.id.etOnboardingLocationSearch)
            val rvResults   = view.findViewById<RecyclerView>(R.id.rvOnboardingLocationResults)
            val tvStatus    = view.findViewById<TextView>(R.id.tvOnboardingLocationStatus)
            val layoutSel   = view.findViewById<LinearLayout>(R.id.layoutSelectedLocation)
            val tvSelName   = view.findViewById<TextView>(R.id.tvSelectedLocationName)
            val btnClear    = view.findViewById<MaterialButton>(R.id.btnClearOnboardingLocation)
            val btnCurrent  = view.findViewById<MaterialButton>(R.id.btnEnableLocation)

            resultsAdapter = LocationResultAdapter { picked ->
                onLocationResult(picked)
                tvSelName.text = picked.name
                layoutSel.visibility = View.VISIBLE
                rvResults.visibility = View.GONE
                tvStatus.visibility  = View.GONE
                etSearch.setText("")
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
                        tvStatus.visibility  = View.GONE
                        return
                    }
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        tvStatus.text = "Searching…"
                        tvStatus.visibility  = View.VISIBLE
                        rvResults.visibility = View.GONE
                        try {
                            val results = withContext(Dispatchers.IO) { geocodeAddress(query) }
                            if (results.isEmpty()) {
                                tvStatus.text = "No results found"
                            } else {
                                tvStatus.visibility  = View.GONE
                                rvResults.visibility = View.VISIBLE
                                resultsAdapter.submitList(results)
                            }
                        } catch (e: Exception) {
                            tvStatus.text = "Search unavailable"
                        }
                    }
                }
            })

            btnCurrent.setOnClickListener {
                val permMgr = PermissionManager(requireActivity())
                if (!permMgr.hasLocationPermission()) {
                    permMgr.requestLocationPermission(requireActivity())
                } else {
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
                                val picked = PickedLocation(name, name, location.latitude, location.longitude)
                                onLocationResult(picked)
                                tvSelName.text = name
                                layoutSel.visibility = View.VISIBLE
                                tvStatus.visibility  = View.GONE
                            } catch (e: Exception) {
                                tvStatus.text = "Could not determine address."
                            }
                        }
                    }
                }
            }

            btnClear.setOnClickListener {
                onLocationResult(null)
                layoutSel.visibility = View.GONE
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
    }

    class PickRoutineFragment(
        private val onPresetSelected: (Long?) -> Unit
    ) : Fragment(R.layout.fragment_onboarding_routine) {

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val chipGroup = view.findViewById<ChipGroup>(R.id.chipGroupPresets) ?: return

            RoutinePresets.rootRoutines.forEach { preset ->
                val chip = Chip(requireContext()).apply {
                    text = "${preset.emoji} ${preset.name}"
                    isCheckable = true
                    shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                        .setAllCornerSizes(999f)
                        .build()
                }
                chip.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) onPresetSelected(preset.id)
                }
                chipGroup.addView(chip)
            }

            view.findViewById<TextView>(R.id.tvSkipRoutine)?.setOnClickListener {
                onPresetSelected(null)
            }
        }
    }
}