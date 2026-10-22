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

// CI's unit test, lint and screenshot runs set -PstopdashFixedVersion (as
// ORG_GRADLE_PROJECT_stopdashFixedVersion): a per-commit versionCode or versionName lands in
// the merged manifest those tasks read, so without it every commit misses the build cache
// for all of :app. Anything installed or shipped is built without it.
val fixedTestVersion: Boolean = providers.gradleProperty("stopdashFixedVersion").isPresent
// Names a debug build's branch: the PR's head branch on a pull request (its checkout is a
// detached merge commit), the pushed branch on main, git's own answer locally.
val buildBranch: String = (
    providers.environmentVariable("GITHUB_HEAD_REF").orNull?.ifEmpty { null }
        ?: providers.environmentVariable("GITHUB_REF_NAME").orNull?.ifEmpty { null }
        ?: gitOutput("rev-parse", "--abbrev-ref", "HEAD")
        ?: "unknown"
    ).replace(Regex("[^A-Za-z0-9._/-]"), "-").take(30)

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
// The fixed test version must never reach an installable release: packaging refuses it, while
// release unit tests (which also pass preReleaseBuild) still run with it.
val releaseFixedVersionCheck = tasks.register("checkReleaseNotFixedVersion") {
    description = "Fails packaging a release build while -PstopdashFixedVersion is set."
    val fixed = fixedTestVersion
    doLast {
        check(!fixed) { "A release build can't be packaged with -PstopdashFixedVersion: its versionCode is a placeholder." }
    }
}
tasks.matching { it.name in setOf("packageRelease", "packageReleaseBundle", "bundleRelease") }
    .configureEach { dependsOn(releaseFixedVersionCheck) }

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
        versionCode = if (fixedTestVersion) 1 else gitCommitCount ?: 1
        versionName = if (fixedTestVersion) {
            "test"
        } else {
            "${gitCommitCount ?: 1}+${gitShortSha ?: "unknown"}"
        }
        // stopdash's proxy (dev-docs/proxy.md), a public address, not a
        // secret: set in gradle.properties once it's deployed. Empty leaves TfL's link-only National
        // Rail statuses as they are.
        val proxyUrl = providers.gradleProperty("stopdash.proxyUrl").getOrElse("").trim()
        require(proxyUrl.isEmpty() || proxyUrl.startsWith("https://")) { "stopdash.proxyUrl must be an https:// address" }
        buildConfigField("String", "PROXY_URL", "\"$proxyUrl\"")
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

    androidResources {
        // Lists the translated languages in the system's per-app language setting, from the
        // values-<lang> folders (res/resources.properties names the base's locale).
        generateLocaleConfig = true
        // The station bundle's sources (stationBundle, below): the APK carries the bundle built from
        // them, and the app reads nothing else for stations and stops.
        ignoreAssetsPatterns += listOf("!stops.tsv", "!station_index.json")
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
        // WorkerThreadCall's existing sites, to move off the main thread one at a time; a new one
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
    // Source-text and build-script tests (LauncherIconBadgeTest, MenuStyleTest,
    // WidgetSourceRulesTest, EnGbStringsTest, …) read these directly from the working directory:
    // track them as task inputs so the build cache invalidates on any edit to them.
    inputs.file(file("build.gradle.kts")).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(file("src/main")).withPathSensitivity(PathSensitivity.RELATIVE)
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
        // Recording writes PNGs into src/test/snapshots/images outside Gradle's tracked outputs
        // (after CI empties that directory), so a cached or up-to-date skip would leave it empty.
        outputs.upToDateWhen { false }
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
        outputs.upToDateWhen { false }
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
// no bundled artifact, and org.jetbrains.compose redirect modules that alias to the androidx
// artifacts on Android, which would render as duplicate rows. A Kotlin-Multiplatform library
// is listed once, by its root coordinate (androidx.compose.ui:ui for the bundled ui-android).
// This task regenerates the export and then keeps only the coordinates that resolve to an
// actual artifact on the release runtime classpath — i.e. what's really bundled in the APK.
// Regenerate with `./gradlew :app:exportBundledLicenses`.
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
            // The plugin lists a Kotlin-Multiplatform library once, by its root coordinate
            // (androidx.compose.ui:ui), where the APK bundles its platform artifact (ui-android).
            .flatMap { listOf(it, it.removeSuffix("-android"), it.removeSuffix("-jvm")) }
            .toSet()
        val root = groovy.json.JsonSlurper().parse(licensesFile) as MutableMap<String, Any?>
        val libraries = root["libraries"] as List<Map<String, Any?>>
        val ids = libraries.map { it["uniqueId"] as String }.filter { it in bundled }.toSet()
        // An org.jetbrains.compose module that redirects to its androidx namesake on Android is
        // that library under a second name, so it's left to the androidx row.
        val kept = libraries.filter {
            val id = it["uniqueId"] as String
            id in bundled && !(id.startsWith("org.jetbrains.compose.") && id.replaceFirst("org.jetbrains.compose.", "androidx.compose.") in ids)
        }
        root["libraries"] = kept
        // Prune any license no longer referenced by a kept library.
        val used = kept.flatMap { (it["licenses"] as? List<String>).orEmpty() }.toSet()
        (root["licenses"] as? MutableMap<String, Any?>)?.keys?.retainAll(used)
        licensesFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(root)) + "\n")
    }
}

