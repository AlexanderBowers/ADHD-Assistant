package com.example.adhdassistant.ui.onboarding
import com.example.adhdassistant.utils.PermissionManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.config.RoutinePresets
import com.example.adhdassistant.databinding.ActivityOnboardingBinding
import com.example.adhdassistant.ui.location.MapPickerActivity
import com.example.adhdassistant.ui.location.PickedLocation
import com.example.adhdassistant.ui.main.MainActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private var selectedPresetId: Long? = null
    private var selectedLocationName: String? = null
    private var selectedLocationLat: Double? = null
    private var selectedLocationLng: Double? = null
    private var selectedLocationRadius: Int? = null

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
                        locationName   = selectedLocationName,
                        locationLat    = selectedLocationLat,
                        locationLng    = selectedLocationLng,
                        locationRadius = selectedLocationRadius
                    )
            } else null
            configRepository.saveRoutine(
                routine ?: ConfigRepository.buildDefaultRoutine().copy(
                    locationName   = selectedLocationName,
                    locationLat    = selectedLocationLat,
                    locationLng    = selectedLocationLng,
                    locationRadius = selectedLocationRadius
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

        private val mapPickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == android.app.Activity.RESULT_OK) {
                val data = result.data ?: return@registerForActivityResult
                val picked = PickedLocation(
                    name         = data.getStringExtra(MapPickerActivity.EXTRA_NAME) ?: "",
                    lat          = data.getDoubleExtra(MapPickerActivity.EXTRA_LAT, 0.0),
                    lng          = data.getDoubleExtra(MapPickerActivity.EXTRA_LNG, 0.0),
                    radiusMeters = data.getIntExtra(MapPickerActivity.EXTRA_RADIUS, 200)
                )
                onLocationResult(picked)
                view?.let { showSelectedLocation(it, picked.name) }
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val btnSet    = view.findViewById<MaterialButton>(R.id.btnSetLocation)
            val btnChange = view.findViewById<MaterialButton>(R.id.btnChangeLocation)

            btnSet.setOnClickListener    { launchMapPicker() }
            btnChange.setOnClickListener { launchMapPicker() }
        }

        private fun launchMapPicker() {
            mapPickerLauncher.launch(Intent(requireContext(), MapPickerActivity::class.java))
        }

        private fun showSelectedLocation(view: View, name: String) {
            view.findViewById<LinearLayout>(R.id.layoutSelectedLocation).visibility = View.VISIBLE
            view.findViewById<TextView>(R.id.tvSelectedLocationName).text = name
            view.findViewById<MaterialButton>(R.id.btnSetLocation).visibility = View.GONE
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