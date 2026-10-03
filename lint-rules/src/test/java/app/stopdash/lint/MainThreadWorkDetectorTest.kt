package app.stopdash.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest.kotlin
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import com.android.tools.lint.checks.infrastructure.TestMode
import org.junit.Test

// Lint's text output drops a message's backticks, so the expectations leave them out.
class MainThreadWorkDetectorTest {
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
            suspend fun <T> withContext(context: Any, block: suspend () -> T): T = block()
            object Dispatchers {
                val Main: Any = Any()
                val Default: Any = Any()
            }
            """,
        ).indented(),
        kotlin(
            """
            package kotlinx.coroutines.flow
            interface Flow<T>
            fun <T, R> Flow<T>.map(transform: suspend (T) -> R): Flow<R> = TODO()
            fun <T> Flow<T>.flowOn(context: Any): Flow<T> = this
            """,
        ).indented(),
        kotlin(
            """
            package app.stopdash.domain
            annotation class MainSafe
            object RouteStops {
                fun resolve(stops: List<String>): List<String> = stops
                @MainSafe fun label(stop: String): String = stop
                suspend fun load(line: String): List<String> = emptyList()
            }
            data class Row(val id: String)
            """,
        ).indented(),
        kotlin(
            """
            package app.stopdash.ui
            import androidx.compose.runtime.Composable
            @Composable
            fun <T> rememberComputed(vararg keys: Any?, placeholder: T, compute: suspend () -> T): T = placeholder
            @Composable
            fun Column(content: @Composable () -> Unit) {}
            @Composable
            fun Button(onClick: () -> Unit, content: @Composable () -> Unit) {}
            """,
        ).indented(),
    )

    private fun check(vararg files: TestFile) =
        lint().files(*stubs, *files).issues(MainThreadWorkDetector.ISSUE).allowMissingSdk().testModes(TestMode.DEFAULT).run()

    @Test
    fun `deriving a list in composition is an error`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.remember
                @Composable
                fun Stops(stops: List<String>) {
                    val shown = remember(stops) { stops.filter { it.isNotBlank() } }
                }
                """,
            ).indented(),
        ).expectErrorCount(1).expectContains("Deriving a collection (filter) on the main thread, in composable Stops")
    }

    @Test
    fun `a domain call not marked main-safe is an error, a marked one is not`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import app.stopdash.domain.RouteStops
                @Composable
                fun Stops(stops: List<String>) {
                    val resolved = RouteStops.resolve(stops)
                    val label = RouteStops.label("A")
                }
                """,
            ).indented(),
        ).expectErrorCount(1).expectContains("RouteStops.resolve, not marked @MainSafe")
    }

    @Test
    fun `an effect or click handler is still the main thread`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                @Composable
                fun Stops(stops: List<String>, onClick: (() -> Unit) -> Unit) {
                    LaunchedEffect(stops) { stops.sortedBy { it.length } }
                    onClick { stops.groupBy { it.length } }
                }
                """,
            ).indented(),
        ).expectErrorCount(2)
    }

    @Test
    fun `work in rememberComputed or withContext, or a suspend domain call, is fine`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import app.stopdash.domain.RouteStops
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext
                @Composable
                fun Stops(stops: List<String>) {
                    val shown = rememberComputed(stops, placeholder = emptyList<String>()) { RouteStops.resolve(stops).filter { it.isNotBlank() } }
                    LaunchedEffect(stops) {
                        RouteStops.load("victoria")
                        withContext(Dispatchers.Default) { stops.map { it.length } }
                    }
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `a plain UI helper is checked, a worker-thread one is not, and composition may not call a worker-thread one`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.annotation.WorkerThread
                import androidx.compose.runtime.Composable
                fun shown(stops: List<String>) = stops.distinct()
                @WorkerThread
                fun resolved(stops: List<String>) = stops.distinct()
                @Composable
                fun Stops(stops: List<String>) {
                    val all = resolved(stops)
                }
                """,
            ).indented(),
        ).expectErrorCount(2)
            .expectContains("in UI function shown")
            .expectContains("resolved, marked @WorkerThread,")
    }

    @Test
    fun `a suspend function's work before it hops is checked, after it is not`() {
        check(
            kotlin(
                """
                package app.stopdash.domain
                import kotlinx.coroutines.withContext
                suspend fun early(stops: List<String>) = stops.distinct()
                suspend fun hopped(stops: List<String>) = withContext(Any()) { stops.distinct() }
                """,
            ).indented(),
            kotlin(
                """
                package app.stopdash.ui
                suspend fun loaded(stops: List<String>) = stops.sorted()
                """,
            ).indented(),
        ).expectErrorCount(2)
            .expectContains("in suspend function early before it hops")
            .expectContains("in suspend function loaded before it hops")
    }

    @Test
    fun `a flow operator's work is checked unless its chain hops with flowOn`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import kotlinx.coroutines.flow.Flow
                import kotlinx.coroutines.flow.flowOn
                import kotlinx.coroutines.flow.map
                fun collected(stops: Flow<List<String>>) = stops.map { it.sorted() }
                fun hopped(stops: Flow<List<String>>) = stops.map { it.sorted() }.flowOn(Any())
                """,
            ).indented(),
        ).expectErrorCount(1).expectContains("in UI function collected")
    }

    @Test
    fun `withContext onto the main thread is no hop`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import kotlinx.coroutines.Dispatchers
                import kotlinx.coroutines.withContext
                suspend fun shown(stops: List<String>) = withContext(Dispatchers.Main) { stops.sorted() }
                """,
            ).indented(),
        ).expectErrorCount(1).expectContains("in suspend function shown before it hops")
    }

    @Test
    fun `a loop lays out items in composition, and walks the input anywhere else`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import androidx.compose.runtime.LaunchedEffect
                import androidx.compose.runtime.remember
                @Composable
                fun Stops(stops: List<String>) {
                    Column { for (stop in stops) println(stop) }
                    Button(onClick = { stops.forEach { println(it) } }) { stops.forEach { println(it) } }
                    LaunchedEffect(stops) { for (stop in stops) println(stop) }
                    val total = remember(stops) { var n = 0; for (stop in stops) n += stop.length; n }
                }
                fun count(stops: List<String>): Int { var n = 0; while (n < stops.size) n++; return n }
                """,
            ).indented(),
        ).expectErrorCount(4)
            .expectContains("A loop (forEach) on the main thread, in composable Stops")
            .expectContains("A loop (while) on the main thread, in UI function count")
    }

    @Test
    fun `a domain constructor that works must be off the main thread, and is checked where it's built`() {
        check(
            kotlin(
                """
                package app.stopdash.domain
                import androidx.annotation.WorkerThread
                class Board(val stops: List<String>) {
                    val shown = stops.filter { it.isNotBlank() }
                }
                @WorkerThread
                class Index(val stops: List<String>) {
                    val byName = stops.associateBy { it }
                }
                class Plain(val stop: String)
                object Names {
                    val all = listOf("A", "B").map { it.lowercase() }
                }
                """,
            ).indented(),
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import app.stopdash.domain.Index
                import app.stopdash.domain.Plain
                @Composable
                fun Stops(stops: List<String>) {
                    val index = Index(stops)
                    val plain = Plain("A")
                }
                """,
            ).indented(),
        ).expectErrorCount(2)
            .expectContains("in the constructor of Board")
            .expectContains("Constructing Index, marked @WorkerThread,")
    }

    @Test
    fun `laying out one item each, or a fixed few, is not deriving`() {
        check(
            kotlin(
                """
                package app.stopdash.ui
                import androidx.compose.runtime.Composable
                import app.stopdash.domain.Row
                @Composable
                fun Stops(stops: List<String>, row: Row) {
                    stops.forEach { println(it) }
                    for (stop in stops) println(stop)
                    val first = stops.firstOrNull()
                    val copied = row.copy(id = "B")
                }
                """,
            ).indented(),
        ).expectClean()
    }

    @Test
    fun `code outside the UI and outside composition is not checked`() {
        check(
            kotlin(
                """
                package app.stopdash.data
                fun shown(stops: List<String>) = stops.distinct().filter { it.isNotBlank() }
                """,
            ).indented(),
        ).expectClean()
    }
}
