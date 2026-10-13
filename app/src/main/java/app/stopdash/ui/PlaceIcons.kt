package app.stopdash.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.stopdash.R
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.FavoritePlaceIcon

/** How a favorite place's icon id is drawn: a vendored Material Symbols glyph, and its spoken name. */
internal class PlaceIconArt(@DrawableRes val drawable: Int, @StringRes val name: Int)

internal val placeIconArt: Map<String, PlaceIconArt> = mapOf(
    FavoritePlaceIcon.HOME to PlaceIconArt(R.drawable.ic_place_home, R.string.place_icon_home),
    FavoritePlaceIcon.OFFICE to PlaceIconArt(R.drawable.ic_place_office, R.string.place_icon_office),
    FavoritePlaceIcon.WORK to PlaceIconArt(R.drawable.ic_place_work, R.string.place_icon_work),
    FavoritePlaceIcon.BACKPACK to PlaceIconArt(R.drawable.ic_place_backpack, R.string.place_icon_backpack),
    FavoritePlaceIcon.SCHOOL to PlaceIconArt(R.drawable.ic_place_school, R.string.place_icon_school),
    FavoritePlaceIcon.PARK to PlaceIconArt(R.drawable.ic_place_park, R.string.place_icon_park),
    FavoritePlaceIcon.STADIUM to PlaceIconArt(R.drawable.ic_place_stadium, R.string.place_icon_stadium),
    FavoritePlaceIcon.HOSPITAL to PlaceIconArt(R.drawable.ic_place_hospital, R.string.place_icon_hospital),
    FavoritePlaceIcon.AIRPORT to PlaceIconArt(R.drawable.ic_place_airport, R.string.place_icon_airport),
    FavoritePlaceIcon.GYM to PlaceIconArt(R.drawable.ic_place_gym, R.string.place_icon_gym),
    FavoritePlaceIcon.SHOP to PlaceIconArt(R.drawable.ic_place_shop, R.string.place_icon_shop),
    FavoritePlaceIcon.CAFE to PlaceIconArt(R.drawable.ic_place_cafe, R.string.place_icon_cafe),
    FavoritePlaceIcon.RESTAURANT to PlaceIconArt(R.drawable.ic_place_restaurant, R.string.place_icon_restaurant),
    FavoritePlaceIcon.THEATER to PlaceIconArt(R.drawable.ic_place_theater, R.string.place_icon_theater),
    FavoritePlaceIcon.BEACH to PlaceIconArt(R.drawable.ic_place_beach, R.string.place_icon_beach),
    FavoritePlaceIcon.HEART to PlaceIconArt(R.drawable.ic_place_heart, R.string.place_icon_heart),
    // The place types search shows, drawn and named as there (RowTag).
    FavoritePlaceIcon.PIN to PlaceIconArt(R.drawable.ic_place_pin, R.string.station_search_kind_place),
    FavoritePlaceIcon.SUPERMARKET to PlaceIconArt(R.drawable.ic_place_supermarket, R.string.place_type_supermarket),
    FavoritePlaceIcon.PUB to PlaceIconArt(R.drawable.ic_place_pub, R.string.place_type_pub),
    FavoritePlaceIcon.BAR to PlaceIconArt(R.drawable.ic_place_bar, R.string.place_type_bar),
    FavoritePlaceIcon.TAKEAWAY to PlaceIconArt(R.drawable.ic_place_takeaway, R.string.place_type_takeaway),
    FavoritePlaceIcon.DOCTOR to PlaceIconArt(R.drawable.ic_place_doctor, R.string.place_type_doctor),
    FavoritePlaceIcon.DENTIST to PlaceIconArt(R.drawable.ic_place_dentist, R.string.place_type_dentist),
    FavoritePlaceIcon.PHARMACY to PlaceIconArt(R.drawable.ic_place_pharmacy, R.string.place_type_pharmacy),
    FavoritePlaceIcon.HEALTH to PlaceIconArt(R.drawable.ic_place_health, R.string.place_type_health),
    FavoritePlaceIcon.CHURCH to PlaceIconArt(R.drawable.ic_place_church, R.string.place_type_church),
    FavoritePlaceIcon.MOSQUE to PlaceIconArt(R.drawable.ic_place_mosque, R.string.place_type_mosque),
    FavoritePlaceIcon.SYNAGOGUE to PlaceIconArt(R.drawable.ic_place_synagogue, R.string.place_type_synagogue),
    FavoritePlaceIcon.TEMPLE to PlaceIconArt(R.drawable.ic_place_temple, R.string.place_type_temple),
    FavoritePlaceIcon.HAIRDRESSER to PlaceIconArt(R.drawable.ic_place_hairdresser, R.string.place_type_hairdresser),
    FavoritePlaceIcon.LIBRARY to PlaceIconArt(R.drawable.ic_place_library, R.string.place_type_library),
    FavoritePlaceIcon.CINEMA to PlaceIconArt(R.drawable.ic_place_cinema, R.string.place_type_cinema),
    FavoritePlaceIcon.MUSEUM to PlaceIconArt(R.drawable.ic_place_museum, R.string.place_type_museum),
    FavoritePlaceIcon.GALLERY to PlaceIconArt(R.drawable.ic_place_gallery, R.string.place_type_gallery),
    FavoritePlaceIcon.POOL to PlaceIconArt(R.drawable.ic_place_pool, R.string.place_type_pool),
    FavoritePlaceIcon.PLAYGROUND to PlaceIconArt(R.drawable.ic_place_playground, R.string.place_type_playground),
    FavoritePlaceIcon.HOTEL to PlaceIconArt(R.drawable.ic_place_hotel, R.string.place_type_hotel),
    FavoritePlaceIcon.MALL to PlaceIconArt(R.drawable.ic_place_mall, R.string.place_type_mall),
    FavoritePlaceIcon.BANK to PlaceIconArt(R.drawable.ic_place_bank, R.string.place_type_bank),
    FavoritePlaceIcon.BUSINESS to PlaceIconArt(R.drawable.ic_place_business, R.string.place_type_office),
    FavoritePlaceIcon.POST_OFFICE to PlaceIconArt(R.drawable.ic_place_post_office, R.string.place_type_post_office),
    FavoritePlaceIcon.COMMUNITY to PlaceIconArt(R.drawable.ic_place_community, R.string.place_type_community),
    FavoritePlaceIcon.VENUE to PlaceIconArt(R.drawable.ic_place_venue, R.string.place_type_venue),
    FavoritePlaceIcon.ATTRACTION to PlaceIconArt(R.drawable.ic_place_attraction, R.string.place_type_attraction),
    FavoritePlaceIcon.LANDMARK to PlaceIconArt(R.drawable.ic_place_landmark, R.string.place_type_landmark),
    FavoritePlaceIcon.GOLF to PlaceIconArt(R.drawable.ic_place_golf, R.string.place_type_golf),
    FavoritePlaceIcon.POLICE to PlaceIconArt(R.drawable.ic_place_police, R.string.place_type_police),
    FavoritePlaceIcon.SQUARE to PlaceIconArt(R.drawable.ic_place_square, R.string.place_type_square),
    FavoritePlaceIcon.CEMETERY to PlaceIconArt(R.drawable.ic_place_cemetery, R.string.place_type_cemetery),
)

