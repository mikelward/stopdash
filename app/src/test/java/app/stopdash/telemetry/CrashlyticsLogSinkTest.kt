package app.stopdash.telemetry

import com.mikelward.androidlog.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashlyticsLogSinkTest {

    private val lines = mutableListOf<String>()
    private val exceptions = mutableListOf<Throwable>()
    private var consent: Boolean? = true

    // Inline delivery, so a test sees what the worker would send.
    private val sink = CrashlyticsLogSink(
        consent = { consent },
        sendLine = { lines += it },
        sendException = { exceptions += it },
        deliver = { it.run() },
    )

    private fun log() = DebugLog().apply { addSink(sink, DebugLog.Destination.OFF_DEVICE) }

    // The log's own first line (its timezone anchor), which isn't under test.
    private val logged get() = lines.filterNot { "timezone offset" in it }

    @Test
    fun `a stop ID in a log line never reaches a crash report`() {
        log().warning("arrivals fetch failed: %s for stop %s", 429, "940GZZLUVIC")
        val line = logged.single()
        assertTrue(line, "429" in line)
        assertFalse(line, "940GZZLUVIC" in line)
    }

    @Test
    fun `a logged exception is recorded without its message`() {
        log().failure(IllegalStateException("no route for 940GZZLUVIC"), "journey route failed")
        assertEquals(1, exceptions.size)
        val sent = generateSequence(exceptions.single()) { it.cause }.joinToString { "${it.message}" }
        assertFalse(sent, "940GZZLUVIC" in sent)
    }

    @Test
    fun `nothing is sent until the user opts in`() {
        consent = false
        log().warning("settings: %s", "read failed")
        assertTrue(lines.isEmpty())
        assertTrue(exceptions.isEmpty())
    }

    @Test
    fun `an opt-out between logging and delivery wins`() {
        val queued = mutableListOf<Runnable>()
        val deferred = CrashlyticsLogSink({ consent }, { lines += it }, { exceptions += it }, { queued += it })
        DebugLog().apply { addSink(deferred, DebugLog.Destination.OFF_DEVICE) }.warning("a line")
        assertTrue(queued.isNotEmpty())
        consent = false
        queued.forEach(Runnable::run)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `a delivery failure is reported once, not dropped silently`() {
        val reports = mutableListOf<String>()
        val failing = CrashlyticsLogSink(
            consent = { true },
            sendLine = { throw IllegalStateException("sdk down") },
            sendException = {},
            deliver = { it.run() },
            onFailure = { reports += it },
        )
        DebugLog().apply { addSink(failing, DebugLog.Destination.OFF_DEVICE) }.run {
            warning("one")
            warning("two")
        }
        assertEquals(1, reports.size)
        assertTrue(reports.single(), "IllegalStateException" in reports.single())
    }

    @Test
    fun `a drain waits for queued lines to reach the SDK, but only so long`() {
        val sent = java.util.Collections.synchronizedList(mutableListOf<String>())
        val sdkBusy = java.util.concurrent.CountDownLatch(1)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val queued = CrashlyticsLogSink(
                consent = { true },
                sendLine = { sdkBusy.await(); sent += it },
                sendException = {},
                deliver = worker::execute,
            )
            queued.log("last line before the crash")
            // The SDK is stuck: the drain gives up rather than hang the crash.
            assertFalse(queued.drain(timeoutMillis = 50))
            sdkBusy.countDown()
            assertTrue(queued.drain(timeoutMillis = 5_000))
            assertEquals(listOf("last line before the crash"), sent.toList())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun `a line logged before the choice loads is sent once it loads as yes, ahead of later ones`() {
        consent = null
        val log = log()
        log.warning("startup: %s", "first")
        log.failure(IllegalStateException("boom"), "startup failed")
        assertTrue(lines.isEmpty())
        assertTrue(exceptions.isEmpty())

        consent = true
        sink.settle()
        log.warning("later")

        assertEquals(logged.toString(), 3, logged.size)
        assertTrue(logged[0], logged[0].endsWith("startup: •••"))
        assertTrue(logged[1], "startup failed" in logged[1])
        assertTrue(logged[2], logged[2].endsWith("later"))
        assertEquals(1, exceptions.size)
    }

    @Test
    fun `held lines are dropped when the choice loads as no`() {
        consent = null
        val log = log()
        log.warning("startup: %s", "first")

        consent = false
        sink.settle()
        log.warning("later")
        // Nor does a later opt-in bring them back: they were logged under a no.
        consent = true
        sink.settle()

        assertTrue(lines.toString(), lines.isEmpty())
    }

    @Test
    fun `the next line releases what was held when nothing settles first`() {
        consent = null
        val log = log()
        log.warning("held")
        consent = true
        log.warning("next")

        assertEquals(listOf("held", "next"), logged.map { it.substringAfterLast(' ') })
    }

    @Test
    fun `settling before the choice loads keeps holding`() {
        consent = null
        val log = log()
        log.warning("held")
        sink.settle()
        assertTrue(lines.isEmpty())

        consent = true
        sink.settle()
        assertEquals(listOf("held"), logged.map { it.substringAfterLast(' ') })
    }

    @Test
    fun `a stalled load keeps only the newest lines and says how many it dropped`() {
        consent = null
        val extra = 5
        repeat(CrashlyticsLogSink.MAX_HELD + extra) { sink.log("line $it") }
        consent = true
        sink.settle()

        assertEquals("telemetry: $extra earlier lines not kept while consent loaded", lines.first())
        assertEquals(
            (extra until CrashlyticsLogSink.MAX_HELD + extra).map { "line $it" },
            lines.drop(1),
        )
    }

    @Test
    fun `a fatal crash's drain sends what was held once the choice has loaded`() {
        consent = null
        sink.log("held before the crash")
        consent = true

        assertTrue(sink.drain(timeoutMillis = 1_000))
        assertEquals(listOf("held before the crash"), lines)
    }
}
