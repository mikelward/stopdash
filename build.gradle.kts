plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Applied by :domain, so the repo's lint checks reach its suspend functions too.
    alias(libs.plugins.android.lint) apply false
    // Applied by :app to export the open-source attribution graph. Declared here
    // so its classpath is pinned once for the build.
    alias(libs.plugins.aboutlibraries) apply false
    // Applied by :domain; its presence here also pins the Kotlin Gradle Plugin
    // AGP's built-in Kotlin compiles with, so the Compose compiler plugin (pinned to
    // the same `kotlin` version) can't drift from the baseline AGP would pick.
    alias(libs.plugins.kotlin.jvm) apply false
    // Declared here so their version rides `kotlin` in the catalog: the Compose
    // and serialization compiler plugins must match the Kotlin version exactly.
    // Compose is applied by :app; serialization by :app and :shared.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    // Applied by :app only when its (untracked) google-services.json exists;
    // declared here so the classpath is pinned once for the build.
    alias(libs.plugins.google.services) apply false
    alias(libs.plugins.firebase.crashlytics) apply false
}