/**
 * A place's icon, tinted with the surrounding content color. Decorative: the place's name carries the
 * meaning for a screen reader wherever this appears. Draws nothing for an id this build doesn't know
 * (a newer build's choice), so the place falls back to its name.
 */
@Composable
internal fun PlaceIcon(id: String?, modifier: Modifier = Modifier) {
    val art = id?.let(placeIconArt::get) ?: return
    Icon(painter = painterResource(art.drawable), contentDescription = null, modifier = modifier)
}

/** Whether this build can draw [id]. */
internal fun hasPlaceIcon(id: String?): Boolean = id != null && id in placeIconArt

/**
 * The saved places' own icons, for the From/To bar to lead a saved place's name with ([TripEndsBar]):
 * by place id (a favorite journey's end), and by a trip end's coordinate and name, which is what a saved
 * place's chip hands a trip ([favoriteRouteName]). Looked up from the places as they are now, so an
 * edited icon shows; an end that matches none (a searched place, a place renamed since) has none.
 * Built once per read of the places, on the worker, so a field only reads it (AGENTS.md *Main thread*).
 * Only the icons this build draws. Not [ready] before the places' first read ([UNREAD]): no end can be
 * told apart from a saved place yet, so a field waits rather than draw a pin it may have to take back.
 */
internal class PlaceIconIndex private constructor(
    private val byId: Map<String, String>,
    private val byEnd: Map<Pair<Coordinates, String>, String>,
    val ready: Boolean = true,
) {
    /** The icon of the saved place [id], if it has one this build draws. */
    fun forPlace(id: String): String? = byId[id]

    /** The icon of the saved place at [coordinate] named [name], if there is one and it has one. */
    fun at(coordinate: Coordinates, name: String): String? = byEnd[coordinate to name]

    companion object {
        val NONE = PlaceIconIndex(emptyMap(), emptyMap())

        /** Before the saved places' first read: nothing known yet, either way. */
        val UNREAD = PlaceIconIndex(emptyMap(), emptyMap(), ready = false)

        /** The icons of [places]; none while they can't be read (null). */
        @WorkerThread
        fun of(places: List<FavoritePlace>?): PlaceIconIndex {
            val drawn = places.orEmpty().mapNotNull { place -> place.icon?.takeIf(::hasPlaceIcon)?.let { place to it } }
            if (drawn.isEmpty()) return NONE
            return PlaceIconIndex(
                drawn.associate { (place, icon) -> place.id to icon },
                drawn.associate { (place, icon) -> (place.coordinate to favoriteRouteName(place)) to icon },
            )
        }
    }
}

/** The saved places' icons ([PlaceIconIndex]), provided where the places are read. */
internal val LocalPlaceIcons = compositionLocalOf { PlaceIconIndex.NONE }
