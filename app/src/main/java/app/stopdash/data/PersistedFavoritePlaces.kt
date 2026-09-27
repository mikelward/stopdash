package app.stopdash.data

import app.stopdash.domain.Coordinates
import app.stopdash.domain.FavoriteKind
import app.stopdash.domain.FavoritePlace
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
        const val CURRENT_VERSION = 1
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
            )
        },
    )

/**
 * The domain list, or null when the stored format is a version this build doesn't know — the caller
 * then reads it as [app.stopdash.domain.FavoritePlacesSet.Unavailable] and preserves the file rather
 * than overwriting it (see [DataStoreFavoritePlacesStore]).
 *
 * With v1 the only schema, a decode failure is genuine corruption (handled by the serializer); a
 * future incompatible bump MUST first add a version check here. An unknown [kind] string within a
 * known version falls back to [FavoriteKind.CUSTOM] so the user's place is kept and usable rather
 * than dropped (a new kind ships behind a version bump, which is caught above).
 */
internal fun PersistedFavoritePlaces.toDomain(): List<FavoritePlace>? {
    if (version != PersistedFavoritePlaces.CURRENT_VERSION) return null
    return places.map {
        FavoritePlace(
            id = it.id,
            kind = runCatching { FavoriteKind.valueOf(it.kind) }.getOrDefault(FavoriteKind.CUSTOM),
            label = it.label,
            coordinate = Coordinates(it.lat, it.lon),
            placeName = it.placeName,
        )
    }
}
