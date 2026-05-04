# ── Kotlin serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
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

# ── Data models ───────────────────────────────────────────────────────────────
-keep class com.example.adhdassistant.data.** { *; }
-keep class com.example.adhdassistant.config.** { *; }
-keep class com.example.adhdassistant.domain.TriggerClause { *; }
-keep class com.example.adhdassistant.domain.TriggerClause$* { *; }

# ── Stack traces ──────────────────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
