package app.stopdash.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import app.stopdash.domain.ConnectedWifi
import app.stopdash.domain.Coordinates
import app.stopdash.savedPlacesOf
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlacesSet
import app.stopdash.domain.LearnedPlace
import app.stopdash.domain.LocationFix
import app.stopdash.domain.MoveFollow
import app.stopdash.domain.RecognizedPlace
import app.stopdash.domain.WifiPlaceSource
import app.stopdash.domain.WifiPlaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The saved places' Wi-Fi on the phone (SPEC *Finding stops → Wi-Fi at saved places*): which saved
 * place the connected access point or network says the rider is at, and what each sure fix teaches
 * ([WifiPlaces]). Only while the setting is on and precise location is allowed; otherwise nothing is
 * read or used, and what was learned is deleted. While either can't be told (the stored choice not yet
 * read, a newer build's list) nothing is used, learned or deleted.
 *
 * Every step runs on [io], learning in the background on [scope]: the stored list is a file, and
 * reading the connected network waits on a system callback. The log says what changed by count only,
 * never which network or place (SPEC *Privacy*).
 */
class AndroidWifiPlaces(
    private val context: Context,
    private val store: WifiPlacesStore,
    // The saved places as stored. A newer build's list can't be read, so nothing is decided on it; a
    // corrupt one discarded counts as none, so what was learned at it goes (Codex).
    private val places: suspend () -> FavoritePlacesSet,
    // The setting, or null while its stored choice hasn't been read: a slow start decides nothing.
    private val enabled: suspend () -> Boolean?,
    private val wifi: suspend () -> WifiReading?,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    // Where a fix is learned from, so the fix itself is never held up; the app's lives as long as it.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val warn: (String) -> Unit = {},
) : WifiPlaceSource {
    // One read-change-write at a time: fixes from the list and from its updates can land together.
    private val lock = Mutex()

    override suspend fun recognize(near: Coordinates?): RecognizedPlace? = withContext(io) {
        // Nothing learned: no reason to ask what the phone is connected to.
        if (withCurrent { _, learned -> learned.isNotEmpty() } != true) return@withContext null
        val reading = wifi() ?: return@withContext null
        // Everything else read again after the wait (see [withCurrent]). The network's id only when a
        // place has one to match it with, so no key is made for nothing.
        withCurrent { saved, learned ->
            WifiPlaces.recognize(learned, saved, connected(reading, keyed = learned.any { it.networks.isNotEmpty() }), near)
        }
    }

    // What the rules are handed of [reading]: the network's keyed id ([keyed]) in place of its name.
    // Only under the lock, with the setting on and precise location allowed: the first id makes the key.
    private fun connected(reading: WifiReading, keyed: Boolean = true) =
        ConnectedWifi(reading.bssid, reading.metered, reading.ssid?.takeIf { keyed }?.let(store::networkId))

    override fun observe(fix: LocationFix) {
        // Most fixes teach nothing: settled here, before anything is started.
        if (!MoveFollow.isSure(fix)) return
        scope.launch(io) {
            try {
                learn(fix)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only this lesson is lost; the next sure fix teaches it again.
                warn("Wi-Fi place learning failed: ${e::class.simpleName}")
            }
        }
    }

    /**
     * Brings what's stored in line with now, whether or not a fix comes: everything deleted while the
     * setting is off or precise location isn't allowed, and a removed or moved place's own deleted. In
     * the background; the app calls it as it comes to the foreground (a permission taken away closes
     * the app) and whenever the saved places change.
     */
    fun reconcile() {
        scope.launch(io) {
            try {
                tidy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Tried again on the next foreground or change; nothing stored is ever used meanwhile
                // without the setting and precise location, which recognize checks for itself.
                warn("Wi-Fi places check failed: ${e::class.simpleName}")
            }
        }
    }

    private suspend fun learn(fix: LocationFix) {
        // Tidied first, so a sure fix off Wi-Fi still clears a removed or moved place.
        tidy() ?: return
        val reading = wifi() ?: return
        // Everything else read again after the wait (see [withCurrent]).
        withCurrent { saved, before ->
            val after = WifiPlaces.learn(before, saved, fix, connected(reading))
            if (after != before) {
                store.write(after)
                warn(changed(before, after))
            }
        }
    }

    // [reconcile]'s work; null when there was nothing to do it with.
    private suspend fun tidy(): Unit? = withCurrent { saved, before ->
        val kept = WifiPlaces.current(before, saved)
        if (kept != before) {
            store.write(kept)
            warn(changed(before, kept))
        }
    }

    // Counts only, never which network or place (SPEC Privacy).
    private fun changed(before: List<LearnedPlace>, after: List<LearnedPlace>): String =
        "Wi-Fi at saved places: ${WifiPlaces.count(after)} access points and ${WifiPlaces.networkCount(after)} networks known, " +
            "was ${WifiPlaces.count(before)} and ${WifiPlaces.networkCount(before)}"

    /** Deletes everything learned (the setting was turned off), in the background, outliving any screen. */
    fun forget() {
        scope.launch(io) { lock.withLock { store.clear() } }
    }

    /**
     * [decide] under the lock, from what is current: the saved places and the stored list, both read
     * inside it, while the setting is on and precise location is allowed. With the setting off or
     * precise location taken away, everything learned is deleted and the answer is null. Null too,
     * with nothing decided or deleted, while anything can't be told: the setting's stored choice not
     * yet read, or the saved places or the stored list a newer build's. Every decision about the
     * stored list is made here, after any wait and never from what was read before one, so the setting
     * turned off, precise location taken away, or a place removed or moved while the Wi-Fi was read is
     * what the decision sees (Codex).
     */
    private suspend fun <T> withCurrent(decide: (saved: List<FavoritePlace>, learned: List<LearnedPlace>) -> T): T? =
        lock.withLock {
            // Everything that waits comes first, and the setting and precise location are checked last,
            // with nothing between the check and the decision for a change to slip into (Codex).
            val saved = savedPlacesOf(places())
            val on = enabled()
            val precise = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (on == false || !precise) {
                store.clear()
                return@withLock null
            }
            // Not read yet: what the rider chose is unknown, so nothing learned is used or deleted (Codex).
            if (on == null) return@withLock null
            saved ?: return@withLock null
            // A newer build's list: not this one's to use or to write over (Codex).
            val learned = store.read() ?: return@withLock null
            decide(saved, learned)
        }

    companion object {
        @Volatile private var instance: AndroidWifiPlaces? = null

        /**
         * The app's one instance (process-wide, so every provider shares its lock): the stored list,
         * the saved places, the setting and the connected Wi-Fi.
         */
        fun from(context: Context, warn: (String) -> Unit): AndroidWifiPlaces =
            instance ?: synchronized(this) {
                instance ?: context.applicationContext.let { app ->
                    AndroidWifiPlaces(
                        context = app,
                        store = WifiPlacesStore.from(app, warn),
                        places = { DataStoreFavoritePlacesStore.from(app, warn).places().first() },
                        // Unknown until the stored choice is actually read: a rider who turned it off
                        // is never relearned, and one who left it on never loses it, just because the
                        // read was slow.
                        enabled = { WifiPlacesSetting.loaded().takeIf { WifiPlacesSetting.isLoaded.value } },
                        wifi = ConnectedWifiReader(app, warn)::connected,
                        warn = warn,
                    )
                }.also { instance = it }
            }
    }
}
