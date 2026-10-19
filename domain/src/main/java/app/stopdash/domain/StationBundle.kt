package app.stopdash.domain

import androidx.annotation.WorkerThread
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlin.system.exitProcess

/**
 * The bundled station index ([StationIndex]), stops ([BundledStops]) and the station model built over
 * them ([Hubs]), worked out when the app is built and shipped as one compact binary asset ([ASSET]):
 * the one file the app reads for them, rather than parsing `station_index.json` and `stops.tsv` and
 * building the model as it starts (maintainer, 2026-10-19). The build runs this module's own readers
 * and [Hubs.of] ([main]), so what the app loads is what it would have built itself; a round-trip test
 * holds the two equal.
 *
 * The file: a header (format and the bundle's content revision), every
 * string once, then the index, the stops and the model, each string named by its place in that
 * table and each position in millionths of a degree, which every source position fits exactly.
 */
object StationBundle {
    const val ASSET = "stations/station_bundle.bin"

    private const val MAGIC = 0x53444231 // "SDB1"
    private const val MICRO = 1_000_000.0
    const val FORMAT_VERSION = 2

    /** What [read] gives back: the index, the stops, and the model where the build could make one. */
    class Contents(val index: StationIndex, val stops: BundledStops, val hubs: Hubs)

    /**
     * Writes [index], [stops] and their model [hubs] to [out].
     * Throws [IllegalArgumentException] for an index and stops from different refreshes (their revisions
     * differ), which [read] would refuse, and for a position finer than a millionth of a degree, which the
     * file couldn't give back exactly.
     */
    @WorkerThread
    fun write(index: StationIndex, stops: BundledStops, hubs: Hubs, out: OutputStream) {
        require(index.revision == stops.revision) { "station bundle: the station index and stops are from different refreshes" }
        val body = Writer()
        writeIndex(index, body)
        stops.writeTo(body)
        hubs.writeTo(body)
        val data = DataOutputStream(out.buffered())
        data.writeInt(MAGIC)
        data.writeInt(FORMAT_VERSION)
        data.writeUTF(stops.revision)
        body.writeStrings(data)
        body.bytes.writeTo(data)
        data.flush()
    }

    /**
     * The index, stops and model [input] holds. Throws [IllegalArgumentException] for a file of
     * another format or one that doesn't hold together, and an [java.io.IOException] for one cut
     * short, for the caller to go without, as it would without the source files. Reads a stream, so off
     * the main thread; unmarked, as the station index's parse it replaces was: the app hands its store to
     * repositories as a loader lambda, which lint reads as a call wherever the repository is built.
     */
    fun read(input: InputStream): Contents {
        val data = DataInputStream(input.buffered(1 shl 16))
        require(data.readInt() == MAGIC) { "station bundle: not a bundle" }
        val version = data.readInt()
        require(version == FORMAT_VERSION) { "station bundle: format $version, not $FORMAT_VERSION" }
        val revision = data.readUTF()
        val reader = Reader(data)
        val index = readIndex(reader)
        val stops = BundledStops.readFrom(reader)
        require(index.revision == revision && stops.revision == revision) { "station bundle: parts from different revisions" }
        val hubs = Hubs.readFrom(reader, index)
        return Contents(index, stops, hubs)
    }

    // The index as [StationIndexFile.parse] reads it: every station's fields, its line names, its
    // revision and how many entries it left out.
    private fun writeIndex(index: StationIndex, out: Writer) {
        out.string(index.revision)
        out.int(index.skippedEntries)
        out.stringMap(index.lineNames)
        out.int(index.stations.size)
        for (station in index.stations) {
            out.string(station.id)
            out.string(station.name)
            out.strings(station.modes)
            out.string(station.interchangeId)
            out.boolean(station.latitude != null && station.longitude != null)
            if (station.latitude != null && station.longitude != null) {
                out.position(station.latitude)
                out.position(station.longitude)
            }
            listOf(station.lines, station.routeEnds, station.terminusEnds).forEach { out.map(it, out::strings) }
            out.strings(station.platforms)
            out.string(station.icsCode)
            out.string(station.station)
            out.string(station.modelHub)
        }
    }

    private fun readIndex(input: Reader): StationIndex {
        val revision = input.string()
        val skipped = input.int()
        val lineNames = input.stringMap()
        val stations = List(input.int()) {
            val id = input.string()
            val name = input.string()
            val modes = input.strings()
            val interchangeId = input.string()
            val placed = input.boolean()
            val latitude = if (placed) input.position() else null
            val longitude = if (placed) input.position() else null
            val (lines, routeEnds, terminusEnds) = List(3) { input.map(input::strings) }
            IndexedStation(
                id = id,
                name = name,
                modes = modes,
                interchangeId = interchangeId,
                latitude = latitude,
                longitude = longitude,
                lines = lines,
                routeEnds = routeEnds,
                terminusEnds = terminusEnds,
                platforms = input.strings(),
                icsCode = input.string(),
                station = input.string(),
                modelHub = input.string(),
            )
        }
        return StationIndex(stations, lineNames = lineNames, revision = revision, skippedEntries = skipped)
    }

