package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.io.BufferedReader
import java.text.Normalizer

/**
 * The bundled London place index (dev-docs/PLACES.md): the places, streets and postcodes a *To…*
 * search finds on the device as the rider types, with nothing sent anywhere. Built by
 * `scripts/build_place_index.py` into `assets/places/place_index.tsv`.
 *
 * About 440,000 entries, so they're held as a few flat arrays rather than an object each: the
 * display names as one UTF-8 byte array, each name's **folded key** (lower-case ASCII letters and
 * digits, nothing else: "The Café, N1" → "thecafen1") as another, and offsets, positions, kinds and
 * ranks alongside. That's roughly 25 MB, read once off the main thread.
 *
 * A search is one pass over the keys, comparing bytes, ranked like the stop search's tiers
 * ([StationMatchTier]) so the two lists merge on equal terms:
 * - [StationMatchTier.Prefix]: the key starts with the query, or does once a leading "the" is
 *   dropped ("british museum" finds The British Museum).
 * - [StationMatchTier.Substring]: the query appears anywhere in the key.
 * - [StationMatchTier.Fuzzy]: the query's letters appear in order from the name's first letter
 *   ("bm" and "brtmus" find British Museum).
 * There is no Anchored tier: without word starts in the key, word-initial queries rank as Fuzzy.
 * Ties go to the more prominent entry (its build-time rank), then the shorter name.
 */
