package app.stopdash.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The process's worker for CPU work that grows with its input — matching a train to its line's
 * routes, merging a line's routes, reading an alert's stations (AGENTS.md *Main thread: read and
 * dispatch only*). A main-safe function hops to it first and takes it as an injectable default, so a
 * test can pass a thread of its own.
 *
 * [compute] is [Dispatchers.Default]. Only a test application replaces it, with the test's main
 * looper, so a screen settles with the work done instead of racing a real thread; nothing in the
 * app does.
 */
object Workers {
    @Volatile
    var compute: CoroutineDispatcher = Dispatchers.Default
}
