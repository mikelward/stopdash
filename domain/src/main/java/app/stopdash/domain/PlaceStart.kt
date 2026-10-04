package app.stopdash.domain

/**
 * A favorite place picked as where a trip starts (From…): carried where a searched station's id is
 * ([id]), so the From… page stands at the place's coordinate as it would at a station's center, with
 * no stop of its own. Never a TfL id (those never contain a colon), and never sent to TfL: it stays in
 * the app's own state and is read back by [coordinate].
 */
object PlaceStart {
    private const val PREFIX = "place:"

    fun id(coordinate: Coordinates): String = "$PREFIX${coordinate.latitude},${coordinate.longitude}"

    /** The place's coordinate, or null when [id] is a station's (or malformed). */
    fun coordinate(id: String): Coordinates? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split(',')
        if (parts.size != 2) return null
        val latitude = parts[0].toDoubleOrNull() ?: return null
        val longitude = parts[1].toDoubleOrNull() ?: return null
        return Coordinates(latitude, longitude)
    }
}