// The bundled stops and the station model built over them (StationBundle, maintainer 2026-10-19),
// worked out here by :domain's own readers rather than as the app starts, and shipped as one binary
// asset beside the source assets: the app reads it back, and the source files only where it can't.
abstract class StationBundleTask : JavaExec() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stops: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val stationIndex: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val stationBundleTool: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
    }
}

val stationBundle = tasks.register<StationBundleTask>("stationBundle") {
    description = "Builds the bundled stops and station model into one binary asset."
    val stopsFile = layout.projectDirectory.file("src/main/assets/stations/stops.tsv")
    val indexFile = layout.projectDirectory.file("src/main/assets/stations/station_index.json")
    val outDir = layout.buildDirectory.dir("generated/stationBundle")
    stops.set(stopsFile)
    stationIndex.set(indexFile)
    outputDir.set(outDir)
    classpath = stationBundleTool
    mainClass.set("app.stopdash.domain.StationBundle")
    args(
        stopsFile.asFile.path,
        indexFile.asFile.path,
        outDir.get().file("stations/station_bundle.bin").asFile.path,
    )
}

// A debug build names its commit and branch, so a sideloaded APK or a bug report from one says
// which build it is; the shipped release keeps its plain "<count>+<sha>".
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        if (!fixedTestVersion) {
            val name = "${gitCommitCount ?: 1}-debug+$buildBranch@${gitShortSha ?: "unknown"}"
            variant.outputs.forEach { it.versionName.set(name) }
        }
    }
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(stationBundle, StationBundleTask::outputDir)
    }
}

dependencies {
    // The repo's own lint check: WorkerThreadCall (AGENTS.md *Main thread: read and dispatch only*).
    lintChecks(project(":lint-rules"))
    implementation(project(":domain"))
    stationBundleTool(project(":domain"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    // The dispatcher every Back handler runs on, which activity-compose brings at 1.0. Declared to hold
    // it at the version Navigation 3 needs (dev-docs/navigation3.md), so the app ships what its tests run on.
    implementation(libs.androidx.navigationevent.compose)
    // Navigation 3: the back stack the app's pages are on (dev-docs/navigation3.md), whose Back draws the page
    // under the one being swiped. About 113 KB in the release APK with R8 on.
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
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
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    // A local HTTP/2 server, so the quiet-connection test runs OkHttp's own connection handling.
    testImplementation(libs.okhttp.mockwebserver3)
    // Drives the widget staleness-redraw scheduler test on an in-memory WorkManager.
    testImplementation(libs.androidx.work.testing)
    // Navigation 3's per-page view models, for its spike alone so far (Navigation3SpikeTest), which confirms
    // on this app's stack what the move onto it relies on (dev-docs/navigation3.md).
    testImplementation(libs.androidx.lifecycle.viewmodel.navigation3)

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
