package app.stopdash

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
    override fun installDiagnosticLog() {
        // Intentionally empty — see the class comment. Not a swallowed failure: there is no
        // work to do here, by design.
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

    override fun installTelemetry() {
        // Intentionally empty — no Firebase in the test suite, and the consent holder and gate are
        // driven directly by their own tests.
    }
}
