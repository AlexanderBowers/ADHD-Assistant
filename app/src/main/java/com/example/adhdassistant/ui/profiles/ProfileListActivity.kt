package com.example.adhdassistant.ui.profiles

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.config.Profile
import com.example.adhdassistant.config.ProfileSchedule
import com.example.adhdassistant.databinding.ActivityProfileListBinding
import com.example.adhdassistant.databinding.ItemProfileBinding
import com.example.adhdassistant.domain.ProfileError
import com.example.adhdassistant.domain.ProfileResolver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Lists all routines (profiles) with toggle switches.
 *
 * Scheduling vs manual:
 *   - Scheduled profiles (DaysOfWeek, TimedDays) show an "Auto" label — they
 *     activate on their own and cannot be manually toggled.
 *   - Manual profiles show a toggle switch.
 *
 * Structural errors (circular inheritance, missing parent) are surfaced with
 * a warning chip on the card. Overlapping hours/days is NOT an error — the
 * merger handles that gracefully.
 */
class ProfileListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileListBinding
    private val configRepository get() = (application as ADHDApplication).configRepository
    private lateinit var adapter: ProfileAdapter

    private var profiles = mutableListOf<Profile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Routines"

        adapter = ProfileAdapter(
            onToggle = { profile, isOn -> applyToggle(profile, isOn) },
            onEdit   = { profile -> openEditProfile(profile) },
            onDelete = { profile -> confirmDelete(profile) }
        )

        binding.recyclerViewProfiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewProfiles.adapter = adapter

        binding.fabAddProfile.setOnClickListener {
            startActivity(Intent(this, EditProfileActivity::class.java))
        }

        observeProfiles()
    }

    private fun observeProfiles() {
        lifecycleScope.launch {
            configRepository.profilesFlow.collectLatest { stored ->
                profiles.clear()
                profiles.addAll(stored)

                val currentDow  = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
                val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
                val activeNow   = ProfileResolver
                    .resolveCurrentlyActive(profiles, currentDow, currentHour)
                    .map { it.id }
                    .toSet()

                // Structural validation — flag broken profiles (circular ref, missing parent)
                val errors = ProfileResolver.validate(profiles)
                val errorIds = errors.mapNotNull { error ->
                    when (error) {
                        is ProfileError.CircularInheritance -> null // can't map to a single id
                        is ProfileError.MissingParent       -> null
                        is ProfileError.IncompleteRoot      -> null
                    }
                }.toSet()

                // Build a set of profile IDs that have any validation error
                val brokenIds = buildSet<Long> {
                    errors.forEach { error ->
                        when (error) {
                            is ProfileError.IncompleteRoot -> {
                                profiles.firstOrNull { it.name == error.profileName }
                                    ?.id?.let { add(it) }
                            }
                            is ProfileError.MissingParent -> {
                                profiles.firstOrNull { it.name == error.profileName }
                                    ?.id?.let { add(it) }
                            }
                            is ProfileError.CircularInheritance -> {
                                // Mark all profiles whose names appear in the cycle path
                                val cycleNames = error.cyclePath.split(" → ")
                                profiles.filter { it.name in cycleNames }
                                    .forEach { add(it.id) }
                            }
                        }
                    }
                }

                binding.tvEmpty.visibility =
                    if (profiles.isEmpty()) View.VISIBLE else View.GONE

                adapter.submitProfiles(profiles.toList(), activeNow, brokenIds)
            }
        }
    }

    private fun applyToggle(profile: Profile, isOn: Boolean) {
        lifecycleScope.launch {
            configRepository.saveProfile(profile.copy(isManuallyActive = isOn))
        }
    }

    private fun openEditProfile(profile: Profile) {
        startActivity(Intent(this, EditProfileActivity::class.java).apply {
            putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id)
        })
    }

    private fun confirmDelete(profile: Profile) {
        val hasChildren = profiles.any { it.parentId == profile.id }
        val message = if (hasChildren) {
            "\"${profile.name}\" has child routines that inherit from it. " +
                    "They will lose their parent and need to be updated. This cannot be undone."
        } else {
            "This cannot be undone."
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Remove \"${profile.name}\"?")
            .setMessage(message)
            .setPositiveButton("Remove") { _, _ ->
                lifecycleScope.launch {
                    configRepository.deleteProfile(profile.id)
                    Snackbar.make(
                        binding.root,
                        "\"${profile.name}\" removed",
                        Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    class ProfileAdapter(
        private val onToggle: (Profile, Boolean) -> Unit,
        private val onEdit:   (Profile) -> Unit,
        private val onDelete: (Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

        private var profiles  = listOf<Profile>()
        private var activeIds = setOf<Long>()
        private var brokenIds = setOf<Long>()

        fun submitProfiles(
            newProfiles:  List<Profile>,
            newActiveIds: Set<Long>,
            newBrokenIds: Set<Long>
        ) {
            profiles  = newProfiles
            activeIds = newActiveIds
            brokenIds = newBrokenIds
            notifyDataSetChanged()
        }

        fun revertToggle(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index >= 0) notifyItemChanged(index)
        }

        inner class ProfileViewHolder(val binding: ItemProfileBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val b = ItemProfileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ProfileViewHolder(b)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            val profile = profiles[position]
            val b = holder.binding

            // ── Identity ──────────────────────────────────────────────────────
            b.tvEmoji.text       = profile.emoji
            b.tvProfileName.text = profile.name
            b.tvScheduleSummary.text = profile.listSummary()

            // ── Active now chip ───────────────────────────────────────────────
            b.chipActive.visibility =
                if (profile.id in activeIds) View.VISIBLE else View.GONE

            // ── Inherits from chip ────────────────────────────────────────────
            val parentName = profiles.firstOrNull { it.id == profile.parentId }?.name
            if (parentName != null) {
                b.chipInherits.text       = "Inherits from $parentName"
                b.chipInherits.visibility = View.VISIBLE
            } else {
                b.chipInherits.visibility = View.GONE
            }

            // ── Error chip ────────────────────────────────────────────────────
            b.chipError.visibility =
                if (profile.id in brokenIds) View.VISIBLE else View.GONE

            // ── Toggle vs Auto label ──────────────────────────────────────────
            when (profile.schedule) {
                is ProfileSchedule.Manual -> {
                    b.switchActive.visibility = View.VISIBLE
                    b.tvAutoLabel.visibility  = View.GONE
                    b.switchActive.setOnCheckedChangeListener(null)
                    b.switchActive.isChecked  = profile.isManuallyActive
                    b.switchActive.setOnCheckedChangeListener { _, isChecked ->
                        onToggle(profile, isChecked)
                    }
                }
                else -> {
                    b.switchActive.visibility = View.GONE
                    b.tvAutoLabel.visibility  = View.VISIBLE
                }
            }

            b.btnEdit.setOnClickListener   { onEdit(profile) }
            b.btnDelete.setOnClickListener { onDelete(profile) }
        }

        override fun getItemCount() = profiles.size
    }
}

// ─── Extension: display summary on raw Profile ────────────────────────────────

/**
 * A lightweight summary for the list card that handles nullable fields gracefully.
 * Child profiles show "Inherits" for any field they haven't overridden.
 */
private fun Profile.listSummary(): String {
    val scheduleStr = when (val s = schedule) {
        is ProfileSchedule.Manual     -> "Manual"
        is ProfileSchedule.DaysOfWeek -> formatDays(s.days)
        is ProfileSchedule.TimedDays  -> "${formatDays(s.days)} from ${fmtHour(s.activateHour)}"
        null                           -> "Inherits schedule"
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
    else -> days.sorted().joinToString(", ") {
        listOf("", "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").getOrElse(it) { "?" }
    }
}

private fun fmtHour(h: Int): String {
    val hh = h % 12
    return "${if (hh == 0) 12 else hh}${if (h < 12) "am" else "pm"}"
}