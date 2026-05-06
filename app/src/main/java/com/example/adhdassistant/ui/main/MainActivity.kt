package com.example.adhdassistant.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.databinding.ActivityMainBinding
import com.example.adhdassistant.tracking.UsageTrackingService
import com.example.adhdassistant.ui.settings.SettingsActivity
import com.example.adhdassistant.utils.PermissionManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var permissionManager: PermissionManager
    private val configRepository get() = (application as ADHDApplication).configRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        permissionManager = PermissionManager(this)

        setupNavButtons()

        // Observe top intention
        lifecycleScope.launch {
            configRepository.intentionListFlow.collectLatest { intentions ->
                val top = intentions.firstOrNull()
                if (top != null) {
                    binding.cardIntention.visibility = View.VISIBLE
                    binding.tvTopIntention.text = top.text
                    binding.tvIntentionMore.text = if (intentions.size > 1) "+ ${intentions.size - 1} more" else ""
                } else {
                    binding.cardIntention.visibility = View.GONE
                }
            }
        }
    }

    private fun setupNavButtons() {
        // Original Buttons
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnIntentions.setOnClickListener {
            startActivity(Intent(this, com.example.adhdassistant.ui.intentions.IntentionListActivity::class.java))
        }
        binding.btnStats.setOnClickListener {
            startActivity(Intent(this, com.example.adhdassistant.ui.stats.StatsActivity::class.java))
        }

        // New Routine/Feature Buttons
        binding.btnRoutines.setOnClickListener {
            startActivity(Intent(this, com.example.adhdassistant.ui.routines.RoutineListActivity::class.java))
        }

        // DEV Test Button
        binding.btnTestInterrupt.setOnClickListener {
            val fakeBundle = com.example.adhdassistant.tracking.AlertTriggerBundle(
                routineId = 0L,
                routineName = "Costco Run (DEV)",
                locationName = "Costco",
                accumulatedTimeMs = 5 * 60 * 1000L,
                thresholdMs = 5 * 60 * 1000L,
                distractingApp = "com.reddit.frontpage",
                requiresMovement = true,
                userWasStill = true,
                allowedAppWasOpen = false
            )
            val intent = Intent(this, com.example.adhdassistant.ui.AlertExplanationActivity::class.java).apply {
                putExtra(com.example.adhdassistant.ui.AlertExplanationActivity.EXTRA_BUNDLE, fakeBundle)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        maybeStartTrackingService()
    }

    private fun maybeStartTrackingService() {
        if (permissionManager.hasUsageStatsPermission()) {
            val serviceIntent = Intent(this, UsageTrackingService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        }
    }
}