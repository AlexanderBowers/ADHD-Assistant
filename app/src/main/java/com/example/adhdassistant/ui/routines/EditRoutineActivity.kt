package com.example.adhdassistant.ui.routines

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.databinding.ActivityEditRoutineBinding
import kotlinx.coroutines.launch
import kotlin.random.Random

class EditRoutineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }

    private lateinit var binding: ActivityEditRoutineBinding
    private val configRepository
        get() = (application as ADHDApplication).configRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRoutineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ Save button that actually exists in your XML
        binding.btnSave.setOnClickListener {
            saveRoutine()
        }
    }

    private fun saveRoutine() {
        val name = binding.tilRoutineName.editText
            ?.text
            ?.toString()
            ?.trim()

        if (name.isNullOrEmpty()) {
            Toast.makeText(this, "Routine name is required", Toast.LENGTH_SHORT).show()
            return
        }

        val thresholdMinutes = binding.sliderThreshold.value.toInt()

        val routine = Routine(
            id = Random.nextLong(),
            name = name,
            emoji = "🧠",
            startHour = 8,
            endHour = 22,
            alertThresholdMinutes = thresholdMinutes,
            schedule = null,
            isManuallyActive = false,
            parentId = null,
            locationName = binding.tilLocation.editText
                ?.text
                ?.toString()
                ?.takeIf { it.isNotBlank() }
        )

        lifecycleScope.launch {
            configRepository.saveRoutine(routine)
            Toast.makeText(
                this@EditRoutineActivity,
                "Routine saved",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}