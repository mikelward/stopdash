plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase (Crashlytics + Analytics) activates per build: these plugins wire the config and the
// mapping upload only when the untracked google-services.json is present, so fresh clones and CI's
// test lanes build with Firebase dormant (dev-docs/firebase.md). Collection is then still off until
// the user opts in (SPEC *Privacy*). Mirrors mikelward/simmo.
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.google.services.get().pluginId)
    apply(plugin = libs.plugins.firebase.crashlytics.get().pluginId)
    // The debug variant never gets Firebase: a build nobody installs has no business filing crashes
    // or analytics beside real data, and the test suites run against it. Disabling the tasks isn't
    // enough — Gradle keeps a disabled task's earlier output — so the generated resources are
    // purged ahead of the merge too, or an old google_app_id would still start Firebase.
    val purgeDebugFirebaseResources = tasks.register<Delete>("purgeDebugGoogleServicesResources") {
        description = "Deletes Firebase resources generated for the debug variant, which ships without them."
        delete(
            layout.buildDirectory.dir("generated/res/processDebugGoogleServices"),
            layout.buildDirectory.dir("generated/res/google-services/debug"),
        )
    }
    afterEvaluate {
        tasks.matching {
            it.name in setOf(
                "processDebugGoogleServices",
                "injectCrashlyticsMappingFileIdDebug",
                "uploadCrashlyticsMappingFileDebug",
            )
        }.configureEach { enabled = false }
        tasks.matching { it.name == "mergeDebugResources" }.configureEach {
            dependsOn(purgeDebugFirebaseResources)
        }
    }
}

fun gitOutput(vararg args: String): String? =
    try {
        // No isIgnoreExitValue: a nonzero exit — a source archive with no .git,
        // where rev-list/rev-parse exit 128 — must throw so the catch runs and
        // warns. Ignoring the exit left that common failure path silent, with
        // the warning below unreachable.
        val output = providers.exec {
            commandLine("git", *args)
        }.standardOutput.asText.get().trim()
        output.ifEmpty { null }
    } catch (e: Exception) {
        // Don't fail configuration on a source-archive/no-git build, but don't
        // be silent either: a fallback versionCode/SHA is fine for a debug build
        // and wrong for a release one, which checkReleaseVersion (below) fails.
        logger.warn("git ${args.joinToString(" ")} failed (${e.message}); using a fallback version")
        null
    }

// Monotonic versionCode as long as main only moves forward; Play rejects an
// AAB whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 so the count isn't truncated by a shallow clone.
val gitCommitCount: Int? = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
val gitShortSha: String? = gitOutput("rev-parse", "--short", "HEAD")
// A shallow clone counts only the commits it holds: a versionCode below main's real one.
val gitShallow: Boolean = gitOutput("rev-parse", "--is-shallow-repository") == "true"
val baseVersionName = "0.1"

