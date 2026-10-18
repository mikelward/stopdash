package app.stopdash.data

import app.stopdash.domain.Pick
import kotlinx.serialization.Serializable

/**
 * A [Pick] as every store saves it (dev-docs/station-model.md *Saved data*): one codec, so a store
 * that keeps a picked place keeps its level the same way as the rest. The level is saved by its code;
 * [Pick.extraStopId] and a place's position only where set, and a store's file a version on, so an
 * older build that would drop the level never writes over one that has it.
 * The rider's own picks: stored on the device and never logged (*Privacy*).
 */
@Serializable
internal data class PersistedPick(
    val id: String,
    val name: String,
    val level: String,
    val extraStopId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
) {
    /**
     * The pick saved, or null for a level this build doesn't know (one a newer build added): the
     * store then reads the entry as a pick saved with none, through [app.stopdash.domain.Hubs.picked].
     */
    fun toDomain(): Pick? = Pick.Level.ofCode(level)?.let { Pick(id, name, it, extraStopId, latitude, longitude) }

    companion object {
        fun of(pick: Pick): PersistedPick =
            PersistedPick(pick.id, pick.name, pick.level.code, pick.extraStopId, pick.latitude, pick.longitude)
    }
}
