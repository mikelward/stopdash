package app.stopdash.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The schema version a saved JSON object says it is, read before anything else in it: a file a newer
 * build wrote may carry a field this build parses written in a shape it can't read, so it is told
 * apart by its version alone, never by decoding the rest (dev-docs/station-model.md *A saved level
 * comes with a version*). A file from before versions were kept is version 1; null when the text
 * isn't a whole JSON object, for the caller to treat as it treats any unparseable file.
 */
internal fun savedVersion(text: String): Int? = try {
    versionJson.decodeFromString<SavedVersion>(text).version
} catch (e: SerializationException) {
    // Not whole: the caller's own decode reports it and handles it as unparseable.
    null
} catch (e: IllegalArgumentException) {
    null
}

@Serializable
private class SavedVersion(val version: Int = 1)

private val versionJson = Json { ignoreUnknownKeys = true }