// A shipped build's versionCode has to be main's real commit count: Play rejects one that
// isn't above the last upload, and the deploy job tags its GitHub prerelease with it. So a
// release build fails, at its first task, when git couldn't give the count (a source
// archive) or gave a truncated one (a shallow clone), rather than ship versionCode 1 or a
// low count under a real-looking tag. A debug build takes the fallback, with the warning
// above. CI checks both failures, and its release jobs show a full clone passing.
val releaseVersionProblem: String? = when {
    gitCommitCount == null || gitShortSha == null ->
        "git couldn't read the history (a source archive, or git missing)"
    gitShallow -> "the clone is shallow, so its commit count is short (run git fetch --unshallow)"
    else -> null
}
val releaseVersionCheck = tasks.register("checkReleaseVersion") {
    description = "Fails a release build whose versionCode didn't come from the full git history."
    val problem = releaseVersionProblem
    doLast {
        check(problem == null) { "A release build needs its versionCode from the full git history: $problem." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(releaseVersionCheck) }

// The four release-keystore variables, normalized once: blank is absent, so a
// whitespace-only secret can't slip past the all-or-none guard and attach an
// empty signing config that fails deep inside AGP.
fun releaseKeystoreEnv(name: String): String? =
    providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

val releaseKeystorePath = releaseKeystoreEnv("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword = releaseKeystoreEnv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseKeystoreEnv("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseKeystoreEnv("RELEASE_KEY_PASSWORD")

val anyReleaseKeystoreVarSet = releaseKeystorePath != null || releaseKeystorePassword != null ||
    releaseKeyAlias != null || releaseKeyPassword != null
val releaseSigningConfigured = releaseKeystorePath != null && releaseKeystorePassword != null &&
    releaseKeyAlias != null && releaseKeyPassword != null

// Which launcher icon and name each build wears, so a phone carrying both the
// Play build and a debug build says which is which from the home screen and the
// app list (the debug build co-installs as `.debug`). Keyed on the build type
// alone, as in the sibling apps. The shipping label stays a string resource so it
// can be localized; the badged one is a literal that never reaches a store
// listing. The in-app header keeps @string/app_name: it is baked into screenshots.
val releaseLauncherIcon = "@mipmap/ic_launcher"
val debugLauncherIcon = "@mipmap/ic_launcher_debug"
val releaseAppLabel = "@string/app_name"
val debugAppLabel = "StopDash Debug"

android {
    namespace = "app.stopdash"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.stopdash"
        // minSdk 34 (Android 14) is the device floor across the sibling fleet;
        // the lock-screen placement (Android 16 QPR) is the OS deciding a
        // standard widget is eligible, not a separate code path (SPEC).
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount ?: 1
        versionName = "$baseVersionName.${gitCommitCount ?: 1}+${gitShortSha ?: "unknown"}"
    }

    signingConfigs {
        // CI materializes the Play upload keystore from a secret; Play App
        // Signing re-signs before delivery. Local builds without the secrets
        // produce an unsigned release AAB, so forks and fresh clones build
        // cleanly. All four vars, or none.
        create("release") {
            if (anyReleaseKeystoreVarSet && !releaseSigningConfigured) {
                error(
                    "Partial release-keystore configuration. Set all of RELEASE_KEYSTORE_FILE, " +
                        "RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD — or none, " +
                        "to build unsigned.",
                )
            }
            if (releaseSigningConfigured) {
                val keystore = file(releaseKeystorePath!!)
                check(keystore.exists()) {
                    "RELEASE_KEYSTORE_FILE is set but does not exist: ${keystore.path}"
                }
                storeFile = keystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Always, not just in CI: the release build is the artifact that
            // ships, so it should be the artifact anyone can reproduce (R8
            // bugs live in reflection/serialization/enum handling, which this
            // app uses for its persisted state).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Only the release build is a real Play app, so only it checks Play for an
            // available update (drives the overflow "update available" dot).
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "true")
            manifestPlaceholders["launcherIcon"] = releaseLauncherIcon
            manifestPlaceholders["appLabel"] = releaseAppLabel
        }
        debug {
            // Suffixed so a debug build co-installs beside a release-signed
            // build instead of colliding on the package name.
            applicationIdSuffix = ".debug"
            // The `.debug` applicationId isn't a Play app, so an update check there only
            // ever fails — never run it (PlayUpdateChecker gates on this).
            buildConfigField("boolean", "PLAY_UPDATE_CHECKS_ENABLED", "false")
            manifestPlaceholders["launcherIcon"] = debugLauncherIcon
            manifestPlaceholders["appLabel"] = debugAppLabel
        }
    }

    buildFeatures {
        compose = true
        // VERSION_NAME for the About screen — a compile-time constant, so the
        // version never needs a PackageManager IPC on a composition path.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // Lint's test-source passes feed a report nothing consumes: a real
        // defect in a test surfaces as a failing test, not a warning.
        ignoreTestSources = true
        // MainThreadWork's existing sites, each one to move off the main thread (TODO.md); a new one
        // fails the build. Holds no other check's issues.
        baseline = file("lint-baseline.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    // Robolectric 4.17's SDK 36 sandbox reads FileDescriptor internals through
    // jdk.internal.access.SharedSecrets, which java.base doesn't export; without this
    // every Robolectric test fails in setup with IllegalAccessException.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// AboutLibraries' Android auto-integration needs the legacy AppExtension that AGP 9
// removed, so the plugin can't generate res/raw for us at build time. Instead we commit
// the export as a resource and regenerate it on demand with
// `./gradlew :app:exportBundledLicenses` (see below). The Licenses screen reads the
// committed R.raw.aboutlibraries at runtime.
aboutLibraries {
    // Scope the export to the release variant, dropping test/debug-only artifacts (JUnit,
    // Robolectric, Roborazzi, Compose tooling); includePlatform = false drops BOM/platform
    // POMs (Compose, coroutines, serialization) that carry no runtime artifact.
    collect {
        filterVariants.add("release")
        includePlatform = false
    }
    export {
        outputFile = file("src/main/res/raw/aboutlibraries.json")
        prettyPrint = true
        // Drop the full SPDX license text: it's resolved from a network-fetched SPDX list
        // whose exact wording varies by environment, so committing it would make the
        // regenerate-and-diff check non-deterministic. The screen still shows each license's
        // name, SPDX id, and URL.
        excludeFields.add("License.content")
    }
}

// The plugin walks the dependency *graph*, so its export still lists nodes that resolve to
// no bundled artifact: Kotlin-Multiplatform metadata coordinates (e.g. androidx.compose.ui:ui,
// which selects …:ui-android) and org.jetbrains.compose redirect modules that alias to the
// androidx artifacts on Android. Both would render as duplicate rows. This task regenerates the
// export and then keeps only the coordinates that resolve to an actual artifact on the release
// runtime classpath — i.e. what's really bundled in the APK. Regenerate with
// `./gradlew :app:exportBundledLicenses`.
@Suppress("UNCHECKED_CAST")
val exportBundledLicenses = tasks.register("exportBundledLicenses") {
    description = "Exports open-source attributions filtered to the release APK's bundled artifacts."
    group = "build"
    dependsOn("exportLibraryDefinitions")
    val licensesFile = file("src/main/res/raw/aboutlibraries.json")
    val runtimeClasspath = configurations.named("releaseRuntimeClasspath")
    doLast {
        val bundled = runtimeClasspath.get().incoming
            .artifactView { lenient(true) }.artifacts.artifacts
            .mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }
            .map { "${it.moduleIdentifier.group}:${it.moduleIdentifier.name}" }
            .toSet()
        val root = groovy.json.JsonSlurper().parse(licensesFile) as MutableMap<String, Any?>
        val libraries = root["libraries"] as List<Map<String, Any?>>
        val kept = libraries.filter { (it["uniqueId"] as String) in bundled }
        root["libraries"] = kept
        // Prune any license no longer referenced by a kept library.
        val used = kept.flatMap { (it["licenses"] as? List<String>).orEmpty() }.toSet()
        (root["licenses"] as? MutableMap<String, Any?>)?.keys?.retainAll(used)
        licensesFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(root)) + "\n")
    }
}

dependencies {
    // The repo's own lint checks: MainThreadWork (AGENTS.md *Main thread: read and dispatch only*).
    lintChecks(project(":lint-rules"))
    implementation(project(":domain"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore)
    // Glance: the home-screen (and, where the OS allows, lock-screen) widget. It renders
    // the persisted departures snapshot; GlanceTheme (glance core) gives light/dark colors.
    implementation(libs.androidx.glance.appwidget)
    // Reads the committed res/raw/aboutlibraries.json at runtime for the Licenses screen
    // (only the JSON reader / Libs model — the stock list UI is not used).
    implementation(libs.aboutlibraries.compose.m3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // WorkManager schedules the widget's one-shot staleness-boundary redraw (SPEC D4): a single
    // deferrable wake per snapshot flips a widget left untouched after the app closes to the
    // stale treatment, since updatePeriodMillis="0" means the host never re-renders it.
    implementation(libs.androidx.work.runtime)

    // Google Play In-App Update: read-only here — checks whether an update is available to
    // drive the overflow "update available" dot. Free, no runtime cost on any hot path (a
    // background Play `Task`, release-only), and it degrades to "no update" if Play is absent.
    implementation(libs.play.app.update)
    // The Wear OS sync (dev-docs/wear-os.md): the Data Layer, and awaiting its Tasks.
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
    // Opens StopDash's Play Store page on a watch that doesn't have it yet (WatchInstall).
    implementation(libs.androidx.wear.remote.interactions)

    // The shared on-device debug log, mikelward/androidlog — resolved from the
    // Maven repository declared in settings.gradle.kts. `logging-android`
    // carries the parts that need a `Context`; `logging-core` is the buffer
    // and the value rules.
    implementation(libs.androidlog.logging.core)
    implementation(libs.androidlog.logging.android)
    // Crash reporting and usage analytics, compiled in but inert unless the build had a
    // google-services.json (Firebase never initializes otherwise) and the user has opted in.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // TfL client: Ktor with the OkHttp engine + kotlinx.serialization content
    // negotiation, mirroring clothescast. MockEngine (below) tests the client
    // against recorded fixtures with no live network (SPEC *Testing*).
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // Drives the widget staleness-redraw scheduler test on an in-memory WorkManager.
    testImplementation(libs.androidx.work.testing)

    // Robolectric + Roborazzi drive the Compose screenshot tests (SPEC *Testing*):
    // they render MainScreen in each state to a PNG on the JVM, no emulator.
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    // Glance widget coverage, two complementary forms: the glance-testing harness asserts
    // the emitted layout nodes (text, structure) per state (WidgetContentTest), and Roborazzi
    // pixel-captures the widget by rendering it to RemoteViews and inflating them to a View
    // (WidgetScreenshotTest), catching clipping/sizing/color the node assertions can't see.
    testImplementation(libs.androidx.glance.testing)
    testImplementation(libs.androidx.glance.appwidget.testing)
    // Declares the activity `createAndroidComposeRule<ComponentActivity>` launches.
    // Debug-only, and safe because unit tests run on the debug variant alone here.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
