package app.stopdash.domain

/**
 * A place a postcode resolved to: a display [name] (the postcode, an address, or a nearby landmark as
 * TfL names it) and its [coordinate]. The rider picks one in the place search (SPEC D9) to set a
 * favorite's location; nothing is auto-selected, so a postcode that maps to several places offers them
 * all.
 */
data class PlaceCandidate(val name: String, val coordinate: Coordinates)

/**
 * The outcome of resolving a postcode (SPEC D9). TfL's Journey Planner answers a postcode it places at
 * one point with a direct resolution (HTTP 200) and one it can't place uniquely with a disambiguation
 * of look-alike places (HTTP 300); the two are **not** interchangeable. A [Resolved] postcode is
 * unambiguous, so the caller may adopt it straight away; [Options] must be chosen from and is never
 * auto-adopted — a disambiguation that happens to survive to one positioned place is still a guess, not
 * a resolution. [None] is a postcode TfL places nowhere.
 */
sealed interface PostcodeResolution {
    data class Resolved(val place: PlaceCandidate) : PostcodeResolution
    data class Options(val places: List<PlaceCandidate>) : PostcodeResolution
    data object None : PostcodeResolution
}

/**
 * Resolves a UK postcode to the place(s) it names, via TfL's Journey Planner (the only geocoder in the
 * free Unified API — no third-party service, so nothing new leaves the device beyond what routing
 * already sends; SPEC D9 / `docs/PRIVACY.md`). Behind a domain interface so the flow is tested against
 * recorded fixtures (SPEC *Testing*).
 *
 * The caller has already checked the text is a complete postcode ([UkPostcode.isComplete]); this makes
 * the network request. Reports whether TfL [Resolved][PostcodeResolution.Resolved] it directly or
 * offered a disambiguation ([Options][PostcodeResolution.Options]) so the caller adopts only the former
 * without a choice. Throws a [TflException] on a transport or decode failure, as [TflClient] does — the
 * surface renders that honestly rather than as "no results".
 */
fun interface PostcodeResolver {
    suspend fun resolvePostcode(postcode: String): PostcodeResolution
}

/**
 * Geocodes free text — a place name, landmark, address or postcode — to the candidate locations TfL's
 * Journey Planner resolves it to, for the To… search's place results (SPEC *Find a station*). The same
 * geocoder [PostcodeResolver] uses; it just returns the raw candidate list (one for a uniquely-placed
 * text, several for a disambiguation), which [PlaceHits.rank] then re-ranks and tags. Sends only the
 * typed query to TfL, as the stop search already does, and nowhere else (SPEC *Privacy*). Throws a
 * [TflException] on a transport or decode failure; the To… search treats geocoding as best-effort and
 * lets its stops stand, so it catches that rather than failing the whole search.
 */
fun interface PlaceSearch {
    suspend fun searchPlaces(query: String): List<PlaceCandidate>
}
