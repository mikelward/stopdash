package app.stopdash.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import app.stopdash.R
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
