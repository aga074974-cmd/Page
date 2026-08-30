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
        // Tesseract4Android روی Maven Central منتشر نمی‌شود؛ فقط از JitPack می‌آید.
        // Tesseract4Android is published on JitPack only — this repo is REQUIRED.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "PersianOCR"
include(":app")
