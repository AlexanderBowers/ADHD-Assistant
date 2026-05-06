package com.example.adhdassistant.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
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
import com.example.adhdassistant.config.ProfilePresets
import com.example.adhdassistant.databinding.ActivityOnboardingBinding
import com.example.adhdassistant.ui.main.MainActivity
import com.example.adhdassistant.utils.PermissionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/**
 * First-run onboarding. Four pages:
 *
 *  1. Welcome     -- what the app is, zero pressure
 *  2. Usage Access -- the one required permission, explained warmly
 *  3. Location    -- optional, explains the home-detection benefit clearly
 *  4. First routine -- pick a preset or skip, creates a default profile
 *
 * Each page has its own Fragment so the layout stays clean.
 * The "Next" / "Get started" button is on the Activity, not the Fragments,
 * so its label can update as the user moves through pages.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var permissionManager: PermissionManager

    // Use the application-level singleton to avoid multiple DataStore instances
    private val configRepository get() = (application as ADHDApplication).configRepository

    private val pageCount = 4

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionManager = PermissionManager(this)

        // Skip onboarding if already complete
        lifecycleScope.launch {
            if (configRepository.isOnboardingComplete()) {
                goToMain()
                return@launch
            }
        }

        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        setupViewPager()
        setupButtons()
        setupIndicators()
    }

    private fun applyWindowInsets() {
        // The bottom button area needs extra padding equal to the nav bar height
        // so buttons are never hidden behind the system navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.layoutBottom.setPadding(
                binding.layoutBottom.paddingLeft,
                binding.layoutBottom.paddingTop,
                binding.layoutBottom.paddingRight,
                bars.bottom + resources.getDimensionPixelSize(R.dimen.onboarding_bottom_padding)
            )
            insets
        }
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = OnboardingAdapter(this)
        binding.viewPager.isUserInputEnabled = false // Controlled by our buttons only

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateButtonLabel(position)
                updateIndicators(position)
            }
        })
    }

    private fun setupButtons() {
        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            when (current) {
                0 -> advanceTo(1)
                1 -> handleUsageAccessPage()
                2 -> advanceTo(3) // Location page -- either granted or skipped, move on
                3 -> finishOnboarding()
            }
        }

        binding.btnSkip.setOnClickListener {
            val current = binding.viewPager.currentItem
            when (current) {
                2 -> advanceTo(3) // Skip location
                else -> advanceTo(current + 1)
            }
        }
    }

    private fun advanceTo(page: Int) {
        binding.viewPager.currentItem = page
    }

    private fun updateButtonLabel(page: Int) {
        binding.btnNext.text = when (page) {
            0    -> getString(R.string.onboarding_btn_next)
            1    -> getString(R.string.onboarding_btn_grant_usage)
            2    -> getString(R.string.onboarding_btn_next)
            else -> getString(R.string.onboarding_btn_finish)
        }
        // Show skip only on the optional location page
        binding.btnSkip.visibility = if (page == 2) View.VISIBLE else View.GONE
    }

    private fun handleUsageAccessPage() {
        if (permissionManager.hasUsageStatsPermission()) {
            // Already granted -- move on
            advanceTo(2)
        } else {
            // Open system Usage Access settings
            permissionManager.requestUsageStatsPermission(this)
            // We'll check again in onResume when user comes back
        }
    }

    override fun onResume() {
        super.onResume()
        // If user came back from Usage Access settings, check and auto-advance
        if (::binding.isInitialized) {
            val current = binding.viewPager.currentItem
            if (current == 1 && permissionManager.hasUsageStatsPermission()) {
                advanceTo(2)
            }
        }
    }

    private fun finishOnboarding() {
        lifecycleScope.launch {
            // Save the selected preset (page 3 Fragment sets this)
            val selectedPreset = selectedPresetId
            if (selectedPreset != null) {
                val preset = ProfilePresets.all.firstOrNull { it.id == selectedPreset }
                if (preset != null) configRepository.saveProfile(preset)
            } else {
                // No preset chosen -- save the default profile
                configRepository.saveProfile(ConfigRepository.buildDefaultProfile())
            }

            configRepository.setOnboardingComplete()
            goToMain()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun setupIndicators() {
        repeat(pageCount) { i ->
            val dot = View(this).apply {
                val size = resources.getDimensionPixelSize(R.dimen.indicator_dot_size)
                val margin = resources.getDimensionPixelSize(R.dimen.indicator_dot_margin)
                layoutParams = ViewGroup.MarginLayoutParams(size, size).apply {
                    leftMargin = margin; rightMargin = margin
                }
                setBackgroundResource(R.drawable.indicator_dot_inactive)
            }
            binding.layoutIndicators.addView(dot)
        }
        updateIndicators(0)
    }

    private fun updateIndicators(active: Int) {
        for (i in 0 until binding.layoutIndicators.childCount) {
            val dot = binding.layoutIndicators.getChildAt(i)
            dot.setBackgroundResource(
                if (i == active) R.drawable.indicator_dot_active
                else R.drawable.indicator_dot_inactive
            )
        }
    }

    private inner class OnboardingAdapter(activity: AppCompatActivity) :
        FragmentStateAdapter(activity) {
        override fun getItemCount() = pageCount
        override fun createFragment(position: Int): Fragment = when (position) {
            0    -> WelcomeFragment()
            1    -> UsageAccessFragment()
            2    -> LocationFragment()
            else -> PickRoutineFragment { id -> selectedPresetId = id }
        }
    }

    // Shared state: which preset the user picked on page 4
    var selectedPresetId: Long? = null

    class WelcomeFragment : Fragment(R.layout.fragment_onboarding_welcome)

    class UsageAccessFragment : Fragment(R.layout.fragment_onboarding_usage)

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

            ProfilePresets.rootProfiles.forEach { preset ->
                val chip = Chip(requireContext()).apply {
                    text = "${preset.emoji} ${preset.name}"
                    isCheckable = true
                    chipCornerRadius = 999f
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
