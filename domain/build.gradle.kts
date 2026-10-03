// The Android-free product logic (SPEC *Testing*): a plain Kotlin/JVM module, so the phone app and
// the Wear OS app (dev-docs/wear-os.md) build rows and staleness from the same code, and its tests
// run on the JVM with no Android in the way.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    // Lint for a plain JVM module: the repo's MainThreadWork check holds a suspend function to
    // hopping before it works.
    alias(libs.plugins.android.lint)
}

lint {
    // MainThreadWork's existing sites, each one to move off the main thread (TODO.md); a new one
    // fails the build. Holds no other check's issues.
    baseline = file("lint-baseline.xml")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    lintChecks(project(":lint-rules"))
    // Public signatures carry Flow and CoroutineDispatcher, so consumers see coroutines too.
    api(libs.kotlinx.coroutines.core)
    // @WorkerThread, which MainThreadWork reads: a function only ever called off the main thread.
    implementation(libs.androidx.annotation)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
