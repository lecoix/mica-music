pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "Mica"
include(":app")
include(":taglib")
project(":taglib").projectDir = file("third_party/taglib")
include(":media3-ffmpeg-decoder-dsd")
project(":media3-ffmpeg-decoder-dsd").projectDir = file("third_party/media3-ffmpeg-decoder")
