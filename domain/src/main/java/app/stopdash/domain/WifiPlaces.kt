package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The Wi-Fi access point the phone is connected to, as far as recognizing a saved place goes: its
 * [bssid] (the access point's own address, unique to it, unlike a network name many homes share),
 * whether Android counts the network as [metered] — a phone's hotspot, a train's or a bus's Wi-Fi —
 * which moves with the rider and so never says where they are, and an opaque id for the network's
 * name ([network]), the same for every access point of one network and never the name itself.
 */
data class ConnectedWifi(val bssid: String, val metered: Boolean, val network: String? = null)

/**
 * The access points and networks learned at one saved place ([placeId]), with where the place was
 * ([at]) when they were learned: a place edited to somewhere else starts afresh rather than keep the
 * old spot's Wi-Fi. [accessPoints] and [networks] are oldest first, so the oldest goes when the place
 * has more than [WifiPlaces.MAX_PER_PLACE] or [WifiPlaces.MAX_NETWORKS_PER_PLACE].
 */
data class LearnedPlace(
    val placeId: String,
    val at: Coordinates,
    val accessPoints: List<String>,
    val networks: List<String> = emptyList(),
)

/** How the connected Wi-Fi said where the rider is: by one of a place's access points, or by its network. */
enum class WifiMatch { ACCESS_POINT, NETWORK }

/** The saved place the connected Wi-Fi says the rider is at, and [by] what. */
data class RecognizedPlace(val place: FavoritePlace, val by: WifiMatch) {
    /** How far from [place] the rider may be, as the fix it stands in for reports it. */
    val accuracyMeters: Float
        get() = when (by) {
            WifiMatch.ACCESS_POINT -> WifiPlaces.RECOGNIZED_ACCURACY_METERS
            WifiMatch.NETWORK -> WifiPlaces.FORGET_BEYOND_METERS.toFloat()
        }
}

/**
 * Recognizing a saved place by its Wi-Fi (SPEC *Finding stops → Wi-Fi at saved places*), so a rider at
 * home or work indoors, where GPS takes seconds and the network's own fix can be a kilometer out, is
 * shown the stops around that place rather than an approximate list that moves when GPS catches up.
 *
 * - **Learned, never set up**: when a sure fix (GPS or fused, [MoveFollow.isSure]) lands within
 *   [LEARN_WITHIN_METERS] of a saved place while the phone is on unmetered Wi-Fi, that access point is
 *   learned for the place. A home with several networks, bands or mesh points teaches each as the
 *   phone moves between them.
 * - **Forgotten when GPS disagrees**: a sure fix more than [FORGET_BEYOND_METERS] from a place while on
 *   one of its access points means that access point isn't fixed there (a moved router, a hotspot that
 *   isn't marked metered), so it is dropped.
 * - **Recognized**: on an access point learned at exactly one saved place, the rider is taken to be at
 *   that place, within [LEARN_WITHIN_METERS] of it, since that is how close a fix had to be to teach it.
 * - **Recognized by the network**, for a place with more access points than can be learned one by one
 *   (an office with hundreds, maintainer 2026-10-14): on an access point never seen there but on the
 *   place's network, the rider is taken to be at the place only when the rough fix is within
 *   [NETWORK_NEAR_METERS] of it, so another site on the same network isn't taken for it. A sure fix
 *   on the network between [FORGET_BEYOND_METERS] and [NETWORK_NEAR_METERS] from the place, where
 *   recognizing it would mislead, forgets the network there, unless that fix is at another saved
 *   place: then both keep it, and a rough fix near both says neither.
 *
 * What's kept is the access point's address and an opaque id for the network's name, beside the
 * place's own id and position: never the name itself, no fix, and nothing about networks the phone
 * isn't connected to.
 */
object WifiPlaces {
    /** How close a sure fix must be to a saved place to teach it the access point the phone is on. */
    const val LEARN_WITHIN_METERS = 100.0

    /**
     * How far a sure fix must be from a place to drop an access point learned there: well beyond a
     * home access point's reach plus a fix's own uncertainty, so it is never a rider in the garden.
     */
    const val FORGET_BEYOND_METERS = 250.0

    /** How many access points one place keeps: a home's networks, bands and mesh points, with room. */
    const val MAX_PER_PLACE = 16

    /** How many networks one place keeps: a home's own and a guest one, an office's few, with room. */
    const val MAX_NETWORKS_PER_PLACE = 4

    /**
     * How near the rough fix must put the rider to a place for its network alone to say they're there.
     * A network fix indoors can be about this far out, so a tighter bound would miss the place it is
     * for; a site of the same network farther than this is told apart.
     */
    const val NETWORK_NEAR_METERS = 1_000.0

    /** The accuracy a recognized place's fix reports: the rider was within this of it to teach it. */
    const val RECOGNIZED_ACCURACY_METERS = LEARN_WITHIN_METERS.toFloat()

    // The address Android reports when it withholds the real one.
    private const val WITHHELD = "02:00:00:00:00:00"

    private val ADDRESS = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    /** [bssid] as kept and compared (lower case), or null when there is none worth keeping. */
    fun normalize(bssid: String?): String? =
        bssid?.trim()?.lowercase()?.takeIf { ADDRESS.matches(it) && it != WITHHELD && it != "00:00:00:00:00:00" }

    /**
     * [learned] without what no longer applies: a place no longer saved, or one moved more than
     * [FavoritePlaces.SAME_PLACE_METERS] from where its access points were learned.
     */
    @WorkerThread
    fun current(learned: List<LearnedPlace>, places: List<FavoritePlace>): List<LearnedPlace> {
        val byId = places.associateBy { it.id }
        return learned.filter { entry ->
            val place = byId[entry.placeId] ?: return@filter false
            (entry.accessPoints.isNotEmpty() || entry.networks.isNotEmpty()) &&
                distance(place.coordinate, entry.at) <= FavoritePlaces.SAME_PLACE_METERS
        }
    }

    /**
     * [learned] after [fix], taken while the phone was on [wifi]: the access point and network learned
     * at the saved place the fix is at, the access point dropped from any place the fix is far from,
     * and the network from any place it would mislead at. Unchanged for a fix that isn't sure, or with
     * no unmetered Wi-Fi.
     */
    @WorkerThread
    fun learn(
        learned: List<LearnedPlace>,
        places: List<FavoritePlace>,
        fix: LocationFix,
        wifi: ConnectedWifi?,
    ): List<LearnedPlace> {
        val kept = current(learned, places)
        val unmetered = wifi?.takeIf { !it.metered } ?: return kept
        val bssid = normalize(unmetered.bssid)
        val network = unmetered.network?.takeIf { it.isNotBlank() }
        if (bssid == null && network == null) return kept
        if (!MoveFollow.isSure(fix)) return kept
        val at = places
            .map { it to distance(it.coordinate, fix.coordinates) }
            .filter { (_, meters) -> meters <= LEARN_WITHIN_METERS }
            .minByOrNull { (_, meters) -> meters }
            ?.first
        val without = kept.mapNotNull { entry ->
            if (entry.placeId == at?.id) return@mapNotNull entry
            val meters = distance(entry.at, fix.coordinates)
            // An access point belongs to one place: off every other, and off any the fix is far from.
            val points = if (bssid != null && bssid in entry.accessPoints && (at != null || meters > FORGET_BEYOND_METERS)) {
                entry.accessPoints - bssid
            } else {
                entry.accessPoints
            }
            // A network may span places, but not reach where it would put the rider at this one wrongly.
            // Seen at another saved place it stays at both: a rough fix near both then says neither,
            // where moving it to the last one seen would take the rider there from the first (Codex).
            val misleads = at == null && meters > FORGET_BEYOND_METERS && meters <= NETWORK_NEAR_METERS
            val networks = if (network != null && network in entry.networks && misleads) {
                entry.networks - network
            } else {
                entry.networks
            }
            if (points == entry.accessPoints && networks == entry.networks) return@mapNotNull entry
            entry.copy(accessPoints = points, networks = networks).takeIf { it.accessPoints.isNotEmpty() || it.networks.isNotEmpty() }
        }
        if (at == null) return without
        val existing = without.firstOrNull { it.placeId == at.id }
        val points = (existing?.accessPoints ?: emptyList()).let { if (bssid == null) it else (it - bssid + bssid).takeLast(MAX_PER_PLACE) }
        val networks = (existing?.networks ?: emptyList()).let { if (network == null) it else (it - network + network).takeLast(MAX_NETWORKS_PER_PLACE) }
        val entry = LearnedPlace(at.id, at.coordinate, points, networks)
        return if (existing == null) without + entry else without.map { if (it.placeId == at.id) entry else it }
    }

    /**
     * The saved place [wifi] says the rider is at: the one place its access point was learned at; or,
     * on an access point learned nowhere, the one place its network was learned at that is within
     * [NETWORK_NEAR_METERS] of the rough fix [near]. Null for none, a metered network, an access point
     * learned at more than one place, or a network with no rough fix to place it by.
     */
    @WorkerThread
    fun recognize(
        learned: List<LearnedPlace>,
        places: List<FavoritePlace>,
        wifi: ConnectedWifi?,
        near: Coordinates? = null,
    ): RecognizedPlace? {
        val unmetered = wifi?.takeIf { !it.metered } ?: return null
        val kept = current(learned, places)
        val byId = places.associateBy { it.id }
        normalize(unmetered.bssid)?.let { bssid ->
            val ids = kept.filter { bssid in it.accessPoints }.map { it.placeId }.distinct()
            // Learned somewhere, the access point settles it: at one place, that place; at more, none.
            if (ids.isNotEmpty()) return ids.singleOrNull()?.let(byId::get)?.let { RecognizedPlace(it, WifiMatch.ACCESS_POINT) }
        }
        val network = unmetered.network?.takeIf { it.isNotBlank() } ?: return null
        val rough = near ?: return null
        val nearby = kept
            .filter { network in it.networks }
            .mapNotNull { byId[it.placeId] }
            .filter { distance(it.coordinate, rough) <= NETWORK_NEAR_METERS }
            .distinct()
        return nearby.singleOrNull()?.let { RecognizedPlace(it, WifiMatch.NETWORK) }
    }

    /** How many access points [learned] holds, across its places: a count for the log, never which. */
    fun count(learned: List<LearnedPlace>): Int = learned.sumOf { it.accessPoints.size }

    /** How many networks [learned] holds, across its places: a count for the log, never which. */
    fun networkCount(learned: List<LearnedPlace>): Int = learned.sumOf { it.networks.size }

    private fun distance(a: Coordinates, b: Coordinates): Double =
        NearestStops.distanceMeters(a.latitude, a.longitude, b.latitude, b.longitude)
}

/**
 * The saved places' Wi-Fi as the location provider uses it (SPEC *Finding stops → Wi-Fi at saved
 * places*): [recognize] the saved place the connected Wi-Fi says the rider is at, and [observe] each
 * sure fix to learn or forget access points. A seam so the provider is testable with a fake; the
 * Android side reads the connected access point, the saved places, the setting and the stored list.
 */
interface WifiPlaceSource {
    /**
     * The saved place the connected Wi-Fi says the rider is at, or null; [near] is the rough fix it
     * would stand in for, without which a place's network alone says nothing.
     */
    suspend fun recognize(near: Coordinates?): RecognizedPlace?

    /**
     * A fix the app has just taken, to learn from or forget by ([WifiPlaces.learn]). Returns at once:
     * the learning happens in the background, so a fix is never held up by it.
     */
    fun observe(fix: LocationFix)

    companion object {
        /** Recognizes nothing and learns nothing: every provider but near me's, and tests. */
        val NONE: WifiPlaceSource = object : WifiPlaceSource {
            override suspend fun recognize(near: Coordinates?): RecognizedPlace? = null
            override fun observe(fix: LocationFix) {}
        }
    }
}
