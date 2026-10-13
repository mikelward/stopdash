package app.stopdash.domain

/**
 * The icons a favorite place can wear (SPEC D9): monochrome glyphs, so a chip reads at a glance — color
 * emoji were too busy at chip size (maintainer, 2026-09-28). Every place type search shows has one too
 * (maintainer, 2026-10-13), so the picker scrolls, most likely first ([CHOICES]), and a place saved from
 * search starts on its type's ([forType]). Stored by id, so a build that adds icons never changes what
 * an older choice means, and an id this build doesn't know is kept rather than dropped.
 */
object FavoritePlaceIcon {
    const val HOME = "home"
    const val OFFICE = "office"
    const val WORK = "work" // a briefcase
    const val BACKPACK = "backpack"
    const val SCHOOL = "school" // a graduation cap
    const val PARK = "park"
    const val STADIUM = "stadium"
    const val HOSPITAL = "hospital"
    const val AIRPORT = "airport"
    const val GYM = "gym"
    const val SHOP = "shop"
    const val CAFE = "cafe"
    const val RESTAURANT = "restaurant"
    const val THEATER = "theater"
    const val BEACH = "beach"
    const val HEART = "heart"
    // The place types search shows (PlaceType), by their codes where no older id already draws them.
    const val SUPERMARKET = "supermarket"
    const val PUB = "pub"
    const val BAR = "bar"
    const val TAKEAWAY = "takeaway"
    const val DOCTOR = "doctor"
    const val DENTIST = "dentist"
    const val PHARMACY = "pharmacy"
    const val HEALTH = "health"
    const val CHURCH = "church"
    const val MOSQUE = "mosque"
    const val SYNAGOGUE = "synagogue"
    const val TEMPLE = "temple"
    const val HAIRDRESSER = "hairdresser"
    const val LIBRARY = "library"
    const val CINEMA = "cinema"
    const val MUSEUM = "museum"
    const val GALLERY = "gallery"
    const val POOL = "pool"
    const val PLAYGROUND = "playground"
    const val HOTEL = "hotel"
    const val MALL = "mall"
    const val BANK = "bank"
    const val BUSINESS = "business" // an office block, besides OFFICE's apartment building
    const val POST_OFFICE = "post_office"
    const val COMMUNITY = "community"
    const val VENUE = "venue"
    const val ATTRACTION = "attraction"
    const val LANDMARK = "landmark"
    const val GOLF = "golf"
    const val POLICE = "police"
    const val SQUARE = "square"
    const val CEMETERY = "cemetery"
    const val PIN = "pin" // a place of no type of its own, as search shows it

    /**
     * The choices, in the order the picker shows them: by how often a rider is likely to save such a
     * place (maintainer, 2026-10-13), the personal ones first and the rare ones last.
     */
    val CHOICES: List<String> = listOf(
        HOME, WORK, HEART, OFFICE, SCHOOL, BACKPACK, GYM, SUPERMARKET, SHOP, CAFE, PUB, RESTAURANT, BAR,
        PARK, DOCTOR, DENTIST, PHARMACY, HOSPITAL, HEALTH, CHURCH, MOSQUE, SYNAGOGUE, TEMPLE, HAIRDRESSER,
        LIBRARY, CINEMA, THEATER, MUSEUM, GALLERY, STADIUM, POOL, PLAYGROUND, HOTEL, AIRPORT, BEACH, MALL,
        BANK, BUSINESS, POST_OFFICE, COMMUNITY, VENUE, ATTRACTION, LANDMARK, GOLF, TAKEAWAY, POLICE,
        SQUARE, CEMETERY, PIN,
    )

    /** The icon a place of search's [type] starts with when saved: the pin, as search shows it, for a plain place. */
    fun forType(type: PlaceType): String? = when (type) {
        PlaceType.PLACE -> PIN
        PlaceType.SCHOOL -> BACKPACK
        PlaceType.COLLEGE -> SCHOOL
        PlaceType.OFFICE -> BUSINESS
        PlaceType.MARKET -> SHOP // the same storefront: offered once
        else -> type.code.takeIf { it in CHOICE_SET }
    }

    // For [forType], called on a pick: a lookup, not a scan of the list.
    private val CHOICE_SET: Set<String> = CHOICES.toSet()

    /** The icon a newly added place of [kind] starts with; a custom place starts with none. */
    fun defaultFor(kind: FavoriteKind): String? = when (kind) {
        FavoriteKind.HOME -> HOME
        FavoriteKind.WORK -> WORK
        FavoriteKind.SCHOOL -> SCHOOL
        FavoriteKind.CUSTOM -> null
    }
}

/** What a place's chip on the near-me list shows: its icon, its name, or both. */
enum class ChipLabel { ICON, NAME, BOTH }
