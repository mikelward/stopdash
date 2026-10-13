package app.stopdash.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.WorkerThread
import app.stopdash.domain.Coordinates
import app.stopdash.domain.LearnedPlace
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * The access points learned at the saved places ([app.stopdash.domain.WifiPlaces]), kept on the phone
 * only (SPEC *Privacy*): in a preferences file of their own that Android backup and device transfer
 * leave out (`backup_rules.xml`, `data_extraction_rules.xml`), so a router's address never leaves
 * the phone. They are relearned within a day or two, so a list that can't be read is dropped rather
 * than kept: unlike the saved places themselves, nothing the user made is lost with it.
 */
class WifiPlacesStore internal constructor(
    // Opened on first use, off the main thread, rather than when the store is made.
    prefsOf: () -> SharedPreferences,
    private val warn: (String) -> Unit = {},
) {
    private val prefs by lazy(prefsOf)

    /**
     * What has been learned; empty when nothing has, or what's stored can't be read. Null when a newer
     * build's list is stored: not this one's to use, or to write over (Codex).
     */
    @WorkerThread
    fun read(): List<LearnedPlace>? {
        val stored = prefs.getString(KEY, null) ?: return emptyList()
        val persisted = try {
            json.decodeFromString(PersistedWifiPlaces.serializer(), stored)
        } catch (e: SerializationException) {
            warn("Wi-Fi places unreadable (${e::class.simpleName}), relearning")
            clear()
            return emptyList()
        } catch (e: IllegalArgumentException) {
            warn("Wi-Fi places unreadable (${e::class.simpleName}), relearning")
            clear()
            return emptyList()
        }
        // A newer build's list: not this one's to read, so it's left as it is.
        if (persisted.version != VERSION) return null
        return persisted.places.map { LearnedPlace(it.placeId, Coordinates(it.lat, it.lon), it.accessPoints) }
    }

    /** Replaces what is stored with [learned]; an empty list removes the file's entry. */
    @WorkerThread
    fun write(learned: List<LearnedPlace>) {
        if (learned.isEmpty()) {
            clear()
            return
        }
        val persisted = PersistedWifiPlaces(VERSION, learned.map { PersistedWifiPlace(it.placeId, it.at.latitude, it.at.longitude, it.accessPoints) })
        prefs.edit().putString(KEY, json.encodeToString(PersistedWifiPlaces.serializer(), persisted)).apply()
    }

    /** Forgets everything learned: the setting turned off, or precise location taken away. */
    @WorkerThread
    fun clear() {
        if (prefs.contains(KEY)) prefs.edit().remove(KEY).apply()
    }

    companion object {
        /** The preferences file, named in the backup rules so it's left out of backup and transfer. */
        const val FILE = "wifi_places"

        private const val KEY = "learned"
        private const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun from(context: Context, warn: (String) -> Unit = {}): WifiPlacesStore =
            context.applicationContext.let { app -> WifiPlacesStore({ app.getSharedPreferences(FILE, Context.MODE_PRIVATE) }, warn) }
    }
}

@Serializable
internal data class PersistedWifiPlaces(val version: Int, val places: List<PersistedWifiPlace>)

@Serializable
internal data class PersistedWifiPlace(val placeId: String, val lat: Double, val lon: Double, val accessPoints: List<String>)
