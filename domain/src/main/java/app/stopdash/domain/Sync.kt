package app.stopdash.domain

import androidx.annotation.WorkerThread
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * When a synced entry last changed (dev-docs/drive-sync.md *Merging*): a hybrid logical clock's
 * reading, the phone's wall time ([wall], epoch millis) unless a change it has read is later, then a
 * [counter] past that, and the [phone] (its install id) to break a tie. Ordered by all three, so two
 * stamps never tie: one phone never makes the same stamp twice, and two phones differ by [phone].
 */
@Serializable
data class SyncStamp(val wall: Long, val counter: Int, val phone: String) : Comparable<SyncStamp> {
    override fun compareTo(other: SyncStamp): Int = COMPARATOR.compare(this, other)

    private companion object {
        val COMPARATOR: Comparator<SyncStamp> = compareBy<SyncStamp>({ it.wall }, { it.counter }, { it.phone })
    }
}

/**
 * One synced entry's last change: its [value] as its store writes it (an opaque string to sync), or
 * null for a removal, kept for good so no phone's older copy brings the entry back.
 */
@Serializable
data class SyncEntry(val stamp: SyncStamp, val value: String?)

/**
 * A phone's synced view: each kind of entry ("rides", later places and the rest) by its id, and the
 * [latest] stamp it has seen, made or read, which its next change comes after. The phone's own ledger
 * and every phone's file in Drive are one of these. Kinds this build doesn't know are carried as they
 * are, so an older build passes a newer one's entries on untouched.
 */
data class SyncLedger(
    val kinds: Map<String, Map<String, SyncEntry>> = emptyMap(),
    val latest: SyncStamp? = null,
) {
    companion object {
        val EMPTY = SyncLedger()
    }
}

/**
 * Drive's clock as a sync reads it (maintainer, 2026-10-22): the `Date` on the answer that listed the
 * files ([driveMillis]), and the phone's monotonic time when it came ([elapsedAtAnswer],
 * `SystemClock.elapsedRealtime`). Every change is stamped by it, never by the phone's own clock, so
 * phones whose clocks disagree, by a minute or a year, stamp alike and never need setting right to
 * sync (Codex on #1141). Held for the one sync it was read for, never stored.
 */
data class DriveClock(val driveMillis: Long, val elapsedAtAnswer: Long) {
    /** Drive's time [elapsedNow] on the same monotonic clock: the answer's, plus the time since. */
    fun now(elapsedNow: Long): Long = driveMillis + maxOf(0L, elapsedNow - elapsedAtAnswer)
}

/** What applying a merged view changes in a store: entries to write ([put]), by id, and ids to remove. */
data class SyncChanges(val put: Map<String, String>, val removed: Set<String>) {
    val none: Boolean get() = put.isEmpty() && removed.isEmpty()
}

/**
 * The sync engine (dev-docs/drive-sync.md): a phone stamps what changed in its stores since it last
 * synced ([record]), takes the newest change of every entry across its own ledger and the other phones'
 * files ([merge]), and writes the result back to its stores ([valuesOf], [changes]). Merging is
 * order-free and repeatable, so reading the files in any order, any number of times, gives the same
 * view. It knows nothing of Drive, the stores or what an entry holds: no network, no storage.
 */
object Sync {
    /**
     * The stamp for a change [phone] makes at [nowMillis] (Drive's time, [DriveClock]), after [latest],
     * the latest it has seen: [nowMillis] where that's later, else [latest]'s time and one count more.
     */
    fun next(latest: SyncStamp?, nowMillis: Long, phone: String): SyncStamp =
        if (latest == null || nowMillis > latest.wall) {
            SyncStamp(nowMillis, 0, phone)
        } else {
            SyncStamp(latest.wall, latest.counter + 1, phone)
        }

