package app.stopdash.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.stopdash.R
import app.stopdash.domain.ModeIcon
import app.stopdash.domain.PlaceHit
import app.stopdash.domain.PlaceKind
import app.stopdash.domain.PlaceType

/**
 * A result row's tag (maintainer, 2026-10-13): the postcode district where it always was, then icons
 * for what the row is, a place's type ("SE1 · [gallery]") or a stop's modes ("EC4M · [tube][rail]"),
 * in place of the words that used to follow it. The icons are decorative: the row's own semantics say
 * the words ([placeTypeLabel], [app.stopdash.domain.StationMatch.modesSpoken]). [extra] names a mode with no icon (one this build doesn't
 * know), after the icons.
 */
@Composable
internal fun RowTag(
    area: String?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    // A stop's precomputed [StationMatch.modeIcons]: the row reads them, never works them out.
    modes: List<ModeIcon> = emptyList(),
    extra: String? = null,
) {
    val any = icon != null || modes.isNotEmpty()
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (area != null) {
            Text(area, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1)
        }
        if (area != null && (any || extra != null)) {
            Text("·", style = MaterialTheme.typography.bodyMedium, color = color)
        }
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        for (mode in modes) {
            Icon(painterResource(modeIcon(mode)), contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        if (extra != null) {
            Text(extra, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A place result's icon: its type's for a place, else the street's or the postcode's. */
@DrawableRes
internal fun placeIcon(place: PlaceHit): Int = when (place.kind) {
    PlaceKind.STREET -> R.drawable.ic_kind_street
    PlaceKind.POSTCODE -> R.drawable.ic_kind_postcode
    PlaceKind.PLACE -> TYPE_ART.getValue(place.type).first
}

/** What a screen reader says for a place result's icon: its type, "Street" or "Postcode". */
@StringRes
internal fun placeTypeLabel(place: PlaceHit): Int = when (place.kind) {
    PlaceKind.STREET -> R.string.station_search_kind_street
    PlaceKind.POSTCODE -> R.string.station_search_kind_postcode
    PlaceKind.PLACE -> TYPE_ART.getValue(place.type).second
}

/** A mode icon's drawable. */
@DrawableRes
internal fun modeIcon(icon: ModeIcon): Int = when (icon) {
    ModeIcon.TUBE -> R.drawable.ic_mode_tube
    ModeIcon.RAIL -> R.drawable.ic_mode_rail
    ModeIcon.DLR -> R.drawable.ic_mode_dlr
    ModeIcon.TRAM -> R.drawable.ic_mode_tram
    ModeIcon.RIVER -> R.drawable.ic_mode_river
    ModeIcon.CABLE_CAR -> R.drawable.ic_mode_cable_car
    ModeIcon.BUS -> R.drawable.ic_mode_bus
}

private val TYPE_ART: Map<PlaceType, Pair<Int, Int>> = mapOf(
    PlaceType.PLACE to (R.drawable.ic_type_place to R.string.station_search_kind_place),
    PlaceType.RESTAURANT to (R.drawable.ic_type_restaurant to R.string.place_type_restaurant),
    PlaceType.CAFE to (R.drawable.ic_type_cafe to R.string.place_type_cafe),
    PlaceType.TAKEAWAY to (R.drawable.ic_type_takeaway to R.string.place_type_takeaway),
    PlaceType.PUB to (R.drawable.ic_type_pub to R.string.place_type_pub),
    PlaceType.BAR to (R.drawable.ic_type_bar to R.string.place_type_bar),
    PlaceType.SUPERMARKET to (R.drawable.ic_type_supermarket to R.string.place_type_supermarket),
    PlaceType.SHOP to (R.drawable.ic_type_shop to R.string.place_type_shop),
    PlaceType.MALL to (R.drawable.ic_type_mall to R.string.place_type_mall),
    PlaceType.HAIRDRESSER to (R.drawable.ic_type_hairdresser to R.string.place_type_hairdresser),
    PlaceType.PHARMACY to (R.drawable.ic_type_pharmacy to R.string.place_type_pharmacy),
    PlaceType.DOCTOR to (R.drawable.ic_type_doctor to R.string.place_type_doctor),
    PlaceType.HEALTH to (R.drawable.ic_type_health to R.string.place_type_health),
    PlaceType.DENTIST to (R.drawable.ic_type_dentist to R.string.place_type_dentist),
    PlaceType.HOSPITAL to (R.drawable.ic_type_hospital to R.string.place_type_hospital),
    PlaceType.BANK to (R.drawable.ic_type_bank to R.string.place_type_bank),
    PlaceType.OFFICE to (R.drawable.ic_type_office to R.string.place_type_office),
    PlaceType.HOTEL to (R.drawable.ic_type_hotel to R.string.place_type_hotel),
    PlaceType.SCHOOL to (R.drawable.ic_type_school to R.string.place_type_school),
    PlaceType.COLLEGE to (R.drawable.ic_type_college to R.string.place_type_college),
    PlaceType.LIBRARY to (R.drawable.ic_type_library to R.string.place_type_library),
    PlaceType.CHURCH to (R.drawable.ic_type_church to R.string.place_type_church),
    PlaceType.MOSQUE to (R.drawable.ic_type_mosque to R.string.place_type_mosque),
    PlaceType.SYNAGOGUE to (R.drawable.ic_type_synagogue to R.string.place_type_synagogue),
    PlaceType.TEMPLE to (R.drawable.ic_type_temple to R.string.place_type_temple),
    PlaceType.PARK to (R.drawable.ic_type_park to R.string.place_type_park),
    PlaceType.PLAYGROUND to (R.drawable.ic_type_playground to R.string.place_type_playground),
    PlaceType.GYM to (R.drawable.ic_type_gym to R.string.place_type_gym),
    PlaceType.POOL to (R.drawable.ic_type_pool to R.string.place_type_pool),
    PlaceType.STADIUM to (R.drawable.ic_type_stadium to R.string.place_type_stadium),
    PlaceType.GOLF to (R.drawable.ic_type_golf to R.string.place_type_golf),
    PlaceType.MUSEUM to (R.drawable.ic_type_museum to R.string.place_type_museum),
    PlaceType.GALLERY to (R.drawable.ic_type_gallery to R.string.place_type_gallery),
    PlaceType.THEATER to (R.drawable.ic_type_theater to R.string.place_type_theater),
    PlaceType.CINEMA to (R.drawable.ic_type_cinema to R.string.place_type_cinema),
    PlaceType.ATTRACTION to (R.drawable.ic_type_attraction to R.string.place_type_attraction),
    PlaceType.LANDMARK to (R.drawable.ic_type_landmark to R.string.place_type_landmark),
    PlaceType.VENUE to (R.drawable.ic_type_venue to R.string.place_type_venue),
    PlaceType.MARKET to (R.drawable.ic_type_market to R.string.place_type_market),
    PlaceType.COMMUNITY to (R.drawable.ic_type_community to R.string.place_type_community),
    PlaceType.POLICE to (R.drawable.ic_type_police to R.string.place_type_police),
    PlaceType.POST_OFFICE to (R.drawable.ic_type_post_office to R.string.place_type_post_office),
    PlaceType.CEMETERY to (R.drawable.ic_type_cemetery to R.string.place_type_cemetery),
    PlaceType.AIRPORT to (R.drawable.ic_type_airport to R.string.place_type_airport),
    PlaceType.SQUARE to (R.drawable.ic_type_square to R.string.place_type_square),
)
