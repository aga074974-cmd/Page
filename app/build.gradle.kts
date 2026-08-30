plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ir.page.persianocr"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.page.persianocr"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABIهایی که کتابخانه‌های بومی Tesseract و OpenCV برایشان ساخته شده‌اند.
        // x86 (۳۲ بیتی) کنار گذاشته شده — عملاً هیچ دستگاه امروزی از آن استفاده نمی‌کند.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // یک APK جداگانه به ازای هر معماری.
    //
    // کتابخانه‌های بومی OpenCV و Tesseract روی هم برای هر ABI ده‌ها مگابایت‌اند؛ یک APK
    // «یونیورسال» که هر سه را داشته باشد حدود ۱۸۷ مگابایت می‌شود و دانلودش روی گوشی
    // عملاً غیرممکن است. با split، هر دستگاه فقط کتابخانهٔ خودش را می‌گیرد
    // (arm64-v8a ≈ ۵۰ مگابایت).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            // نسخهٔ یونیورسال ساخته نمی‌شود؛ اگر لازمش داشتید این را true کنید.
            isUniversalApk = false
        }
    }

    // فایل‌های traineddata نباید فشرده شوند؛ در غیر این صورت کپی از assets کند و پرحافظه می‌شود.
    androidResources {
        noCompress += listOf("traineddata")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        // OpenCV و Tesseract هر دو ممکن است لایسنس‌های تکراری بیاورند.
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    // موتور OCR — Tesseract 5 (چندنخی / OpenMP)
    implementation(libs.tesseract4android)

    // پیش‌پردازش تصویر
    implementation(libs.opencv)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
