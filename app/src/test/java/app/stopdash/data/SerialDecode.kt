package app.stopdash.data

import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Where a test client reads its answers: one thread, so the callbacks a client makes while reading
 * (a warning, a station lookup) come one at a time into a test's plain lists, even when the client
 * sends two requests at once (a plan's quickest and fewest-changes). [kotlinx.coroutines.Dispatchers.Unconfined]
 * wouldn't do: it carries on wherever the mock engine answered, which is a pool.
 */
internal val serialDecode: CoroutineDispatcher =
    Executors.newSingleThreadExecutor { task -> Thread(task, "test-decode").apply { isDaemon = true } }.asCoroutineDispatcher()
