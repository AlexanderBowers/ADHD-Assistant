package com.example.adhdassistant.ui.stats

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.ADHDApplication
import com.example.adhdassistant.R
import com.example.adhdassistant.data.ActivityEvent
import com.example.adhdassistant.data.AppDatabase
import com.example.adhdassistant.databinding.ActivityStatsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

class StatsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatsBinding
    private lateinit var database: AppDatabase
    private val configRepository get() = (application as ADHDApplication).configRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        database = AppDatabase.getDatabase(applicationContext)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.setPadding(0, 0, 0, bars.bottom)
            insets
        }

        loadStats()
    }

    private fun loadStats() {
        lifecycleScope.launch {
            val weekStart = weekStartMs()
            val events = database.activityEventDao().getAllEventsForWeek(weekStart).first()

            // Only resolved events (exclude still-pending TRIGGERED rows)
            val resolved = events.filter { it.actionTaken != "TRIGGERED" }

            if (resolved.isEmpty()) {
                binding.tvNoData.visibility    = View.VISIBLE
                binding.layoutSummary.visibility = View.GONE
                binding.cardRate.visibility    = View.GONE
                return@launch
            }

            binding.tvNoData.visibility      = View.GONE
            binding.layoutSummary.visibility = View.VISIBLE
            binding.cardRate.visibility      = View.VISIBLE

            val totalCheckins = resolved.size
            val putDownCount  = resolved.count { it.actionTaken == "MOVED" }
            val putDownRate   = if (totalCheckins > 0) putDownCount * 100 / totalCheckins else 0

            binding.tvCheckinCount.text = totalCheckins.toString()
            binding.tvPutDownCount.text = putDownCount.toString()
            binding.tvPutDownRate.text  = "$putDownRate%"

            // Top trigger app
            val topApp = resolved
                .groupingBy { it.appPackage }
                .eachCount()
                .maxByOrNull { it.value }

            if (topApp != null && topApp.key.isNotBlank()) {
                binding.cardTopApp.visibility = View.VISIBLE
                val appLabel = try {
                    packageManager.getApplicationLabel(
                        packageManager.getApplicationInfo(topApp.key, 0)
                    ).toString()
                } catch (_: Exception) {
                    topApp.key.substringAfterLast(".").replaceFirstChar { it.uppercaseChar() }
                }
                binding.tvTopApp.text       = appLabel
                binding.tvTopAppDetail.text = "${topApp.value} check-in${if (topApp.value != 1) "s" else ""} this week"
            }

            // Day-by-day bar chart
            buildDayBars(resolved)

            // Encouragement
            binding.cardEncouragement.visibility = View.VISIBLE
            binding.tvEncouragement.text = when {
                totalCheckins < 5  -> getString(R.string.stats_encouragement_low)
                totalCheckins < 20 -> getString(R.string.stats_encouragement_mid, totalCheckins)
                else               -> getString(R.string.stats_encouragement_high, totalCheckins)
            }
        }
    }

    /**
     * Builds a simple 7-column bar chart — one column per day of the week
     * (Sun–Sat), scaled to the tallest day. Today's column is highlighted.
     */
    private fun buildDayBars(events: List<ActivityEvent>) {
        val labels = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val counts = IntArray(7)
        events.forEach { event ->
            val dow = Calendar.getInstance()
                .apply { timeInMillis = event.timestamp }
                .get(Calendar.DAY_OF_WEEK) - 1   // 0=Sun … 6=Sat
            counts[dow]++
        }

        val maxCount    = counts.max().coerceAtLeast(1)
        val maxBarPx    = resources.getDimensionPixelSize(R.dimen.fab_bottom_clearance)  // 88dp
        val minBarPx    = 8
        val today       = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1

        val container = binding.layoutDayBars
        container.removeAllViews()

        for (i in 0..6) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity     = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(4, 0, 4, 0)
            }

            // Count above bar
            val countTv = TextView(this).apply {
                text      = if (counts[i] > 0) counts[i].toString() else ""
                textSize  = 10f
                gravity   = Gravity.CENTER
                setTextColor(getColor(R.color.on_surface_variant))
            }

            // Bar
            val barH = if (counts[i] == 0) minBarPx
                       else (maxBarPx * counts[i] / maxCount).coerceAtLeast(minBarPx)
            val colorRes = if (i == today) R.color.primary else R.color.primary_container
            val bar = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, barH
                ).apply { topMargin = 4 }
                background = GradientDrawable().apply {
                    setColor(getColor(colorRes))
                    cornerRadius = 8f
                }
            }

            // Day label below bar
            val dayTv = TextView(this).apply {
                text      = labels[i]
                textSize  = 10f
                gravity   = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setTextColor(
                    if (i == today) getColor(R.color.primary)
                    else getColor(R.color.on_surface_variant)
                )
            }

            col.addView(countTv)
            col.addView(bar)
            col.addView(dayTv)
            container.addView(col)
        }

        binding.cardDailyBreakdown.visibility = View.VISIBLE
    }

    /** Returns the timestamp for the start of the current Sunday (00:00:00). */
    private fun weekStartMs(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
    }.timeInMillis

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }
}
