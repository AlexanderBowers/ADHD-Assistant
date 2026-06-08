package com.example.adhdassistant.ui.alert

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.IntentionItem
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.databinding.ActivityAlertBinding
import com.example.adhdassistant.domain.AdaptiveThresholdManager
import com.example.adhdassistant.tracking.ReAlertTracker
import com.example.adhdassistant.tracking.UsageTrackingService
import com.google.android.material.chip.Chip
import com.google.android.material.shape.CornerFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DURATION_MS    = "duration_ms"
        const val EXTRA_APP_PACKAGE    = "app_package"
        const val EXTRA_CHORE_TEXT     = "chore_text"
        const val EXTRA_ALERT_LEVEL    = "alert_level"
        const val EXTRA_TRIGGER_TYPE   = "trigger_type"

        const val TRIGGER_TYPE_THRESHOLD = "threshold"
        const val TRIGGER_TYPE_ON_OPEN   = "on_open"

        private val IGNORE_OPTIONS = listOf(
            "15 min"  to 15,
            "30 min"  to 30,
            "1 hour"  to 60,
            "4 hours" to 240,
            "1 day"   to 1440
        )
    }

    private lateinit var binding: ActivityAlertBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
    private lateinit var database: AppDatabase

    private var durationMs    = 0L
    private var appPackage    = ""
    private var intentionText: String? = null
    private var alertLevel    = ReAlertTracker.AlertLevel.FIRST
    private var triggerType   = TRIGGER_TYPE_THRESHOLD

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        window.decorView.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(
                ContextCompat.getColor(this, R.color.alert_gradient_top),
                ContextCompat.getColor(this, R.color.alert_gradient_mid),
                ContextCompat.getColor(this, R.color.alert_gradient_bot)
            )
        )

        binding = ActivityAlertBinding.inflate(layoutInflater)
        setContentView(binding.root)

        database = AppDatabase.getDatabase(applicationContext)

        onBackPressedDispatcher.addCallback(this) {
            // If a panel is open, go back to buttons; otherwise no-op
            if (binding.layoutIntentions.visibility == View.VISIBLE ||
                binding.layoutIgnore.visibility == View.VISIBLE) {
                showButtons()
            }
        }

        durationMs    = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        appPackage    = intent.getStringExtra(EXTRA_APP_PACKAGE) ?: ""
        intentionText = intent.getStringExtra(EXTRA_CHORE_TEXT)
        alertLevel    = intent.getStringExtra(EXTRA_ALERT_LEVEL)
            ?.let { runCatching { ReAlertTracker.AlertLevel.valueOf(it) }.getOrNull() }
            ?: ReAlertTracker.AlertLevel.FIRST
        triggerType   = intent.getStringExtra(EXTRA_TRIGGER_TYPE) ?: TRIGGER_TYPE_THRESHOLD

        applyWindowInsets()
        setupGreeting()
        setupIntentionCard()
        setupButtons()
        setupIgnorePanel()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val inner = binding.layoutAlert.getChildAt(0)
            inner?.setPadding(
                inner.paddingLeft,
                inner.paddingTop,
                inner.paddingRight,
                bars.bottom + resources.getDimensionPixelOffset(R.dimen.alert_bottom_extra)
            )
            insets
        }
    }

    private fun setupGreeting() {
        data class Greeting(val emoji: String, val title: String, val message: String)

        val g = if (triggerType == TRIGGER_TYPE_ON_OPEN) {
            val appLabel = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(appPackage, 0)
                ).toString()
            }.getOrElse { appPackage }
            Greeting(
                "🎯",
                getString(R.string.alert_greeting_on_open),
                getString(R.string.alert_message_on_open, appLabel)
            )
        } else {
            val minutes = (durationMs / 60_000).coerceAtLeast(1)
            when (alertLevel) {
                ReAlertTracker.AlertLevel.FIRST -> Greeting(
                    "👋",
                    getString(R.string.alert_greeting_first),
                    getString(R.string.alert_message_first, minutes.toString())
                )
                ReAlertTracker.AlertLevel.REPEAT -> Greeting(
                    "😊",
                    getString(R.string.alert_greeting_repeat),
                    getString(R.string.alert_message_repeat, minutes.toString())
                )
                ReAlertTracker.AlertLevel.MANY -> Greeting(
                    "😄",
                    getString(R.string.alert_greeting_many),
                    getString(R.string.alert_message_many)
                )
            }
        }

        binding.tvEmoji.text  = g.emoji
        binding.tvTitle.text  = g.title
        binding.tvMessage.text = android.text.Html.fromHtml(
            g.message, android.text.Html.FROM_HTML_MODE_COMPACT
        )
    }

    private fun setupIntentionCard() {
        if (!intentionText.isNullOrEmpty()) {
            binding.cardIntention.visibility = View.VISIBLE
            binding.tvIntentionText.text     = intentionText
        }
    }

    private fun setupButtons() {
        binding.btnThankYou.setOnClickListener {
            logAction("IGNORED")
            finish()
        }
        binding.btnRelevant.setOnClickListener {
            logAction("USEFUL")
            sendSnoozeToService(15)
            finish()
        }
        binding.btnIntentionDone.setOnClickListener {
            showPanel(binding.layoutIntentions)
            loadIntentionsPanel()
        }
        binding.btnIgnore.setOnClickListener {
            showPanel(binding.layoutIgnore)
        }

        binding.btnIntentionsBack.setOnClickListener { showButtons() }
        binding.btnIgnoreBack.setOnClickListener { showButtons() }
    }

    private fun setupIgnorePanel() {
        IGNORE_OPTIONS.forEach { (label, minutes) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                shapeAppearanceModel = shapeAppearanceModel.toBuilder()
                    .setAllCorners(CornerFamily.ROUNDED, resources.getDimension(R.dimen.chip_corner_radius))
                    .build()
                chipStrokeWidth = resources.getDimension(R.dimen.chip_stroke_width)
                setTextAppearanceResource(R.style.TextAppearance_ADHDAssistant_Chip)
                setChipBackgroundColorResource(R.color.chip_bg_selector)
                setChipStrokeColorResource(R.color.chip_stroke_selector)
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_selector))
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    val until = System.currentTimeMillis() + (minutes * 60_000L)
                    lifecycleScope.launch {
                        configRepository.setAppSnoozedUntil(appPackage, until)
                        logAction("IGNORED")
                        finish()
                    }
                }
            }
            binding.chipGroupIgnore.addView(chip)
        }
    }

    private fun loadIntentionsPanel() {
        binding.containerIntentionsList.removeAllViews()
        lifecycleScope.launch {
            val intentions = configRepository.intentionListFlow.first()
            if (intentions.isEmpty()) {
                binding.tvNoIntentions.visibility = View.VISIBLE
            } else {
                binding.tvNoIntentions.visibility = View.GONE
                intentions.forEach { item -> addIntentionRow(item) }
            }
        }
    }

    private fun addIntentionRow(item: IntentionItem) {
        val row = layoutInflater.inflate(
            R.layout.item_intention_check,
            binding.containerIntentionsList,
            false
        )
        row.findViewById<TextView>(R.id.tvIntentionRowText).text = item.text
        row.setOnClickListener {
            lifecycleScope.launch {
                val current = configRepository.intentionListFlow.first().toMutableList()
                current.removeAll { it.id == item.id }
                configRepository.updateIntentionList(current)
                logAction("COMPLETED")
                finish()
            }
        }
        binding.containerIntentionsList.addView(row)
    }

    private fun showPanel(panel: View) {
        binding.layoutButtons.animate().alpha(0f).setDuration(150).withEndAction {
            binding.layoutButtons.visibility = View.GONE
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(200).start()
        }.start()
    }

    private fun showButtons() {
        listOf(binding.layoutIntentions, binding.layoutIgnore).forEach { panel ->
            if (panel.visibility == View.VISIBLE) {
                panel.animate().alpha(0f).setDuration(150).withEndAction {
                    panel.visibility = View.GONE
                }.start()
            }
        }
        binding.layoutButtons.alpha = 0f
        binding.layoutButtons.visibility = View.VISIBLE
        binding.layoutButtons.animate().alpha(1f).setDuration(200).start()
    }

    private fun logAction(action: String) {
        lifecycleScope.launch {
            val routine = configRepository.getActiveRoutine()
                ?: com.example.adhdassistant.config.ConfigRepository.buildDefaultRoutine()
            database.activityEventDao().resolveLastEvent(routine.id, action)
            AdaptiveThresholdManager(configRepository).recordAction(
                action = action,
                routineId = routine.id,
                routineName = routine.name,
                currentThreshold = routine.alertThresholdMinutes ?: 5
            )
        }
    }

    private fun sendSnoozeToService(minutes: Int) {
        startService(UsageTrackingService.snoozeIntent(this, minutes))
    }
}