    /**
     * The build's step: `main <stops.tsv> <station_index.json> <out>`. Fails the build on a file it
     * can't read whole, and on files [Hubs.of] builds no station model from, saying why: the app never
     * ships without one (maintainer, 2026-10-19), so nothing in it falls back to TfL's codes or names.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.size != 3) {
            System.err.println("usage: StationBundle <stops.tsv> <station_index.json> <out>")
            exitProcess(2)
        }
        val (stopsFile, indexFile, outFile) = args.map(::File)
        build(stopsFile, indexFile, outFile)?.let { why ->
            System.err.println("station bundle: $why")
            exitProcess(1)
        }
    }

    /** [main]'s work: writes the bundle to [outFile], or says why it can't, writing nothing. */
    fun build(stopsFile: File, indexFile: File, outFile: File): String? {
        val stops = stopsFile.bufferedReader().use { BundledStops.read(it) }
        val index = StationIndexFile.parse(indexFile.readText()) { System.err.println("station index: $it") }
        // A bundle the app would refuse fails the build instead (Codex on #1002).
        if (index.revision != stops.revision) return "the station index and stops are from different refreshes"
        var why = "unknown"
        val hubs = Hubs.of(index, stops) { why = it } ?: return "no station model: $why"
        outFile.parentFile?.mkdirs()
        outFile.outputStream().use { write(index, stops, hubs, it) }
        return null
    }

    /** Writes a bundle's values, each string once ([string]), into [bytes]. */
    internal class Writer {
        val bytes = ByteArrayOutputStream(1 shl 20)
        private val out = DataOutputStream(bytes)
        private val strings = LinkedHashMap<String, Int>()

        fun int(value: Int) {
            require(value >= 0) { "station bundle: negative count" }
            var v = value
            while (v >= 0x80) {
                out.writeByte((v and 0x7f) or 0x80)
                v = v ushr 7
            }
            out.writeByte(v)
        }

        fun double(value: Double) = out.writeDouble(value)

        // A latitude or longitude in millionths of a degree: half a double's bytes, and exact for
        // every source position (no file gives more than six places), which this checks.
        fun position(value: Double) {
            val micro = Math.round(value * MICRO)
            require(micro in Int.MIN_VALUE..Int.MAX_VALUE && micro / MICRO == value) {
                "station bundle: a position finer than a millionth of a degree"
            }
            out.writeInt(micro.toInt())
        }

        fun boolean(value: Boolean) = out.writeBoolean(value)

        fun bytes(value: ByteArray) {
            int(value.size)
            out.write(value)
        }

        fun string(value: String) = int(strings.getOrPut(value) { strings.size })

        fun strings(values: Collection<String>) {
            int(values.size)
            values.forEach(::string)
        }

        fun stringMap(map: Map<String, String>) {
            int(map.size)
            map.forEach { (k, v) -> string(k); string(v) }
        }

        fun <V> map(map: Map<String, V>, value: (V) -> Unit) {
            int(map.size)
            map.forEach { (k, v) -> string(k); value(v) }
        }

        fun points(points: List<Coordinates>) {
            int(points.size)
            points.forEach { position(it.latitude); position(it.longitude) }
        }

        fun writeStrings(data: DataOutputStream) {
            data.writeInt(strings.size)
            strings.keys.forEach(data::writeUTF)
        }
    }

    /** Reads what [Writer] wrote, in the same order. */
    internal class Reader(private val data: DataInputStream) {
        private val table: Array<String> = data.readInt().let { size ->
            require(size >= 0) { "station bundle: malformed string table" }
            Array(size) { data.readUTF() }
        }

        fun int(): Int {
            var value = 0
            var shift = 0
            while (true) {
                val b = data.readUnsignedByte()
                value = value or ((b and 0x7f) shl shift)
                if (b < 0x80) return value.also { require(it >= 0) { "station bundle: malformed count" } }
                shift += 7
                require(shift < 35) { "station bundle: malformed count" }
            }
        }

        fun double(): Double = data.readDouble()

        fun position(): Double = data.readInt() / MICRO

        fun boolean(): Boolean = data.readBoolean()

        fun bytes(): ByteArray = ByteArray(int()).also(data::readFully)

        fun string(): String = table.getOrNull(int()) ?: throw IllegalArgumentException("station bundle: unknown string")

        fun strings(): List<String> = List(int()) { string() }

        // In the order written: a station's lines are shown in its index's order (Codex on #1002).
        fun stringMap(): LinkedHashMap<String, String> {
            val size = int()
            return LinkedHashMap<String, String>(capacity(size)).also { map -> repeat(size) { map[string()] = string() } }
        }

        fun <V> map(value: () -> V): LinkedHashMap<String, V> {
            val size = int()
            return LinkedHashMap<String, V>(capacity(size)).also { map -> repeat(size) { map[string()] = value() } }
        }

        fun points(): List<Coordinates> = List(int()) { Coordinates(position(), position()) }

        private fun capacity(size: Int) = (size / 0.75f).toInt() + 1
    }
}
