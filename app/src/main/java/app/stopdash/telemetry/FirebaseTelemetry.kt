package app.stopdash.telemetry

import android.content.Context
import android.os.Bundle
import android.util.Log
import app.stopdash.domain.UsageEvent
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.mikelward.androidlog.DebugLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The Firebase-backed [TelemetryBackend], or null from [orNull] when this build carries no
 * Firebase config (no google-services.json, or a debug build — dev-docs/firebase.md): Firebase
 * never initializes then, and its accessors would throw.
 */
class FirebaseTelemetryBackend private constructor(
    private val analytics: FirebaseAnalytics,
    private val crashlytics: FirebaseCrashlytics,
) : TelemetryBackend {

    override val collecting: Boolean get() = crashlytics.isCrashlyticsCollectionEnabled

    override fun switchCollection(enabled: Boolean) {
        // Crashlytics' flag is the commit marker (TelemetryBackend): last on, first off.
        if (enabled) {
            analytics.setAnalyticsCollectionEnabled(true)
            crashlytics.setCrashlyticsCollectionEnabled(true)
        } else {
            // Both attempted even if the first throws: one SDK failing must not leave the other on.
            attemptAll(
                { crashlytics.setCrashlyticsCollectionEnabled(false) },
                { analytics.setAnalyticsCollectionEnabled(false) },
            )
        }
    }

    override fun checkUnsent(result: (Boolean?) -> Unit) {
        crashlytics.checkForUnsentReports().addOnCompleteListener { check ->
            result(if (check.isSuccessful) check.result == true else null)
        }
    }

    /**
     * Sends [event] as an Analytics event, each parameter a string ([UsageEvent] holds only
     * categories and buckets). The SDK queues it on its own worker, and drops it while collection is
     * off; [UsageEvents] sends only while the rider has opted in anyway.
     */
    fun logEvent(event: UsageEvent) {
        analytics.logEvent(event.name, usageEventBundle(event))
    }

    /**
     * Sets each of [properties] as an Analytics user property ([UsageProperties]: categories and buckets
     * only). The SDK keeps them with the app-instance's data, so a withdrawal's reset clears them too.
     */
    fun setUserProperties(properties: Map<String, String>) {
        properties.forEach { (name, value) -> analytics.setUserProperty(name, value) }
    }

    override fun discardUnsent() {
        attemptAll(
            { crashlytics.deleteUnsentReports() },
            // A fresh Analytics app-instance ID after a withdrawal or a new opt-in, so the two
            // stretches of usage stats aren't linked. (Crashlytics keeps its own installation ID;
            // docs/PRIVACY.md says so.)
            { analytics.resetAnalyticsData() },
        )
    }

    companion object {
        fun orNull(context: Context): FirebaseTelemetryBackend? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            // If one SDK can't be reached, the other is switched off before the failure is
            // reported: the caller gets no backend, so nothing else could turn it off.
            val (analytics, crashlytics) = acquireBoth(
                first = { FirebaseAnalytics.getInstance(context) },
                switchFirstOff = { it.setAnalyticsCollectionEnabled(false) },
                second = { FirebaseCrashlytics.getInstance() },
                switchSecondOff = { it.setCrashlyticsCollectionEnabled(false) },
            )
            return FirebaseTelemetryBackend(analytics, crashlytics)
        }

        /**
         * Puts a [RedactingCrashHandler] directly in front of Crashlytics' fatal-crash handler, so a
         * crash reaches Crashlytics only as [redact] renders it. Call before anything else chains
         * onto the default handler (the diagnostic log's file sink), so that sink, which stays on
         * the device, still sees the original. True once installed, or when this build has no
         * Firebase to protect; false if Crashlytics' handler couldn't be found.
         */
        fun installCrashRedaction(
            context: Context,
            redact: (Throwable) -> Throwable,
            beforeReport: () -> Unit,
        ): Boolean {
            if (FirebaseApp.getApps(context).isEmpty()) return true
            FirebaseCrashlytics.getInstance() // its handler is installed when it starts
            val crashlytics = Thread.getDefaultUncaughtExceptionHandler() ?: return false
            Thread.setDefaultUncaughtExceptionHandler(RedactingCrashHandler(crashlytics, redact, beforeReport))
            return true
        }
    }
}

/** [event]'s parameters as the [Bundle] Analytics takes, every value a string. */
internal fun usageEventBundle(event: UsageEvent): Bundle =
    Bundle().apply { event.params.forEach { (key, value) -> putString(key, value) } }

