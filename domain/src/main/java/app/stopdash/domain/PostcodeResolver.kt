package app.stopdash.domain

/**
 * A place a postcode resolved to: a display [name] (the postcode, an address, or a nearby landmark as
 * TfL names it) and its [coordinate]. The rider picks one in the place search (SPEC D9) to set a
 * favorite's location; nothing is auto-selected, so a postcode that maps to several places offers them
 * all.
 */
data class PlaceCandidate(val name: String, val coordinate: Coordinates)

/**
 * Resolves a UK postcode to the place(s) it names, via TfL's Journey Planner (the only geocoder in the
 * free Unified API — no third-party service, so nothing new leaves the device beyond what routing
 * already sends; SPEC D9 / `docs/PRIVACY.md`). Behind a domain interface so the flow is tested against
 * recorded fixtures (SPEC *Testing*).
 *
 * The caller has already checked the text is a complete postcode ([UkPostcode.isComplete]); this makes
 * the network request. Returns the candidates in TfL's order (a clean postcode usually resolves to
 * one), or empty when TfL places it nowhere. Throws a [TflException] on a transport or decode failure,
 * as [TflClient] does — the surface renders that honestly rather than as "no results".
 */
interface PostcodeResolver {
    suspend fun resolvePostcode(postcode: String): List<PlaceCandidate>
}
