package app.stopdash

import android.app.Application
import android.content.Context
import android.util.Log
import app.stopdash.data.DataStoreAppSettings
import app.stopdash.data.DataStoreFavoriteJourneysStore
import app.stopdash.data.DataStoreFavoritePlacesStore
import app.stopdash.data.DataStoreStarredRowsStore
import app.stopdash.data.DeviceSteadyClock
import app.stopdash.data.DisruptionsRowSetting
import app.stopdash.data.DistanceUnitsSetting
import app.stopdash.data.MaxWalkSetting
import app.stopdash.data.StepFreeSetting
import app.stopdash.data.SummaryNetworksSetting
import app.stopdash.data.TripModesSetting
import app.stopdash.data.AvoidedLinesSetting
import app.stopdash.data.WalkingSpeedSetting
import app.stopdash.data.HiddenModesSetting
import app.stopdash.data.RailApiKeySetting
import app.stopdash.data.UserApiKeySetting
import app.stopdash.data.logAppSettingsWarning
import app.stopdash.domain.SteadyClock
import app.stopdash.telemetry.CrashlyticsLogSink
import app.stopdash.telemetry.FileConsentMark
import app.stopdash.telemetry.FilePendingMarker
import app.stopdash.telemetry.FirebaseTelemetryBackend
import app.stopdash.telemetry.NoPendingMarker
import app.stopdash.telemetry.PrefsConsentStore
import app.stopdash.telemetry.TelemetryConsent
import app.stopdash.telemetry.TelemetryGate
import app.stopdash.telemetry.UsageEvents
import app.stopdash.telemetry.UsageProperties
import app.stopdash.telemetry.UsagePropertiesPublisher
import app.stopdash.telemetry.UsageStateReader
import app.stopdash.telemetry.settleWhenConsentLoads
import app.stopdash.telemetry.startTelemetry
import app.stopdash.telemetry.whenConsentLoads
import app.stopdash.watch.WatchSync
import app.stopdash.widget.WidgetDismissalRedraw
import app.stopdash.widget.redrawWidgets
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.android.DebugFileSink
import com.mikelward.androidlog.android.LogcatSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private const val LOGCAT_TAG = "StopDash"

// How long a fatal crash waits for queued breadcrumbs to reach Crashlytics before it is reported.
private const val FATAL_DRAIN_MILLIS = 500L

/**
 * Registers the diagnostic log's DEVICE sinks on [log]: the developer-facing [LogcatSink] and
 * the on-device [DebugFileSink] that persists the buffer to `cacheDir`. [DebugFileSink.start]
 * rotates the previous run's file aside and (when [installCrashHandler]) chains an
 * uncaught-exception handler; it runs **before** the file sink is added so the rotation is
 * ordered ahead of this run's writes.
 *
 * Top-level, with [log] and [installCrashHandler] injected and the file sink returned, so a
 * test can drive the real registration against a fresh [DebugLog], without hijacking the JVM's
 * crash handler, and then flush the returned sink ([DebugFileSink.awaitIdle]) to assert the
 * warning actually reached the persisted file — the production installation path is covered,
 * not bypassed.
 */
internal fun installDiagnosticSinks(
    log: DebugLog,
    context: Context,
    installCrashHandler: Boolean = true,
): DebugFileSink {
    log.addSink(LogcatSink(LOGCAT_TAG), DebugLog.Destination.DEVICE)
    val fileSink = DebugFileSink(log, context)
    fileSink.start(installCrashHandler)
    log.addSink(fileSink, DebugLog.Destination.DEVICE)
    return fileSink
}

/**
 * The Application. Its one job today is to stand up the diagnostic log ([StopdashDebugLog]) at
 * process start — before any Activity, refresh, or widget update runs — so every warning after
 * startup is both visible in Logcat and persisted on-device.
 *
 * The persisted file is **on-device only** (`cacheDir`, excluded from backup) and is not shared
 * from here; a user-shareable export with travel data redacted is a later change
 * (`docs/PRIVACY.md`, `TODO.md`). The one off-device sink is Crashlytics, registered only in a
 * build with a Firebase config and fed only while the user has opted in ([installTelemetry]).
 */
