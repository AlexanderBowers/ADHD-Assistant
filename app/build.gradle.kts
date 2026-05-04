plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace  = "com.example.adhdassistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.adhdassistant"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0.0"
    }

    signingConfigs {
        // Reads from keystore.properties — that file is in .gitignore and never committed.
        // Create it locally with these four keys:
        //   storeFile=../release.jks
        //   storePassword=yourStorePassword
        //   keyAlias=yourKeyAlias
        //   keyPassword=yourKeyPassword
        create("release") {
            val props = java.util.Properties()
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) props.load(propsFile.inputStream())

            keyAlias      = props.getProperty("keyAlias")      ?: ""
            keyPassword   = props.getProperty("keyPassword")   ?: ""
            storeFile     = props.getProperty("storeFile")?.let { file(it) }
            storePassword = props.getProperty("storePassword") ?: ""
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
            // Debug builds don't need a keystore — Android signs them automatically
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // UI — Material 3
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)

    // Architecture
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)

    // Data
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // Kotlin
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // Billing
    implementation(libs.billing.ktx)

    // Unit tests (run on your machine, no device needed)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)

    // Instrumented tests (run on device/emulator)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.room.testing)
}