/**
 * Mirrors the diagnostic log's **off-device** lines into Crashlytics, so an uploaded crash report
 * carries the few lines that led up to it, and records each logged exception as a non-fatal.
 *
 * Registered as a [DebugLog.Destination.OFF_DEVICE] sink, so androidlog redacts before anything
 * reaches here: an argument not marked `safe(...)` (a stop ID, a line ID, a coordinate) arrives
 * as a placeholder, and an exception arrives as its types and frames without its message. This
 * sink adds no redaction of its own and must never be registered as a DEVICE sink.
 *
 * [consent] is the choice in force: null until the stored choice has loaded, then whether the user
 * opted in. It is read at submit and again at delivery, so an opt-out that lands in between wins.
 * A line logged before the choice has loaded is **held** in memory, at most [MAX_HELD] of them
 * with the oldest dropped first, and [settle] then sends them on a yes and drops them on a no.
 * Otherwise every opted-in start would lose its first lines, which is where the process-exit record
 * and the settings warm-up's warnings are. Held lines never outlive the process, and nothing is
 * sent unless the stored choice is yes.
 *
 * Delivery runs on its own single worker, never on the logging thread: a log call is often on a
 * refresh or render path, which must not wait on the SDK. A delivery failure goes to Logcat —
 * not the shared log, which would feed it straight back here — once per process, so a broken SDK
 * leaves a trace without flooding it; the lines themselves are best-effort.
 */
class CrashlyticsLogSink internal constructor(
    private val consent: () -> Boolean?,
    private val sendLine: (String) -> Unit,
    private val sendException: (Throwable) -> Unit,
    private val deliver: (Runnable) -> Unit,
    private val onFailure: (String) -> Unit = {},
) : DebugLog.Sink {

    constructor(consent: () -> Boolean?) : this(
        consent,
        sendLine = { FirebaseCrashlytics.getInstance().log(it) },
        sendException = { FirebaseCrashlytics.getInstance().recordException(it) },
        deliver = worker()::execute,
        onFailure = { Log.w("StopDash", "telemetry: $it") },
    )

    private class Held(val line: String, val throwable: Throwable?)

    // Guarded by itself. Emptied for good once the choice is known.
    private val held = ArrayDeque<Held>()
    private var droppedWhileHeld = 0

    // Set, under the lock, only after the held lines are submitted, so a line that skips the lock
    // because of it can't reach the worker ahead of them.
    @Volatile
    private var settled = false

    override fun log(line: String) = log(line, ' ', null)

    override fun log(line: String, level: Char) = log(line, level, null)

    override fun log(line: String, level: Char, throwable: Throwable?) {
        if (!settled && hold(line, throwable)) return
        if (consent() != true) return
        submit(line, throwable)
    }

    /**
     * Sends the held lines if the stored choice has loaded as yes, or drops them if it loaded as no;
     * does nothing while it hasn't loaded. Called once the choice is known, so the held lines don't
     * wait for the next log line, and before a fatal crash's [drain].
     */
    fun settle() {
        if (settled) return
        synchronized(held) {
            if (!settled) consent()?.let(::release)
        }
    }

    // True if the line was held because the choice hasn't loaded. Otherwise settles first, so the
    // caller's line follows whatever was held.
    private fun hold(line: String, throwable: Throwable?): Boolean {
        synchronized(held) {
            if (settled) return false
            val choice = consent()
            if (choice != null) {
                release(choice)
                return false
            }
            if (held.size == MAX_HELD) {
                held.removeFirst()
                droppedWhileHeld++
            }
            held.addLast(Held(line, throwable))
            return true
        }
    }

    // Caller holds the lock. Submitting under it keeps the held lines ahead of any later one.
    private fun release(optedIn: Boolean) {
        if (optedIn) {
            if (droppedWhileHeld > 0) {
                submit("telemetry: $droppedWhileHeld earlier lines not kept while consent loaded", null)
            }
            held.forEach { submit(it.line, it.throwable) }
        }
        held.clear()
        droppedWhileHeld = 0
        settled = true
    }

    private fun submit(line: String, throwable: Throwable?) {
        runCatching {
            deliver(
                Runnable {
                    if (consent() != true) return@Runnable
                    runCatching { sendLine(line) }.onFailure(::reportFailure)
                    if (throwable != null) runCatching { sendException(throwable) }.onFailure(::reportFailure)
                },
            )
        }.onFailure(::reportFailure)
    }

    /**
     * Waits up to [timeoutMillis] for everything already queued to reach the SDK. For the fatal-crash
     * path ([RedactingCrashHandler.beforeReport]): the last line before a crash is the one most worth
     * having, and Crashlytics may close the report and end the process before the worker gets to it.
     * Bounded, so a stuck SDK can't hang the crash. True if the queue drained in time. Settles
     * first, so lines held before the choice loaded go too once it has.
     */
    fun drain(timeoutMillis: Long): Boolean {
        settle()
        val done = CountDownLatch(1)
        return try {
            deliver(Runnable { done.countDown() })
            done.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            reportFailure(e)
            false
        }
    }

    @Volatile
    private var failureReported = false

    private fun reportFailure(e: Throwable) {
        if (failureReported) return
        failureReported = true
        runCatching { onFailure("crashlytics delivery failed: ${e::class.simpleName}") }
    }

    internal companion object {
        /**
         * How many lines are held before the choice loads. That takes a prefs read, so normally
         * only the first few lines of a start; the bound is for a load that stalls.
         */
        const val MAX_HELD = 64

        private fun worker() = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue()) { runnable ->
            Thread(runnable, "stopdash-crashlytics-log").apply { isDaemon = true }
        }
    }
}