open class StopdashApp : Application() {
    /**
     * The persisted diagnostic sink once it is stood up — the shared logger's on-device file
     * store. Handed to the consent-gated bug report so it can bundle (and, once shared, consume)
     * the earlier runs the sink kept. Null before [onCreate] runs, if setup failed, or in the
     * test Application that skips installation — a report then simply carries no earlier runs.
     */
    var diagnosticSink: DebugFileSink? = null
        private set

    /**
     * A process-lived scope for work that must outlive the Activity that started it — the bug
     * report's collect+share, which reads the persisted log (up to ~10 s) and opens the share
     * sheet, so a rotation mid-collect must not cancel it and drop the share silently (SPEC
     * principle 2; Codex P2 on #86). Main-dispatched because delivery touches the clipboard and
     * starts an activity; the collect step moves itself to IO. Never canceled — it lives for the
     * process, like the app.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Whether a fatal crash reaches Crashlytics only redacted; telemetry doesn't start without it.
    private var crashRedacted = false

    // The Crashlytics log sink once registered, for the fatal-crash path to flush.
    @Volatile
    private var crashlyticsSink: CrashlyticsLogSink? = null

    override fun onCreate() {
        super.onCreate()
        // First, so the diagnostic log's crash handler chains onto the redacting one: its on-device
        // file keeps the full crash, Crashlytics gets the redacted copy.
        installCrashRedaction()
        installDiagnosticLog()
        // Right after the log, so the main thread's disk use from here on is said there.
        installMainThreadWatch()
        // Next, so its log sink sees startup's lines; it holds them until the stored choice loads.
        installTelemetry()
        logProcessExits()
        // Before anything fetches, so every fetch is stamped by it.
        installSteadyClock()
        warmSharedState()
        installWatchSync()
        installWidgetDismissalRedraw()
    }

    /**
     * Says in the debug log when the main thread reads or writes the disk
     * ([MainThreadViolations]). `open` so the test [Application] can skip it: Robolectric runs
     * everything on its main thread, so a test would report its own setup.
     */
    protected open fun installMainThreadWatch() {
        // The code's package, not packageName: a debug build's applicationId ends in ".debug".
        MainThreadViolations.install(StopdashDebugLog, StopdashApp::class.java.name.substringBeforeLast('.'))
    }

    /**
     * The device's monotonic clock as the one a fetch's age is judged by ([SteadyClock], SPEC
     * *Freshness*), so setting the clock doesn't make old departures read as new. `open` so the test
     * [Application] can skip it: a unit test judges ages by the wall clock it injects.
     */
    protected open fun installSteadyClock() {
        DeviceSteadyClock.install(this)
    }

    /**
     * Records why the previous processes ended ([logRecentProcessExits]), off the main thread and
     * after [installDiagnosticLog], so the file sink keeps the lines. `open` so the test
     * [Application] skips it: the singleton log would otherwise carry lines no test wrote.
     */
    protected open fun logProcessExits() {
        logRecentProcessExitsInBackground(applicationScope) { logRecentProcessExits(this) }
    }

    /**
     * Redraws the widget when the user's dismissed alerts change, since it applies them as it draws.
     * `open` so the test [Application] can skip it; guarded like the watch sync.
     */
    protected open fun installWidgetDismissalRedraw() {
        try {
            WidgetDismissalRedraw.start(this, applicationScope)
        } catch (e: Exception) {
            StopdashDebugLog.warning("widget: dismissal redraw start failed: %s", e::class.simpleName)
        }
    }

