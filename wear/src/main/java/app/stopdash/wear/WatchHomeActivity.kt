package app.stopdash.wear

import android.os.Bundle
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WatchEnvelopeStore.from(this)
        val frame = MutableStateFlow<TileFrame?>(null)
        lifecycleScope.launch(Dispatchers.IO) {
            store.load()
            WatchRefresh.resume(this@WatchHomeActivity)
            val topology = RouteTopologyStore.load(this@WatchHomeActivity)
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Each envelope that arrives restarts the ticker from it; leaving the foreground
                // cancels it and clears the frame, so coming back never shows the last live
                // countdowns before the ticker has recomputed them from now.
                launch {
                    try {
                        store.state.collectLatest { WatchAppFrames.tick(it, topology, Instant::now, { r -> store.current() ?: r.envelope }) { f -> frame.value = f } }
                    } finally {
                        frame.value = null
                    }
                }
                // Opening the app asks the phone for fresh departures (debounced), and looks up the
                // phone's latest item, so an update the listener couldn't read is picked up.
                WatchRefresh.request(this@WatchHomeActivity)
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
            WatchHomeScreen(shown, notice?.kind) { WatchRefresh.request(this@WatchHomeActivity) }
        }
    }
}
