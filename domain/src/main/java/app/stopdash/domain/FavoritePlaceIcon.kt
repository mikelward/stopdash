package app.stopdash.domain

/**
 * The icons a favorite place can wear (SPEC D9): a short fixed set of monochrome glyphs, so the picker
 * is a single glance and a chip reads at a glance — color emoji were too busy at chip size
 * (maintainer, 2026-09-28). Stored by id, so a build that adds icons never changes what an older
 * choice means, and an id this build doesn't know is kept rather than dropped.
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

    /** The choices, in the order the picker shows them. */
    val CHOICES: List<String> = listOf(
        HOME, OFFICE, WORK, BACKPACK, SCHOOL, PARK, STADIUM, HOSPITAL,
        AIRPORT, GYM, SHOP, CAFE, RESTAURANT, THEATER, BEACH, HEART,
    )

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
