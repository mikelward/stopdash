// The repo's own lint checks (AGENTS.md *Main thread: read and dispatch only*), run by :app's lint
// like the platform's: a plain JVM module lint loads from :app's `lintChecks`.
plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    compileOnly(libs.lint.api)

    testImplementation(libs.junit)
    testImplementation(libs.lint.api)
    testImplementation(libs.lint.tests)
}