    /**
     * Publishes the widget's snapshot to a paired Wear OS watch that has the app (dev-docs/wear-os.md).
     * Nothing is sent when no watch has it. `open` so the test [Application] skips it; guarded, since
     * a sync that can't start is a lost convenience, never a reason to take the app down.
     */
    protected open fun installWatchSync() {
        try {
            WatchSync.start(this, applicationScope)
        } catch (e: Exception) {
            StopdashDebugLog.warning("watch: sync start failed: %s", e::class.simpleName)
        }
    }

    /**
     * Crash reporting and usage stats, off until the user opts in (SPEC *Privacy*). Reads the stored
     * choice, keeps both SDKs in step with it ([TelemetryGate]), and mirrors the log's redacted
     * off-device lines into crash reports ([CrashlyticsLogSink]). A build with no Firebase config —
     * every debug build, and any build without google-services.json — skips all of it.
     *
     * `open` so the test [Application] skips it. Guarded like the log: telemetry failing to start is
     * a lost diagnostic, never a reason to take the app down, and it fails closed ([startTelemetry]).
     */
    /**
     * Makes a fatal crash reach Crashlytics only as the log redacts an off-device throwable (types
     * and frames, no messages — a request URL in a message carries coordinates and the `app_key`).
     * `open` so the test [Application] skips it. If it fails, [installTelemetry] switches the SDKs
     * off rather than let an unredacted crash be sent.
     */
    protected open fun installCrashRedaction() {
        crashRedacted = try {
            FirebaseTelemetryBackend.installCrashRedaction(
                this,
                StopdashDebugLog::offDeviceThrowable,
                beforeReport = { crashlyticsSink?.drain(FATAL_DRAIN_MILLIS) },
            )
        } catch (e: Exception) {
            Log.w(LOGCAT_TAG, "crash redaction failed: ${e::class.simpleName}")
            false
        }
    }

    protected open fun installTelemetry() {
        var firebase: FirebaseTelemetryBackend? = null
        startTelemetry(
            createBackend = { FirebaseTelemetryBackend.orNull(this)?.also { firebase = it } },
            registerSink = {
                // Fails closed through startTelemetry: without redaction, the SDKs are switched off.
                check(crashRedacted) { "crash redaction not installed" }
                val sink = CrashlyticsLogSink { TelemetryConsent.state.value }
                StopdashDebugLog.addSink(sink, DebugLog.Destination.OFF_DEVICE)
                crashlyticsSink = sink
                settleWhenConsentLoads(sink, TelemetryConsent.state, applicationScope)
                // Usage events (categories and buckets only) go to Analytics while opted in; those raised
                // before the stored choice loads are held until it has.
                firebase?.let { backend ->
                    UsageEvents.install(backend::logEvent)
                    whenConsentLoads(TelemetryConsent.state, applicationScope, Dispatchers.Default, UsageEvents::settle)
                    // And the rider's standing choices and setup, as user properties, read off the main thread.
                    UsageProperties.install(
                        UsagePropertiesPublisher(
                            TelemetryConsent.state,
                            UsageStateReader(this@StopdashApp)::read,
                            backend::setUserProperties,
                            Dispatchers.IO,
                            warn = { StopdashDebugLog.warning("telemetry: %s", it) },
                            // The counts change as the rider stars and saves, with the app in front.
                            changes = listOf(
                                DataStoreStarredRowsStore.from(this@StopdashApp, warn = ::logStarWarning).starred(),
                                DataStoreFavoritePlacesStore.from(this@StopdashApp, warn = ::logStarWarning).places(),
                                DataStoreFavoriteJourneysStore.from(this@StopdashApp, warn = ::logStarWarning).journeys(),
                            ),
                        ),
                        applicationScope,
                    )
                }
            },
            startLoad = { backend ->
                // The stored choice is a small prefs read, but still disk I/O: off the main thread.
                // The Settings switch stays disabled until it lands.
                applicationScope.launch(Dispatchers.IO) {
                    var gate: TelemetryGate? = null
                    try {
                        val store = PrefsConsentStore(this@StopdashApp)
                        gate = backend?.let { TelemetryGate(it, FilePendingMarker(this@StopdashApp)) }
                        TelemetryConsent.load(store, gate, FileConsentMark.optOut(this@StopdashApp), FileConsentMark.optIn(this@StopdashApp))
                    } catch (e: Exception) {
                        // Fail closed: SDKs off and the switch shown off, so an earlier opt-in can't
                        // keep collecting with the switch stuck disabled.
                        Log.w(LOGCAT_TAG, "telemetry consent load failed: ${e::class.simpleName}")
                        TelemetryConsent.loadFailed(gate ?: backend?.let { TelemetryGate(it, NoPendingMarker) })
                    }
                }
            },
        )
    }

