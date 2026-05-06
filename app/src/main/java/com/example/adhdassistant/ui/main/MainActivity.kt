package com.example.adhdassistant.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.databinding.ActivityMainBinding
import com.example.adhdassistant.tracking.UsageTrackingService
import com.example.adhdassistant.ui.onboarding.OnboardingActivity
import com.example.adhdassistant.ui.settings.SettingsActivity
import com.example.adhdassistant.utils.PermissionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Home screen. Shown after onboarding is complete.
 *
 * Responsibilities:
 *   - Redirect to OnboardingActivity if onboarding hasn't been done.
 *   - Show tracking status and top intention.
 *   - Start the tracking service if Usage Access is granted.
 *   - Never ask for any permission cold — all permission requests go
 *     through onboarding or the Settings screen.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager

    private val configRepository get() = (application as ADHDApplication).configRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionManager = PermissionManager(this)

        lifecycleScope.launch {
            if (!configRepository.isOnboardingComplete()) {
                startActivity(Intent(this@MainActivity, OnboardingActivity::class.java))
                finish()
                return@launch
            }
            initUI()
        }
    }

    private fun initUI() {
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        applyWindowInsets()
        setupStatus()
        setupIntentionCard()
        setupNavButtons()
        maybeStartTrackingService()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.scrollViewContent.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun setupStatus() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        binding.tvGreeting.text = when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else      -> "Good evening"
        }

        if (permissionManager.hasUsageStatsPermission()) {
            binding.tvStatus.text        = getString(com.example.adhdassistant.R.string.status_tracking)
            binding.statusDot.visibility = View.VISIBLE
        } else {
            binding.tvStatus.text        = getString(com.example.adhdassistant.R.string.status_needs_permission)
            binding.statusDot.visibility = View.GONE
            binding.btnFixPermission.visibility = View.VISIBLE
            binding.btnFixPermission.setOnClickListener {
                permissionManager.requestUsageStatsPermission(this)
            }
        }
    }

    private fun setupIntentionCard() {
        lifecycleScope.launch {
            configRepository.choreListFlow.collectLatest { chores ->
                val top = chores.firstOrNull()
                if (top != null) {
                    binding.cardIntention.visibility = View.VISIBLE
                    binding.tvTopIntention.text      = top.text
                    binding.tvIntentionMore.text     = if (chores.size > 1) "+ ${chores.size - 1} more" else ""
                } else {
                    binding.cardIntention.visibility = View.GONE
                }
            }
        }
    }

    private fun setupNavButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnIntentions.setOnClickListener {
            startActivity(Intent(this, com.example.adhdassistant.ui.chores.ChoreListActivity::class.java))
        }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, com.example.adhdassistant.ui.stats.StatsActivity::class.java))
        }
    }

    private fun maybeStartTrackingService() {
        if (permissionManager.hasUsageStatsPermission()) {
            startForegroundService(Intent(this, UsageTrackingService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) setupStatus()
        if (permissionManager.hasUsageStatsPermission()) maybeStartTrackingService()
    }
}
