package app.stopdash.wear

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
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
import kotlinx.coroutines.launch

/**
 * The watch app's entry point (dev-docs/wear-os.md *Surfaces*): every one of the widget's rows,
 * favorites first. Renders from the stored envelope at once (the read runs off-thread); while in
 * the foreground a ticker re-renders it at each countdown minute, departure and staleness boundary
 * ([WatchAppFrames.tick]), and it stops when the app leaves the foreground.
 */
class WatchHomeActivity : ComponentActivity() {
    private companion object {
        // How often the trip's minutes and age are worked out again while it's shown.
        val TRIP_TICK: Duration = Duration.ofSeconds(15)
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
                launch { WatchTripState.lookUpRetrying(this@WatchHomeActivity) }
                WatchSurfaces.lookUpRetrying(this@WatchHomeActivity, store)
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
            WatchHomeScreen(shown, notice?.kind, { WatchRefresh.request(this@WatchHomeActivity) }, WatchTripState.shown(trip, now, elapsedNow), now)
        }
    }
}
