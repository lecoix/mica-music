buildscript {
    repositories {
        google()
    }
    dependencies {
        // Lyricon 0.1.70 brings Kotlin 2.3 metadata, requiring R8 8.13.19.
        // AGP 8.9.1 bundles an older R8; keep the override explicit.
        // https://developer.android.com/build/kotlin-support
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}
