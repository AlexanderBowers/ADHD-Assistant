package com.example.adhdassistant.ui.routines

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.databinding.ActivityEditRoutineBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

class EditRoutineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }

    private lateinit var binding: ActivityEditRoutineBinding
    private val configRepository
        get() = (application as ADHDApplication).configRepository

    private var editingRoutineId: Long = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRoutineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingRoutineId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)

        if (editingRoutineId != -1L) {
            lifecycleScope.launch {
                val existing = configRepository.routineListFlow.first()
                    .firstOrNull { it.id == editingRoutineId }
                if (existing != null) {
                    binding.tilRoutineName.editText?.setText(existing.name)
                    binding.tilLocation.editText?.setText(existing.locationName ?: "")
                    binding.sliderThreshold.value = (existing.alertThresholdMinutes ?: 5).toFloat()
                }
            }
        }

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
            id = if (editingRoutineId != -1L) editingRoutineId else Random.nextLong(),
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
                if (editingRoutineId != -1L) "Routine updated" else "Routine saved",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }
}