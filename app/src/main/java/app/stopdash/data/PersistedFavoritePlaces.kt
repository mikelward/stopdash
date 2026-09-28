package app.stopdash.data

import app.stopdash.domain.ChipLabel
import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
import java.time.DayOfWeek
import kotlinx.serialization.Serializable

/**
 * The on-disk shape of the favorite places, kept in the `data` layer so the domain types stay free
 * of serialization annotations (mirrors [PersistedStarredRows]). `version` lets a future format
 * change be detected and discarded rather than mis-read; an unknown version reads as
 * [PersistedFavoritePlaces.toDomain]`== null`, which the store treats as
 * [app.stopdash.domain.FavoritePlacesSet.Unavailable] and preserves untouched.
 *
 * A private persistence detail — the app opens no off-device channel of its own for it — but the
 * file is not strictly device-local: it rides Android backup and device-to-device transfer like the
 * rest of the app's config (SPEC §12 / *Privacy* / D9), a platform path the user controls. Each
 * entry carries a **coordinate**, the sensitive datum: it is never logged or placed in any pushed
 * artifact of ours (SPEC *Privacy*).
 */
@Serializable
internal data class PersistedFavoritePlaces(
    val version: Int = CURRENT_VERSION,
    val places: List<PersistedFavoritePlace> = emptyList(),
    // A durable tombstone written when a corrupt file is discarded: it survives process death, so the
    // loss is still surfaced (Discarded) on a later launch rather than reading as an empty list. A
    // normal write clears it (default false). Codex.
    val discarded: Boolean = false,
) {
    companion object {
        /** The current on-disk format. Bump when a field's meaning changes incompatibly, or when a
         *  new [FavoriteKind] is added (so an older build reads the file as Unavailable, not as a
         *  set with an unknown kind). */
        const val CURRENT_VERSION = 3

        /** The oldest format this build still reads (v1: no per-place days, which read as every day). */
        const val OLDEST_READABLE_VERSION = 1
    }
}

@Serializable
internal data class PersistedFavoritePlace(
    val id: String,
    val kind: String,
    val label: String,
    val lat: Double,
    val lon: Double,
    val placeName: String? = null,
    // The days the chip shows, as ISO day numbers (Monday = 1). Null means every day, which is how a v1
    // file (from before the field existed) reads. Added in v2 rather than within v1: a v1 build would
    // read a newer file, ignore this field, and write it back without it on its next edit — resetting
    // every schedule after a rollback (Codex). At v2 that build reads the file as Unavailable and
    // leaves it alone.
    val showOnDays: List<Int>? = null,
    // The place's icon id and what its chip shows ([ChipLabel] by name), each null for unset. Added in v3
    // for the same reason the days took v2: a v2 build would drop them on its next write after a
    // rollback, where at v3 it preserves the file instead.
    val icon: String? = null,
    val chipShows: String? = null,
)

internal fun List<FavoritePlace>.toPersisted(): PersistedFavoritePlaces =
    PersistedFavoritePlaces(
        places = map {
            PersistedFavoritePlace(
                id = it.id,
                kind = it.kind.name,
                label = it.label,
                lat = it.coordinate.latitude,
                lon = it.coordinate.longitude,
                placeName = it.placeName,
                showOnDays = it.showOnDays
                    .takeUnless { days -> days == FavoritePlace.EVERY_DAY }
                    ?.map(DayOfWeek::getValue)
                    ?.sorted(),
                icon = it.icon,
                chipShows = it.chipShows?.name,
            )
        },
    )

/**
 * The domain list, or null when the stored format is a version this build doesn't know — the caller
 * then reads it as [app.stopdash.domain.FavoritePlacesSet.Unavailable] and preserves the file rather
 * than overwriting it (see [DataStoreFavoritePlacesStore]).
 *
 * v1 to v3 share one shape (v2 adds the optional per-place days and v3 the icon and chip label, each absent before), so a decode failure
 * is genuine corruption (handled by the serializer); a future incompatible bump MUST first add a
 * version check here. An unknown [kind] string within a
 * known version falls back to [FavoriteKind.CUSTOM] so the user's place is kept and usable rather
 * than dropped (a new kind ships behind a version bump, which is caught above).
 */
internal fun PersistedFavoritePlaces.toDomain(): List<FavoritePlace>? {
    if (version !in PersistedFavoritePlaces.OLDEST_READABLE_VERSION..PersistedFavoritePlaces.CURRENT_VERSION) return null
    return places.map {
        FavoritePlace(
            id = it.id,
            kind = runCatching { FavoriteKind.valueOf(it.kind) }.getOrDefault(FavoriteKind.CUSTOM),
            label = it.label,
            coordinate = Coordinates(it.lat, it.lon),
            placeName = it.placeName,
            // A number outside 1..7 is dropped rather than failing the whole list.
            showOnDays = it.showOnDays
                ?.mapNotNull { day -> day.takeIf { it in 1..7 }?.let(DayOfWeek::of) }
                ?.toSet()
                ?: FavoritePlace.EVERY_DAY,
            // Kept as stored even when it isn't one of this build's choices, so a newer build's pick
            // survives; blank reads as none.
            icon = it.icon?.takeIf(String::isNotBlank),
            // An unknown value reads as unset: the default, rather than failing the list.
            chipShows = it.chipShows?.let { name -> ChipLabel.values().firstOrNull { label -> label.name == name } },
        )
    }
}
