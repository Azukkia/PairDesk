import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The PairDesk Android application: Compose UI, persistent identity and
// settings, the network layer (transport + signaling of :core) and the WebRTC
// layer (io.github.webrtc-sdk, the org.webrtc API).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.azukkia.pairdesk"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.azukkia.pairdesk"
        minSdk = 26
        targetSdk = 35
        // Announced to peers in intro / accepted (`appVersion`): the desktop
        // gates features on it, so it follows the desktop release that this
        // app is compatible with, not the package.json of the working tree.
        versionCode = 10200
        versionName = "1.2.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 is off for now (the org.webrtc JNI classes need keep rules).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // The app speaks English (values/) and French (values-fr/): library
        // strings follow, and Android 13+ offers the choice per app.
        localeFilters += setOf("en", "fr")
        generateLocaleConfig = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            test.useJUnitPlatform()
            // The desktop sources (src/shared/keymap.js…) that the Android tables must match.
            test.systemProperty("pairdesk.repoRoot", rootProject.projectDir.parentFile.absolutePath)
            test.testLogging {
                events("passed", "skipped", "failed")
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.webrtc.android)
    implementation(libs.paho.mqtt)
    implementation(libs.okhttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}
