package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The names of sync's files in the rider's Drive app data folder (dev-docs/drive-sync.md *One file per
 * phone*): one per phone, by its install id, and debug builds' apart from release builds' so a test build
 * never touches the rider's real setup.
 */
object SyncFiles {
    fun nameOf(phone: String, debug: Boolean): String = "${prefix(debug)}$phone.json"

    /** Whether [name] is a phone's file of this kind of build. */
    fun isPhoneFile(name: String, debug: Boolean): Boolean = name.startsWith(prefix(debug)) && name.endsWith(".json")

    private fun prefix(debug: Boolean) = if (debug) "debug-phone-" else "phone-"
}

/**
 * One store sync keeps in step with the other phones (dev-docs/drive-sync.md): its entries by kind, read
 * and applied as the store does, and how it reads a value ([understood], [same], as [Sync.record] takes
 * them). Favorite rides are the first.
 */
interface SyncedStore {
    val kinds: List<String>

    /** The store's entries by kind; null when it can't be synced now (a newer build's file). */
    suspend fun entries(): Map<String, Map<String, String>>?

    /**
     * [merged] written only while the store still holds [expected], and [mark] with it in the same write;
     * false, with nothing written, if not.
     */
    suspend fun apply(expected: Map<String, Map<String, String>>, merged: Map<String, Map<String, String>>, mark: String): Boolean

    /** The [mark] of the last sync written to the store ([apply]); null before any. */
    suspend fun mark(): String?

    fun understood(kind: String, value: String): Boolean

    fun same(kind: String, a: String, b: String): Boolean
}

/**
 * What a phone keeps between syncs, on the phone only and never in its backups (dev-docs/drive-sync.md
 * *Android backup*): its [ledger]; what it last read of each other phone's file, by id ([read]), so a
 * file unchanged since isn't downloaded again; its own file's text as last uploaded ([uploaded]); and a
 * sync's merge kept before it wrote the store ([pending]), until that sync keeps its result.
 */
data class SyncState(
    val ledger: SyncLedger = SyncLedger.EMPTY,
    val read: Map<String, ReadFile> = emptyMap(),
    val uploaded: String? = null,
    val pending: Pending? = null,
) {
    /** Another phone's file as last read: Drive's stamp on it then, and its text. */
    data class ReadFile(val modifiedTime: String, val text: String)

    /**
     * A sync's merged [ledger], kept before the store was written ([SyncRun]'s `keep`) with the [mark] the
     * store is written with. A sync stopped before keeping its result (the process gone, the state
     * unwritable) leaves it, and the next sync settles it by the store's own mark ([SyncedStore.mark]):
     * written, the merged ledger is what the store was last synced to; not, the ledger as it was. So a change
     * taken in from another phone is never taken for one here, nor an edit here, a removal included, for one
     * taken in (Codex on #1151).
     */
    data class Pending(val mark: String, val ledger: SyncLedger)

    companion object {
        val EMPTY = SyncState()
    }
}

/** How a sync ended, as the sync's status says it (dev-docs/drive-sync.md *What the rider sees*). */
sealed interface SyncOutcome {
    /**
     * In step with every file read. [removed]: the ids each kind lost here to another phone's removal.
     * [newerPhone]: a phone on a newer StopDash wrote a file this build can't read ("Update StopDash to sync
     * with your other phone"). [changed]: the kinds the store took changes in (the widget's pins follow
     * the rides).
     */
    data class Synced(
        val removed: Map<String, Set<String>>,
        val newerPhone: Boolean,
        val changed: Set<String> = emptySet(),
    ) : SyncOutcome

    /**
     * Drive came to nothing. Where only the upload failed, the store is in step with every file read and
     * [removed] and [changed] say what it took in, as [Synced]'s do (Codex on #1151); the next sync sends
     * this phone's file. Otherwise nothing here changed.
     */
    data class Failed(
        val failure: DriveFailure,
        val removed: Map<String, Set<String>> = emptyMap(),
        val changed: Set<String> = emptySet(),
    ) : SyncOutcome

    /** This phone's own store couldn't be synced (a newer build's file); nothing changed. */
    data object StoreUnsynced : SyncOutcome

    /** The store changed while this sync worked: nothing written, and the next sync takes the change in. */
    data object Overtaken : SyncOutcome
}

