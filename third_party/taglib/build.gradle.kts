plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.kyant.taglib"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    lint {
        checkReleaseBuilds = false
    }
}

kotlin {
    explicitApi()
    jvmToolchain(17)
}

dependencies {
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
