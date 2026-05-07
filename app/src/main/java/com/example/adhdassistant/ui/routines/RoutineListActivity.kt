package com.example.adhdassistant.ui.routines

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.config.Routine
import com.example.adhdassistant.config.RoutineSchedule
import com.example.adhdassistant.databinding.ActivityRoutineListBinding
import com.example.adhdassistant.databinding.ItemRoutineBinding
import com.example.adhdassistant.domain.RoutineError
import com.example.adhdassistant.domain.RoutineResolver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.example.adhdassistant.ui.routines.EditRoutineActivity.Companion.EXTRA_PROFILE_ID

class RoutineListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRoutineListBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
    private lateinit var adapter: RoutineAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRoutineListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.recyclerView.setPadding(0, 0, 0, bars.bottom + 200)
            insets
        }

        adapter = RoutineAdapter(
            onToggle = { routine, isChecked ->
                lifecycleScope.launch {
                    configRepository.saveRoutine(routine.copy(isManuallyActive = isChecked))
                }
            },
            onEdit = { routine ->
                startActivity(
                    Intent(this@RoutineListActivity, EditRoutineActivity::class.java)
                        .putExtra(EditRoutineActivity.EXTRA_PROFILE_ID, routine.id)
                )
            },
            onDelete = { routine ->
                confirmDelete(routine)
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        binding.fabAddRoutine.setOnClickListener {
            startActivity(
                Intent(this, EditRoutineActivity::class.java)
                    .putExtra(EditRoutineActivity.EXTRA_PROFILE_ID, -1L)
            )
        }

        lifecycleScope.launch {
            configRepository.routinesFlow.collectLatest { routines ->
                val activeRoutines = RoutineResolver.resolveCurrentlyActive(routines)
                val errors = RoutineResolver.validate(routines)

                val activeIds = activeRoutines.map { it.id }.toSet()
                val brokenIds = errors.mapNotNull { error ->
                    when (error) {
                        is RoutineError.MissingParent -> routines.firstOrNull { it.name == error.routineName }?.id
                        is RoutineError.IncompleteRoot -> routines.firstOrNull { it.name == error.routineName }?.id
                        else -> null
                    }
                }.toSet()

                adapter.submitRoutines(routines, activeIds, brokenIds)

                if (routines.isEmpty()) {
                    binding.emptyText.visibility = View.VISIBLE
                    binding.recyclerView.visibility = View.GONE
                } else {
                    binding.emptyText.visibility = View.GONE
                    binding.recyclerView.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun confirmDelete(routine: Routine) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Routine?")
            .setMessage("Are you sure you want to delete '${routine.name}'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    configRepository.deleteRoutine(routine)
                    Snackbar.make(binding.root, "${routine.name} deleted", Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ─── Adapter ─────────────────────────────────────────────────────────────

class RoutineAdapter(
    private val onToggle: (Routine, Boolean) -> Unit,
    private val onEdit:   (Routine) -> Unit,
    private val onDelete: (Routine) -> Unit
) : RecyclerView.Adapter<RoutineAdapter.RoutineViewHolder>() {

    private var routines  = listOf<Routine>()
    private var activeIds = setOf<Long>()
    private var brokenIds = setOf<Long>()

    fun submitRoutines(
        newRoutines:  List<Routine>,
        newActiveIds: Set<Long>,
        newBrokenIds: Set<Long>
    ) {
        routines  = newRoutines
        activeIds = newActiveIds
        brokenIds = newBrokenIds
        notifyDataSetChanged()
    }

    fun revertToggle(routineId: Long) {
        val index = routines.indexOfFirst { it.id == routineId }
        if (index >= 0) notifyItemChanged(index)
    }

    inner class RoutineViewHolder(val binding: ItemRoutineBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RoutineViewHolder {
        val b = ItemRoutineBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RoutineViewHolder(b)
    }

    override fun onBindViewHolder(holder: RoutineViewHolder, position: Int) {
        val routine = routines[position]
        val b = holder.binding

        // ── Identity ──────────────────────────────────────────────────────
        b.tvEmoji.text       = routine.emoji
        b.tvRoutineName.text = routine.name
        b.tvScheduleSummary.text = routine.listSummary()

        // ── Active now chip ───────────────────────────────────────────────
        b.chipActive.visibility =
            if (routine.id in activeIds) View.VISIBLE else View.GONE

        // ── Inherits from chip ────────────────────────────────────────────
        val parentName = routines.firstOrNull { it.id == routine.parentId }?.name
        if (parentName != null) {
            b.chipInherits.text       = "Inherits from $parentName"
            b.chipInherits.visibility = View.VISIBLE
        } else {
            b.chipInherits.visibility = View.GONE
        }

        // ── Error chip ────────────────────────────────────────────────────
        b.chipError.visibility =
            if (routine.id in brokenIds) View.VISIBLE else View.GONE

        // ── Toggle vs Auto label ──────────────────────────────────────────
        when (routine.schedule) {
            is RoutineSchedule.Manual -> {
                b.switchActive.visibility = View.VISIBLE
                b.tvAutoLabel.visibility  = View.GONE
                b.switchActive.setOnCheckedChangeListener(null)
                b.switchActive.isChecked  = routine.isManuallyActive
                b.switchActive.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(routine, isChecked)
                }
            }
            else -> {
                b.switchActive.visibility = View.GONE
                b.tvAutoLabel.visibility  = View.VISIBLE
            }
        }

        b.btnEdit.setOnClickListener   { onEdit(routine) }
        b.btnDelete.setOnClickListener { onDelete(routine) }
    }

    override fun getItemCount() = routines.size
}

// ─── Extension: display summary on raw Routine ────────────────────────────────

private fun Routine.listSummary(): String {
    val scheduleStr = when (val s = schedule) {
        is RoutineSchedule.Manual     -> "Manual"
        is RoutineSchedule.DaysOfWeek -> formatDays(s.days)
        is RoutineSchedule.TimedDays  -> "${formatDays(s.days)} from ${fmtHour(s.activateHour)}"
        null                          -> "Inherits schedule"
    }
    val timeStr = if (startHour != null && endHour != null) {
        "${fmtHour(startHour)}–${fmtHour(endHour)}"
    } else "Inherits hours"

    val thresholdStr = if (alertThresholdMinutes != null) {
        "${alertThresholdMinutes}min"
    } else "Inherits threshold"

    return "$scheduleStr · $timeStr · $thresholdStr"
}

private fun formatDays(days: Set<Int>): String = when (days.sorted().toSet()) {
    setOf(2, 3, 4, 5, 6)       -> "Mon–Fri"
    setOf(1, 7)                 -> "Weekends"
    setOf(1, 2, 3, 4, 5, 6, 7) -> "Every day"
    else -> days.joinToString(", ") {
        when(it) {
            1 -> "Sun"
            2 -> "Mon"
            3 -> "Tue"
            4 -> "Wed"
            5 -> "Thu"
            6 -> "Fri"
            7 -> "Sat"
            else -> ""
        }
    }
}

private fun fmtHour(h: Int): String {
    val ampm = if (h >= 12) "PM" else "AM"
    var hr12 = h % 12
    if (hr12 == 0) hr12 = 12
    return "$hr12 $ampm"
}