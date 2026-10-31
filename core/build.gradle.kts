import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.animalsniffer)
}

group = "io.github.eternalliquet.bamboobd2"
version = "0.1.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

// G1 negative control: code that calls an API added after Android API 26. The
// API-26 signature check must reject it (see verifyApiNegativeControl).
val apiNegativeControl: SourceSet by sourceSets.creating

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    signature("${libs.gummy.bears.api26.get()}@signature")
}

animalsniffer {
    // Main code must pass; the negative control is checked separately below.
    sourceSets = listOf(project.sourceSets["main"], apiNegativeControl)
}

tasks.named<AnimalSniffer>("animalsnifferApiNegativeControl") {
    ignoreFailures = true
}

val verifyApiNegativeControl by tasks.registering {
    group = "verification"
    description = "Fails unless the API-26 check rejects the deliberate later-API call."
    val report = layout.buildDirectory.file("reports/animalsniffer/apiNegativeControl.text")
    dependsOn("animalsnifferApiNegativeControl")
    inputs.file(report)
    doLast {
        val text = report.get().asFile.readText()
        check("java.nio.file.Files.readString" in text) {
            "API-26 signature check did not flag the negative control:\n$text"
        }
    }
}

// G1 purity: no platform imports, java.* only in storage (plus UUID and
// System time in the default id/clock), and only pinned runtime dependencies.
val verifyCorePurity by tasks.registering {
    group = "verification"
    description = "Checks that :core stays platform-independent."
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    val runtime = configurations.runtimeClasspath
    inputs.files(sources)
    doLast {
        val forbidden = Regex("""^import\s+(android|androidx|com\.google\.android|java\.awt|javax\.swing)\.""")
        val javaImport = Regex("""^import\s+(java|javax)\.([\w.]+)""")
        val allowedJavaOutsideStorage = setOf("java.util.UUID")
        val problems = mutableListOf<String>()
        sources.forEach { file ->
            val inStorage = file.invariantSeparatorsPath.contains("/core/storage/")
            file.readLines().forEachIndexed { i, line ->
                val l = line.trim()
                if (forbidden.containsMatchIn(l)) problems += "${file.name}:${i + 1} platform import: $l"
                javaImport.find(l)?.let { m ->
                    val name = l.removePrefix("import ").trim()
                    if (!inStorage && name !in allowedJavaOutsideStorage) {
                        problems += "${file.name}:${i + 1} java.* outside storage: $l"
                    }
                }
            }
        }
        val allowedGroups = setOf("org.jetbrains.kotlin", "org.jetbrains.kotlinx", "org.jetbrains")
        runtime.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { it.group.isNotEmpty() && it.group !in allowedGroups && it.group != project.group.toString() }
            .forEach { problems += "unexpected runtime dependency: ${it.group}:${it.name}:${it.version}" }
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}

tasks.named("check") {
    dependsOn(verifyApiNegativeControl, verifyCorePurity)
}

apply(from = "../gradle/transport-registry.gradle.kts")

// Offline JVM tool only. No Android connection or imported command execution.
tasks.register<JavaExec>("offlineDiagnostics") {
    group = "application"
    description = "Preview the disabled identification plan, rehearse synthetic data, or inspect a local recording."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.github.eternalliquet.bamboobd2.core.storage.OfflineDiagnosticsMainKt")
    val mode = providers.gradleProperty("mode").orElse("plan")
    doFirst {
        args(mode.get())
        providers.gradleProperty("input").orNull?.let { args(it) }
        if (mode.get() == "capture") {
            args(providers.gradleProperty("protocol").get(), providers.gradleProperty("framing").get())
        }
    }
}
