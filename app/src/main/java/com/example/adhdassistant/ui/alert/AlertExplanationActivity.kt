package com.example.adhdassistant.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.adhdassistant.R
import com.example.adhdassistant.tracking.AlertTriggerBundle

class AlertExplanationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUNDLE = "alert_trigger_bundle"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ Show over lockscreen ONLY while visible (Play‑compliant)
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

        // ✅ Modern, type‑safe Parcelable extraction
        val bundle: AlertTriggerBundle? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_BUNDLE, AlertTriggerBundle::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_BUNDLE)
            }

        if (bundle == null) {
            finish()
            return
        }

        setContentView(R.layout.activity_alert_explanation)

        val titleText: TextView = findViewById(R.id.alertTitle)
        val messageText: TextView = findViewById(R.id.alertMessage)
        val tipText: TextView = findViewById(R.id.alertTip)
        val dismissButton: Button = findViewById(R.id.dismissButton)
        val snoozeButton: Button = findViewById(R.id.snoozeButton)

        titleText.text = "Pause & Check In"

        messageText.text =
            "You've been in ${bundle.distractingApp} for a while.\n\n" +
                    "This isn't a failure — just a moment to choose what comes next."

        if (bundle.userWasStill && bundle.requiresMovement) {
            tipText.visibility = TextView.VISIBLE
            tipText.text = "Tip: a quick stretch or walk can help reset focus."
        } else {
            tipText.visibility = TextView.GONE
        }

        dismissButton.setOnClickListener {
            finish()
        }

        snoozeButton.setOnClickListener {
            // Optional: send snooze intent to UsageTrackingService
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Prevent lingering presence if user leaves immediately
        finish()
    }
}
