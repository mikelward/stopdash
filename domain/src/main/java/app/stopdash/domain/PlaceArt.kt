package app.stopdash.domain

/**
 * What a place is, as its icon shows it (maintainer, 2026-10-13): a street, a postcode, or a place of
 * a [PlaceType]. A searched place carries it from its row into the trip's From and To fields; a place
 * nobody typed (a saved place, a shared link, one an older build kept) is [PLAIN].
 */
data class PlaceArt(val kind: PlaceKind = PlaceKind.PLACE, val type: PlaceType = PlaceType.PLACE) {
    /** This art as one word saved state keeps: "street", "postcode", or the place's [PlaceType.code]. */
    val code: String
        get() = when (kind) {
            PlaceKind.STREET -> STREET
            PlaceKind.POSTCODE -> POSTCODE
            PlaceKind.PLACE -> type.code
        }

    companion object {
        val PLAIN = PlaceArt()

        private const val STREET = "street"
        private const val POSTCODE = "postcode"

        /** What [code] saved; none (an older build's) or one this build doesn't know is [PLAIN]. */
        fun ofCode(code: String?): PlaceArt = when (code) {
            null -> PLAIN
            STREET -> PlaceArt(PlaceKind.STREET)
            POSTCODE -> PlaceArt(PlaceKind.POSTCODE)
            else -> PlaceType.ofCode(code)?.let { PlaceArt(type = it) } ?: PLAIN
        }
    }
}
