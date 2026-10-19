// The Android-free product logic (SPEC *Testing*): a plain Kotlin/JVM module, so the phone app and
// the Wear OS app (dev-docs/wear-os.md) build rows and staleness from the same code, and its tests
// run on the JVM with no Android in the way.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Public signatures carry Flow and CoroutineDispatcher, so consumers see coroutines too.
    api(libs.kotlinx.coroutines.core)
    // @WorkerThread marks the work a screen may not call from composition (lint's WorkerThreadCall).
    implementation(libs.androidx.annotation)
    // The bundled station index's JSON (StationIndexFile), read here so the build's station bundle
    // reads it as the app does.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Tests that read files outside :domain (AnalyticsDefinitionsTest, BundledStopsTest, StationBundleTest,
// PlaceIndexTest, PlaceHitsTest): a change to any of them runs the tests again rather than passing as
// up to date or restoring from the build cache, and RELATIVE sensitivity keeps the cache key
// checkout-independent.
tasks.test {
    inputs.files(
        rootProject.file("dev-docs/analytics-definitions.tsv"),
        rootProject.file("app/src/main/assets/stations/stops.tsv"),
        rootProject.file("app/src/main/assets/stations/station_index.json"),
        rootProject.file("app/src/main/assets/places/place_index.tsv"),
        rootProject.file("scripts/build_place_index.py"),
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
