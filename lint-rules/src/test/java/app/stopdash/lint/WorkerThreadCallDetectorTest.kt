package app.stopdash.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

// Lint's text output drops a message's backticks, so the expectations leave them out.
class WorkerThreadCallDetectorTest {
    // Stand-ins for the annotations and calls the check knows by name.
    private val stubs: Array<TestFile> = arrayOf(
        kotlin(
            """
            package androidx.compose.runtime
            annotation class Composable
            inline fun <T> remember(calculation: () -> T): T = calculation()
            fun LaunchedEffect(key: Any?, block: suspend () -> Unit) {}
            """,
        ).indented(),
        kotlin(
            """
            package androidx.annotation
            annotation class WorkerThread
            """,
        ).indented(),
        kotlin(
            """
            package kotlinx.coroutines
            interface CoroutineContext
            operator fun CoroutineContext.plus(other: CoroutineContext): CoroutineContext = other
            abstract class CoroutineDispatcher : CoroutineContext
            abstract class MainCoroutineDispatcher : CoroutineDispatcher()
            interface Job : CoroutineContext
            object NonCancellable : Job
            class CoroutineName(val name: String) : CoroutineContext
            interface CoroutineScope
            enum class CoroutineStart { DEFAULT, UNDISPATCHED }
            fun CoroutineScope.launch(
                context: CoroutineContext = NonCancellable,
                start: CoroutineStart = CoroutineStart.DEFAULT,
                block: suspend CoroutineScope.() -> Unit,
            ): Job = TODO()
            suspend fun <T> withContext(context: CoroutineContext, block: suspend () -> T): T = block()
            object Dispatchers {
                val Main: MainCoroutineDispatcher get() = TODO()
                val Default: CoroutineDispatcher get() = TODO()
                val Unconfined: CoroutineDispatcher get() = TODO()
            }
            """,
        ).indented(),
        kotlin(
            """
            package kotlinx.coroutines.flow
            interface Flow<T>
            fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = TODO()
            fun <T> Flow<T>.flowOn(context: kotlinx.coroutines.CoroutineContext): Flow<T> = this
            suspend fun <T> Flow<T>.collect(action: suspend (T) -> Unit) {}
            suspend fun <T> Flow<T>.toList(): List<T> = TODO()
            """,
        ).indented(),
        kotlin(
            """
            package app.stopdash.domain
            import androidx.annotation.WorkerThread
            object RouteStops {
                @WorkerThread fun resolve(stops: List<String>): List<String> = stops
                @WorkerThread fun resolveTwice(stops: List<String>): List<String> = resolve(resolve(stops))
                fun label(stop: String): String = stop
            }
            @WorkerThread
            class Index(val stops: List<String>)
            """,
        ).indented(),
    )

    private fun check(vararg files: TestFile) =
        lint().files(*stubs, *files).issues(WorkerThreadCallDetector.ISSUE).allowMissingSdk().testModes(TestMode.DEFAULT).run()

