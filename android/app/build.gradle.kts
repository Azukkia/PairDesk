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
        versionCode = 10301
        versionName = "1.3.1"
    }

    signingConfigs {
        // The release key comes from the environment (CI: repository secrets,
        // see .github/scripts/android-signing.sh). Without it, release builds
        // are signed with the debug key so that local and pull request builds
        // still produce an installable APK.
        val keystore = providers.environmentVariable("PAIRDESK_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
        if (keystore != null) {
            fun env(name: String): String = providers.environmentVariable(name).orNull?.takeIf { it.isNotEmpty() }
                ?: throw GradleException("PAIRDESK_KEYSTORE_FILE is set but $name is missing")
            create("release") {
                storeFile = file(keystore)
                storePassword = env("PAIRDESK_KEYSTORE_PASSWORD")
                keyAlias = env("PAIRDESK_KEY_ALIAS")
                keyPassword = env("PAIRDESK_KEY_PASSWORD")
            }
        }
    }

    // Two editions of the same application (same id and signing key: one
    // installs over the other). Google Play Protect blocks any app installed
    // from outside a store that declares an accessibility service ("enhanced
    // fraud protection"), whatever it does with it:
    //  - standard (PairDesk.apk): no accessibility service. Controls a
    //    computer, phone camera as a webcam, phone screen shown on a computer.
    //  - full (PairDesk-full.apk): with it, a computer can also control the
    //    phone. Play Protect has to be paused to install it.
    flavorDimensions += "edition"
    productFlavors {
        create("standard") {
            dimension = "edition"
            buildConfigField("boolean", "PHONE_CONTROL", "false")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "PHONE_CONTROL", "true")
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // R8 is off: the org.webrtc JNI classes need keep rules, and a
            // minified APK cannot be tested without a device here.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
