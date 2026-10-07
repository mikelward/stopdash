package app.stopdash

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.IconCompat
import app.stopdash.domain.FavoritePlace
import app.stopdash.domain.LauncherShortcuts
import app.stopdash.ui.favoriteRouteName
import app.stopdash.ui.placeIconArt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The launcher's long-press shortcuts to the saved places (SPEC *Launcher shortcuts*): each opens
 * the app on a trip to that place from the rider's position, as its chip does. The shortcut carries
 * the place's id alone ([LauncherShortcuts]); the coordinate is looked up when it's tapped.
 */
internal object PlaceShortcuts {
    const val ACTION_ROUTE_TO_PLACE = "app.stopdash.action.ROUTE_TO_PLACE"
    const val EXTRA_PLACE_ID = "app.stopdash.extra.PLACE_ID"

    // Glyph on the launcher icon's own white, in its blue (ic_launcher_foreground).
    private const val GLYPH_COLOR = 0xFF0098D4.toInt()
    private const val BACKGROUND_COLOR = 0xFFFFFFFF.toInt()

    // An adaptive icon is 108 dp, its visible part the middle 72; the glyph sits well inside that.
    private const val ICON_DP = 108
    private const val GLYPH_DP = 48

    private val lock = Mutex()

    // What was last handed the launcher, so reopening the app (or a rotation) asks it for nothing new.
    @Volatile
    private var published: List<Published>? = null

    private data class Published(val id: String, val name: String, val icon: String?)

    /**
     * Hands the launcher a shortcut for each of [places] it has room for, renames pinned ones still
     * saved, and disables pinned ones whose place is gone (a tap then says so in the launcher). Hops
     * to [worker] first: it walks the places, draws icons and talks to the launcher, none of which
     * belongs on the main thread. A launcher that refuses (rate limit, a bad icon) is logged and the
     * next call tries again; the app is unaffected.
     */
    suspend fun publish(
        context: Context,
        places: List<FavoritePlace>,
        removedMessage: String,
        worker: CoroutineDispatcher = Dispatchers.Default,
        warn: (String) -> Unit,
    ) = withContext(worker) {
        lock.withLock {
            try {
                val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
                val offered = LauncherShortcuts.offered(places, max)
                val all = LauncherShortcuts.offered(places, Int.MAX_VALUE)
                val next = all.map { Published(LauncherShortcuts.idFor(it), favoriteRouteName(it), it.icon) }
                // The framework's list (minSdk 34 has it): every pin, disabled ones included.
                val pinnedShortcuts = context.getSystemService(ShortcutManager::class.java)?.pinnedShortcuts.orEmpty()
                val pinned = pinnedShortcuts.map { it.id }
                // Only pins still enabled: one already disabled stays pinned, and counting it would
                // republish everything on every call.
                val stale = LauncherShortcuts.stale(pinnedShortcuts.filter { it.isEnabled }.map { it.id }, places)
                if (next == published && stale.isEmpty()) return@withLock
                // First, and whatever the launcher does with the rest: a removed place's shortcut must
                // not stay launchable because a later call was refused.
                if (stale.isNotEmpty()) ShortcutManagerCompat.disableShortcuts(context, stale, removedMessage)
                // Built only for what the launcher gets: the shortcuts offered, and pinned ones past them
                // (renamed and re-enabled too, for a place put back). Each is an icon bitmap, so never
                // one per saved place however many there are.
                val pinnedIds = pinned.toHashSet()
                val infos = all.mapIndexedNotNull { rank, place ->
                    if (rank < offered.size || LauncherShortcuts.idFor(place) in pinnedIds) info(context, place, rank) else null
                }
                if (!ShortcutManagerCompat.setDynamicShortcuts(context, infos.filter { it.rank < offered.size })) {
                    published = null
                    warn("launcher shortcuts: rate-limited, ${offered.size} not published")
                    return@withLock
                }
                val pinnedSaved = infos.filter { it.id in pinnedIds }
                if (pinnedSaved.isNotEmpty()) {
                    ShortcutManagerCompat.enableShortcuts(context, pinnedSaved)
                    // Refused (rate-limited) leaves the pinned ones stale: nothing is recorded as
                    // published, so the next call tries again rather than skipping as unchanged.
                    if (!ShortcutManagerCompat.updateShortcuts(context, pinnedSaved)) {
                        published = null
                        warn("launcher shortcuts: rate-limited, ${pinnedSaved.size} pinned not updated")
                        return@withLock
                    }
                }
                published = next
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // IllegalStateException (locked user, rate limit) or IllegalArgumentException (a refused
                // shortcut): nothing held to undo; the last published set stays unrecorded so the next
                // change (or the next app start) tries again.
                published = null
                warn("launcher shortcuts: publish failed (${e::class.simpleName})")
            }
        }
    }

    /** Forgets what was last published, as a new process would (each test's launcher starts empty). */
    @VisibleForTesting
    fun forgetPublished() {
        published = null
    }

    /** The place a launcher shortcut asked for, taken off [intent] so it's acted on once. */
    fun takePlaceId(intent: Intent?): String? {
        if (intent?.action != ACTION_ROUTE_TO_PLACE) return null
        val id = intent.getStringExtra(EXTRA_PLACE_ID)
        intent.removeExtra(EXTRA_PLACE_ID)
        // Left as the shortcut's action, a recreation reading the intent again would find no id.
        return id?.takeIf { it.isNotBlank() }
    }

    /**
     * Tells the launcher [placeId]'s shortcut was used, so it ranks the ones the rider takes. Hops to
     * [worker] first: it's a call to the system's shortcut service.
     */
    suspend fun reportUsed(
        context: Context,
        placeId: String,
        worker: CoroutineDispatcher = Dispatchers.Default,
        warn: (String) -> Unit,
    ) = withContext(worker) {
        try {
            ShortcutManagerCompat.reportShortcutUsed(context, LauncherShortcuts.idFor(placeId))
        } catch (e: IllegalStateException) {
            // Only the launcher's ranking is lost; the trip is already open.
            warn("launcher shortcuts: report failed (${e::class.simpleName})")
        }
    }

    private fun info(context: Context, place: FavoritePlace, rank: Int): ShortcutInfoCompat {
        val name = favoriteRouteName(place)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_ROUTE_TO_PLACE)
            .putExtra(EXTRA_PLACE_ID, place.id)
            // A fresh screen, not whatever was left open: every overlay, dialog and half-done
            // replan is screen state, so starting afresh closes them all without listing them. A trip
            // on the way is the process's, and carries on.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return ShortcutInfoCompat.Builder(context, LauncherShortcuts.idFor(place))
            .setShortLabel(name)
            .setLongLabel(name)
            .setIcon(icon(context, place.icon))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    // The place's glyph on white, as an adaptive bitmap; the app's own foreground for a place with none.
    private fun icon(context: Context, iconId: String?): IconCompat {
        val density = context.resources.displayMetrics.density
        val size = (ICON_DP * density).toInt()
        val bitmap: Bitmap = createBitmap(size, size)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BACKGROUND_COLOR)
        val art = iconId?.let(placeIconArt::get)
        val drawable = if (art != null) {
            ContextCompat.getDrawable(context, art.drawable)?.mutate()?.apply { setTint(GLYPH_COLOR) }
        } else {
            ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        }
        if (drawable != null) {
            val inset = if (art != null) ((ICON_DP - GLYPH_DP) * density / 2).toInt() else 0
            drawable.setBounds(inset, inset, size - inset, size - inset)
            drawable.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
