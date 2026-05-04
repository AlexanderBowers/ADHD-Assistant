package com.example.adhdassistant.ui.profiles

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.config.Profile
import com.example.adhdassistant.config.ProfileSchedule
import com.example.adhdassistant.databinding.ActivityProfileListBinding
import com.example.adhdassistant.databinding.ItemProfileBinding
import com.example.adhdassistant.domain.ProfileConflict
import com.example.adhdassistant.domain.ProfileConflictDetector
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Lists all profiles with toggle switches.
 *
 * Key behaviour:
 *  - Toggling a profile ON runs conflict detection immediately.
 *  - If a conflict is found, a dialog explains which profiles clash and how
 *    the service will resolve it, then asks the user to confirm.
 *  - If the user confirms, both profiles stay active and the conflict is noted
 *    with a warning chip on each card.
 *  - Scheduled profiles show "Auto" instead of a toggle (they activate by
 *    schedule, not by user tap). Manual profiles show a toggle.
 */
class ProfileListActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProfileListBinding
    private lateinit var configRepository: ConfigRepository
    private lateinit var adapter: ProfileAdapter

    private var profiles = mutableListOf<Profile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Profiles"

        configRepository = ConfigRepository(applicationContext)

        adapter = ProfileAdapter(
            onToggle = { profile, isOn -> handleToggle(profile, isOn) },
            onEdit = { profile -> openEditProfile(profile) },
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
                val activeProfiles = ProfileConflictDetector.resolveCurrentlyActive(profiles)
                val conflicts = ProfileConflictDetector.detect(activeProfiles)
                adapter.submitProfiles(profiles.toList(), conflicts)
            }
        }
    }

    private fun handleToggle(profile: Profile, isOn: Boolean) {
        if (profile.schedule !is ProfileSchedule.Manual) return  // Scheduled profiles can't be manually toggled

        val proposedActive = if (isOn) {
            ProfileConflictDetector.resolveCurrentlyActive(profiles)
                .toMutableList().also { it.add(profile) }
        } else {
            ProfileConflictDetector.resolveCurrentlyActive(profiles)
                .filter { it.id != profile.id }
        }

        val conflicts = ProfileConflictDetector.detect(proposedActive)

        if (isOn && conflicts.isNotEmpty()) {
            // Show conflict dialog — user can still proceed if they want
            showConflictDialog(
                incomingProfile = profile,
                conflicts = conflicts,
                onConfirm = { applyToggle(profile, true) },
                onCancel = { adapter.revertToggle(profile.id) }
            )
        } else {
            applyToggle(profile, isOn)
        }
    }

    private fun applyToggle(profile: Profile, isOn: Boolean) {
        lifecycleScope.launch {
            val updated = profile.copy(isManuallyActive = isOn)
            configRepository.saveProfile(updated)
        }
    }

    private fun showConflictDialog(
        incomingProfile: Profile,
        conflicts: List<ProfileConflict>,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        val conflictSummary = conflicts.joinToString("\n\n") { it.describe() }

        MaterialAlertDialogBuilder(this)
            .setTitle("⚠️ Profile Overlap Detected")
            .setMessage(
                "\"${incomingProfile.name}\" overlaps with another active profile:\n\n" +
                        "$conflictSummary\n\n" +
                        "You can still enable it — the strictest settings will apply during the overlap."
            )
            .setPositiveButton("Enable Anyway") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .show()
    }

    private fun openEditProfile(profile: Profile) {
        startActivity(Intent(this, EditProfileActivity::class.java).apply {
            putExtra(EditProfileActivity.EXTRA_PROFILE_ID, profile.id)
        })
    }

    private fun confirmDelete(profile: Profile) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete \"${profile.name}\"?")
            .setMessage("This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    configRepository.deleteProfile(profile.id)
                    Snackbar.make(binding.root, "\"${profile.name}\" deleted", Snackbar.LENGTH_SHORT).show()
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
        private val onEdit: (Profile) -> Unit,
        private val onDelete: (Profile) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.ProfileViewHolder>() {

        private var profiles = listOf<Profile>()
        private var conflicts = listOf<ProfileConflict>()

        fun submitProfiles(newProfiles: List<Profile>, newConflicts: List<ProfileConflict>) {
            profiles = newProfiles
            conflicts = newConflicts
            notifyDataSetChanged()
        }

        /** Called when user cancels a conflict dialog — reverts the switch UI state. */
        fun revertToggle(profileId: Long) {
            val index = profiles.indexOfFirst { it.id == profileId }
            if (index >= 0) notifyItemChanged(index)
        }

        inner class ProfileViewHolder(val binding: ItemProfileBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
            val binding = ItemProfileBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ProfileViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
            val profile = profiles[position]
            val b = holder.binding

            b.tvEmoji.text = profile.emoji
            b.tvProfileName.text = profile.name
            b.tvScheduleSummary.text = profile.scheduleSummary()

            // Show conflict warning chip if this profile is involved in any conflict
            val hasConflict = conflicts.any {
                it.profileA.id == profile.id || it.profileB.id == profile.id
            }
            b.chipConflict.visibility = if (hasConflict) View.VISIBLE else View.GONE

            // Show "Active now" badge if scheduled and currently active
            val isActiveNow = ProfileConflictDetector
                .resolveCurrentlyActive(profiles)
                .any { it.id == profile.id }
            b.chipActive.visibility = if (isActiveNow) View.VISIBLE else View.GONE

            // Manual profiles get a toggle; scheduled profiles show "Auto"
            when (profile.schedule) {
                is ProfileSchedule.Manual -> {
                    b.switchActive.visibility = View.VISIBLE
                    b.tvAutoLabel.visibility = View.GONE
                    // Detach listener before setting value to avoid phantom callbacks
                    b.switchActive.setOnCheckedChangeListener(null)
                    b.switchActive.isChecked = profile.isManuallyActive
                    b.switchActive.setOnCheckedChangeListener { _, isChecked ->
                        onToggle(profile, isChecked)
                    }
                }
                else -> {
                    b.switchActive.visibility = View.GONE
                    b.tvAutoLabel.visibility = View.VISIBLE
                }
            }

            b.btnEdit.setOnClickListener { onEdit(profile) }
            b.btnDelete.setOnClickListener { onDelete(profile) }
        }

        override fun getItemCount() = profiles.size
    }
}