package app.stopdash.domain

import androidx.annotation.WorkerThread

/**
 * The Wi-Fi access point the phone is connected to, as far as recognizing a saved place goes: its
 * [bssid] (the access point's own address, unique to it, unlike a network name many homes share) and
 * whether Android counts the network as [metered] — a phone's hotspot, a train's or a bus's Wi-Fi —
 * which moves with the rider and so never says where they are.
 */
data class ConnectedWifi(val bssid: String, val metered: Boolean)

/**
 * The access points learned at one saved place ([placeId]), with where the place was ([at]) when they
 * were learned: a place edited to somewhere else starts afresh rather than keep the old spot's Wi-Fi.
 * [accessPoints] are oldest first, so the oldest goes when the place has more than
 * [WifiPlaces.MAX_PER_PLACE].
 */
data class LearnedPlace(val placeId: String, val at: Coordinates, val accessPoints: List<String>)

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
 *
 * Only the access point's address is kept, beside the place's own id and position: no network name,
 * no fix, and nothing about networks the phone isn't connected to.
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
            entry.accessPoints.isNotEmpty() && distance(place.coordinate, entry.at) <= FavoritePlaces.SAME_PLACE_METERS
        }
    }

    /**
     * [learned] after [fix], taken while the phone was on [wifi]: the access point learned at the saved
     * place the fix is at, and dropped from any place the fix is far from. Unchanged for a fix that
     * isn't sure, or with no unmetered Wi-Fi.
     */
    @WorkerThread
    fun learn(
        learned: List<LearnedPlace>,
        places: List<FavoritePlace>,
        fix: LocationFix,
        wifi: ConnectedWifi?,
    ): List<LearnedPlace> {
        val kept = current(learned, places)
        val bssid = wifi?.takeIf { !it.metered }?.let { normalize(it.bssid) } ?: return kept
        if (!MoveFollow.isSure(fix)) return kept
        val at = places
            .map { it to distance(it.coordinate, fix.coordinates) }
            .filter { (_, meters) -> meters <= LEARN_WITHIN_METERS }
            .minByOrNull { (_, meters) -> meters }
            ?.first
        // An access point belongs to one place: off every other, and off any the fix is far from.
        val without = kept.mapNotNull { entry ->
            if (bssid !in entry.accessPoints || entry.placeId == at?.id) return@mapNotNull entry
            if (at == null && distance(entry.at, fix.coordinates) <= FORGET_BEYOND_METERS) return@mapNotNull entry
            entry.copy(accessPoints = entry.accessPoints - bssid).takeIf { it.accessPoints.isNotEmpty() }
        }
        if (at == null) return without
        val existing = without.firstOrNull { it.placeId == at.id }
        val points = ((existing?.accessPoints ?: emptyList()) - bssid + bssid).takeLast(MAX_PER_PLACE)
        val entry = LearnedPlace(at.id, at.coordinate, points)
        return if (existing == null) without + entry else without.map { if (it.placeId == at.id) entry else it }
    }

    /**
     * The saved place [wifi] says the rider is at: the one place its access point was learned at, or
     * null for none, a metered network, or an access point learned at more than one.
     */
    @WorkerThread
    fun recognize(learned: List<LearnedPlace>, places: List<FavoritePlace>, wifi: ConnectedWifi?): FavoritePlace? {
        val bssid = wifi?.takeIf { !it.metered }?.let { normalize(it.bssid) } ?: return null
        val ids = current(learned, places).filter { bssid in it.accessPoints }.map { it.placeId }.distinct()
        return ids.singleOrNull()?.let { id -> places.firstOrNull { it.id == id } }
    }

    /** How many access points [learned] holds, across its places: a count for the log, never which. */
    fun count(learned: List<LearnedPlace>): Int = learned.sumOf { it.accessPoints.size }

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
    /** The saved place the connected Wi-Fi says the rider is at, or null. */
    suspend fun recognize(): FavoritePlace?

    /**
     * A fix the app has just taken, to learn from or forget by ([WifiPlaces.learn]). Returns at once:
     * the learning happens in the background, so a fix is never held up by it.
     */
    fun observe(fix: LocationFix)

    companion object {
        /** Recognizes nothing and learns nothing: every provider but near me's, and tests. */
        val NONE: WifiPlaceSource = object : WifiPlaceSource {
            override suspend fun recognize(): FavoritePlace? = null
            override fun observe(fix: LocationFix) {}
        }
    }
}