    /**
     * [ledger] with the changes a store of [kind] made since it was last synced: [local] is the store as
     * it is now, each entry's value by its id. An entry new or changed since the ledger's copy, or one
     * the store dropped, takes a stamp from [phone] at [nowMillis], Drive's time as this sync reads it
     * ([DriveClock]), never the phone's own clock, after [ledger]'s latest and after
     * [seen]; one unchanged keeps its stamp, so a newer change from another phone still wins over it.
     * [same] says whether a ledger value and a local one are the same entry: one this build re-encodes
     * without fields only a newer build knows isn't a change here, so it never overwrites that build's
     * richer copy. [understood] says whether this build can read a ledger value at all: one it can't (a
     * newer build's format for that entry) is never in the store, as nothing here could write it there,
     * so its absence isn't a removal and it's kept as it is, for the build that wrote it.
     *
     * A sync reads the other phones' files first and passes their [latestOf] as [seen], then [merge]s
     * them in: the ledger, not the merged view, is what the store is compared with, so an entry only
     * another phone holds is never taken for one removed here. A ledger with no entries is a phone's
     * first sync, and everything in its stores is stamped after everything in those files, so joining
     * adds and never removes, even from a phone whose clock is a little behind (Codex on #1141).
     */
    @WorkerThread
    fun record(
        ledger: SyncLedger,
        kind: String,
        local: Map<String, String>,
        nowMillis: Long,
        phone: String,
        seen: SyncStamp? = null,
        understood: (ledgerValue: String) -> Boolean = { true },
        same: (ledgerValue: String, localValue: String) -> Boolean = { a, b -> a == b },
    ): SyncLedger {
        val before = ledger.kinds[kind].orEmpty()
        var latest = later(ledger.latest, seen)
        var changed = false
        fun stamp(): SyncStamp = next(latest, nowMillis, phone).also { latest = it; changed = true }
        val after = LinkedHashMap<String, SyncEntry>(before.size + local.size)
        for ((id, entry) in before) {
            val value = local[id]
            after[id] = when {
                value == null && entry.value == null -> entry
                value == null && entry.value != null && !understood(entry.value) -> entry
                value == null -> SyncEntry(stamp(), null)
                entry.value != null && same(entry.value, value) -> entry
                else -> SyncEntry(stamp(), value)
            }
        }
        for ((id, value) in local) {
            if (id !in before) after[id] = SyncEntry(stamp(), value)
        }
        if (!changed) return ledger
        return SyncLedger(ledger.kinds + (kind to after), latest)
    }

    /**
     * The latest stamp in [views] (the other phones' files, as read): what a sync's [record] stamps its
     * changes after.
     */
    @WorkerThread
    fun latestOf(views: List<SyncLedger>): SyncStamp? {
        var latest: SyncStamp? = null
        for (view in views) {
            latest = later(latest, view.latest)
            for (entries in view.kinds.values) for (entry in entries.values) latest = later(latest, entry.stamp)
        }
        return latest
    }

    /**
     * [ledger] with every entry's newest change across it and [others] (the other phones' files), and
     * its [SyncLedger.latest] moved past every stamp read, so a change made after this comes after all
     * of them. Kinds this build doesn't know merge the same way.
     */
    @WorkerThread
    fun merge(ledger: SyncLedger, others: List<SyncLedger>): SyncLedger {
        val views = listOf(ledger) + others
        val kinds = HashMap<String, HashMap<String, SyncEntry>>()
        for (view in views) {
            for ((kind, entries) in view.kinds) {
                val merged = kinds.getOrPut(kind) { HashMap() }
                for ((id, entry) in entries) {
                    val held = merged[id]
                    if (held == null || entry.stamp > held.stamp) merged[id] = entry
                }
            }
        }
        return SyncLedger(kinds, latestOf(views))
    }

    private fun later(a: SyncStamp?, b: SyncStamp?): SyncStamp? = if (a == null || b != null && b > a) b else a

