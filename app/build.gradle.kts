import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.tinkercobble.ham"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.tinkercobble.ham"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    flavorDimensions += "channel"
    productFlavors {
        create("dev") {
            dimension = "channel"
            applicationIdSuffix = ".dev"
            // The public DEV signer rotates on Android 9+; older platforms cannot verify it.
            minSdk = 28
            versionCode = 13
            versionName = "0.1.12-dev"
            resValue("string", "app_name", "HAM DEV")
        }
        create("stableDirect") {
            dimension = "channel"
            resValue("string", "app_name", "HAM")
        }
        create("stablePlay") {
            dimension = "channel"
            resValue("string", "app_name", "HAM")
        }
        create("stableFdroid") {
            dimension = "channel"
            resValue("string", "app_name", "HAM")
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release { isMinifyEnabled = true; isShrinkResources = true }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.okhttp)
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    testImplementation(libs.junit)
}
