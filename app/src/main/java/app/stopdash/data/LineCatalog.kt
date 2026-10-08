package app.stopdash.data

import app.stopdash.domain.LineRef
import app.stopdash.domain.riderLineName
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * TfL's lines, as *Lines…* searches them (SPEC *Finding a line*): fetched once a day and kept in one
 * file ([store]). A kept list is answered at once, whatever its age ([lines]), so a search never waits
 * on the network once it has had one; one a day old is renewed behind it ([refreshed]). Public TfL
 * data, never the rider's. A failed renewal keeps the last list and says so in the log; with none kept
 * [lines] throws, for the screen to say it couldn't load the lines.
 */
internal class LineCatalog(
    private val fetch: suspend () -> List<LineRef>,
    private val store: FileLineCatalogStore,
    private val now: () -> Instant = Instant::now,
    private val warn: (String) -> Unit = {},
) {
    private val lock = Mutex()
    private var held: LineCatalogFile? = null

    /**
     * The lines: the kept list, at once and whatever its age (renewed by [refreshed]), else a first one
     * from TfL. Blocking file and network work; call it off the main thread. Throws when no list was
     * ever kept and TfL can't be reached.
     */
    suspend fun lines(): List<LineRef> = lock.withLock {
        kept()?.lines ?: fetchAndKeep()
    }

    /**
     * A new list from TfL if the kept one is a day old (or dated after now), else null: the search shows
     * the kept one meanwhile and takes this one when it comes. Null too when TfL can't be reached, with
     * the kept list left as it was and the failure logged. Blocking; off the main thread.
     */
    suspend fun refreshed(): List<LineRef>? = lock.withLock {
        val kept = kept()
        if (kept != null && fresh(kept)) return@withLock null
        try {
            fetchAndKeep()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A day-old list still names almost every line; a new route is the only thing it misses.
            warn("line list not refreshed (${e::class.simpleName}), kept one used")
            null
        }
    }

    private fun kept(): LineCatalogFile? = held ?: store.load()?.also { held = it }

    private suspend fun fetchAndKeep(): List<LineRef> {
        val lines = fetch()
        if (lines.isEmpty()) throw IOException("no lines")
        return LineCatalogFile(now().toEpochMilli(), lines).also {
            held = it
            store.save(it)
        }.lines
    }

    // Under a day old by the wall clock; one dated after now is an age that can't be told, so stale.
    private fun fresh(kept: LineCatalogFile): Boolean {
        val age = Duration.between(Instant.ofEpochMilli(kept.fetchedAt), now())
        return !age.isNegative && age < MAX_AGE
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofDays(1)

        /** The modes searched: every line a rider can take and StopDash shows; not National Rail's operators, nor coaches. */
        val MODES = listOf("tube", "dlr", "overground", "elizabeth-line", "tram", "bus", "river-bus", "cable-car")
    }
}

/** A kept list: when it was fetched (epoch milliseconds) and its lines. */
internal data class LineCatalogFile(val fetchedAt: Long, val lines: List<LineRef>)

// A line as kept on disk: [LineRef] lives in the pure domain module, which has no serialization.
@Serializable
private data class PersistedCatalogLine(val id: String, val name: String, val mode: String = "")

private fun LineRef.persisted() = PersistedCatalogLine(id, name, mode)

// Renamed on the way back in, as every saved name is ([riderLineName]).
private fun PersistedCatalogLine.toDomain() = LineRef(id, riderLineName(name, mode), mode)

@Serializable
private data class PersistedLineCatalog(val fetchedAt: Long, val lines: List<PersistedCatalogLine> = emptyList())

/**
 * The kept line list, in one JSON [file] in the app's cache: TfL's public lines, nothing of the
 * rider's. An unreadable file loads as none and is deleted; a failed write is logged and the list is
 * fetched again next time.
 */
internal class FileLineCatalogStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun load(): LineCatalogFile? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<PersistedLineCatalog>(file.readText()).let { saved ->
                LineCatalogFile(saved.fetchedAt, saved.lines.map { it.toDomain() })
            }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: IllegalArgumentException) {
            discard("unparseable", e)
        }
    }

    @Synchronized
    fun save(catalog: LineCatalogFile) {
        try {
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(PersistedLineCatalog(catalog.fetchedAt, catalog.lines.map { it.persisted() })))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("line list not saved: rename failed")
        } catch (e: IOException) {
            warn("line list not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun discard(why: String, e: Exception): LineCatalogFile? {
        val deleted = file.delete()
        warn("line list $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The lines recently opened from *Lines…*, newest first, in one JSON [file] in the app's no-backup
 * directory, as the recent stations are ([FileRecentStationsStore]): which lines a rider looks up can
 * say where they go, so it survives a restart but never leaves the device. Blocking; call it off the
 * main thread. An unreadable file loads as empty and is deleted; a failed write is logged (a bare
 * reason, never a line) and the list is simply not remembered.
 */
internal class FileRecentLinesStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun load(): List<LineRef> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString<RecentLinesFile>(file.readText()).lines.map { it.toDomain() }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        } catch (e: IllegalArgumentException) {
            discard("unparseable", e)
        }
    }

    /** Put [opened] at the front of the list ([app.stopdash.domain.RecentLines.add]), save it, and return it. */
    @Synchronized
    fun add(opened: LineRef): List<LineRef> {
        val lines = app.stopdash.domain.RecentLines.add(load(), opened)
        try {
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(RecentLinesFile(lines.map { it.persisted() })))
            if (!tmp.renameTo(file)) warn("recent lines not saved: rename failed")
        } catch (e: IOException) {
            warn("recent lines not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        return lines
    }

    private fun discard(why: String, e: Exception): List<LineRef> {
        val deleted = file.delete()
        warn("recent lines $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return emptyList()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class RecentLinesFile(val lines: List<PersistedCatalogLine> = emptyList())
