import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.concurrent.TimeUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.tinkercobble.ham"
    compileSdk = 37
    defaultConfig {
        // Canonical package chosen by the owner; legacy .dev remains installed until migration acceptance.
        applicationId = "com.tinkercobble.ham"
        minSdk = 28
        targetSdk = 36
        versionCode = 164
        versionName = "0.3.48"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "HAM")
    }
    buildTypes {
        // A debug APK must never share the signed, data-bearing package identity.
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    buildFeatures { compose = true; buildConfig = true; resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

// AGP's connected runner manages package installation/removal on every attached
// target. Fail closed before it runs whenever a non-emulator is connected.
val verifyEmulatorOnly = tasks.register("verifyEmulatorOnly") {
    doLast {
        val sdkRoot = System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }
            ?: System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }
            ?: error("Android test target check requires an SDK path.")
        val adb = file(sdkRoot).resolve("platform-tools")
            .resolve(if (System.getProperty("os.name").startsWith("Windows")) "adb.exe" else "adb")
        check(adb.isFile) { "Android test target check could not find adb." }
        val process = ProcessBuilder(adb.absolutePath, "devices").redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Android test target check timed out.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "Android test target check failed." }
        val devices = output.lineSequence().drop(1).map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("*") }.toList()
        check(devices.isNotEmpty() && devices.all { it.matches(Regex("emulator-[0-9]+\\s+device")) }) {
            "Connected Android tests require only online disposable emulators; a physical or unavailable target is present."
        }
    }
}
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(verifyEmulatorOnly)
}

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
    implementation(libs.androidx.work.runtime.ktx)
    implementation("org.mnode.ical4j:ical4j:4.4.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
