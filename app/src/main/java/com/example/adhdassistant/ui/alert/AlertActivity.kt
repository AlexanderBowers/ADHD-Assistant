package com.example.adhdassistant.ui.alert

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.databinding.ActivityAlertBinding
import com.example.adhdassistant.domain.AdaptiveThresholdManager
import com.example.adhdassistant.tracking.ReAlertTracker
import com.example.adhdassistant.tracking.UsageTrackingService
import com.google.android.material.chip.Chip
import kotlinx.coroutines.launch

/**
 * The check-in alert screen.
 *
 * Three language levels based on consecutive "keep going" responses:
 *   FIRST  — "Quick check-in! 👋"
 *   REPEAT — "Hey again! 😊"
 *   MANY   — "We keep meeting like this 😄"
 *
 * Flow:
 *   → "Wrapping up"        → grounding moment → close
 *   → "Doing something useful" → UsefulSessionActivity + snooze
 *   → "Keep going"         → log + close
 *
 * Back button disabled — user must make an active choice.
 * The moment of choosing is the intervention.
 */
class AlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DURATION_MS  = "duration_ms"
        const val EXTRA_APP_PACKAGE  = "app_package"
        const val EXTRA_CHORE_TEXT   = "chore_text"
        const val EXTRA_TRIGGER_DESC = "trigger_desc"
        const val EXTRA_ALERT_LEVEL  = "alert_level"
    }

    private lateinit var binding: ActivityAlertBinding
    private lateinit var configRepository: ConfigRepository
    private lateinit var database: AppDatabase

    private var durationMs    = 0L
    private var appPackage    = ""
    private var intentionText: String? = null
    private var alertLevel    = ReAlertTracker.AlertLevel.FIRST
    private var selectedChip: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen — shoulder tap, not alarm
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Warm sunrise gradient — not dark, not alarming
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

        configRepository = ConfigRepository(applicationContext)
        database         = AppDatabase.getDatabase(applicationContext)

        durationMs    = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        appPackage    = intent.getStringExtra(EXTRA_APP_PACKAGE) ?: ""
        intentionText = intent.getStringExtra(EXTRA_CHORE_TEXT)
        alertLevel    = intent.getStringExtra(EXTRA_ALERT_LEVEL)
            ?.let { runCatching { ReAlertTracker.AlertLevel.valueOf(it) }.getOrNull() }
            ?: ReAlertTracker.AlertLevel.FIRST

        setupGreeting()
        setupIntentionCard()
        setupGroundingChips()
        setupButtons()
    }

    // ─── Greeting ────────────────────────────────────────────────────────────

    private fun setupGreeting() {
        val minutes = (durationMs / 60_000).coerceAtLeast(1)

        data class Greeting(val emoji: String, val title: String, val message: String)

        val g = when (alertLevel) {
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
                getString(R.string.alert_message_many, minutes.toString())
            )
        }

        binding.tvEmoji.text  = g.emoji
        binding.tvTitle.text  = g.title
        binding.tvMessage.text = android.text.Html.fromHtml(
            g.message, android.text.Html.FROM_HTML_MODE_COMPACT
        )
    }

    // ─── Intention card ───────────────────────────────────────────────────────

    private fun setupIntentionCard() {
        if (!intentionText.isNullOrEmpty()) {
            binding.cardIntention.visibility = View.VISIBLE
            binding.tvIntentionText.text     = intentionText
        }
    }

    // ─── "What brought you here?" chips ──────────────────────────────────────

    private fun setupGroundingChips() {
        val labels = listOf(
            getString(R.string.alert_chip_browsing),
            getString(R.string.alert_chip_lookup),
            getString(R.string.alert_chip_fun),
            getString(R.string.alert_chip_work),
            getString(R.string.alert_chip_social),
        )

        labels.forEach { label ->
            val chip = Chip(this).apply {
                text           = label
                isCheckable    = true
                chipCornerRadius = resources.getDimension(R.dimen.chip_corner_radius)
                chipStrokeWidth = resources.getDimension(R.dimen.chip_stroke_width)
                setTextAppearanceResource(R.style.TextAppearance_ADHDAssistant_Chip)
                setChipBackgroundColorResource(R.color.chip_bg_selector)
                setChipStrokeColorResource(R.color.chip_stroke_selector)
                setTextColor(ContextCompat.getColorStateList(context, R.color.chip_text_selector))
            }
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedChip = label
                    // Deselect others
                    for (i in 0 until binding.chipGroupGrounding.childCount) {
                        val c = binding.chipGroupGrounding.getChildAt(i) as? Chip
                        if (c != chip) c?.isChecked = false
                    }
                } else if (selectedChip == label) {
                    selectedChip = null
                }
            }
            binding.chipGroupGrounding.addView(chip)
        }
    }

    // ─── Action buttons ───────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnDone.setOnClickListener     { handleDone() }
        binding.btnUseful.setOnClickListener   { handleUseful() }
        binding.btnContinue.setOnClickListener { handleContinue() }
    }

    private fun handleDone() {
        logAction("MOVED")
        showGroundingMoment()
    }

    private fun handleUseful() {
        logAction("USEFUL")
        startActivity(Intent(this, UsefulSessionActivity::class.java))
        sendSnoozeToService(15)
        finish()
    }

    private fun handleContinue() {
        logAction("IGNORED")
        finish()
    }

    // ─── Grounding moment ─────────────────────────────────────────────────────

    private fun showGroundingMoment() {
        // Fade out the alert content
        binding.layoutAlert.animate()
            .alpha(0f).translationY(-32f).setDuration(220)
            .withEndAction {
                binding.layoutAlert.visibility    = View.GONE
                binding.layoutGrounding.visibility = View.VISIBLE
                binding.layoutGrounding.alpha      = 0f
                binding.layoutGrounding.translationY = 48f

                // Slide up with a gentle overshoot
                binding.layoutGrounding.animate()
                    .alpha(1f).translationY(0f).setDuration(380)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                    .start()
            }
            .start()

        // Choose grounding message
        binding.tvGroundingMessage.text = if (!selectedChip.isNullOrEmpty()) {
            getString(R.string.grounding_message_with_chip, selectedChip!!.lowercase())
        } else {
            getString(R.string.grounding_message_generic)
        }

        // Show intention as next-up prompt
        if (!intentionText.isNullOrEmpty()) {
            binding.cardGroundingIntention.visibility = View.VISIBLE
            binding.tvGroundingIntention.text         = intentionText
        }

        binding.btnGroundingDone.setOnClickListener { finish() }
    }

    // ─── Logging ─────────────────────────────────────────────────────────────

    private fun logAction(action: String) {
        lifecycleScope.launch {
            val profileId = configRepository.getActiveProfile()?.id ?: 1L
            database.activityEventDao().resolveLastEvent(profileId, action)

            // Record grounding chip choice alongside the action for stats
            selectedChip?.let { choice ->
                database.activityEventDao().updateLastEventGroundingChoice(profileId, choice)
            }

            // Update adaptive threshold tracking
            AdaptiveThresholdManager(configRepository).recordAction(action, profileId)
        }
    }

    private fun sendSnoozeToService(minutes: Int) {
        startService(UsageTrackingService.snoozeIntent(this, minutes))
    }

    // No back — user must choose
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = Unit
}