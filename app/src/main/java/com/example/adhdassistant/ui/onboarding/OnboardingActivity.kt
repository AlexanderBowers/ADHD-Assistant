package com.example.adhdassistant.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
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
import com.example.adhdassistant.ui.main.MainActivity
import com.example.adhdassistant.utils.PermissionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private var selectedPresetId: Long? = null

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
            if (currentItem < 2) {
                binding.viewPager.currentItem = currentItem + 1
            } else {
                finishOnboarding()
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 3
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> Fragment(R.layout.fragment_onboarding_welcome)
                1 -> UsageAccessFragment()
                2 -> PickRoutineFragment { presetId ->
                    selectedPresetId = presetId
                }
                else -> throw IllegalArgumentException()
            }
        }

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                binding.btnNext.text = if (position == 2) "Get Started" else "Next"
            }
        })
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            val selectedPreset = selectedPresetId
            if (selectedPreset != null) {
                val preset = RoutinePresets.all.firstOrNull { it.id == selectedPreset }
                if (preset != null) configRepository.saveRoutine(preset)
            } else {
                configRepository.saveRoutine(ConfigRepository.buildDefaultRoutine())
            }

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

    class LocationFragment : Fragment(R.layout.fragment_onboarding_location) {
        private lateinit var permissionManager: PermissionManager

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            permissionManager = PermissionManager(requireActivity())

            view.findViewById<MaterialButton>(R.id.btnEnableLocation)?.setOnClickListener {
                permissionManager.requestLocationPermission(requireActivity())
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