    /** The entries of [kind] [ledger] holds, by id, its removals left out: what its store should hold. */
    @WorkerThread
    fun valuesOf(ledger: SyncLedger, kind: String): Map<String, String> =
        ledger.kinds[kind].orEmpty().mapNotNull { (id, entry) -> entry.value?.let { id to it } }.toMap()

    /**
     * What turns a store holding [local] into one holding [merged] ([valuesOf]): the store applies it
     * under its own write lock, and only while it still holds [local], else a change made meanwhile
     * would be undone; the next sync records that change and merges again. [same] is [record]'s, so an
     * entry this build reads as unchanged isn't written again on every sync.
     */
    @WorkerThread
    fun changes(
        local: Map<String, String>,
        merged: Map<String, String>,
        same: (mergedValue: String, localValue: String) -> Boolean = { a, b -> a == b },
    ): SyncChanges =
        SyncChanges(
            put = merged.filter { (id, value) -> local[id]?.let { same(value, it) } != true },
            removed = local.keys.filterTo(LinkedHashSet()) { it !in merged },
        )
}

/** A phone's file in Drive, or its own ledger, as read ([SyncFile.decode]). */
sealed interface SyncFileRead {
    data class Read(val ledger: SyncLedger) : SyncFileRead

    /** Written by a newer StopDash in a format this build can't read: skipped, never misread. */
    data class Newer(val version: Int) : SyncFileRead

    /** Not a sync file at all (cut short, or not JSON): skipped and said. */
    data object Unreadable : SyncFileRead
}

/**
 * The sync file's format: JSON, one per phone in Drive (`phone-<install id>.json`), and each phone's
 * own ledger. A field a later build adds is read past; a format an older build would misread bumps
 * [CURRENT_VERSION], which that build then skips ([SyncFileRead.Newer]).
 */
object SyncFile {
    const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * [ledger] as its file holds it. Kinds and ids in order, so the same view always reads the same and
     * a phone whose view hasn't changed has nothing new to upload.
     */
    @WorkerThread
    fun encode(ledger: SyncLedger): String {
        val kinds = ledger.kinds.toSortedMap().mapValues { (_, entries) -> entries.toSortedMap() }
        return json.encodeToString(FileDto.serializer(), FileDto(CURRENT_VERSION, ledger.latest, kinds))
    }

    /**
     * A file's text as a ledger, or why it can't be one. [warn], required so no caller drops it, is told
     * of an unreadable file by the exception's type and where it was thrown, never its message, which
     * could quote the file and so the rider's stops (AGENTS.md *Error handling*; Codex on #1141).
     */
    @WorkerThread
    fun decode(text: String, warn: (String) -> Unit): SyncFileRead {
        val file = try {
            val element = json.parseToJsonElement(text)
            // The version first: a newer format may shape its other fields in ways this build can't
            // decode, and that's a file to skip, not a broken one (Codex on #1141).
            val version = (element.jsonObject["version"] as? JsonPrimitive)?.intOrNull ?: 0
            if (version > CURRENT_VERSION) return SyncFileRead.Newer(version)
            json.decodeFromJsonElement(FileDto.serializer(), element)
        } catch (e: SerializationException) {
            return unreadable(e, warn)
        } catch (e: IllegalArgumentException) {
            return unreadable(e, warn)
        }
        return SyncFileRead.Read(SyncLedger(file.kinds, file.latest))
    }

    // Skipped and said, by its type and its first frames only.
    private fun unreadable(e: Exception, warn: (String) -> Unit): SyncFileRead {
        val at = e.stackTrace.take(STACK_FRAMES).joinToString("") { " at $it" }
        warn("sync file unreadable (${e.javaClass.simpleName}$at)")
        return SyncFileRead.Unreadable
    }

    private const val STACK_FRAMES = 3

    @Serializable
    private data class FileDto(
        // Absent reads as 0, below every real version, so a file without one is read, not skipped.
        val version: Int = 0,
        val latest: SyncStamp? = null,
        val kinds: Map<String, Map<String, SyncEntry>> = emptyMap(),
    )
}
