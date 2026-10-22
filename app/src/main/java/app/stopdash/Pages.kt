package app.stopdash

import androidx.compose.runtime.MutableState
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty

/**
 * The app's pages, as the back stack holds them (`dev-docs/navigation3.md`): the near-me view at the bottom, and
 * each screen opened over it above, in the order they were opened, so Back returns to the one under it as it
 * was left (SPEC *Back*). A sealed family saved by its own serializer, so nothing is looked up by name.
 */
@Serializable
internal sealed interface Page : NavKey {
    /** The near-me list or the location gate: a new [generation] opens it afresh. */
    @Serializable
    data class NearMe(val generation: Int) : Page

    /**
     * The trip from here (To… from the near-me list), over the page it was opened from: the list, or Lines… for a
     * stop's To. Of the near-me view's [generation], so a new one opens it afresh too.
     */
    @Serializable
    data class HereTrip(val generation: Int) : Page

    @Serializable
    data object Licenses : Page

    @Serializable
    data object Settings : Page

    /** The trip on the way's screen. */
    @Serializable
    data object OnTheWay : Page

    @Serializable
    data object FavoritePlaces : Page

    @Serializable
    data object FavoriteJourneys : Page

    /** *Lines…*: the line search, a line's page and its stops. */
    @Serializable
    data object Lines : Page

    /** The From… search: "Find a station", or a trip's From row. */
    @Serializable
    data object StationSearch : Page

    /** A station's page (or a place's, to start from) and its trip, over the search it was picked in, if any. */
    @Serializable
    data object Station : Page

    /** A stop's details, tapped on a route page's stop list: a window over the page that route page is on. */
    @Serializable
    data object RouteStop : Page

    /** A stop's Departures, opened from its details or Lines…: a window over them. */
    @Serializable
    data object StopDepartures : Page
}

/**
 * A window over the page under it ([Page.RouteStop], [Page.StopDepartures]): on the stack, so a page opened over it
 * covers it until that page's Back, but drawn over the pages, which stay as they were under it, rather than among
 * them ([Pages.pages]).
 */
internal val Page.isWindow: Boolean get() = this == Page.RouteStop || this == Page.StopDepartures

/**
 * What's open, asked of the back stack: a screen is open while its page is on it, and opens over whatever is
 * on top. The flags the screens were opened and closed by read and write it ([flag]), so every way in and out
 * keeps working as it did, and what shows is what was opened last.
 *
 * Each page is on the stack at most once (opening one already open leaves it or brings it up), so it never holds
 * more than one of each kind of page, twelve: walking it is constant work, not work that grows with anything the
 * rider has, and it's read in composition like any other state (AGENTS.md *Main thread*). A hop to a worker would
 * draw a frame of the stack as it was.
 */
internal class Pages(val stack: NavBackStack<Page>) {
    init {
        require(stack.firstOrNull() is Page.NearMe) { "the near-me view is the stack's bottom" }
    }

    /** What was opened last: the page showing, or a window over it. */
    val top: Page get() = stack.last()

    /** The page showing, under any windows over it ([isWindow]). */
    val topPage: Page get() = stack.last { !it.isWindow }

    /** The stack's pages, its windows aside: what the pages are drawn from. */
    val pages: List<Page> get() = stack.filterNot { it.isWindow }

    /** Whether [window] shows: it's open with nothing over it but other windows, which show over it. */
    fun shows(window: Page): Boolean {
        val at = stack.indexOf(window)
        if (at < 0) return false
        for (i in at + 1 until stack.size) if (!stack[i].isWindow) return false
        return true
    }

    fun isOpen(page: Page): Boolean = page in stack

    /** Whether [page] is open under [over]: Lines… a stop's To opened the trip from here over. */
    fun isUnder(page: Page, over: Page): Boolean {
        val at = stack.indexOf(page)
        return at >= 0 && at < stack.indexOf(over)
    }

    /** [page] open over whatever is on top, or left where it is if it's open already. */
    fun open(page: Page) {
        require(page !is Page.NearMe) { "the near-me view is always open" }
        if (page !in stack) stack.add(page)
    }

    fun close(page: Page) {
        require(page !is Page.NearMe) { "the near-me view is always open" }
        stack.remove(page)
    }

    fun setOpen(page: Page, open: Boolean) = if (open) open(page) else close(page)

    /** [page] showing: opened over whatever is on top, or brought up from under the pages opened since. */
    fun show(page: Page) {
        if (top == page) return
        close(page)
        stack.add(page)
    }

    /** The near-me view's generation ([Page.NearMe]). */
    var generation: Int
        get() = (stack.first() as Page.NearMe).generation
        // A new key, so the near-me view opens afresh, its kept pages dropped, the trip from here's with it;
        // what's over them stays.
        set(value) {
            stack[0] = Page.NearMe(value)
            val trip = stack.indexOfFirst { it is Page.HereTrip }
            if (trip >= 0) stack[trip] = Page.HereTrip(value)
        }

    /** The trip from here's page, of this generation. */
    val hereTrip: Page.HereTrip get() = Page.HereTrip(generation)

    /** Whether the trip from here is open: set, it shows ([show]); cleared, it closes. */
    var tripOpen: Boolean
        get() = hereTrip in stack
        set(value) = if (value) show(hereTrip) else close(hereTrip)

    /**
     * A screen's open flag, read from and written to the stack: set, the screen shows ([show]), as opening it
     * asks, even from under a page opened over it since (a trip End couldn't forget, reopening the trip on the
     * way's screen to say so); cleared, it closes.
     */
    fun flag(page: Page): Flag = Flag(this, page)

    class Flag(private val pages: Pages, private val page: Page) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = pages.isOpen(page)

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) =
            if (value) pages.show(page) else pages.close(page)
    }

    /**
     * A value kept in [state] whose page is open while [open] holds for it: set, the page opens over whatever is
     * on top, or stays where it is if it's open already ([setOpen]), as the From… search does under the station
     * a change of start goes back to; cleared, it closes.
     */
    fun <T> kept(page: Page, state: MutableState<T>, open: (T) -> Boolean): Kept<T> = Kept(this, page, state, open)

    class Kept<T>(
        private val pages: Pages,
        private val page: Page,
        private val state: MutableState<T>,
        private val open: (T) -> Boolean,
    ) {
        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

        operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            pages.setOpen(page, open(value))
        }
    }
}
