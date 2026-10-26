package app.stopdash.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.stopdash.domain.SignalReturns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext

/**
 * Tells [returns] when the phone's default network goes and when it reaches the internet again, until
 * canceled: run while a trip on the way is followed, so it refreshes as soon as the signal comes back
 * ([app.stopdash.domain.awaitRefresh]). Only whether there is a connection, never which network. A
 * phone that won't register the callback is said once, with the stack, and followed by the timer alone.
 */
suspend fun watchSignal(
    context: Context,
    returns: SignalReturns,
    // Where the network is read and the callback registered and removed: binder calls, never on the main thread.
    io: CoroutineDispatcher = Dispatchers.IO,
    fail: (Throwable, String) -> Unit,
) {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return awaitCancellation()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (reachesInternet(capabilities)) returns.back() else returns.lost()
        }

        override fun onLost(network: Network) = returns.lost()
    }
    watchWith(io, fail, start = {
        // Offline as it starts (a trip started or restored in a tunnel): marked lost, as Android tells a callback of a
        // network gone only once it was there, so the first return still starts a hunt (Codex, #1235). Read before
        // registering, so a return in between still counts; online, nothing is marked and no hunt starts.
        val now = manager.activeNetwork?.let(manager::getNetworkCapabilities)
        if (now == null || !reachesInternet(now)) returns.lost()
        manager.registerDefaultNetworkCallback(callback)
    }, stop = { manager.unregisterNetworkCallback(callback) })
}

/**
 * Runs [start] on [io], then waits to be canceled and runs [stop] on [io] (a single-thread caller, the main thread
 * included, does neither: Codex, #1235). A [start] that throws is [fail]ed and the wait goes on with nothing to stop.
 */
internal suspend fun watchWith(io: CoroutineDispatcher, fail: (Throwable, String) -> Unit, start: () -> Unit, stop: () -> Unit) {
    // Whether [start] finished: canceled as it returns, what it registered is still removed.
    var started = false
    try {
        withContext(io) {
            start()
            started = true
        }
        awaitCancellation()
    } catch (e: CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        // Too many callbacks registered, or the network permission missing: the trip still refreshes on its timer.
        // Its stack, never its message, which the log's failure() leaves out: which of the calls above threw.
        fail(e, "signal watch not started")
        awaitCancellation()
    } finally {
        if (started) withContext(NonCancellable + io) { stop() }
    }
}

/** Whether a network with [capabilities] reaches the internet: Android checked it does, not only that it's there. */
internal fun reachesInternet(capabilities: NetworkCapabilities): Boolean =
    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
