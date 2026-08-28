import java.util.Properties

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
val abiSplitApks = providers.gradleProperty("mica.abiSplitApks")
    .map(String::toBoolean)
    .getOrElse(false)

val media3FfmpegLocalAar = file("libs/media3-ffmpeg-decoder-dsd.aar")
val media3FfmpegGeneratedAar =
    layout.buildDirectory.file("generated/media3-ffmpeg/media3-ffmpeg-decoder-dsd.aar").get().asFile
val media3FfmpegLocalJni =
    rootProject.file("third_party/media3-ffmpeg-decoder/src/main/jniLibs/arm64-v8a/libffmpegJNI.so")
val hasDsdFfmpeg = media3FfmpegLocalAar.exists() ||
    media3FfmpegGeneratedAar.exists() ||
    media3FfmpegLocalJni.exists()

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        load(keystorePropertiesFile.inputStream())
    }
}

fun readReleaseSigningEnv(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }

fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val updateDomesticManifestUrl = providers.gradleProperty("mica.update.domesticUrl")
    .orNull
    .orEmpty()
val updateInternationalManifestUrl = providers.gradleProperty("mica.update.internationalUrl")
    .orNull
    ?.takeIf { it.isNotBlank() }
    ?: "https://lecoix.github.io/mica-music/update.json"

android {
    namespace = "com.mica.music"
    compileSdk = 35

    defaultConfig {
        applicationId = if (qaSideBySide) "com.mica.music.qa" else "com.mica.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 53
        versionName = "0.3.1" + if (qaSideBySide) "-qa" else ""
        buildConfigField(
            "String",
            "UPDATE_DOMESTIC_MANIFEST_URL",
            buildConfigString(updateDomesticManifestUrl),
        )
        buildConfigField(
            "String",
            "UPDATE_INTERNATIONAL_MANIFEST_URL",
            buildConfigString(updateInternationalManifestUrl),
        )
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // Package both supported ABIs; each self-owned native library must exist for both.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    splits {
        abi {
            isEnable = abiSplitApks
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        getByName("androidTest").assets.srcDir(file("schemas"))
    }

    signingConfigs {
        create("release") {
            val ciKeystoreFile = readReleaseSigningEnv("MICA_KEYSTORE_FILE")?.let(::file)
            when {
                ciKeystoreFile?.exists() == true -> {
                    storeFile = ciKeystoreFile
                    storePassword = readReleaseSigningEnv("MICA_KEYSTORE_PASSWORD")
                    keyAlias = readReleaseSigningEnv("MICA_KEY_ALIAS")
                    keyPassword = readReleaseSigningEnv("MICA_KEY_PASSWORD")
                }
                keystorePropertiesFile.exists() -> {
                    storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")
                ?.takeIf { it.storeFile?.exists() == true }
                ?.let { signingConfig = it }
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
        buildConfig = true
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
    implementation(libs.androidx.media)

    when {
        media3FfmpegLocalAar.exists() -> implementation(files(media3FfmpegLocalAar))
        media3FfmpegGeneratedAar.exists() -> implementation(files(media3FfmpegGeneratedAar))
        media3FfmpegLocalJni.exists() -> implementation(project(":media3-ffmpeg-decoder-dsd"))
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
    implementation(project(":taglib"))
    implementation(project(":sylvakru-usb-transport"))
    implementation(libs.jaudiotagger)
    implementation(libs.blurview)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(project(":usb-sk02-native-prototype"))

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

    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
}

tasks.matching { it.name == "preReleaseBuild" || it.name == "prePerfBuild" }.configureEach {
    doFirst {
        check(hasDsdFfmpeg) {
            """
            |DSD-enabled Media3 FFmpeg is required for release and perf builds.
            |Run: .\scripts\build-media3-ffmpeg-dsd.ps1
            """.trimMargin()
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
    systemProperty("mica.screenshotGolden", fullScreenshotsRequested.toString())
}

tasks.register("micaNightlyCheck") {
    group = "verification"
    description = "Runs compile, lint, all JVM/Robolectric tests, full screenshots, and nightly fuzzing."
    dependsOn(
        "micaCheck",
        "micaScreenshotFull",
    )
}
