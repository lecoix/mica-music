plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// assets 里的 ffmpeg → jniLibs（lib*.so），安装后位于 nativeLibraryDir 才可 exec
val ffmpegJniDir = layout.buildDirectory.dir("generated/ffmpeg-jni")
val ffmpegAsset = file("src/main/assets/ffmpeg/arm64-v8a/ffmpeg")
val syncFfmpegNative = tasks.register<Copy>("syncFfmpegNative") {
    onlyIf { ffmpegAsset.exists() }
    from(ffmpegAsset)
    into(ffmpegJniDir.map { it.dir("arm64-v8a") })
    rename { "libmica_ffmpeg.so" }
}

android {
    namespace = "com.mica.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mica.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.1"
        ndk {
            // 仅 64 位真机；自编 FFmpeg 也只编 arm64-v8a
            abiFilters += listOf("arm64-v8a")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(ffmpegJniDir)
        }
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
            isDebuggable = false
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

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.reorderable)

    debugImplementation(libs.androidx.ui.tooling)
}

tasks.named("preBuild") {
    dependsOn(syncFfmpegNative)
    doFirst {
        if (!ffmpegAsset.exists()) {
            logger.warn(
                """
                |
                | *** FFmpeg binary missing: ${ffmpegAsset.absolutePath}
                | *** Run: .\scripts\build-ffmpeg-arm64.ps1
                | *** Then rebuild APK. Playback will fail until then.
                |
                """.trimMargin(),
            )
        }
    }
}