class PlaceIndex private constructor(
    private val names: ByteArray,
    private val nameStarts: IntArray,
    private val keys: ByteArray,
    private val keyStarts: IntArray,
    private val kinds: ByteArray,
    private val ranks: ByteArray,
    private val latitudes: IntArray,
    private val longitudes: IntArray,
    /** Lines the reader couldn't read and left out, so the caller can say so. */
    val skippedLines: Int,
) {
    enum class Kind { PLACE, STREET, POSTCODE }

    /**
     * One destination found: its [name], what [kind] it is, and where it is. [notable]: a landmark
     * Wikipedia covers widely (rank [NOTABLE_RANK] or more), which outranks a bus stop matched as well.
     */
    data class Entry(val name: String, val kind: Kind, val coordinate: Coordinates, val notable: Boolean = false)

    val size: Int get() = kinds.size

    /**
     * The entries [query] finds, best first, at most [limit]. Short queries find prefixes only.
     * [checkpoint] runs every [CHECKPOINT_EVERY] entries, so a caller can stop a scan nobody will
     * read (a coroutine's `ensureActive`, when the rider has typed on): it throws to stop. [only]
     * keeps one [Kind] before the [limit] is applied, so "E1" finds postcodes rather than places
     * named "E12…" crowding them out. Postcodes alone match as prefixes only: a partial postcode is
     * completed, never answered with codes whose characters merely occur in it ("BR1 1B" isn't BR1 1AB).
     * [near], where the trip starts, puts the nearer of equal matches first: "Tesco" finds the nearest
     * one (maintainer, 2026-10-12).
     */
    @WorkerThread
    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        checkpoint: () -> Unit = {},
        only: Kind? = null,
        near: Coordinates? = null,
        // A kind left out: a name search that mustn't answer with postcodes the query only loosely matches.
        except: Kind? = null,
    ): List<Entry> {
        val q = fold(query)
        if (q.size < MIN_QUERY_LENGTH || limit <= 0) return emptyList()
        val prefixOnly = q.size < LOOSE_QUERY_LENGTH || only == Kind.POSTCODE
        // The best [limit] so far, worst at the head, by a packed score: tier, then whether the name is
        // exactly what's typed, then how well known the place is (its rank), then distance from [near]
        // (maintainer, 2026-10-12). A name's length isn't a measure of a match: "St Paul's Cathedral"
        // shouldn't lose to a shorter, lesser "St Paul's Church".
        val best = java.util.PriorityQueue<Long>(limit + 1, compareByDescending { it })
        val onlyKind = only?.ordinal?.toByte()
        val exceptKind = except?.ordinal?.toByte()
        val nearLat = near?.let { Math.round(it.latitude * COORDINATE_SCALE).toInt() }
        val nearLon = near?.let { Math.round(it.longitude * COORDINATE_SCALE).toInt() }
        for (i in 0 until size) {
            if (i % CHECKPOINT_EVERY == 0) checkpoint()
            if (onlyKind != null && kinds[i] != onlyKind) continue
            if (exceptKind != null && kinds[i] == exceptKind) continue
            val start = keyStarts[i]
            val end = keyStarts[i + 1]
            val the = startsWithThe(i)
            val tier = tierOf(q, start, end, prefixOnly, the) ?: continue
            val inexact = if (isExact(q, start, end, the)) 0L else 1L
            val score = (tier.toLong() shl 56) or (inexact shl 52) or ((MAX_RANK - ranks[i]).toLong() shl 48) or
                (distanceStep(i, nearLat, nearLon) shl 20) or i.toLong()
            if (best.size < limit) {
                best.add(score)
            } else if (score < best.peek()) {
                best.poll()
                best.add(score)
            }
        }
        return best.sorted().map { entryAt((it and INDEX_MASK).toInt()) }
    }

    /**
     * The postcode district ("EC4M") of the postcode nearest [latitude], [longitude], within about a
     * kilometer: what tells same-named stops apart in a search (maintainer, 2026-10-11). Null where
     * no bundled postcode is that near (outside London). The first call builds a grid of the
     * postcodes, once.
     */
    @WorkerThread
    fun districtNear(latitude: Double, longitude: Double): String? {
        val lat = Math.round(latitude * COORDINATE_SCALE).toInt()
        val lon = Math.round(longitude * COORDINATE_SCALE).toInt()
        val cellLat = latCell(lat)
        val cellLon = lonCell(lon)
        var best = -1
        var bestDistance = Long.MAX_VALUE
        for (dLat in -1..1) for (dLon in -1..1) {
            val members = postcodeGrid[cellKey(cellLat + dLat, cellLon + dLon)] ?: continue
            for (i in members) {
                val north = (latitudes[i] - lat).toLong()
                // A degree east is about 0.62 of a degree north at London's latitude.
                val east = (longitudes[i] - lon).toLong() * 62 / 100
                val distance = north * north + east * east
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
        }
        // The 3x3 cells reach further than a kilometer at their corners: farther than that is no answer.
        if (best < 0 || bestDistance > MAX_DISTRICT_DISTANCE * MAX_DISTRICT_DISTANCE) return null
        val name = String(names, nameStarts[best], nameStarts[best + 1] - nameStarts[best], Charsets.UTF_8)
        return name.substringBefore(' ')
    }

    // The postcodes by grid cell (about 1.1 km a side), for [districtNear].
    private val postcodeGrid: Map<Long, IntArray> by lazy {
        val cells = HashMap<Long, IntList>()
        val postcode = Kind.POSTCODE.ordinal.toByte()
        for (i in 0 until size) {
            if (kinds[i] != postcode) continue
            cells.getOrPut(cellKey(latCell(latitudes[i]), lonCell(longitudes[i]))) { IntList(16) }.add(i)
        }
        cells.mapValues { it.value.toArray() }
    }

    private fun latCell(scaled: Int): Int = Math.floorDiv(scaled, LAT_CELL)

    private fun lonCell(scaled: Int): Int = Math.floorDiv(scaled, LON_CELL)

    private fun cellKey(lat: Int, lon: Int): Long = (lat.toLong() shl 32) or (lon.toLong() and 0xffffffffL)

    // Entry [i]'s distance from a position in scaled degrees, in steps of about 18 m (16 scaled units
    // north), capped to fit its 16 bits of a search's score; 0 for every entry when there's no position.
    private fun distanceStep(i: Int, nearLat: Int?, nearLon: Int?): Long {
        if (nearLat == null || nearLon == null) return 0
        val north = (latitudes[i] - nearLat).toDouble()
        val east = (longitudes[i] - nearLon) * EAST_SCALE
        return minOf(Math.sqrt(north * north + east * east).toLong() / DISTANCE_STEP, MAX_DISTANCE_STEP)
    }

    private fun entryAt(i: Int): Entry = Entry(
        name = String(names, nameStarts[i], nameStarts[i + 1] - nameStarts[i], Charsets.UTF_8),
        kind = Kind.entries[kinds[i].toInt()],
        coordinate = Coordinates(latitudes[i] / COORDINATE_SCALE, longitudes[i] / COORDINATE_SCALE),
        notable = ranks[i] >= NOTABLE_RANK,
    )

    // Whether entry [i]'s name starts with the word "The", so its key's leading "the" can be passed
    // over; a key's spaces are folded away, so "Theodore" would otherwise look the same.
    private fun startsWithThe(i: Int): Boolean {
        val start = nameStarts[i]
        if (nameStarts[i + 1] - start <= THE.size) return false
        for (j in THE.indices) if (names[start + j].toInt() or 0x20 != THE[j].toInt()) return false
        return names[start + THE.size] == ' '.code.toByte()
    }

    // The tier [q] matches the key in keys[start, end) at, as a [StationMatchTier] ordinal, or null.
    // [the]: the name starts with the word "The" ([startsWithThe]), which may be passed over.
    private fun tierOf(q: ByteArray, start: Int, end: Int, prefixOnly: Boolean, the: Boolean): Int? {
        val length = end - start
        if (length < q.size) return null
        if (startsWith(q, start, end)) return StationMatchTier.Prefix.ordinal
        if (the && length >= THE.size + q.size && startsWith(q, start + THE.size, end)) {
            return StationMatchTier.Prefix.ordinal
        }
        if (prefixOnly) return null
        if (contains(q, start, end)) return StationMatchTier.Substring.ordinal
        if (keys[start] == q[0] && inOrder(q, start, end)) return StationMatchTier.Fuzzy.ordinal
        // Letters in order from the first letter after a leading "the" too ("brtmus", The British Museum).
        if (the && length > THE.size && keys[start + THE.size] == q[0] &&
            inOrder(q, start + THE.size, end)
        ) {
            return StationMatchTier.Fuzzy.ordinal
        }
        return null
    }

    // Whether the key in keys[start, end) is [q] itself, or the word "The" and [q] ("The British
    // Museum"), given [the] ([startsWithThe]).
    private fun isExact(q: ByteArray, start: Int, end: Int, the: Boolean): Boolean {
        val length = end - start
        return (length == q.size && startsWith(q, start, end)) ||
            (the && length == THE.size + q.size && startsWith(q, start + THE.size, end))
    }

    private fun startsWith(q: ByteArray, start: Int, end: Int): Boolean {
        if (end - start < q.size) return false
        for (j in q.indices) if (keys[start + j] != q[j]) return false
        return true
    }

    private fun contains(q: ByteArray, start: Int, end: Int): Boolean {
        val last = end - q.size
        var from = start
        while (from <= last) {
            if (keys[from] == q[0]) {
                var j = 1
                while (j < q.size && keys[from + j] == q[j]) j++
                if (j == q.size) return true
            }
            from++
        }
        return false
    }

    private fun inOrder(q: ByteArray, start: Int, end: Int): Boolean {
        var j = 0
        var k = start
        while (j < q.size && k < end) {
            if (keys[k] == q[j]) j++
            k++
        }
        return j == q.size
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val DEFAULT_LIMIT = 8
        /** The shortest query searched at all, and the shortest searched beyond prefixes. */
        const val MIN_QUERY_LENGTH = 2
        const val LOOSE_QUERY_LENGTH = 3
        // About a millisecond of scanning on a phone: often enough to drop a superseded search fast.
        const val CHECKPOINT_EVERY = 8192
        private const val MAX_RANK = 9

        // A place Wikipedia covers in five or more languages (`scripts/build_place_index.py`).
        const val NOTABLE_RANK = 8
        private const val COORDINATE_SCALE = 1e5
        // Grid cells for [districtNear], in scaled degrees: 0.01° north (1.1 km) by 0.017° east (1.2 km),
        // each at least [MAX_DISTRICT_DISTANCE] across, so the 3x3 cells around a position hold every
        // postcode it can accept (east, that's 1000 / 0.62 ≈ 1613 scaled units).
        private const val LAT_CELL = 1000
        private const val LON_CELL = 1700
        // About 1.1 km, in scaled degrees north, for [districtNear].
        private const val MAX_DISTRICT_DISTANCE = 1000L
        private const val INDEX_MASK = (1L shl 20) - 1
        // A scaled degree east is 0.62 of one north at London's latitude, as [districtNear] reckons it.
        private const val EAST_SCALE = 0.62
        private const val DISTANCE_STEP = 16L
        private const val MAX_DISTANCE_STEP = (1L shl 16) - 1
        private val THE = "the".toByteArray()

        val EMPTY = PlaceIndex(ByteArray(0), IntArray(1), ByteArray(0), IntArray(1), ByteArray(0), ByteArray(0), IntArray(0), IntArray(0), 0)

        /**
         * Reads the index file (see `scripts/build_place_index.py`). Throws [IllegalArgumentException]
         * on a header for another format version, since its lines can't be trusted to mean the same;
         * a single unreadable line is left out and counted in [skippedLines].
         */
        @WorkerThread
        fun read(reader: BufferedReader): PlaceIndex {
            val header = reader.readLine() ?: return EMPTY
            require(header.startsWith("# stopdash place index v$FORMAT_VERSION\t") || header == "# stopdash place index v$FORMAT_VERSION") {
                "place index: unexpected header for format $FORMAT_VERSION"
            }
            val names = java.io.ByteArrayOutputStream(16 shl 20)
            val keys = java.io.ByteArrayOutputStream(16 shl 20)
            val nameStarts = IntList()
            val keyStarts = IntList()
            val kinds = java.io.ByteArrayOutputStream()
            val ranks = java.io.ByteArrayOutputStream()
            val latitudes = IntList()
            val longitudes = IntList()
            var skipped = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) continue
                val fields = line.split('\t')
                val kind = when (fields.getOrNull(0)) {
                    "P" -> Kind.PLACE
                    "S" -> Kind.STREET
                    "C" -> Kind.POSTCODE
                    else -> null
                }
                val lat = fields.getOrNull(3)?.toDoubleOrNull()
                val lon = fields.getOrNull(4)?.toDoubleOrNull()
                val rank = fields.getOrNull(5)?.toIntOrNull()
                val name = fields.getOrNull(1).orEmpty()
                val key = fold(name)
                if (fields.size != 6 || kind == null || lat == null || lon == null || rank == null || rank !in 0..MAX_RANK ||
                    name.isBlank() || key.isEmpty() || nameStarts.size >= INDEX_MASK
                ) {
                    skipped++
                    continue
                }
                nameStarts.add(names.size())
                names.write(name.toByteArray(Charsets.UTF_8))
                keyStarts.add(keys.size())
                keys.write(key)
                kinds.write(kind.ordinal)
                ranks.write(rank)
                latitudes.add(Math.round(lat * COORDINATE_SCALE).toInt())
                longitudes.add(Math.round(lon * COORDINATE_SCALE).toInt())
            }
            nameStarts.add(names.size())
            keyStarts.add(keys.size())
            return PlaceIndex(
                names.toByteArray(), nameStarts.toArray(), keys.toByteArray(), keyStarts.toArray(),
                kinds.toByteArray(), ranks.toByteArray(), latitudes.toArray(), longitudes.toArray(), skipped,
            )
        }

        /**
         * [text]'s key: lower-case ASCII letters and digits only, accents and styled letters folded
         * ("Café" → "cafe", "𝑮𝒀𝑴" → "gym"), everything else dropped. A fast path for plain ASCII, which nearly every name is.
         */
        internal fun fold(text: String): ByteArray {
            val ascii = text.all { it.code < 128 }
            val source = if (ascii) text else Normalizer.normalize(text, Normalizer.Form.NFKD)
            val out = java.io.ByteArrayOutputStream(source.length)
            for (c in source) {
                when (c) {
                    in 'a'..'z', in '0'..'9' -> out.write(c.code)
                    in 'A'..'Z' -> out.write(c.code + ('a' - 'A'))
                    else -> Unit
                }
            }
            return out.toByteArray()
        }
    }
}

// A growable IntArray, so reading 440,000 entries boxes nothing.
private class IntList(capacity: Int = 1 shl 16) {
    private var items = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == items.size) items = items.copyOf(size * 2)
        items[size++] = value
    }

    fun toArray(): IntArray = items.copyOf(size)
}
