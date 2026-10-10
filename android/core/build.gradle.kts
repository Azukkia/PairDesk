import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM implementation of the PairDesk protocol: identities, PRS,
// CPace over ristretto255, key schedule, sealed messages, the signaling state
// machine and its transports (public MQTT relays, private PairDesk server).
// No Android API here, so everything is unit tested on the JVM.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.curve25519.elisabeth)
    implementation(libs.paho.mqtt)
    api(libs.okhttp) // WsTransport takes an optional OkHttpClient

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The repository root (protocol test vectors, Node interop peer).
val androidDir: File = rootProject.projectDir
val repoRoot: File = androidDir.parentFile

tasks.test {
    useJUnitPlatform()
    systemProperty("pairdesk.vectors", File(androidDir, "protocol-vectors.json").absolutePath)
    systemProperty("pairdesk.repoRoot", repoRoot.absolutePath)
    // Opt out of the Node interop tests with -Ppairdesk.skipInterop=true.
    systemProperty("pairdesk.skipInterop", (findProperty("pairdesk.skipInterop") ?: "false").toString())
    inputs.file(File(androidDir, "protocol-vectors.json")).withPropertyName("vectors").optional()
    // The interop tests run the desktop implementation: re-run them when it changes.
    inputs.files(
        File(repoRoot, "scripts/android-interop-peer.mjs"),
        fileTree(File(repoRoot, "src/main/signaling")),
        fileTree(File(repoRoot, "src/main/crypto")),
        File(repoRoot, "src/shared/protocol.js"),
        File(repoRoot, "server/src/server.js"),
        File(repoRoot, "pairdesk.config.json"),
    ).withPropertyName("desktopProtocolSources").withPathSensitivity(PathSensitivity.RELATIVE)
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = false
    }
}