    /**
     * Warms the process-wide TfL `app_key` holder ([UserApiKeySetting]) at process start, so the
     * app's long-lived request clients read the current key (their `appKey` provider is
     * `{ UserApiKeySetting.current }`) without a disk hit on any request path (SPEC D7). Done here
     * rather than in [MainActivity] so it happens once, early, for whatever entry point runs first.
     * (The widget worker doesn't rely on this — it reads its own key snapshot directly.)
     *
     * `open` so the test [Application] skips it — a unit test needs no real DataStore-backed holder
     * or its background collector. Launches coroutines only (no first-frame I/O), like the log setup.
     */
    protected open fun warmSharedState() {
        UserApiKeySetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        RailApiKeySetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        HiddenModesSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        DistanceUnitsSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        DisruptionsRowSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        SummaryNetworksSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        WalkingSpeedSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        MaxWalkSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        StepFreeSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        TripModesSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        AvoidedLinesSetting.warm(DataStoreAppSettings.from(this, warn = ::logAppSettingsWarning))
        // Redraw the widget when the hidden modes change, so it leaves out what the list does without
        // waiting for the next refresh. Process-wide, so a change made just before the user leaves
        // for Settings or a search still reaches it; keyed on the in-process set the widget reads,
        // so a change that failed to save still applies there until restart, as in the list.
        applicationScope.launch {
            HiddenModesSetting.changes
                .drop(1)
                .collect {
                    try {
                        redrawWidgets(this@StopdashApp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The next snapshot save redraws it anyway; only the prompt redraw is lost.
                        logAppSettingsWarning("widget redraw after hiding failed: ${e::class.simpleName}")
                    }
                }
        }
    }

    /**
     * `open` so a test [Application] can skip standing up the persisted sink and the process-wide
     * crash handler (the unit-test suite does, via `robolectric.properties`).
     *
     * Guarded: the log exists to diagnose failures, so a failure to *stand it up* must never
     * crash the app. Per the error-handling rule it is logged (to Logcat, the sink always safe to
     * reach here) and then swallowed — a missing persisted log is a lost diagnostic, not a reason
     * to take down the process. There is nothing acquired to clean up on this path.
     */
    protected open fun installDiagnosticLog() {
        // Synchronous, on the main thread, deliberately: DebugFileSink.start() installs the
        // uncaught-exception handler on the *calling* thread, so it must run before onCreate
        // returns — otherwise a crash in an Activity, widget receiver, or worker launched right
        // after startup would go uncaptured, defeating the crash-survival guarantee (Codex, PR
        // #84). It does no first-frame I/O: the library rotates the prior run's file on its own
        // worker, never here. Guarded so a failure to stand up the log is logged and swallowed
        // rather than crashing the app it exists to diagnose (nothing acquired to clean up here).
        try {
            diagnosticSink = installDiagnosticSinks(StopdashDebugLog, this)
        } catch (e: Exception) {
            Log.w(LOGCAT_TAG, "diagnostic log setup failed: ${e::class.simpleName}")
        }
    }
}

/** A starred-store warning, worded as the activity's are. */
private fun logStarWarning(message: String) = StopdashDebugLog.warning("stars: %s", message)
