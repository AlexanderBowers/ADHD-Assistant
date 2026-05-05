package com.example.adhdassistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.R
import com.example.adhdassistant.billing.BillingManager
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.databinding.ActivitySettingsBinding
import com.example.adhdassistant.tracking.UsageTrackingService
import com.example.adhdassistant.ui.excluded.ExcludedAppsActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var configRepository: ConfigRepository
    private lateinit var billingManager: BillingManager

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            enableBackgroundService()
        } else {
            binding.switchBackground.isChecked = false
            Toast.makeText(this, "Notification permission required for background tracking", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        configRepository = ConfigRepository(applicationContext)
        billingManager = BillingManager(applicationContext, lifecycleScope, configRepository)

        setupUI()
        observeProStatus()
        observeBillingState()
    }

    override fun onDestroy() {
        super.onDestroy()
        billingManager.destroy()
    }

    private fun setupUI() {
        lifecycleScope.launch {
            // Load current values
            val isPro = configRepository.isProVersion()
            val runInBg = configRepository.isRunInBackground()

            binding.switchBackground.isChecked = runInBg
            binding.switchBackground.isEnabled = isPro
            binding.btnManageExclusions.isEnabled = isPro
            updateProButton(isPro)
        }

        // Background toggle
        binding.switchBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestNotificationPermissionIfNeeded()
            } else {
                disableBackgroundService()
            }
        }

        // Excluded apps (Pro only)
        binding.btnManageExclusions.setOnClickListener {
            startActivity(Intent(this, ExcludedAppsActivity::class.java))
        }

        // Usage Access
        binding.btnGrantUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        // Pro upgrade
        binding.btnUpgradePro.setOnClickListener {
            lifecycleScope.launch {
                billingManager.launchProPurchase(this@SettingsActivity)
            }
        }

        // Restore purchase
        binding.btnRestorePurchase.setOnClickListener {
            lifecycleScope.launch {
                billingManager.restorePurchases()
                Toast.makeText(this@SettingsActivity, "Checking for previous purchase…", Toast.LENGTH_SHORT).show()
            }
        }

        // Privacy policy link
        binding.tvPrivacyPolicy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://yoursite.com/privacy")))
        }
    }

    private fun observeProStatus() {
        lifecycleScope.launch {
            billingManager.proStatus.collectLatest { isPro ->
                updateProButton(isPro)
                binding.switchBackground.isEnabled = isPro
                binding.btnManageExclusions.isEnabled = isPro

                if (!isPro) {
                    // If pro revoked (refund), disable background service
                    binding.switchBackground.isChecked = false
                    disableBackgroundService()
                }
            }
        }
    }

    private fun observeBillingState() {
        lifecycleScope.launch {
            billingManager.billingState.collectLatest { state ->
                when (state) {
                    is BillingManager.BillingState.PurchaseSuccess -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_success), Toast.LENGTH_LONG).show()
                    }
                    is BillingManager.BillingState.PurchaseError -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_failed), Toast.LENGTH_SHORT).show()
                    }
                    is BillingManager.BillingState.AlreadyOwned -> {
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_restored), Toast.LENGTH_SHORT).show()
                    }
                    is BillingManager.BillingState.PurchaseCancelled -> {
                        // User cancelled — no message needed
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun updateProButton(isPro: Boolean) {
        if (isPro) {
            binding.btnUpgradePro.text = getString(R.string.settings_pro_active)
            binding.btnUpgradePro.isEnabled = false
            binding.btnRestorePurchase.isEnabled = false
        } else {
            val price = billingManager.getFormattedPrice()
            binding.btnUpgradePro.text = if (price != null)
                "Upgrade to Pro — $price"
            else getString(R.string.settings_upgrade_default)
            binding.btnUpgradePro.isEnabled = true
            binding.btnRestorePurchase.isEnabled = true
        }
    }

    // ─── Notification Permission ──────────────────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> {
                    enableBackgroundService()
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    // Show rationale then request
                    // In production: show a dialog explaining why before calling launch()
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            // Pre-Android 13: no runtime permission needed
            enableBackgroundService()
        }
    }

    private fun enableBackgroundService() {
        lifecycleScope.launch {
            configRepository.setRunInBackground(true)
        }
        val intent = Intent(this, UsageTrackingService::class.java)
        startForegroundService(intent)
        Toast.makeText(this, "Background tracking enabled", Toast.LENGTH_SHORT).show()
    }

    private fun disableBackgroundService() {
        lifecycleScope.launch {
            configRepository.setRunInBackground(false)
        }
        stopService(Intent(this, UsageTrackingService::class.java))
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}