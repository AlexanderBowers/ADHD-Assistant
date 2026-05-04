package com.example.adhdassistant.ui

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.adhdassistant.R // Fixes the "Unresolved reference 'R'" error
import com.example.adhdassistant.data.ActivityEvent
import com.example.adhdassistant.data.AppDatabase
import kotlinx.coroutines.launch

class MovementEnforcerActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var stepSensor: Sensor? = null
    private var stepsTaken = 0

    private lateinit var database: AppDatabase
    private lateinit var stepsTextView: TextView // Moved to class level to reuse

    // Fixes the naming convention warning by making it a true constant
    companion object {
        private const val STEPS_REQUIRED = 10
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or // Fixed redundant qualifier
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        setContentView(R.layout.activity_movement_enforcer)

        database = AppDatabase.getDatabase(this)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        // Fixes unused variable error by reusing this assignment later
        stepsTextView = findViewById(R.id.stepsTextView)
        val delayButton: Button = findViewById(R.id.delayButton)
        val reasonInput: EditText = findViewById(R.id.reasonEditText)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                Toast.makeText(this@MovementEnforcerActivity, "Move or provide a reason to dismiss.", Toast.LENGTH_SHORT).show()
            }
        })

        delayButton.setOnClickListener {
            val reason = reasonInput.text.toString()
            if (reason.isBlank()) {
                Toast.makeText(this, "You must enter a reason to delay.", Toast.LENGTH_SHORT).show()
            } else {
                logEventAndClose("DELAYED", reason)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        stepSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    // Tells Android to ignore the translation warning for our hardcoded string
    @SuppressLint("SetTextI18n")
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            stepsTaken++
            stepsTextView.text = "Steps: $stepsTaken / $STEPS_REQUIRED"

            if (stepsTaken >= STEPS_REQUIRED) {
                logEventAndClose("MOVED", null)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun logEventAndClose(action: String, reason: String?) {
        lifecycleScope.launch {
            database.activityEventDao().insertEvent(
                ActivityEvent(actionTaken = action, delayReason = reason)
            )
            finish()
        }
    }
}