package com.example.adhdassistant.ui.profiles

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.adhdassistant.databinding.ActivityEditProfileBinding

/**
 * Create/edit a single routine (Profile).
 *
 * Full implementation to be built out. This stub is enough to:
 *   - Satisfy the EXTRA_PROFILE_ID reference in ProfileListActivity
 *   - Accept the incoming profile ID when launched from the list
 *   - Compile and launch without crashing
 *
 * When profileId == -1L the screen is in "create" mode.
 * When profileId is any other value it is in "edit" mode.
 */
class EditProfileActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val NEW_PROFILE = -1L
    }

    private lateinit var binding: ActivityEditProfileBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, NEW_PROFILE)
        val isNewProfile = profileId == NEW_PROFILE

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (isNewProfile) "New Routine" else "Edit Routine"

        // Full form implementation goes here.
        // Fields needed:
        //   - Name (EditText)
        //   - Emoji picker (horizontal scroll of options)
        //   - Parent profile selector (Spinner / BottomSheet) — Pro
        //   - Schedule type (Manual / DaysOfWeek / TimedDays) (ChipGroup)
        //   - Day selector (7 toggleable chips) — shown when not Manual
        //   - Activate-at hour (TimePicker) — shown for TimedDays only
        //   - Active hours range (two TimePickerDialogs or a RangeSlider)
        //   - Alert threshold (Slider, 1–30 min)
        //   - Excluded apps override (add/remove chips) — Pro
        //   - Save button
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}