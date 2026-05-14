package com.example.adhdassistant.ui.location

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "extra_name"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_RADIUS = "extra_radius"

        const val EXTRA_INIT_LAT = "extra_init_lat"
        const val EXTRA_INIT_LNG = "extra_init_lng"
        const val EXTRA_INIT_RADIUS = "extra_init_radius"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO: Set up your Map UI and return the selected data via Intent
    }
}