    @Test
    fun `a worker-thread call in composition, remember, an effect or a click handler is an error`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import androidx.compose.runtime.remember
                import app.stopdash.domain.Index
                import app.stopdash.domain.RouteStops
                @Composable
                fun Stops(stops: List<String>, onClick: (() -> Unit) -> Unit) {
                    val resolved = RouteStops.resolve(stops)
                    val remembered = remember(stops) { RouteStops.resolve(stops) }
                    LaunchedEffect(stops) { RouteStops.resolve(stops) }
                    onClick { RouteStops.resolve(stops) }
                    val index = Index(stops)
                    val label = RouteStops.label("A")
                }
                """,
            ).indented(),
        ).expectErrorCount(5).expectContains("RouteStops.resolve is marked @WorkerThread but called on the main thread, in composable Stops")
    }

    @Test
    fun `off the main thread, or outside composition, it is fine`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import app.stopdash.domain.RouteStops
                import androidx.annotation.WorkerThread
                import kotlinx.coroutines.CoroutineDispatcher
                import kotlinx.coroutines.CoroutineName
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.NonCancellable
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.collect
                import kotlinx.coroutines.flow.flowOn
                import kotlinx.coroutines.flow.map
                import kotlinx.coroutines.flow.toList
                import kotlinx.coroutines.launch
                import kotlinx.coroutines.plus
                import kotlinx.coroutines.withContext
                @Composable
                fun Stops(stops: List<String>, flow: Flow<List<String>>, worker: CoroutineDispatcher) {
                    LaunchedEffect(stops) { withContext(Dispatchers.Default) { RouteStops.resolve(stops) } }
                    LaunchedEffect(stops) { withContext(worker) { RouteStops.resolve(stops) } }
                    LaunchedEffect(stops) { withContext(NonCancellable + worker) { RouteStops.resolve(stops) } }
                    val hopped = flow.map { RouteStops.resolve(it) }.flowOn(Dispatchers.Default)
                    val stored = RouteStops::resolve
                    val held = keep(RouteStops::resolve)
                    val heldStored = keep(stored)
                    val viaWorker = flow.map(stored).flowOn(Dispatchers.Default)
                    val mapped = flow.map { RouteStops.resolve(it) }
                    val moved = mapped.flowOn(Dispatchers.Default)
                    LaunchedEffect(stops) { withContext(CoroutineName("resolve") + Dispatchers.Default) { RouteStops.resolve(stops) } }
                    LaunchedEffect(stops) { withContext(Dispatchers.Default) { flow.collect { RouteStops.resolve(it) } } }
                    // Collected in place, on the worker.
                    LaunchedEffect(stops) { withContext(Dispatchers.Default) { flow.map { RouteStops.resolve(it) }.toList() } }
                    // Handed to a helper that runs it on a worker.
                    val worked = onWorker(stops) { RouteStops.resolve(stops) }
                }
                @Composable
                fun <T> onWorker(key: Any, @WorkerThread compute: () -> T): T? {
                    LaunchedEffect(key) { withContext(Dispatchers.Default) { compute() } }
                    return null
                }
                fun <T> keep(value: T) = value
                @WorkerThread fun helper(stops: List<String>) = RouteStops.resolve(stops)
                fun launched(scope: CoroutineScope, stops: List<String>) = scope.launch(Dispatchers.Default) { RouteStops.resolve(stops) }
                @Composable
                fun Page(stops: List<String>) {
                    // Declared here, but run only inside the hop.
                    fun resolve() = RouteStops.resolve(stops)
                    LaunchedEffect(stops) { withContext(Dispatchers.Default) { resolve() } }
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `withContext or flowOn onto the main thread, or a flow collected without flowOn, is still the main thread`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import app.stopdash.domain.RouteStops
                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.CoroutineStart
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.NonCancellable
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.flowOn
                import kotlinx.coroutines.flow.map
                import kotlinx.coroutines.launch
                import kotlinx.coroutines.plus
                import kotlinx.coroutines.withContext
                val Typed: @Composable (List<String>) -> Unit = { RouteStops.resolve(it) }
                fun provideContent(content: @Composable () -> Unit) {}
                fun widget(stops: List<String>) = provideContent { RouteStops.resolve(stops) }
                @Composable
                fun Stops(stops: List<String>, flow: Flow<List<String>>, scope: CoroutineScope, startMode: CoroutineStart) {
                    LaunchedEffect(stops) { withContext(Dispatchers.Main) { RouteStops.resolve(stops) } }
                    val main = Dispatchers.Main
                    LaunchedEffect(stops) { withContext(main) { RouteStops.resolve(stops) } }
                    LaunchedEffect(stops) { withContext(NonCancellable) { RouteStops.resolve(stops) } }
                    val collected = flow.map { RouteStops.resolve(it) }
                    val onMain = flow.map { RouteStops.resolve(it) }.flowOn(Dispatchers.Unconfined)
                    LaunchedEffect(stops) {
                        withContext(Dispatchers.Default) { withContext(Dispatchers.Main) { RouteStops.resolve(stops) } }
                    }
                    scope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { RouteStops.resolve(stops) }
                    val mode = CoroutineStart.UNDISPATCHED
                    // A helper's parameter not marked @WorkerThread may run its block right here.
                    val unmarked = inPlace(stops) { RouteStops.resolve(stops) }
                    // Nor is the mark trusted where the helper runs the block in place, or hands it on.
                    val marked = markedInPlace(stops) { RouteStops.resolve(stops) }
                    val handed = markedHandedOn(stops) { RouteStops.resolve(stops) }
                    // Nor where it builds the block's call on a worker but runs it after, back here.
                    LaunchedEffect(stops) { markedDeferred(stops) { RouteStops.resolve(stops) } }
                    // A block built on a worker but run back here, whichever hop built it.
                    LaunchedEffect(stops) {
                        val run = withContext(Dispatchers.Default) { { RouteStops.resolve(stops) } }
                        run()
                    }
                    val later = runsOnWorker(stops) { { RouteStops.resolve(stops) } }
                    later?.invoke()
                    scope.launch(Dispatchers.Default, start = mode) { RouteStops.resolve(stops) }
                    val resolve = RouteStops::resolve
                    val invoked = resolve(stops)
                    val passed = stops.let(resolve)
                    val twice = flow.map { RouteStops.resolve(it) }
                    val shifted = twice.flowOn(Dispatchers.Default)
                    val unshifted = twice
                    scope.launch(Dispatchers.Default, start = startMode) { RouteStops.resolve(stops) }
                    LaunchedEffect(stops) { withContext(Dispatchers.Default + Dispatchers.Main) { RouteStops.resolve(stops) } }
                    LaunchedEffect(stops) {
                        // Built on a worker, collected here: its operator runs on the main thread.
                        val built = withContext(Dispatchers.Default) { flow.map { RouteStops.resolve(it) } }
                    }
                    val referenced = stops.let(RouteStops::resolve)
                    // Set to a worker, then moved back: a `var` isn't trusted to hold its first value.
                    var dispatcher = Dispatchers.Default
                    dispatcher = Dispatchers.Main
                    scope.launch(dispatcher) { RouteStops.resolve(stops) }
                    var later = CoroutineStart.DEFAULT
                    later = CoroutineStart.UNDISPATCHED
                    scope.launch(Dispatchers.Default, start = later) { RouteStops.resolve(stops) }
                }
                fun <T> inPlace(key: Any, compute: () -> T): T = compute()
                fun <T> markedInPlace(key: Any, @androidx.annotation.WorkerThread compute: () -> T): T = compute()
                fun <T> markedHandedOn(key: Any, @androidx.annotation.WorkerThread compute: () -> T): T = inPlace(key, compute)
                @Composable
                fun <T> runsOnWorker(key: Any, @androidx.annotation.WorkerThread compute: () -> T): T? {
                    LaunchedEffect(key) { withContext(Dispatchers.Default) { compute() } }
                    return null
                }
                suspend fun <T> markedDeferred(key: Any, @androidx.annotation.WorkerThread compute: () -> T): T {
                    val run = withContext(Dispatchers.Default) { { compute() } }
                    return run()
                }
                """,
            ).indented(),
        ).expectErrorCount(25)
    }

    @Test
    fun `a helper in any file is followed into, unless it hops off the main thread`() {
        check(
            kotlin(
                """
                package app.stopdash.data
                import app.stopdash.domain.RouteStops
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.flow.map
                import kotlinx.coroutines.withContext
                fun unmarked(stops: List<String>) = RouteStops.resolve(stops)
                fun both(stops: List<String>) = RouteStops.resolve(stops) + RouteStops.resolveTwice(stops)
                fun again(stops: List<String>) = RouteStops.resolve(stops) + RouteStops.resolve(stops)
                fun runsTwice(stops: List<String>): List<String> {
                    val run = { RouteStops.resolve(stops) }
                    return run() + run()
                }
                fun deeper(stops: List<String>) = unmarked(stops).map { it }
                suspend fun hopped(stops: List<String>) = withContext(Dispatchers.Default) { RouteStops.resolve(stops) }
                fun cheap(stops: List<String>) = stops.size
                fun referenced(stops: List<String>) = stops.let(RouteStops::resolve)
                fun deferred(stops: List<String>): () -> List<String> = { RouteStops.resolve(stops) }
                fun resolving(flow: kotlinx.coroutines.flow.Flow<List<String>>) = flow.map { RouteStops.resolve(it) }
                fun runsStored(stops: List<String>): List<String> {
                    val run = { RouteStops.resolve(stops) }
                    return run()
                }
                fun declaresOnly(stops: List<String>): Int {
                    fun unused() = RouteStops.resolve(stops)
                    return stops.size
                }
                fun callsLocal(stops: List<String>): Int {
                    fun used() = RouteStops.resolve(stops)
                    return used().size
                }
                """,
            ).indented(),
            kotlin(
                """
                package app.stopdash.ui
                import androidx.annotation.WorkerThread
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import app.stopdash.data.again
                import app.stopdash.data.both
                import app.stopdash.data.callsLocal
                import app.stopdash.data.cheap
                import app.stopdash.data.declaresOnly
                import app.stopdash.data.deferred
                import app.stopdash.data.resolving
                import app.stopdash.data.runsStored
                import app.stopdash.data.runsTwice
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.flow.flowOn
                import kotlinx.coroutines.flow.toList
                import kotlinx.coroutines.withContext
                import app.stopdash.data.deeper
                import app.stopdash.data.hopped
                import app.stopdash.data.referenced
                import app.stopdash.data.unmarked
                import app.stopdash.domain.RouteStops
                @WorkerThread fun marked(stops: List<String>) = RouteStops.resolve(stops)
                @Composable
                fun Stops(stops: List<String>, flow: kotlinx.coroutines.flow.Flow<List<String>>) {
                    val one = unmarked(stops)
                    val two_works = both(stops)
                    val two_calls = again(stops)
                    val two = deeper(stops)
                    val three = marked(stops)
                    LaunchedEffect(stops) { hopped(stops) }
                    val four = cheap(stops)
                    val five = referenced(stops)
                    val six = declaresOnly(stops)
                    val seven = callsLocal(stops)
                    val later = deferred(stops)
                    val now = deferred(stops)()
                    val kept = deferred(stops)
                    val runNow = kept()
                    val ran = runsStored(stops)
                    val ranTwice = runsTwice(stops)
                    val moved = resolving(flow).flowOn(Dispatchers.Default)
                    val unmoved = resolving(flow)
                    // Collected in place on the worker: fine.
                    LaunchedEffect(stops) { withContext(Dispatchers.Default) { resolving(flow).toList() } }
                    LaunchedEffect(stops) {
                        // Only built on the worker; collected here, on the main thread.
                        val pending = withContext(Dispatchers.Default) { resolving(flow) }
                        pending.toList()
                    }
                }
                """,
            ).indented(),
        ).expectErrorCount(14)
            .expectContains("RouteStops.resolve is marked @WorkerThread but called 2 times on the main thread (through runsTwice())")
            .expectContains("RouteStops.resolve is marked @WorkerThread but called 2 times on the main thread (through again())")
            .expectContains("RouteStops.resolve is marked @WorkerThread but called on the main thread (through unmarked()), in composable Stops")
            .expectContains("RouteStops.resolveTwice is marked @WorkerThread but called on the main thread (through both())")
            .expectContains("(through resolving())")
            .expectContains("(through deferred())")
            .expectContains("(through runsStored())")
            .expectContains("(through callsLocal())")
            .expectContains("RouteStops.resolve is marked @WorkerThread but called on the main thread (through referenced()), in composable Stops")
            .expectContains("RouteStops.resolve is marked @WorkerThread but called on the main thread (through deeper()), in composable Stops")
            .expectContains("marked is marked @WorkerThread but called on the main thread, in composable Stops")
    }

    @Test
    fun `a local function is checked where it is called`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.remember
                import app.stopdash.domain.RouteStops
                @Composable
                fun Page(stops: List<String>, onClick: (() -> Unit) -> Unit) {
                    fun resolve() = RouteStops.resolve(stops)
                    fun twice() = resolve() + resolve()
                    val now = remember(stops) { resolve() }
                    onClick { twice() }
                    fun one(stop: String) = RouteStops.resolve(listOf(stop))
                    stops.forEach(::one)
                    fun all() = RouteStops.resolve(stops)
                    val work = ::all
                    val worked = work()
                }
                """,
            ).indented(),
        ).expectErrorCount(4).expectContains("RouteStops.resolve is marked @WorkerThread but called on the main thread (through resolve()), in composable Page")
            .expectContains("RouteStops.resolve is marked @WorkerThread but called 2 times on the main thread (through twice())")
    }
}
