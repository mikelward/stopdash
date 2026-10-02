package app.stopdash

import android.os.StrictMode
import android.os.strictmode.Violation
import com.mikelward.androidlog.DebugLog
import com.mikelward.androidlog.safe
import java.util.concurrent.Executors

/**
 * Says in the debug log when the main thread reads or writes the disk ([StrictMode]), in every
 * build: the reports that reach us come from release builds, where logcat is out of reach. Network
 * use isn't reported here, because the platform already refuses it on the main thread: it throws
 * `NetworkOnMainThreadException` before StrictMode makes a violation for a listener to see, and
 * an uncaught one is a crash the next run's process-exit lines record.
 * The main thread is for UI only (AGENTS.md *Main-safe by default*); a freeze long enough to be an
 * ANR is pinned by androidlog's process-exit lines, and this catches the shorter ones that only
 * stutter.
 *
 * Each distinct violation is said once a run, by its kind and the first of the app's own frames
 * under it, which is the call to move off the main thread (see [firstAppFrame] for which are the
 * app's own); at most [MAX_REPORTED] of them, so an
 * old offender can't push the rest of the log out. A frame is a code location, never a value, and
 * goes in as a plain argument: the device's copy carries it, and so does a consent-gated bug report
 * (which sends that copy), while Crashlytics gets a placeholder. Decoding and other CPU
 * work aren't seen here: StrictMode doesn't watch them, which is what the rule's test is for.
 */
internal class MainThreadViolations(
    private val log: DebugLog,
    private val appPackage: String,
) {
    private val seen = HashSet<String>()

    /** Logs a violation of [kind] under [frames] unless it was said already, or enough have been. */
    fun report(kind: String, frames: List<StackTraceElement>) {
        val frame = firstAppFrame(frames, appPackage) ?: "none of the app's"
        synchronized(seen) {
            if (seen.size >= MAX_REPORTED || !seen.add("$kind@$frame")) return
        }
        log.warning("main thread: %s at %s", safe(kind), frame)
    }

    companion object {
        /** Distinct violations said in one run. */
        const val MAX_REPORTED = 20

        /**
         * The first of [frames] in [appPackage], rendered as a stack trace writes it. [appPackage] is
         * the code's package, not the applicationId: a debug build's `.debug` suffix names no
         * class. Where none is in it, as when R8 has renamed the app's classes in a release build,
         * the first frame outside the platform's own packages stands in; null when every frame is
         * the platform's.
         */
        fun firstAppFrame(frames: List<StackTraceElement>, appPackage: String): String? =
            (
                frames.firstOrNull { it.className.startsWith("$appPackage.") }
                    ?: frames.firstOrNull { frame -> PLATFORM_PREFIXES.none { frame.className.startsWith(it) } }
                )?.toString()

        /** Packages whose frames are the platform's or the language's, never the app's call. */
        private val PLATFORM_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "libcore.", "dalvik.",
            "android.", "com.android.", "androidx.", "kotlin.", "kotlinx.",
        )

        /**
         * Turns StrictMode on for the main thread's disk reads and writes, reporting through
         * [log]. Its listener runs on a thread of its own, so reporting costs the main thread
         * nothing.
         */
        fun install(log: DebugLog, appPackage: String) {
            val violations = MainThreadViolations(log, appPackage)
            val reporter = Executors.newSingleThreadExecutor { task ->
                Thread(task, "strict-mode-report").apply { isDaemon = true }
            }
            StrictMode.setThreadPolicy(
                // Built on the policy already in place, not a blank one, so the platform's own crash
                // on network use from the main thread stays. It throws before any listener runs, so
                // there is no network detection to add here.
                StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy())
                    .detectDiskReads()
                    .detectDiskWrites()
                    .penaltyListener(reporter) { violation: Violation ->
                        violations.report(violation::class.java.simpleName, violation.stackTrace.toList())
                    }
                    .build(),
            )
        }
    }
}
