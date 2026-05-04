// ══════════════════════════════════════════════════════════════════════════════
// FILE: app/src/main/java/com/example/adhdassistant/ADHDApplication.kt
// ══════════════════════════════════════════════════════════════════════════════

package com.example.adhdassistant

import android.app.Application
import com.example.adhdassistant.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * Application class. Handles one-time startup tasks:
 *   - Prune event log entries older than 90 days (keeps the DB lean)
 *
 * Everything else uses lazy initialisation — no heavy work in onCreate().
 */
class ADHDApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        pruneOldEvents()
    }

    private fun pruneOldEvents() {
        appScope.launch {
            val ninetyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
            AppDatabase.getDatabase(this@ADHDApplication)
                .activityEventDao()
                .pruneOldEvents(ninetyDaysAgo)
        }
    }
}


// ══════════════════════════════════════════════════════════════════════════════
// FILE: app/proguard-rules.pro
// ══════════════════════════════════════════════════════════════════════════════

/*

# ── Kotlin serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.example.adhdassistant.**$$serializer { *; }
-keepclassmembers class com.example.adhdassistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.adhdassistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── Room ─────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# ── Billing ──────────────────────────────────────────────────────────────────
-keep class com.android.billingclient.** { *; }

# ── Data models (keep for Room + serialization) ───────────────────────────────
-keep class com.example.adhdassistant.data.** { *; }
-keep class com.example.adhdassistant.config.** { *; }
-keep class com.example.adhdassistant.domain.TriggerClause { *; }
-keep class com.example.adhdassistant.domain.TriggerClause$* { *; }

# ── General Android ───────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

*/