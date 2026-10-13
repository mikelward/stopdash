package app.stopdash.domain

/**
 * A place picked as where a trip starts (From…): carried where a searched station's id is ([id]), so
 * the From… page stands at the place's coordinate as it would at a station's center, with no stop of
 * its own. Never a TfL id (those never contain a colon), and never sent to TfL: it stays in the app's
 * own state and is read back by [coordinate], and what the place is by [art].
 */
object PlaceStart {
    private const val PREFIX = "place:"

    /** The id of a place at [coordinate]; a searched place's [art] rides after it, a plain place's doesn't. */
    fun id(coordinate: Coordinates, art: PlaceArt = PlaceArt.PLAIN): String {
        val at = "$PREFIX${coordinate.latitude},${coordinate.longitude}"
        return if (art == PlaceArt.PLAIN) at else "$at,${art.code}"
    }

    /** The place's coordinate, or null when [id] is a station's (or malformed). */
    fun coordinate(id: String): Coordinates? {
        val parts = fields(id) ?: return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        return Coordinates(latitude, longitude)
    }

    /** What the place [id] names is: its [PlaceArt], plain for one with none (or a station's id). */
    fun art(id: String): PlaceArt = PlaceArt.ofCode(fields(id)?.getOrNull(2))

    private fun fields(id: String): List<String>? {
        if (!id.startsWith(PREFIX)) return null
        return id.removePrefix(PREFIX).split(',').takeIf { it.size == 2 || it.size == 3 }
    }
}
