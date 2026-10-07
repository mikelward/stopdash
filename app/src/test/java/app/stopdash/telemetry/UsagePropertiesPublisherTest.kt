package app.stopdash.telemetry

import app.stopdash.domain.DistanceUnits
import app.stopdash.domain.MaxWalk
import app.stopdash.domain.StepFree
import app.stopdash.domain.TripModes
import app.stopdash.domain.UsageEvent
import app.stopdash.domain.UsageState
import app.stopdash.domain.WalkingSpeed
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsagePropertiesPublisherTest {
    private val state = UsageState(
        walkingSpeed = WalkingSpeed.SLOW,
        maxWalk = MaxWalk.DEFAULT,
        stepFree = StepFree.STATION,
        tripModes = TripModes.DEFAULT,
        avoidedLines = 0,
        hiddenModes = emptySet(),
        distanceUnits = DistanceUnits.AUTOMATIC,
        disruptionsRow = true,
        liveWidget = false,
        textSize = 1f,
        pinchToResize = true,
        tflKey = false,
        railKey = false,
        widgets = 1,
        watch = UsageState.Watch.NONE,
        starredRows = 2,
        favoritePlaces = 0,
        favoriteJourneys = 0,
        notifications = true,
        location = UsageEvent.Grant.PRECISE,
    )

    @Test
    fun `nothing is read or sent until the rider opts in, then the properties go at once`() = runTest {
        val consent = MutableStateFlow<Boolean?>(null)
        var reads = 0
        val sent = mutableListOf<Map<String, String>>()
        val publisher = UsagePropertiesPublisher(consent, { reads++; state }, { sent += it }, StandardTestDispatcher(testScheduler), warn = {})
        publisher.start(backgroundScope)
        publisher.refresh()
        runCurrent()
        consent.value = false
        publisher.refresh()
        runCurrent()
        assertEquals(0, reads)
        assertEquals(emptyList<Map<String, String>>(), sent)
        consent.value = true
        runCurrent()
        assertEquals(1, sent.size)
        assertEquals("slow", sent.single()["walking_speed"])
        assertEquals("station", sent.single()["step_free"])
        assertEquals("1", sent.single()["widgets"])
    }

    @Test
    fun `each refresh while opted in sends them again, and none after a withdrawal`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val sent = mutableListOf<Map<String, String>>()
        val publisher = UsagePropertiesPublisher(consent, { state }, { sent += it }, StandardTestDispatcher(testScheduler), warn = {})
        publisher.start(backgroundScope)
        runCurrent()
        publisher.refresh()
        runCurrent()
        assertEquals(2, sent.size)
        consent.value = false
        publisher.refresh()
        runCurrent()
        assertEquals(2, sent.size)
        // Opted in again: sent afresh, as the withdrawal cleared them.
        consent.value = true
        runCurrent()
        assertEquals(3, sent.size)
    }

    @Test
    fun `a withdrawal while reading sends nothing`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val reading = CompletableDeferred<UsageState>()
        val sent = mutableListOf<Map<String, String>>()
        val publisher = UsagePropertiesPublisher(consent, { reading.await() }, { sent += it }, StandardTestDispatcher(testScheduler), warn = {})
        publisher.start(backgroundScope)
        runCurrent()
        consent.value = false
        reading.complete(state)
        runCurrent()
        assertEquals(emptyList<Map<String, String>>(), sent)
    }

    @Test
    fun `a read or a send that fails is logged and the publisher carries on`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val warnings = mutableListOf<String>()
        var fail = true
        val sent = mutableListOf<Map<String, String>>()
        val publisher = UsagePropertiesPublisher(
            consent,
            { if (fail) error("store") else state },
            { sent += it },
            StandardTestDispatcher(testScheduler),
            warn = { warnings += it },
        )
        publisher.start(backgroundScope)
        runCurrent()
        assertEquals(listOf("usage properties not read: IllegalStateException"), warnings)
        assertEquals(emptyList<Map<String, String>>(), sent)
        fail = false
        publisher.refresh()
        runCurrent()
        assertEquals(1, sent.size)
    }

    @Test
    fun `a cancellation from the send isn't taken for a failure`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val warnings = mutableListOf<String>()
        var sends = 0
        val publisher = UsagePropertiesPublisher(
            consent,
            { state },
            { sends++; throw CancellationException("stopping") },
            StandardTestDispatcher(testScheduler),
            warn = { warnings += it },
        )
        val job = publisher.start(backgroundScope)
        runCurrent()
        // It ends the publisher, as structured concurrency asks, rather than being logged and carried past.
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), warnings)
        publisher.refresh()
        runCurrent()
        assertEquals(1, sends)
    }

    @Test
    fun `a change to what's counted sends them again, and is watched only while opted in`() = runTest {
        val consent = MutableStateFlow<Boolean?>(false)
        val changes = MutableSharedFlow<Int>()
        var reads = 0
        val publisher = UsagePropertiesPublisher(
            consent, { reads++; state }, {}, StandardTestDispatcher(testScheduler), warn = {}, changes = listOf(changes),
        )
        publisher.start(backgroundScope)
        runCurrent()
        // Opted out: nothing is even listening.
        assertEquals(0, changes.subscriptionCount.value)
        consent.value = true
        runCurrent()
        assertEquals(1, reads)
        assertEquals(1, changes.subscriptionCount.value)
        changes.emit(1)
        runCurrent()
        assertEquals(2, reads)
        consent.value = false
        runCurrent()
        assertEquals(0, changes.subscriptionCount.value)
        assertEquals(2, reads)
    }

    @Test
    fun `a change source that fails is logged and watched again, the others and the publisher carrying on`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val warnings = mutableListOf<String>()
        var reads = 0
        var watches = 0
        val later = MutableSharedFlow<Int>()
        // A store whose first read fails, as a DataStore read can.
        val failing = flow {
            watches++
            if (watches == 1) throw IOException("disk")
            emitAll(later)
        }
        val steady = MutableSharedFlow<Int>()
        val publisher = UsagePropertiesPublisher(
            consent, { reads++; state }, {}, StandardTestDispatcher(testScheduler), warn = { warnings += it },
            changes = listOf(failing, steady),
        )
        val job = publisher.start(backgroundScope)
        runCurrent()
        assertEquals(listOf("usage property changes not watched: IOException"), warnings)
        assertTrue(job.isActive)
        // The other store is still watched, and a refresh still sends.
        var before = reads
        steady.emit(1)
        runCurrent()
        assertEquals(before + 1, reads)
        before = reads
        publisher.refresh()
        runCurrent()
        assertEquals(before + 1, reads)
        // Watched again after the pause: a change to it sends them again.
        advanceTimeBy(UsagePropertiesPublisher.CHANGES_RETRY_MILLIS + 1)
        runCurrent()
        assertEquals(2, watches)
        before = reads
        later.emit(1)
        runCurrent()
        assertEquals(before + 1, reads)
    }

    @Test
    fun `a change source that ends after a failed read is watched again`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        var reads = 0
        var watches = 0
        val later = MutableSharedFlow<Int>()
        // As the favorite journeys' store does: it says it couldn't read, and ends.
        val ending = flow<Any?> {
            watches++
            if (watches == 1) emit(null) else emitAll(later)
        }
        val publisher = UsagePropertiesPublisher(
            consent, { reads++; state }, {}, StandardTestDispatcher(testScheduler), warn = {},
            changes = listOf(ending, MutableSharedFlow<Int>()),
        )
        publisher.start(backgroundScope)
        runCurrent()
        assertEquals(1, watches)
        advanceTimeBy(UsagePropertiesPublisher.CHANGES_RETRY_MILLIS + 1)
        runCurrent()
        assertEquals(2, watches)
        val before = reads
        later.emit(1)
        runCurrent()
        assertEquals(before + 1, reads)
    }

    @Test
    fun `a cancellation from a change source is neither taken for a failure nor retried`() = runTest {
        val consent = MutableStateFlow<Boolean?>(true)
        val warnings = mutableListOf<String>()
        var watches = 0
        val stopping = flow<Any?> {
            watches++
            throw CancellationException("stopping")
        }
        val publisher = UsagePropertiesPublisher(
            consent, { state }, {}, StandardTestDispatcher(testScheduler), warn = { warnings += it }, changes = listOf(stopping),
        )
        publisher.start(backgroundScope)
        runCurrent()
        // It ends that watch, as a cancellation does, rather than being logged and watched again.
        advanceTimeBy(UsagePropertiesPublisher.CHANGES_RETRY_MILLIS + 1)
        runCurrent()
        assertEquals(1, watches)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `a refresh only hands off, the reading running on the worker, not the caller's thread`() {
        val worker = Executors.newSingleThreadExecutor { Thread(it, "properties-worker") }
        val scope = CoroutineScope(SupervisorJob())
        try {
            val consent = MutableStateFlow<Boolean?>(false)
            val read = CountDownLatch(1)
            var readOn = ""
            val publisher = UsagePropertiesPublisher(
                consent,
                { readOn = Thread.currentThread().name; read.countDown(); state },
                {},
                worker.asCoroutineDispatcher(),
                warn = {},
            )
            publisher.start(scope)
            consent.value = true
            publisher.refresh()
            assertTrue(read.await(5, TimeUnit.SECONDS))
            assertEquals("properties-worker", readOn.substringBefore(" @"))
        } finally {
            scope.cancel()
            worker.shutdown()
        }
    }
}
