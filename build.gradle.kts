// Android and Kotlin plugins share one classloader for the app build. The
// core build (core/) does not see this file.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
