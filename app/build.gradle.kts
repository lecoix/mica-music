plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

providers.gradleProperty("mica.alternateBuildDir").orNull?.let { alternateDir ->
    layout.buildDirectory.set(file(alternateDir))
}

val qaSideBySide = providers.gradleProperty("mica.qaSideBySide")
    .map(String::toBoolean)
    .getOrElse(false)

val media3FfmpegLocalAar = file("libs/media3-ffmpeg-decoder-dsd.aar")
val media3FfmpegGeneratedAar =
    layout.buildDirectory.file("generated/media3-ffmpeg/media3-ffmpeg-decoder-dsd.aar").get().asFile
val media3FfmpegLocalJniCandidates = listOf(
    rootProject.file("third_party/media3-ffmpeg-decoder/src/main/jniLibs/arm64-v8a/libffmpegJNI.so"),
    rootProject.file("third_party/media3-ffmpeg-decoder/jniLibs/arm64-v8a/libffmpegJNI.so"),
)
val media3FfmpegLocalJni = media3FfmpegLocalJniCandidates.firstOrNull { it.exists() }

android {
    namespace = "com.mica.music"
    compileSdk = 35

    defaultConfig {
        applicationId = if (qaSideBySide) "com.mica.music.qa" else "com.mica.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 19
        versionName = "0.1.8.4-Exo-only" + if (qaSideBySide) "-qa" else ""
        ndk {
            // 仅 64 位真机；自编 FFmpeg 也只编 arm64-v8a
            abiFilters += listOf("arm64-v8a")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("perf") {
            initWith(getByName("release"))
            isDebuggable = qaSideBySide
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Kotlin 2.2 + lifecycle lint 2.9.x 分析 API 不兼容时会崩溃（NonNullableMutableLiveDataDetector）
    lint {
        disable += "NullSafeMutableLiveData"
    }
}

ksp {
    arg("room.schemaLocation", file("schemas").absolutePath)
}

roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

configurations.configureEach {
    resolutionStrategy.force(
        "androidx.activity:activity:1.9.2",
        "androidx.activity:activity-ktx:1.9.2",
        "androidx.activity:activity-compose:1.9.2",
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.coil.compose)
    implementation(libs.androidx.palette.ktx)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.session)

    when {
        media3FfmpegLocalAar.exists() -> implementation(files(media3FfmpegLocalAar))
        media3FfmpegGeneratedAar.exists() -> implementation(files(media3FfmpegGeneratedAar))
        media3FfmpegLocalJni != null -> implementation(project(":media3-ffmpeg-decoder-dsd"))
        else -> {
            logger.warn(
                """
                |
                | *** DSD-enabled Media3 FFmpeg not found.
                | *** Run: .\scripts\build-media3-ffmpeg-dsd.ps1
                | *** Falling back to org.jellyfin.media3:media3-ffmpeg-decoder (no DSD / audio/dsd).
                |
                """.trimMargin(),
            )
            implementation(libs.androidx.media3.exoplayer.ffmpeg)
        }
    }

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.reorderable)
    implementation(libs.kyant.taglib)
    implementation(libs.jaudiotagger)
    implementation(libs.blurview)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}

tasks.named("preBuild") {
    doFirst {
        if (
            !media3FfmpegLocalAar.exists() &&
            !media3FfmpegGeneratedAar.exists() &&
            media3FfmpegLocalJni == null
        ) {
            logger.warn(
                """
                |
                | *** libffmpegJNI with dsd_lsbf missing.
                | *** Run: .\scripts\build-media3-ffmpeg-dsd.ps1 for Exo DSF playback.
                |
                """.trimMargin(),
            )
        }
    }
}

tasks.register("micaCheck") {
    group = "verification"
    description = "Runs Mica's compile, lint, JVM/Robolectric, and screenshot regression gates."
    dependsOn(
        "compileDebugKotlin",
        "lintDebug",
        "testDebugUnitTest",
        "verifyRoborazziDebug",
    )
}

tasks.register("micaScreenshotFull") {
    group = "verification"
    description = "Runs the complete Roborazzi screenshot regression matrix."
    dependsOn("verifyRoborazziDebug")
}

tasks.register("micaRecordScreenshotFull") {
    group = "verification"
    description = "Records the complete Roborazzi screenshot regression matrix."
    dependsOn("recordRoborazziDebug")
}

val nightlyRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') == "micaNightlyCheck"
}
val fullScreenshotsRequested = nightlyRequested || gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("micaScreenshotFull", "micaRecordScreenshotFull")
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    systemProperty("mica.nightly", nightlyRequested.toString())
    systemProperty("mica.fullScreenshots", fullScreenshotsRequested.toString())
}

tasks.register("micaNightlyCheck") {
    group = "verification"
    description = "Runs compile, lint, all JVM/Robolectric tests, full screenshots, and nightly fuzzing."
    dependsOn(
        "micaCheck",
        "micaScreenshotFull",
    )
}
