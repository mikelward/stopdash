// The Android-free product logic (SPEC *Testing*): a plain Kotlin/JVM module, so the phone app and
// the Wear OS app (dev-docs/wear-os.md) build rows and staleness from the same code, and its tests
// run on the JVM with no Android in the way.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Public signatures carry Flow and CoroutineDispatcher, so consumers see coroutines too.
    api(libs.kotlinx.coroutines.core)
    // @WorkerThread marks the work a screen may not call from composition (lint's WorkerThreadCall).
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// AnalyticsDefinitionsTest reads the definitions the registration script registers: a change to them alone
// runs the tests again rather than passing as up to date.
tasks.test {
    inputs.file(rootProject.file("dev-docs/analytics-definitions.tsv"))
}
