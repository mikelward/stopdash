package app.stopdash.telemetry

import app.stopdash.domain.UsageState
import app.stopdash.domain.usageProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The rider's standing choices and setup as Analytics user properties ([usageProperties]), sent while
 * the rider has opted in (SPEC *Privacy*): as the opt-in lands (a withdrawal clears them with the rest of
 * the app-instance's data), and again on each [refresh] — the app coming to the front, which catches a
 * widget placed or a watch paired since, and a setting changed in the app.
 *
 * Nothing is read until there's somewhere to send it ([install]); a build with no Firebase never reads
 * anything for it.
 */
object UsageProperties {
    @Volatile
    private var publisher: UsagePropertiesPublisher? = null

    /** Starts [publisher] in [scope], from now on the one each [refresh] asks. */
    fun install(publisher: UsagePropertiesPublisher, scope: CoroutineScope) {
        this.publisher = publisher
        publisher.start(scope)
    }

    /** Sends the properties again, if opted in; returns at once, the reading done on the publisher's worker. */
    fun refresh() {
        publisher?.refresh()
    }

    internal fun resetForTest() {
        publisher = null
    }
}

/**
 * Reads the rider's [UsageState] ([read]) and sends it as user properties ([send]), on [worker], never
 * the caller's thread: [read] is disk and Play services (AGENTS.md *Main thread: read and dispatch
 * only*). Runs when [consent] turns to yes, on each [refresh] and on each of [changes] while it reads
 * yes; asks made while a read is under way collapse into one more. [changes] is collected only while
 * opted in, so nothing is read for it otherwise. A read or a send that fails is logged ([warn]) and
 * sends nothing, so a property is never sent half-read. Each of [changes] is watched on its own: one
 * that fails is logged, and one that fails or ends (a store that stops after a failed read) is watched
 * again after [CHANGES_RETRY_MILLIS], the others and each [refresh] still sending them meanwhile, so a
 * store that can't be read never stops them going.
 */
class UsagePropertiesPublisher(
    private val consent: StateFlow<Boolean?>,
    private val read: suspend () -> UsageState,
    private val send: (Map<String, String>) -> Unit,
    private val worker: CoroutineDispatcher,
    private val warn: (String) -> Unit,
    // What the properties count changing (the starred rows, the saved places and journeys), from the
    // stores the counts are read from.
    private val changes: List<Flow<Any?>> = emptyList(),
) {
    private val asks = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun refresh() {
        asks.tryEmit(Unit)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope): Job = scope.launch(worker) {
        val optedIn = consent.map { it == true }.distinctUntilChanged()
        merge(
            optedIn.filter { it }.map {},
            asks,
            optedIn.flatMapLatest { yes -> if (yes) changes.map(::watched).merge() else emptyFlow() },
        )
            .conflate()
            .collect { publish() }
    }

    // One of [changes], begun again a while after it fails or ends, rather than ending the publisher or
    // going unwatched. Only its own failures are caught, never the collector's, and never a cancellation.
    private fun watched(source: Flow<Any?>): Flow<Unit> = flow {
        while (true) {
            emitAll(
                source.map {}.catch { e ->
                    if (e is CancellationException || e !is Exception) throw e
                    warn("usage property changes not watched: ${e::class.simpleName}")
                },
            )
            delay(CHANGES_RETRY_MILLIS)
        }
    }

    private suspend fun publish() {
        if (consent.value != true) return
        val properties = try {
            usageProperties(read())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("usage properties not read: ${e::class.simpleName}")
            return
        }
        // Withdrawn while reading: nothing goes.
        if (consent.value != true) return
        try {
            send(properties)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("usage properties not sent: ${e::class.simpleName}")
        }
    }

    companion object {
        /** How long after one of [changes] fails or ends it's watched again: a store's read outage, retried unhurried. */
        internal const val CHANGES_RETRY_MILLIS = 60_000L
    }
}
