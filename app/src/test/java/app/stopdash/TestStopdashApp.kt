package app.stopdash

import app.stopdash.domain.Workers
import kotlinx.coroutines.Dispatchers

/**
 * The [android.app.Application] Robolectric instantiates for the unit-test suite (wired via
 * `src/test/resources/robolectric.properties`), in place of [StopdashApp].
 *
 * It skips [StopdashApp.installDiagnosticLog] so the suite does not, per test, stand up the
 * on-device file sink, spin up its writer thread, and chain a process-wide uncaught-exception
 * handler — none of which a screen or domain test needs. Tests exercise [StopdashDebugLog]
 * directly, and the library's own tests cover the file sink and crash handler.
 */
class TestStopdashApp : StopdashApp() {
    override fun onCreate() {
        // Off-main work posted to the test's main looper, which Robolectric runs as a screen settles,
        // instead of racing a real worker thread: it comes back after the frame that asked for it, as
        // in the app, so a test sees the same order. A test of the hop itself passes a worker of its own.
        Workers.compute = Dispatchers.Main
        super.onCreate()
    }

    override fun installDiagnosticLog() {
        // Intentionally empty — see the class comment. Not a swallowed failure: there is no
        // work to do here, by design.
    }

    override fun logProcessExits() {
        // Intentionally empty — the startup query would write lines into the shared log that no
        // test wrote; ProcessExitReasonsTest drives the collection against its own log.
    }

    override fun warmSharedState() {
        // Intentionally empty — a unit test needs no real DataStore-backed app_key holder or its
        // background collector. Tests that exercise the holder drive it directly.
    }

    override fun installCrashRedaction() {
        // Intentionally empty — no Firebase in the test suite, and no process-wide crash handler
        // to chain; RedactingCrashHandlerTest covers the handler.
    }

    override fun installWatchSync() {
        // Intentionally empty — no Data Layer in the test suite; WatchPublisherTest drives the
        // publisher with fakes.
    }

    override fun installWidgetDismissalRedraw() {
        // Intentionally empty — no widget host in the test suite, and no real dismissed-alerts
        // DataStore to follow; WidgetDismissalRedrawTest drives the redraw with fakes.
    }

    override fun installJourneyAlerts() {
        // Intentionally empty — no WorkManager in the test suite, and no real journeys DataStore to
        // follow; JourneyAlertsTest covers the decisions.
    }

    override fun installMainThreadWatch() {
        // Intentionally empty — Robolectric runs everything on its main thread, so the watch would
        // report the tests' own setup; MainThreadViolationsTest covers what it says.
    }

    override fun installSteadyClock() {
        // Intentionally empty — a test's ages are the wall clock's it injects; SteadyClockTest and
        // the store's tests install a source of their own where they need one.
    }

    override fun installTelemetry() {
        // Intentionally empty — no Firebase in the test suite, and the consent holder and gate are
        // driven directly by their own tests.
    }
}
