package com.example.adhdassistant.ui.alert

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.databinding.ActivityAlertBinding
import com.example.adhdassistant.domain.AdaptiveThresholdManager
import com.example.adhdassistant.tracking.ReAlertTracker
import com.example.adhdassistant.tracking.UsageTrackingService
import com.google.android.material.chip.Chip
import com.google.android.material.shape.CornerFamily
import kotlinx.coroutines.launch

/**
 * The check-in alert screen.
 *
 * Three language levels based on consecutive "keep going" responses:
 *   FIRST  -- "Quick check-in! \uD83D\uDC4B"
 *   REPEAT -- "Hey again! \uD83D\uDE0A"
 *   MANY   -- "We keep meeting like this \uD83D\uDE04"
 *
 * Flow:
 *   -> "Wrapping up"        -> grounding moment -> close
 *   -> "Doing something useful" -> UsefulSessionActivity + snooze
 *   -> "Keep going"         -> log + close
 *
 * Back button disabled -- user must make an active choice.
 * The moment of choosing is the intervention.
 */
class AlertActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DURATION_MS    = "duration_ms"
        const val EXTRA_APP_PACKAGE    = "app_package"
        const val EXTRA_CHORE_TEXT     = "chore_text"
        const val EXTRA_ALERT_LEVEL    = "alert_level"
        const val EXTRA_TRIGGER_TYPE   = "trigger_type"

        /** Alert fired by the time-based continuous-usage threshold. */
        const val TRIGGER_TYPE_THRESHOLD = "threshold"
        /** Alert fired immediately when a watched app opens. */
        const val TRIGGER_TYPE_ON_OPEN   = "on_open"
    }

    private lateinit var binding: ActivityAlertBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
    private lateinit var database: AppDatabase

    private var durationMs    = 0L
    private var appPackage    = ""
    private var intentionText: String? = null
    private var alertLevel    = ReAlertTracker.AlertLevel.FIRST
    private var triggerType   = TRIGGER_TYPE_THRESHOLD
    private var selectedChip: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show on lock screen -- shoulder tap, not alarm
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

        // Warm sunrise gradient -- not dark, not alarming
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
            // No back -- user must choose
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
        setupGroundingChips()
        setupButtons()
    }

    private fun applyWindowInsets() {
        // The alert screen has two ScrollViews (alert content + grounding).
        // Give each one extra bottom padding matching the system navigation bar,
        // so the action buttons are never hidden behind permanent nav buttons.
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // Extend the existing paddingBottom in each ScrollView's inner LinearLayout
            listOf(binding.layoutAlert, binding.layoutGrounding).forEach { scrollView ->
                val inner = scrollView.getChildAt(0)
                inner?.setPadding(
                    inner.paddingLeft,
                    inner.paddingTop,
                    inner.paddingRight,
                    bars.bottom + resources.getDimensionPixelOffset(R.dimen.alert_bottom_extra)
                )
            }
            insets
        }
    }

    private fun setupGreeting() {
        data class Greeting(val emoji: String, val title: String, val message: String)

        val g = if (triggerType == TRIGGER_TYPE_ON_OPEN) {
            // On-open: prompt for intention before the user gets absorbed
            val appLabel = runCatching {
                packageManager.getApplicationLabel(
                    packageManager.getApplicationInfo(appPackage, 0)
                ).toString()
            }.getOrElse { appPackage }
            Greeting(
                "\uD83C\uDFAF",
                getString(R.string.alert_greeting_on_open),
                getString(R.string.alert_message_on_open, appLabel)
            )
        } else {
            val minutes = (durationMs / 60_000).coerceAtLeast(1)
            when (alertLevel) {
                ReAlertTracker.AlertLevel.FIRST -> Greeting(
                    "\uD83D\uDC4B",
                    getString(R.string.alert_greeting_first),
                    getString(R.string.alert_message_first, minutes.toString())
                )
                ReAlertTracker.AlertLevel.REPEAT -> Greeting(
                    "\uD83D\uDE0A",
                    getString(R.string.alert_greeting_repeat),
                    getString(R.string.alert_message_repeat, minutes.toString())
                )
                ReAlertTracker.AlertLevel.MANY -> Greeting(
                    "\uD83D\uDE04",
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
}
