// The Wear OS companion app (dev-docs/wear-os.md): it renders the widget's snapshot, which the
// phone pushes over the Wearable Data Layer. It never calls TfL, holds no key and needs no location.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Data Layer only pairs apps with the same application ID (and signing key), so the watch
// app takes the phone's.
val watchApplicationId = "app.stopdash"

// The watch app ships in the phone's Play listing, and every bundle in one listing needs a
// versionCode of its own. The watch takes main's commit count, as the phone does, plus this
// offset, so the two never collide and both rise with every merge. Kept in step with
// app/build.gradle.kts, which reads the history the same way.
val watchVersionCodeOffset = 100_000_000

fun gitOutput(vararg args: String): String? =
    try {
        val output = providers.exec {
            commandLine("git", *args)
        }.standardOutput.asText.get().trim()
        output.ifEmpty { null }
    } catch (e: Exception) {
        // A source archive with no .git: fine for a debug build, refused for a release one below.
        logger.warn("git ${args.joinToString(" ")} failed (${e.message}); using a fallback version")
        null
    }

val gitCommitCount: Int? = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull()
val gitShortSha: String? = gitOutput("rev-parse", "--short", "HEAD")
val gitShallow: Boolean = gitOutput("rev-parse", "--is-shallow-repository") == "true"

// As for the phone: a shipped build's versionCode must come from main's full history, or Play
// rejects it (or it lands under a real-looking number it doesn't deserve).
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

// The phone's upload key: the Data Layer pairs only apps signed alike, and Play App Signing
// re-signs both. All four variables, or none (an unsigned local release build).
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

android {
    namespace = "app.stopdash.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = watchApplicationId
        // Wear OS 5 (Android 14), the fleet's API floor.
        minSdk = 34
        targetSdk = 36
        versionCode = watchVersionCodeOffset + (gitCommitCount ?: 1)
        versionName = "0.1.${gitCommitCount ?: 1}+${gitShortSha ?: "unknown"}"
    }

    signingConfigs {
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // Matches the phone's debug suffix, so a debug phone build pairs with a debug watch build.
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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

dependencies {
    // The repo's own lint check: WorkerThreadCall (AGENTS.md *Main thread: read and dispatch only*).
    lintChecks(project(":lint-rules"))
    implementation(project(":shared"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.complications.data.source)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
