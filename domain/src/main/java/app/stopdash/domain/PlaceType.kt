package app.stopdash.domain

/**
 * What kind of place a bundled place is, shown on its search row as an icon (maintainer, 2026-10-13):
 * the codes `scripts/build_place_index.py` writes (its TYPES), from the OpenStreetMap tag that makes it
 * a place. A code this build doesn't know reads as [PLACE].
 */
enum class PlaceType(val code: String) {
    PLACE("place"),
    RESTAURANT("restaurant"),
    CAFE("cafe"),
    TAKEAWAY("takeaway"),
    PUB("pub"),
    BAR("bar"),
    SUPERMARKET("supermarket"),
    SHOP("shop"),
    MALL("mall"),
    HAIRDRESSER("hairdresser"),
    PHARMACY("pharmacy"),
    DOCTOR("doctor"),
    HEALTH("health"),
    DENTIST("dentist"),
    HOSPITAL("hospital"),
    BANK("bank"),
    OFFICE("office"),
    HOTEL("hotel"),
    SCHOOL("school"),
    COLLEGE("college"),
    LIBRARY("library"),
    CHURCH("church"),
    MOSQUE("mosque"),
    SYNAGOGUE("synagogue"),
    TEMPLE("temple"),
    PARK("park"),
    PLAYGROUND("playground"),
    GYM("gym"),
    POOL("pool"),
    STADIUM("stadium"),
    GOLF("golf"),
    MUSEUM("museum"),
    GALLERY("gallery"),
    THEATER("theater"),
    CINEMA("cinema"),
    ATTRACTION("attraction"),
    LANDMARK("landmark"),
    VENUE("venue"),
    MARKET("market"),
    COMMUNITY("community"),
    POLICE("police"),
    POST_OFFICE("post_office"),
    CEMETERY("cemetery"),
    AIRPORT("airport"),
    SQUARE("square"),
    ;

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** The type written as [code], or null for one this build doesn't know. */
        fun ofCode(code: String): PlaceType? = byCode[code]
    }
}