/**
 * One sync (dev-docs/drive-sync.md): list the folder, read the other phones' files that changed since last
 * read, stamp what changed in [store] since the last sync by Drive's clock, after everything those files
 * hold, merge, write the result to [store], and upload this phone's file if it changed. [phone] is this
 * install's id, [debug] whether this is a debug build ([SyncFiles]), [elapsed] the phone's monotonic clock
 * for Drive's ([DriveClock]). [warn] hears of a file that couldn't be read, by its kind of trouble alone.
 * [keep] writes the state before the store is ([SyncState.Pending]); where it throws, the store isn't
 * written and the throw is the caller's.
 */
class SyncRun(
    private val drive: DriveAppData,
    private val store: SyncedStore,
    private val phone: String,
    private val debug: Boolean,
    private val elapsed: () -> Long,
    private val warn: (String) -> Unit,
    private val keep: suspend (SyncState) -> Unit,
) {
    /** One sync from [state]: how it ended, and the state to keep for the next ([state] itself unless it changed). */
    @WorkerThread
    suspend fun run(state: SyncState): Pair<SyncOutcome, SyncState> {
        val listing = when (val listed = drive.list()) {
            is DriveResult.Failed -> return SyncOutcome.Failed(listed.failure) to state
            is DriveResult.Ok -> listed.value
        }
        val ownName = SyncFiles.nameOf(phone, debug)
        val own = listing.files.firstOrNull { it.name == ownName }
        val others = listing.files.filter { it.name != ownName && SyncFiles.isPhoneFile(it.name, debug) }

        // The other phones' files: those unchanged since last read from what was kept, the rest downloaded.
        val read = HashMap<String, SyncState.ReadFile>()
        for (file in others) {
            val kept = state.read[file.id]
            if (kept != null && kept.modifiedTime == file.modifiedTime) {
                read[file.id] = kept
                continue
            }
            when (val text = drive.download(file.id)) {
                is DriveResult.Failed -> {
                    // Gone since it was listed (its phone's rider deleted it): nothing to read.
                    if (text.failure == DriveFailure.NotFound) continue
                    return SyncOutcome.Failed(text.failure) to state
                }
                is DriveResult.Ok -> read[file.id] = SyncState.ReadFile(file.modifiedTime, text.value)
            }
        }
        var newerPhone = false
        val ledgers = read.values.mapNotNull { file ->
            when (val decoded = SyncFile.decode(file.text, warn)) {
                is SyncFileRead.Read -> decoded.ledger
                is SyncFileRead.Newer -> {
                    newerPhone = true
                    null
                }
                SyncFileRead.Unreadable -> null
            }
        }

        val local = store.entries() ?: return SyncOutcome.StoreUnsynced to state
        val now = listing.clock.now(elapsed())
        val seen = Sync.latestOf(ledgers)
        // A stopped sync's merge settled first: what follows builds on it, a new kept merge included, so an
        // overtaken sync never loses it (Codex on #1151).
        val base = settled(state)
        var recorded = base.ledger
        for (kind in store.kinds) {
            recorded = Sync.record(
                recorded, kind, local[kind].orEmpty(), now, phone, seen,
                understood = { store.understood(kind, it) },
                same = { a, b -> store.same(kind, a, b) },
            )
        }
        val merged = Sync.merge(recorded, ledgers)
        // What the store can hold: an entry in a shape this build can't read stays in the ledger, for the
        // build that wrote it, and never reaches the store, which keeps the readable copy it has, if any
        // (Codex on #1151): a ride a newer build changed is still this phone's ride, not a removed one.
        val values = store.kinds.associateWith { kind ->
            val mine = local[kind].orEmpty()
            val held = LinkedHashMap<String, String>()
            for ((id, value) in Sync.valuesOf(merged, kind)) {
                val readable = if (store.understood(kind, value)) value else mine[id]
                if (readable != null) held[id] = readable
            }
            held
        }
        val changed = store.kinds.filterTo(LinkedHashSet()) { kind ->
            !Sync.changes(local[kind].orEmpty(), values.getValue(kind)) { a, b -> store.same(kind, a, b) }.none
        }
        if (changed.isNotEmpty()) {
            // Kept before the store is written, so a sync stopped between the two is settled by the next.
            val mark = UUID.randomUUID().toString()
            keep(base.copy(pending = SyncState.Pending(mark, merged)))
            // Not written: the store's mark says so, and the next sync settles the kept merge as unwritten.
            if (!store.apply(local, values, mark)) return SyncOutcome.Overtaken to base
        }
        val removed = store.kinds.associateWith { kind ->
            local[kind].orEmpty().keys.filterTo(LinkedHashSet()) { it !in values.getValue(kind) }
        }.filterValues { it.isNotEmpty() }

        // This phone's file, written only when it says something new, or isn't there (the rider deleted it).
        val text = SyncFile.encode(merged)
        val uploaded = when {
            own == null -> drive.create(ownName, text)
            text != state.uploaded -> drive.update(own.id, text)
            else -> null
        }
        val next = SyncState(merged, read, if (uploaded == null) state.uploaded else text)
        if (uploaded is DriveResult.Failed) {
            // The store is in step; only the upload waits for the next sync, which compares against what
            // was last uploaded and so sends it then.
            return SyncOutcome.Failed(uploaded.failure, removed, changed) to next.copy(uploaded = state.uploaded)
        }
        return SyncOutcome.Synced(removed, newerPhone, changed) to next
    }

    // [state] with a stopped sync's merge settled by the store's mark ([SyncState.Pending]): the merged
    // ledger where the store was written with it, else the ledger as it was.
    private suspend fun settled(state: SyncState): SyncState {
        val pending = state.pending ?: return state
        val ledger = if (store.mark() == pending.mark) pending.ledger else state.ledger
        return SyncState(ledger, state.read, state.uploaded)
    }
}

