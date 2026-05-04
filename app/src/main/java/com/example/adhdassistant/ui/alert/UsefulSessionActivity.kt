package com.example.adhdassistant.ui.alert

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.adhdassistant.R
import com.example.adhdassistant.tracking.UsageTrackingService
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * Shown when the user taps "Actually doing something useful".
 * Lets them tag what they're doing (for stats) and optionally extend the snooze.
 * AlertActivity already sent a 15-minute snooze before launching this.
 */
class UsefulSessionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_useful_session)

        val chipGroup = findViewById<ChipGroup>(R.id.chipGroupUseType)
        val btnSpace  = findViewById<MaterialButton>(R.id.btnGiveSpace)
        val btnNever  = findViewById<MaterialButton>(R.id.btnNevermind)

        val options = listOf(
            getString(R.string.useful_opt_navigation),
            getString(R.string.useful_opt_music),
            getString(R.string.useful_opt_work),
            getString(R.string.useful_opt_reading),
            getString(R.string.useful_opt_messaging),
            getString(R.string.useful_opt_photos),
        )

        options.forEach { label ->
            val chip = Chip(this).apply {
                text        = label
                isCheckable = true
            }
            chipGroup.addView(chip)
        }

        // User wants more time — extend snooze to 30 min
        btnSpace.setOnClickListener {
            startService(UsageTrackingService.snoozeIntent(this, 30))
            finish()
        }

        // Changed their mind — just close
        btnNever.setOnClickListener { finish() }
    }
}
