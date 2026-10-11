package app.stopdash.widget

import android.content.Context
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import app.stopdash.BuildConfig
import app.stopdash.StopdashDebugLog
import app.stopdash.data.RouteTopologyStore
import app.stopdash.domain.Departure
import app.stopdash.domain.DeparturesSnapshot
import app.stopdash.domain.LineRef
import app.stopdash.domain.LineStatus
import app.stopdash.domain.LineStatusCheck
import app.stopdash.domain.RouteTopology
import app.stopdash.domain.StopArrivals
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The example the launcher's widget picker shows before the widget is placed (SPEC *One widget,
 * many surfaces*): made-up departures at King's Cross St. Pancras, drawn by the widget's own
 * layout, so the picker shows what the widget looks like rather than the app's icon. Public TfL
 * line and station names only, and never the user's own stops (SPEC *Privacy*). The clock is
 * fixed, so the preview reads the same however long after publishing the picker shows it.
 */
internal object WidgetPreview {
    /** The time the example was "updated", and the clock its countdowns are read against. */
    val NOW: Instant = Instant.parse("2026-09-18T08:00:00Z")

    /**
     * The sizes the picker may draw the example at: the provider's minimum, its default 3x2 cell,
     * and a wider one. The launcher shows whichever fits its preview best.
     */
    val SIZES: Set<DpSize> = setOf(DpSize(180.dp, 110.dp), DpSize(250.dp, 180.dp), DpSize(320.dp, 180.dp))

    private const val STOP_ID = "940GZZLUKSX"

    fun snapshot(): DeparturesSnapshot {
        fun dep(lineId: String, lineName: String, direction: String, destination: String, inSeconds: Long, branch: String? = null) =
            Departure(lineId, lineName, direction, destination, null, NOW.plusSeconds(inSeconds), "tube", branch = branch)
        val lines = listOf(
            LineRef("victoria", "Victoria", "tube"),
            LineRef("northern", "Northern", "tube"),
            LineRef("piccadilly", "Piccadilly", "tube"),
        )
        val stop = StopArrivals(
            stopId = STOP_ID,
            stopName = "King's Cross St. Pancras",
            departures = listOf(
                dep("victoria", "Victoria", "outbound", "Brixton", 90),
                dep("victoria", "Victoria", "outbound", "Brixton", 330),
                dep("northern", "Northern", "outbound", "Morden", 150, branch = "Bank"),
                dep("northern", "Northern", "outbound", "Kennington", 480, branch = "Bank"),
                dep("piccadilly", "Piccadilly", "outbound", "Heathrow T5", 240),
                dep("piccadilly", "Piccadilly", "outbound", "Uxbridge", 600),
            ),
            fetchedAt = NOW,
            lines = lines,
        )
        // Every line checked and running well, so the example says nothing about disruptions.
        val statuses = lines.associate { it.id to LineStatusCheck(LineStatus(it.id, LineStatus.GOOD_SERVICE, "Good Service"), NOW) }
        return DeparturesSnapshot(stops = listOf(stop), fetchedAt = NOW, lineStatuses = statuses)
    }

    /**
     * The example's model at each of [SIZES], with the bundled branch topology read on [io] and the
     * models worked out on [worker], neither on the caller's thread (AGENTS.md *Main thread*); the
     * compact widget's when [bare].
     */
    suspend fun models(
        context: Context,
        bare: Boolean = false,
        io: CoroutineDispatcher = Dispatchers.IO,
        worker: CoroutineDispatcher = Dispatchers.Default,
    ): WidgetModels =
        models(withContext(io) { RouteTopologyStore.load(context) }, worker, bare)

    /**
     * The example's model at each of [SIZES], worked out on [worker] (AGENTS.md *Main thread*); the
     * compact widget's when [bare].
     */
    suspend fun models(topology: RouteTopology, worker: CoroutineDispatcher = Dispatchers.Default, bare: Boolean = false): WidgetModels =
        widgetModels(snapshot(), NOW, emptySet(), fontScale = 1f, topology, emptySet(), SIZES, worker = worker, bare = bare)

    // Kept out of backup and device transfer (backup_rules.xml): the system's preview belongs to this
    // install, so a restored record would stop a new phone ever getting one.
    private const val PREFS = "widget_preview"
    private const val PUBLISHED_VERSION = "published_version"

    /**
     * Hands the picker the example once per app version, on Android 15 and later, where the
     * picker shows a generated preview. The system limits how often a preview may be set, so it
     * isn't sent on every start; a call it refuses is tried again at the next start. Off the main
     * thread, and a failure costs only the picker's example, so it is logged and dropped.
     */
    fun publishOnce(context: Context, scope: CoroutineScope) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val app = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                if (prefs.getInt(PUBLISHED_VERSION, -1) == BuildConfig.VERSION_CODE) return@launch
                // Both widgets in the picker, the compact one too; both must take for the version to count.
                val manager = GlanceAppWidgetManager(app)
                val results = listOf(StopDashWidgetReceiver::class, StopDashCompactWidgetReceiver::class)
                    .map { manager.setWidgetPreviews(it) }
                if (results.all { it == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS }) {
                    prefs.edit().putInt(PUBLISHED_VERSION, BuildConfig.VERSION_CODE).apply()
                    StopdashDebugLog.info("widget: picker preview published")
                } else {
                    StopdashDebugLog.info("widget: picker preview rate-limited, retrying next start")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                StopdashDebugLog.warning("widget: picker preview failed: %s", e::class.simpleName)
            }
        }
    }
}
