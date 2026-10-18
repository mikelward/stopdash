package app.stopdash.data

import app.stopdash.domain.StationMatch
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The place behind each starred row's stop (its stop area or station, name and modes), recorded
 * when the star is toggled, so "Find a station" can list a star by name even once the stop has
 * left the nearby cache and the widget's snapshot — a star set on a searched station's page is
 * in neither. A star row itself carries only its identity (SPEC D8); this is the display side, kept
 * apart. One JSON [file] in the no-backup directory: never backed up, logged, or sent
 * (`docs/PRIVACY.md`). Blocking; call it off the main thread. An unreadable or unparseable file
 * loads as empty and is deleted; a failed write is logged (a bare reason, never a stop) and dropped.
 * A file a newer build wrote ([PersistedStarredPlaces.CURRENT_VERSION]) loads as empty and is kept as
 * it is: never written over, so going back to that build loses nothing.
 */
internal class FileStarredPlacesStore(
    private val file: File,
    private val warn: (String) -> Unit = {},
) {
    private val tmp = File(file.path + ".tmp")

    /** Each recorded stop id's place. */
    @Synchronized
    fun load(): Map<String, StationMatch> = read().orEmpty()

    // The places, or null for a file a newer build wrote, kept unread.
    private fun read(): Map<String, StationMatch>? {
        if (tmp.exists() && !tmp.delete()) warn("starred places: stale temp file not deleted")
        if (!file.exists()) return emptyMap()
        return try {
            val text = file.readText()
            if ((savedVersion(text) ?: 1) > PersistedStarredPlaces.CURRENT_VERSION) {
                warn("starred places file is a newer schema version; preserving it, not overwriting")
                null
            } else {
                json.decodeFromString<PersistedStarredPlaces>(text).places.associate { it.stopId to StationMatch(it.id, it.name, it.modes) }
            }
        } catch (e: IOException) {
            discard("unreadable", e)
        } catch (e: SerializationException) {
            discard("unparseable", e)
        }
    }

    /**
     * In one write: record [add]'s places, then forget every stop not in [starred] — the file then
     * holds exactly the current stars' places. [starred] must be the star set as of this call; the
     * caller serializes reading it with this write, so an older read can't land last.
     */
    @Synchronized
    fun reconcile(starred: Set<String>, add: Map<String, StationMatch> = emptyMap()) {
        val current = read() ?: return
        val next = (current + add).filterKeys { it in starred }
        if (next != current) save(next)
    }

    private fun save(places: Map<String, StationMatch>) {
        try {
            val persisted = places.map { (stopId, p) -> PersistedStarredPlace(stopId, p.id, p.name, p.modes) }
            // Its directory is named, not checked, when the store is built ([AppDirs]), so made here.
            tmp.parentFile?.mkdirs()
            tmp.writeText(json.encodeToString(PersistedStarredPlaces(version = PersistedStarredPlaces.CURRENT_VERSION, places = persisted)))
            // Replace in one step, so a reader never sees a half-written file.
            if (!tmp.renameTo(file)) warn("starred places not saved: rename failed")
        } catch (e: IOException) {
            warn("starred places not saved: ${e::class.simpleName}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun discard(why: String, e: Exception): Map<String, StationMatch> {
        val deleted = file.delete()
        warn("starred places $why (${e::class.simpleName}), ${if (deleted) "deleted" else "not deleted"}")
        return emptyMap()
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class PersistedStarredPlaces(
    // A file without one is version 1, whatever this build's is; written even then, so every file
    // says its version. Each write names this build's ([CURRENT_VERSION]).
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val version: Int = 1,
    val places: List<PersistedStarredPlace> = emptyList(),
) {
    companion object {
        /** The current on-disk format. Bump when a field an older build would drop or misread is added. */
        const val CURRENT_VERSION = 1
    }
}

@Serializable
private data class PersistedStarredPlace(
    val stopId: String,
    val id: String,
    val name: String,
    val modes: List<String> = emptyList(),
)
