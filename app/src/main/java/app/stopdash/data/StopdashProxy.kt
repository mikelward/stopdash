package app.stopdash.data

import app.stopdash.BuildConfig

/**
 * stopdash's proxy (`dev-docs/proxy.md`): National Rail's incidents and live
 * departure boards with stopdash's own keys, so a rider needs none (SPEC *National Rail*). A rider's own
 * key still goes straight to National Rail, as before the proxy.
 */
object StopdashProxy {
    private val built: String? = BuildConfig.PROXY_URL.trim().trimEnd('/').ifEmpty { null }

    /** The proxy's address, from `stopdash.proxyUrl`; null in a build with none. */
    @Volatile
    var url: String? = built
        private set

    /** Lets a test stand for a build with no proxy, or with one; [resetForTest] restores the build's. */
    internal fun setForTest(url: String?) {
        this.url = url
    }

    internal fun resetForTest() {
        url = built
    }

    /** Whether National Rail times can be had with [key] (the rider's own, or none): with it, or through the proxy. */
    fun timesWith(key: String?, proxy: String? = url): Boolean = !key.isNullOrBlank() || proxy != null
}
