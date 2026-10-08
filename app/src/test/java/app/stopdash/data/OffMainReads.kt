package app.stopdash.data

import androidx.datastore.core.DataStore
import app.stopdash.ThreadRecorder
import java.io.Closeable
import java.util.concurrent.Executors
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

/**
 * For a store's "mapped off the main thread" test (AGENTS.md *Main thread: read and dispatch only*): a
 * [worker] on a thread of its own, named [WORKER], and [recorded] lists that note the thread of each
 * read of them ([reads]). A stored file holding a recorded list is mapped by reading it, so [reads] says
 * where the mapping itself ran.
 */
internal class OffMainReads : Closeable {
    private val recorder = ThreadRecorder()

    /** The thread each read of a [recorded] list ran on, as a snapshot. */
    val reads: List<String> get() = recorder.threads()

    val worker: ExecutorCoroutineDispatcher = Executors.newSingleThreadExecutor { Thread(it, WORKER) }.asCoroutineDispatcher()

    /** [items], noting the thread of each read. */
    fun <E> recorded(vararg items: E): List<E> = object : AbstractList<E>() {
        override val size: Int get() = items.size
        override fun get(index: Int): E {
            recorder.note()
            return items[index]
        }
    }

    /** A store's file, holding [stored]. */
    fun <T> dataStore(stored: T): DataStore<T> = object : DataStore<T> {
        override val data: Flow<T> = flowOf(stored)

        override suspend fun updateData(transform: suspend (t: T) -> T): T = transform(stored)
    }

    /** [read] run from a single thread of its own, named [CALLER], as a screen's collector would. */
    fun <R> fromCaller(read: suspend () -> R): R {
        val caller = Executors.newSingleThreadExecutor { Thread(it, CALLER) }.asCoroutineDispatcher()
        return caller.use { runBlocking(it) { read() } }
    }

    override fun close() = worker.close()

    companion object {
        const val WORKER = "store-worker"
        const val CALLER = "caller"
    }
}
