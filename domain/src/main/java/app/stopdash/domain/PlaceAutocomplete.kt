package app.stopdash.domain

/**
 * One Google Places autocomplete suggestion (SPEC *To… a place or postcode*): its stable [placeId], the
 * [name] to show (Google's main text), the [detail] that tells same-named places apart (its secondary
 * text), and Google's place [types], by which a station is told from a landmark. No coordinate: Google
 * gives one only to a details lookup ([PlaceAutocomplete.locate]), made once a suggestion is tapped.
 */
data class PlaceSuggestion(val placeId: String, val name: String, val detail: String?, val types: List<String> = emptyList())

/**
 * Google Places autocomplete for the To… search, behind a domain interface so the search is tested
 * against fakes and the client against recorded fixtures (SPEC *Testing*). A [session] token groups the
 * suggestions typed toward one pick with the [locate] that ends it, which Google bills as one session
 * (SPEC *Cost*). Sends only the typed text and the token — never the rider's position (SPEC *Privacy*).
 * Throws a [PlacesException] on a transport, quota or decode failure.
 */
interface PlaceAutocomplete {
    suspend fun suggest(query: String, session: String): List<PlaceSuggestion>

    /** Where [placeId] is; [session] is the token its suggestion came under, ending that session. */
    suspend fun locate(placeId: String, session: String?): Coordinates
}

/** A Places request that failed, by why, so the search can say so honestly (SPEC principle 2). */
class PlacesException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { OFFLINE, DENIED, UNREACHABLE }
}
