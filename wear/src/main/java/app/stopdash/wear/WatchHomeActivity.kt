package app.stopdash.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.stopdash.data.RouteTopologyStore
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The watch app's entry point (dev-docs/wear-os.md *Surfaces*): every one of the widget's rows,
 * favorites first. Renders from the stored envelope at once (the read runs off-thread); while in
 * the foreground a ticker re-renders it at each countdown minute, departure and staleness boundary
 * ([WatchAppFrames.tick]), and it stops when the app leaves the foreground.
 */
class WatchHomeActivity : ComponentActivity() {
    private companion object {
        const val TAG = "StopDash.Watch"
        const val ASKED_NOTIFICATIONS = "asked_notifications"

        // How often the trip's minutes and age are worked out again while it's shown.
        val TRIP_TICK: Duration = Duration.ofSeconds(15)
    }

    private var askedNotifications = false

    // Counts taps on the trip's ongoing activity that reached the app already open ([onNewIntent]).
    private val openTrip = MutableStateFlow(0)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(WatchTripOngoing.EXTRA_OPEN_TRIP, false)) openTrip.value++
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(ASKED_NOTIFICATIONS, askedNotifications)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchEnvelopeStore.from(this)
        val frame = MutableStateFlow<TileFrame?>(null)
        lifecycleScope.launch(Dispatchers.IO) {
            store.load()
            WatchRefresh.resume(this@WatchHomeActivity)
            // The watch's own asset, read once here off the main thread; each envelope's route lines
            // go over it below.
            RouteTopologyStore.bundled(this@WatchHomeActivity)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each envelope that arrives restarts the ticker from it; leaving the foreground
                // cancels it and clears the frame, so coming back never shows the last live
                // countdowns before the ticker has recomputed them from now.
                launch {
                    try {
                        store.state.collectLatest { received ->
                            val topology = RouteTopologyStore.over(
                                this@WatchHomeActivity,
                                (received as? WatchReceived.Received)?.envelope?.routePatterns().orEmpty(),
                            )
                            WatchAppFrames.tick(received, topology, Instant::now, { r -> store.current() ?: r.envelope }) { f -> frame.value = f }
                        }
                    } finally {
                        frame.value = null
                    }
                }
                // Opening the app asks the phone for fresh departures (debounced), and looks up the
                // phone's latest item, so an update the listener couldn't read is picked up.
                WatchRefresh.request(this@WatchHomeActivity)
                // The trip on the way, which may have arrived before this process started.
                launch {
                    WatchTripState.lookUpRetrying(this@WatchHomeActivity)
                    // One read back by a new process gets its ongoing activity too.
                    WatchTripOngoing.update(this@WatchHomeActivity)
                }
                WatchSurfaces.lookUpRetrying(this@WatchHomeActivity, store)
            }
        }
        // The trip's ongoing activity needs notifications: asked for while the app is in front with a
        // trip on screen, so the reason is in view, and once per open, kept across recreation (the
        // dialog itself pauses and resumes the app; the system stops asking after two refusals).
        // A held trip past its time isn't shown, so it doesn't ask.
        askedNotifications = savedInstanceState?.getBoolean(ASKED_NOTIFICATIONS) ?: false
        val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                lifecycleScope.launch(Dispatchers.IO) { WatchTripOngoing.update(this@WatchHomeActivity) }
            } else {
                Log.i(TAG, "notifications refused: no trip ongoing activity")
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                WatchTripState.trip.first { WatchTripState.shown(it, Instant.now(), SystemClock.elapsedRealtime()) != null }
                if (askedNotifications) return@repeatOnLifecycle
                askedNotifications = true
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        setContent {
            val shown by frame.collectAsStateWithLifecycle()
            val refresh by WatchRefresh.state.collectAsStateWithLifecycle()
            // The refresh notice, dropped when it expires without waiting for another change.
            val notice by produceState<RefreshNotice?>(null, refresh) {
                while (true) {
                    val current = RefreshPolicy.notice(refresh, Instant.now())
                    value = current
                    current ?: break
                    delay(Duration.between(Instant.now(), current.until).toMillis().coerceAtLeast(0) + 1)
                }
            }
            val trip by WatchTripState.trip.collectAsStateWithLifecycle()
            // The trip's countdowns and age, read again every [TRIP_TICK] and with each new trip: wall
            // time for the countdowns, the monotonic clock for how long the trip has been held. Only
            // while there's a trip and the app is in the foreground, so it wakes nothing otherwise.
            val clock by produceState(Instant.now() to SystemClock.elapsedRealtime(), trip) {
                value = Instant.now() to SystemClock.elapsedRealtime()
                if (trip == null) return@produceState
                this@WatchHomeActivity.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    // Until the trip goes ([WatchTripState.shown]): a held trip past its time is shown as
                    // none, and nothing it would redraw is left to tick for.
                    while (true) {
                        val tick = Instant.now() to SystemClock.elapsedRealtime()
                        value = tick
                        if (WatchTripState.shown(trip, tick.first, tick.second) == null) break
                        delay(TRIP_TICK.toMillis())
                    }
                }
            }
            val (now, elapsedNow) = clock
            val opened by openTrip.collectAsStateWithLifecycle()
            WatchHomeScreen(shown, notice?.kind, { WatchRefresh.request(this@WatchHomeActivity) }, WatchTripState.shown(trip, now, elapsedNow), now, opened)
        }
    }
}
