package com.example.adhdassistant.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.billing.BillingManager
import com.example.adhdassistant.databinding.ActivitySettingsBinding
import com.example.adhdassistant.tracking.UsageTrackingService
import com.example.adhdassistant.ui.excluded.ExcludedAppsActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
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

        billingManager = BillingManager(applicationContext, lifecycleScope, configRepository)

        applyWindowInsets()
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
            val isPro = configRepository.isProVersion()
            val runInBg = configRepository.isRunInBackground()

            binding.switchBackground.isChecked = runInBg
            binding.switchBackground.isEnabled = isPro
            binding.btnManageExclusions.isEnabled = isPro
            updateProButton(isPro)
        }

        binding.switchBackground.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestNotificationPermissionIfNeeded() else disableBackgroundService()
        }

        binding.btnManageExclusions.setOnClickListener {
            startActivity(Intent(this, ExcludedAppsActivity::class.java))
        }

        binding.btnGrantUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        binding.btnUpgradePro.setOnClickListener {
            lifecycleScope.launch { billingManager.launchProPurchase(this@SettingsActivity) }
        }

        binding.btnRestorePurchase.setOnClickListener {
            lifecycleScope.launch {
                billingManager.restorePurchases()
                Toast.makeText(this@SettingsActivity, "Checking for previous purchase…", Toast.LENGTH_SHORT).show()
            }
        }

        binding.tvPrivacyPolicy.setOnClickListener { showPrivacyPolicyDialog() }
    }

    private fun observeProStatus() {
        lifecycleScope.launch {
            billingManager.proStatus.collectLatest { isPro ->
                updateProButton(isPro)
                binding.switchBackground.isEnabled = isPro
                binding.btnManageExclusions.isEnabled = isPro
                if (!isPro) {
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
                    is BillingManager.BillingState.PurchaseSuccess ->
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_success), Toast.LENGTH_LONG).show()
                    is BillingManager.BillingState.PurchaseError ->
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_failed), Toast.LENGTH_SHORT).show()
                    is BillingManager.BillingState.AlreadyOwned ->
                        Toast.makeText(this@SettingsActivity, getString(R.string.pro_restored), Toast.LENGTH_SHORT).show()
                    is BillingManager.BillingState.PurchaseCancelled -> Unit
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
            binding.btnUpgradePro.text = if (price != null) "Upgrade to Pro — $price"
                                         else getString(R.string.settings_upgrade_default)
            binding.btnUpgradePro.isEnabled = true
            binding.btnRestorePurchase.isEnabled = true
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> enableBackgroundService()
                else -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            enableBackgroundService()
        }
    }

    private fun enableBackgroundService() {
        lifecycleScope.launch { configRepository.setRunInBackground(true) }
        startForegroundService(Intent(this, UsageTrackingService::class.java))
        Toast.makeText(this, "Background tracking enabled", Toast.LENGTH_SHORT).show()
    }

    private fun disableBackgroundService() {
        lifecycleScope.launch { configRepository.setRunInBackground(false) }
        stopService(Intent(this, UsageTrackingService::class.java))
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.scrollViewSettings.setPadding(0, 0, 0, bars.bottom)
            insets
        }
    }

    private fun showPrivacyPolicyDialog() {
        val text = """
            ADHD Assistant Privacy Policy

            Your data never leaves your device.

            - We collect nothing. There is no server, no analytics, no tracking.
            - App usage data (which apps you open) is read locally by this app only and never transmitted.
            - Your intentions and settings are stored only in your phone's local storage.
            - We do not use ads. We do not sell data. We never will.
            - Location data (if you set up home detection) is only used to detect when you're home. It is never stored persistently or sent anywhere.

            If you have questions, email the developer directly.

            Last updated: 2024
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_privacy))
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
