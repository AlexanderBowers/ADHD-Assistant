package com.example.adhdassistant.ui.routines

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.config.RoutineSchedule
import com.example.adhdassistant.databinding.ActivityEditRoutineBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.random.Random

class EditRoutineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "extra_profile_id"
    }

    private lateinit var binding: ActivityEditRoutineBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private var editingRoutineId: Long = -1L
    private var loadedRoutine: Routine? = null

    private var selectedStartHour: Int = 8
    private var selectedEndHour: Int = 22
    private var selectedActivateHour: Int = 8

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRoutineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingRoutineId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)

        setupSlider()
        setupHourButtons()
        setupScheduleChips()

        if (editingRoutineId != -1L) {
            binding.btnDelete.visibility = View.VISIBLE
            lifecycleScope.launch {
                val existing = configRepository.routineListFlow.first()
                    .firstOrNull { it.id == editingRoutineId }
                if (existing != null) {
                    loadedRoutine = existing
                    populateFrom(existing)
                }
            }
        }

        binding.btnSave.setOnClickListener { saveRoutine() }
        binding.btnDelete.setOnClickListener { deleteRoutine() }
    }

    private fun setupSlider() {
        updateThresholdLabel(binding.sliderThreshold.value.toInt())
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            updateThresholdLabel(value.toInt())
        }
    }

    private fun updateThresholdLabel(minutes: Int) {
        binding.tvThresholdValue.text = "$minutes min"
    }

    private fun setupHourButtons() {
        updateStartHourButton()
        updateEndHourButton()
        updateActivateHourButton()

        binding.btnStartHour.setOnClickListener {
            TimePickerDialog(this, { _, hour, _ ->
                selectedStartHour = hour
                updateStartHourButton()
            }, selectedStartHour, 0, false).show()
        }

        binding.btnEndHour.setOnClickListener {
            TimePickerDialog(this, { _, hour, _ ->
                selectedEndHour = hour
                updateEndHourButton()
            }, selectedEndHour, 0, false).show()
        }

        binding.btnActivateHour.setOnClickListener {
            TimePickerDialog(this, { _, hour, _ ->
                selectedActivateHour = hour
                updateActivateHourButton()
            }, selectedActivateHour, 0, false).show()
        }
    }

    private fun formatHour(hour: Int): String {
        val h = if (hour % 12 == 0) 12 else hour % 12
        val amPm = if (hour < 12) "AM" else "PM"
        return "$h $amPm"
    }

    private fun updateStartHourButton() { binding.btnStartHour.text = formatHour(selectedStartHour) }
    private fun updateEndHourButton() { binding.btnEndHour.text = formatHour(selectedEndHour) }
    private fun updateActivateHourButton() { binding.btnActivateHour.text = formatHour(selectedActivateHour) }

    private fun setupScheduleChips() {
        binding.chipManual.isChecked = true
        binding.chipGroupScheduleType.setOnCheckedStateChangeListener { _, checkedIds ->
            val showDays = R.id.chipDaysOfWeek in checkedIds || R.id.chipTimedDays in checkedIds
            val showActivate = R.id.chipTimedDays in checkedIds
            binding.layoutDaySelector.visibility = if (showDays) View.VISIBLE else View.GONE
            binding.layoutActivateHour.visibility = if (showActivate) View.VISIBLE else View.GONE
        }
    }

    private fun populateFrom(routine: Routine) {
        binding.tilRoutineName.editText?.setText(routine.name)
        binding.tilLocation.editText?.setText(routine.locationName ?: "")

        routine.startHour?.let { selectedStartHour = it }
        routine.endHour?.let { selectedEndHour = it }
        updateStartHourButton()
        updateEndHourButton()

        val threshold = routine.alertThresholdMinutes ?: 5
        binding.sliderThreshold.value = threshold.toFloat()
        updateThresholdLabel(threshold)

        when (val schedule = routine.schedule) {
            is RoutineSchedule.Manual, null -> binding.chipManual.isChecked = true
            is RoutineSchedule.DaysOfWeek -> {
                binding.chipDaysOfWeek.isChecked = true
                applyDayChips(schedule.days)
            }
            is RoutineSchedule.TimedDays -> {
                binding.chipTimedDays.isChecked = true
                applyDayChips(schedule.days)
                selectedActivateHour = schedule.activateHour
                updateActivateHourButton()
            }
        }
    }

    private fun applyDayChips(days: Set<Int>) {
        binding.chipSun.isChecked = 1 in days
        binding.chipMon.isChecked = 2 in days
        binding.chipTue.isChecked = 3 in days
        binding.chipWed.isChecked = 4 in days
        binding.chipThu.isChecked = 5 in days
        binding.chipFri.isChecked = 6 in days
        binding.chipSat.isChecked = 7 in days
    }

    private fun buildSchedule(): RoutineSchedule {
        val days = buildSet<Int> {
            if (binding.chipSun.isChecked) add(1)
            if (binding.chipMon.isChecked) add(2)
            if (binding.chipTue.isChecked) add(3)
            if (binding.chipWed.isChecked) add(4)
            if (binding.chipThu.isChecked) add(5)
            if (binding.chipFri.isChecked) add(6)
            if (binding.chipSat.isChecked) add(7)
        }.ifEmpty { setOf(2, 3, 4, 5, 6) }

        return when (binding.chipGroupScheduleType.checkedChipId) {
            R.id.chipDaysOfWeek -> RoutineSchedule.DaysOfWeek(days)
            R.id.chipTimedDays  -> RoutineSchedule.TimedDays(days, selectedActivateHour)
            else                -> RoutineSchedule.Manual
        }
    }

    private fun saveRoutine() {
        val name = binding.tilRoutineName.editText?.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            Toast.makeText(this, "Routine name is required", Toast.LENGTH_SHORT).show()
            return
        }

        val routine = Routine(
            id = if (editingRoutineId != -1L) editingRoutineId else Random.nextLong(),
            name = name,
            emoji = "🧠",
            startHour = selectedStartHour,
            endHour = selectedEndHour,
            alertThresholdMinutes = binding.sliderThreshold.value.toInt(),
            schedule = buildSchedule(),
            isManuallyActive = false,
            parentId = null,
            locationName = binding.tilLocation.editText?.text?.toString()?.takeIf { it.isNotBlank() }
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

    private fun deleteRoutine() {
        val routine = loadedRoutine ?: return
        lifecycleScope.launch {
            configRepository.deleteRoutine(routine)
            Toast.makeText(this@EditRoutineActivity, "Routine deleted", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