/**
 * The [SyncState] file's format, kept on the phone only: the ledger as [SyncFile] writes it, the other
 * phones' files as last read, and this phone's file as last uploaded. A newer format reads as nothing
 * kept, never misread: the next sync reads every file afresh, and only stamps changes it finds.
 */
object SyncStateFile {
    private const val CURRENT_VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @WorkerThread
    fun encode(state: SyncState): String = json.encodeToString(
        StateDto.serializer(),
        StateDto(
            CURRENT_VERSION,
            SyncFile.encode(state.ledger),
            state.read.toSortedMap().mapValues { (_, file) -> ReadDto(file.modifiedTime, file.text) },
            state.uploaded,
            state.pending?.let { PendingDto(it.mark, SyncFile.encode(it.ledger)) },
        ),
    )

    /** [text] as a state, or null where it can't be one ([warn] told by the exception's type and first frames). */
    @WorkerThread
    fun decode(text: String, warn: (String) -> Unit): SyncState? {
        val dto = try {
            val element = json.parseToJsonElement(text)
            val version = (element.jsonObject["version"] as? JsonPrimitive)?.intOrNull ?: 0
            if (version > CURRENT_VERSION) return null
            json.decodeFromJsonElement(StateDto.serializer(), element)
        } catch (e: SerializationException) {
            return unreadable(e, warn)
        } catch (e: IllegalArgumentException) {
            return unreadable(e, warn)
        }
        val ledger = when (val read = SyncFile.decode(dto.ledger, warn)) {
            is SyncFileRead.Read -> read.ledger
            else -> return null
        }
        val pending = dto.pending?.let { kept ->
            when (val read = SyncFile.decode(kept.ledger, warn)) {
                is SyncFileRead.Read -> SyncState.Pending(kept.mark, read.ledger)
                else -> return null
            }
        }
        return SyncState(ledger, dto.read.mapValues { (_, file) -> SyncState.ReadFile(file.modifiedTime, file.text) }, dto.uploaded, pending)
    }

    private fun unreadable(e: Exception, warn: (String) -> Unit): SyncState? {
        val at = e.stackTrace.take(STACK_FRAMES).joinToString("") { " at $it" }
        warn("sync state unreadable (${e.javaClass.simpleName}$at)")
        return null
    }

    private const val STACK_FRAMES = 3

    @Serializable
    private data class StateDto(
        val version: Int = 0,
        val ledger: String,
        val read: Map<String, ReadDto> = emptyMap(),
        val uploaded: String? = null,
        val pending: PendingDto? = null,
    )

    @Serializable
    private data class ReadDto(val modifiedTime: String, val text: String)

    @Serializable
    private data class PendingDto(val mark: String, val ledger: String)
}
