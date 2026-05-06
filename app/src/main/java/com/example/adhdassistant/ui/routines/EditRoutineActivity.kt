package com.example.adhdassistant.ui.routines

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.config.RoutineSchedule
import com.example.adhdassistant.databinding.ActivityEditRoutineBinding
import com.example.adhdassistant.ui.excluded.ExcludedAppsActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EditRoutineActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PROFILE_ID = "routine_id"
        private const val NEW_ROUTINE = -1L

        private val EMOJIS = listOf(
            "🧠", "💼", "🌅", "📅", "🏋️", "😤", "🎉", "🖥️",
            "📚", "🎮", "🍕", "🌙", "☀️", "🏠", "🚗", "🎯",
            "💪", "🧘", "🎵", "📱", "🌿", "⚡", "🔥", "✨"
        )
    }

    private lateinit var binding: ActivityEditRoutineBinding
    private val configRepository get() = (application as ADHDApplication).configRepository

    private var selectedEmoji = "🧠"
    private var startHour = 8
    private var endHour = 22
    private var activateHour = 8
    private var existingRoutine: Routine? = null
    private var parentRoutines = listOf<Routine>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEditRoutineBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val routineId = intent.getLongExtra(EXTRA_PROFILE_ID, NEW_ROUTINE)
        val isNew = routineId == NEW_ROUTINE

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = if (isNew) "New Routine" else "Edit Routine"

        applyWindowInsets()
        setupEmojiPicker()
        setupScheduleChips()
        setupThresholdSlider()
        setupButtons(isNew)

        lifecycleScope.launch {
            val allRoutines = configRepository.routineListFlow.first()
            val isPro = configRepository.isProVersion()

            if (!isNew) {
                existingRoutine = allRoutines.firstOrNull { it.id == routineId }
                existingRoutine?.let { loadRoutine(it) }
            }

            // Parent selector — Pro only, root routines only as parents
            if (isPro) {
                parentRoutines = allRoutines.filter {
                    it.id != routineId && it.parentId == null
                }
                if (parentRoutines.isNotEmpty()) {
                    setupParentSpinner(existingRoutine?.parentId)
                }
            }

            binding.btnEditExcluded.isEnabled = isPro
            if (!isPro) {
                binding.btnEditExcluded.alpha = 0.5f
                binding.tvExcludedAppsDesc.text = "Unlock Pro to manage intentional apps"
            }
        }
    }

    // ── Window insets ─────────────────────────────────────────────────────────

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.scrollView.setPadding(0, 0, 0, bars.bottom + 24)
            insets
        }
    }

    // ── Emoji picker ──────────────────────────────────────────────────────────

    private fun setupEmojiPicker() {
        val pad = resources.getDimensionPixelOffset(R.dimen.dialog_input_margin) / 2
        for (emoji in EMOJIS) {
            val tv = TextView(this).apply {
                text = emoji
                textSize = 28f
                setPadding(pad, 8, pad, 8)
                setOnClickListener { selectEmoji(emoji) }
            }
            binding.layoutEmojiPicker.addView(tv)
        }
        selectEmoji(selectedEmoji)
    }

    private fun selectEmoji(emoji: String) {
        selectedEmoji = emoji
        for (i in 0 until binding.layoutEmojiPicker.childCount) {
            val tv = binding.layoutEmojiPicker.getChildAt(i) as? TextView ?: continue
            tv.alpha = if (tv.text == emoji) 1f else 0.35f
        }
    }

    // ── Schedule chips ────────────────────────────────────────────────────────

    private fun setupScheduleChips() {
        binding.chipGroupScheduleType.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val showDays     = id == R.id.chipDaysOfWeek || id == R.id.chipTimedDays
            val showActivate = id == R.id.chipTimedDays
            binding.layoutDaySelector.visibility   = if (showDays)     View.VISIBLE else View.GONE
            binding.layoutActivateHour.visibility  = if (showActivate) View.VISIBLE else View.GONE
        }

        // Default for new routines: Mon–Fri
        binding.chipDaysOfWeek.isChecked = true
        setDayChips(setOf(2, 3, 4, 5, 6))

        binding.btnActivateHour.setOnClickListener {
            pickTime(activateHour) { h ->
                activateHour = h
                binding.btnActivateHour.text = fmtHour(h)
            }
        }
    }

    private fun setDayChips(selected: Set<Int>) {
        mapOf(1 to R.id.chipSun, 2 to R.id.chipMon, 3 to R.id.chipTue, 4 to R.id.chipWed,
              5 to R.id.chipThu, 6 to R.id.chipFri, 7 to R.id.chipSat)
            .forEach { (day, id) ->
                binding.chipGroupDays.findViewById<Chip>(id)?.isChecked = day in selected
            }
    }

    private fun selectedDays(): Set<Int> =
        mapOf(R.id.chipSun to 1, R.id.chipMon to 2, R.id.chipTue to 3, R.id.chipWed to 4,
              R.id.chipThu to 5, R.id.chipFri to 6, R.id.chipSat to 7)
            .filter { (id, _) ->
                binding.chipGroupDays.findViewById<Chip>(id)?.isChecked == true
            }.values.toSet()

    // ── Threshold slider ──────────────────────────────────────────────────────

    private fun setupThresholdSlider() {
        binding.tvThresholdValue.text = "${binding.sliderThreshold.value.toInt()} min"
        binding.sliderThreshold.addOnChangeListener { _, value, _ ->
            binding.tvThresholdValue.text = "${value.toInt()} min"
        }
    }

    // ── Save / Delete buttons + time pickers ──────────────────────────────────

    private fun setupButtons(isNew: Boolean) {
        binding.btnStartHour.text = fmtHour(startHour)
        binding.btnEndHour.text   = fmtHour(endHour)

        binding.btnStartHour.setOnClickListener {
            pickTime(startHour) { h -> startHour = h; binding.btnStartHour.text = fmtHour(h) }
        }
        binding.btnEndHour.setOnClickListener {
            pickTime(endHour) { h -> endHour = h; binding.btnEndHour.text = fmtHour(h) }
        }

        binding.btnEditExcluded.setOnClickListener {
            startActivity(Intent(this, ExcludedAppsActivity::class.java))
        }

        binding.btnSave.setOnClickListener { attemptSave() }

        if (!isNew) {
            binding.btnDelete.visibility = View.VISIBLE
            binding.btnDelete.setOnClickListener { confirmDelete() }
        }

        binding.etRoutineName.doAfterTextChanged { binding.tilRoutineName.error = null }
    }

    // ── Parent spinner (Pro) ──────────────────────────────────────────────────

    private fun setupParentSpinner(currentParentId: Long?) {
        binding.cardParent.visibility = View.VISIBLE
        val labels = listOf("None (root routine)") + parentRoutines.map { "${it.emoji} ${it.name}" }
        binding.spinnerParent.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        if (currentParentId != null) {
            val idx = parentRoutines.indexOfFirst { it.id == currentParentId }
            if (idx >= 0) binding.spinnerParent.setSelection(idx + 1)
        }
    }

    // ── Load existing routine into form ───────────────────────────────────────

    private fun loadRoutine(routine: Routine) {
        binding.etRoutineName.setText(routine.name)
        selectEmoji(routine.emoji)

        val threshold = routine.alertThresholdMinutes ?: 5
        binding.sliderThreshold.value = threshold.toFloat().coerceIn(1f, 30f)

        routine.startHour?.let { startHour = it; binding.btnStartHour.text = fmtHour(it) }
        routine.endHour?.let   { endHour   = it; binding.btnEndHour.text   = fmtHour(it) }

        when (val s = routine.schedule) {
            is RoutineSchedule.Manual     -> binding.chipManual.isChecked = true
            is RoutineSchedule.DaysOfWeek -> { binding.chipDaysOfWeek.isChecked = true; setDayChips(s.days) }
            is RoutineSchedule.TimedDays  -> {
                binding.chipTimedDays.isChecked = true
                setDayChips(s.days)
                activateHour = s.activateHour
                binding.btnActivateHour.text = fmtHour(s.activateHour)
            }
            null -> binding.chipDaysOfWeek.isChecked = true
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private fun attemptSave() {
        val name = binding.etRoutineName.text?.toString()?.trim()
        if (name.isNullOrEmpty()) {
            binding.tilRoutineName.error = "A name is required"
            return
        }

        val schedule: RoutineSchedule = when (binding.chipGroupScheduleType.checkedChipId) {
            R.id.chipManual     -> RoutineSchedule.Manual
            R.id.chipDaysOfWeek -> {
                val days = selectedDays()
                if (days.isEmpty()) { showError("Please select at least one day."); return }
                RoutineSchedule.DaysOfWeek(days)
            }
            R.id.chipTimedDays  -> {
                val days = selectedDays()
                if (days.isEmpty()) { showError("Please select at least one day."); return }
                RoutineSchedule.TimedDays(days, activateHour)
            }
            else -> RoutineSchedule.DaysOfWeek(setOf(2, 3, 4, 5, 6))
        }

        if (startHour == endHour) { showError("Start and end hours can't be the same."); return }

        val parentId: Long? = if (binding.cardParent.visibility == View.VISIBLE) {
            val pos = binding.spinnerParent.selectedItemPosition
            if (pos > 0) parentRoutines.getOrNull(pos - 1)?.id else null
        } else {
            existingRoutine?.parentId
        }

        val routine = (existingRoutine
            ?: Routine(id = System.currentTimeMillis(), name = name)).copy(
            name                  = name,
            emoji                 = selectedEmoji,
            parentId              = parentId,
            startHour             = startHour,
            endHour               = endHour,
            alertThresholdMinutes = binding.sliderThreshold.value.toInt(),
            schedule              = schedule,
            isManuallyActive      = existingRoutine?.isManuallyActive ?: false
        )

        lifecycleScope.launch { configRepository.saveRoutine(routine); finish() }
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun confirmDelete() {
        val name = existingRoutine?.name ?: "this routine"
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"$name\"?")
            .setMessage("This cannot be undone. Child routines that inherit from it will lose their parent.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch { existingRoutine?.let { configRepository.deleteRoutine(it) }; finish() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pickTime(current: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, hour, _ -> onPicked(hour) }, current, 0, false).show()
    }

    private fun fmtHour(h: Int): String {
        val ampm = if (h >= 12) "PM" else "AM"
        val hr   = (h % 12).let { if (it == 0) 12 else it }
        return "$hr $ampm"
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(this).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
