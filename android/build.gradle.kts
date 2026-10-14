// PairDesk for Android. Modules:
//  - :core  pure Kotlin/JVM implementation of the PairDesk protocol (see ../docs/PROTOCOL.md)
//  - :app   the Android application
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
