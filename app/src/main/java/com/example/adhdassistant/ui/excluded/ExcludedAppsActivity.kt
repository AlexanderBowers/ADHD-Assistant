package com.example.adhdassistant.ui.excluded

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.adhdassistant.R
import com.example.adhdassistant.config.ConfigRepository
import com.example.adhdassistant.databinding.ActivityExcludedAppsBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pro-only screen: lets users exclude specific apps from triggering focus alerts.
 *
 * Key architectural decision: We use queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)
 * instead of getInstalledApplications(). This approach:
 *  - Requires NO special permissions (no QUERY_ALL_PACKAGES)
 *  - Returns only user-launchable apps (no system noise)
 *  - Works on all Android versions including Android 11+ package visibility changes
 *  - Passes Google Play's policy review without a declaration
 */
class ExcludedAppsActivity : AppCompatActivity() {

    data class AppItem(
        val name: String,
        val packageName: String,
        var isExcluded: Boolean
    )

    private lateinit var binding: ActivityExcludedAppsBinding
    private lateinit var configRepository: ConfigRepository
    private lateinit var adapter: AppAdapter

    private val allApps = mutableListOf<AppItem>()
    private var savedExclusions = emptySet<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityExcludedAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.excluded_title)

        configRepository = ConfigRepository(applicationContext)

        adapter = AppAdapter { app, isChecked ->
            app.isExcluded = isChecked
        }

        binding.recyclerViewApps.layoutManager = LinearLayoutManager(this)
        binding.recyclerViewApps.adapter = adapter
        binding.recyclerViewApps.setHasFixedSize(true)

        setupSearch()
        loadApps()

        binding.btnSaveExcluded.setOnClickListener {
            saveAndFinish()
        }
    }

    private fun loadApps() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerViewApps.visibility = View.GONE

        lifecycleScope.launch {
            savedExclusions = configRepository.getExcludedApps()

            val apps = withContext(Dispatchers.IO) {
                queryLaunchableApps()
            }

            allApps.clear()
            allApps.addAll(apps)

            binding.progressBar.visibility = View.GONE
            binding.recyclerViewApps.visibility = View.VISIBLE
            binding.tvAppCount.text = getString(R.string.excluded_count, apps.size)
            adapter.submitList(apps.toList())
        }
    }

    /**
     * Query all apps that appear in the launcher (user-facing apps only).
     * No special permission required.
     */
    private fun queryLaunchableApps(): List<AppItem> {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = packageManager
            .queryIntentActivities(intent, 0)

        return resolveInfos
            .map { ri ->
                val pkgName = ri.activityInfo.packageName
                AppItem(
                    name = ri.loadLabel(packageManager).toString(),
                    packageName = pkgName,
                    isExcluded = savedExclusions.contains(pkgName)
                )
            }
            .filter { it.packageName != packageName }  // Remove ourselves
            .sortedWith(compareByDescending<AppItem> { it.isExcluded }.thenBy { it.name })
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (query.isEmpty()) {
                    allApps.toList()
                } else {
                    allApps.filter {
                        it.name.lowercase().contains(query) ||
                                it.packageName.lowercase().contains(query)
                    }
                }
                adapter.submitList(filtered)
            }
        })
    }

    private fun saveAndFinish() {
        lifecycleScope.launch {
            val newExclusions = allApps.filter { it.isExcluded }.map { it.packageName }.toSet()
            configRepository.setExcludedApps(newExclusions)

            Snackbar.make(binding.root, "Saved ${newExclusions.size} exclusions", Snackbar.LENGTH_SHORT).show()

            // Short delay so user sees the Snackbar before we close
            kotlinx.coroutines.delay(800)
            finish()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    // ─── Adapter ──────────────────────────────────────────────────────────────

    class AppAdapter(
        private val onCheckedChanged: (AppItem, Boolean) -> Unit
    ) : ListAdapter<AppItem, AppAdapter.ViewHolder>(DIFF_CALLBACK) {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvAppName)
            val tvPackage: TextView = view.findViewById(R.id.tvPackageName)
            val cbExclude: CheckBox = view.findViewById(R.id.cbExclude)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_excluded_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = getItem(position)
            holder.tvName.text = app.name
            holder.tvPackage.text = app.packageName

            // Remove listener before setting value to avoid phantom callbacks
            holder.cbExclude.setOnCheckedChangeListener(null)
            holder.cbExclude.isChecked = app.isExcluded
            holder.cbExclude.contentDescription = holder.itemView.context
                .getString(R.string.cd_check_excluded)

            holder.cbExclude.setOnCheckedChangeListener { _, isChecked ->
                onCheckedChanged(app, isChecked)
            }

            // Whole row taps toggle the checkbox too
            holder.itemView.setOnClickListener {
                holder.cbExclude.isChecked = !holder.cbExclude.isChecked
            }
        }

        companion object {
            val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppItem>() {
                override fun areItemsTheSame(oldItem: AppItem, newItem: AppItem) =
                    oldItem.packageName == newItem.packageName
                override fun areContentsTheSame(oldItem: AppItem, newItem: AppItem) =
                    oldItem == newItem
            }
        }
    }
}