package app.stopdash.domain

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The `geo:` link that shows a stop in the user's maps app: a pin at the stop's own coordinate,
 * labeled with its name. Built from TfL's published stop position — never the user's fix — so
 * the maps app learns only which stop was tapped. Pure and JVM-testable.
 */
object StopMap {
    /**
     * [geoUri] built on [dispatcher], for a tap handler: encoding walks every byte of the name,
     * so it hops off the main thread first (AGENTS.md *Main thread: read and dispatch only*).
     */
    suspend fun geoUriOn(dispatcher: CoroutineDispatcher, latitude: Double, longitude: Double, label: String): String =
        withContext(dispatcher) { geoUri(latitude, longitude, label) }

    @WorkerThread
    fun geoUri(latitude: Double, longitude: Double, label: String): String {
        val at = "$latitude,$longitude"
        val name = label.trim()
        // The query form (`geo:0,0?q=lat,lng(label)`) is what drops a labeled pin; a bare
        // `geo:lat,lng` only centers the map. The label is percent-encoded so a "(" or "&" in a
        // stop name can't end the query early.
        return if (name.isEmpty()) "geo:0,0?q=$at" else "geo:0,0?q=$at(${encode(name)})"
    }

    private fun encode(text: String): String = buildString {
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") {
                append(c.toChar())
            } else {
                append('%').append("%02X".format(c))
            }
        }
    }
}
