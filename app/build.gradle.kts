import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.eternalliquet.bamboobd2.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.eternalliquet.bamboobd2"
        // Provisional product baseline: docs/ANDROID_MILESTONE_1.md, "Platform baseline".
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric reaches JDK internals for file descriptors on JDK 17+.
            test.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
            )
            // Optional override for where Robolectric downloads its Android
            // runtime jars (default: Maven Central). Set per environment.
            providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
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
    implementation("io.github.eternalliquet.bamboobd2:core")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// F11, revised for the Bluetooth Classic transport stage (#12): the merged manifest
// requests exactly the reviewed Bluetooth set below and nothing else that reaches
// outside the phone. No Internet, location, scanning, discovery or advertising.
androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val task = tasks.register("verifyNoForbiddenPermissions$name") {
            group = "verification"
            description = "Fails if the merged $name manifest requests forbidden permissions."
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val tags = Regex("""<uses-permission(?:-sdk-23)?\b[^>]*>""").findAll(text).map { it.value }.toList()
                val requested = tags.map { Regex("""android:name="([^"]+)"""").find(it)!!.groupValues[1] }
                // Each allowed Bluetooth permission with the maxSdkVersion it must carry (null: none).
                val allowedBluetooth = mapOf(
                    "android.permission.BLUETOOTH" to "30",
                    "android.permission.BLUETOOTH_CONNECT" to null,
                )
                val problems = mutableListOf<String>()
                tags.zip(requested).forEach { (tag, p) ->
                    val maxSdk = Regex("""android:maxSdkVersion="(\d+)"""").find(tag)?.groupValues?.get(1)
                    when {
                        p in allowedBluetooth -> if (maxSdk != allowedBluetooth[p]) problems += "$p must have maxSdkVersion ${allowedBluetooth[p]}, has $maxSdk"
                        p.contains("BLUETOOTH") || p.contains("LOCATION") || p == "android.permission.INTERNET" ||
                            p == "android.permission.NEARBY_WIFI_DEVICES" -> problems += "forbidden: $p"
                    }
                }
                allowedBluetooth.keys.filter { it !in requested }.forEach { problems += "expected but missing: $it" }
                check(problems.isEmpty()) { "Merged manifest permissions: ${problems.joinToString("; ")}" }
                logger.lifecycle("Merged $name manifest permissions: ${requested.ifEmpty { listOf("none") }}")
            }
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(task) }
    }
}

// Name kept from milestone 1 so existing commands still work; it now checks the revised F11 set.
tasks.register("verifyNoForbiddenPermissions") {
    group = "verification"
    dependsOn("verifyNoForbiddenPermissionsDebug", "verifyNoForbiddenPermissionsRelease")
}
