package app.stopdash.data

import android.Manifest
import android.app.Application
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowWifiInfo

/** Which access point the phone is on, read from what Android holds. A synthetic address only. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConnectedWifiReaderTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(ConnectivityManager::class.java)

    private fun onWifi() {
        shadowOf(manager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true),
        )
    }

    private fun capabilities(bssid: String, metered: Boolean, ssid: String? = null): NetworkCapabilities =
        ShadowNetworkCapabilities.newInstance().also { caps ->
            shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            if (!metered) shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            shadowOf(caps).setTransportInfo(
                ShadowWifiInfo.newInstance().also { info ->
                    shadowOf(info).setBSSID(bssid)
                    ssid?.let { shadowOf(info).setSSID(it) }
                },
            )
        }

    private fun precise() = shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

    @Test
    fun `on Wi-Fi, the access point it's on and whether it's metered, and the callback is removed after`() = runTest {
        precise()
        onWifi()
        for (metered in listOf(false, true)) {
            val reading = async { ConnectedWifiReader(app).connected() }
            runCurrent()
            val callback = shadowOf(manager).networkCallbacks.single()
            callback.onCapabilitiesChanged(checkNotNull(manager.activeNetwork), capabilities("aa:bb:cc:00:00:01", metered))
            runCurrent()
            assertEquals(WifiReading("aa:bb:cc:00:00:01", ssid = null, metered), reading.getCompleted())
            assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
        }
    }

    @Test
    fun `behind a VPN, the Wi-Fi under it is still read`() = runTest {
        precise()
        shadowOf(manager).setActiveNetworkInfo(
            ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_VPN, 0, true, true),
        )
        val vpn = checkNotNull(manager.activeNetwork)
        shadowOf(manager).setNetworkCapabilities(vpn, ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(NetworkCapabilities.TRANSPORT_VPN) })
        val reading = async { ConnectedWifiReader(app).connected() }
        runCurrent()
        val wifiNetwork = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
        shadowOf(manager).networkCallbacks.single().onCapabilitiesChanged(wifiNetwork, capabilities("aa:bb:cc:00:00:01", metered = false))
        runCurrent()
        assertEquals(WifiReading("aa:bb:cc:00:00:01", ssid = null, metered = false), reading.getCompleted())
    }

    @Test
    fun `with mobile data the default, the Wi-Fi the phone is still on is read`() = runTest {
        precise()
        // Robolectric's default: mobile data is the default network, with a Wi-Fi network beside it,
        // as when the Wi-Fi has no internet.
        val reading = async { ConnectedWifiReader(app).connected() }
        runCurrent()
        val wifiNetwork = manager.allNetworks.first { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        shadowOf(manager).networkCallbacks.single().onCapabilitiesChanged(wifiNetwork, capabilities("aa:bb:cc:00:00:01", metered = false))
        runCurrent()
        assertEquals(WifiReading("aa:bb:cc:00:00:01", ssid = null, metered = false), reading.getCompleted())
    }

    @Test
    fun `not on Wi-Fi, nothing is asked for at all`() = runTest {
        precise()
        // Mobile data only: no Wi-Fi network at all, so no callback that could only time out.
        manager.allNetworks
            .filter { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            .forEach { shadowOf(manager).removeNetwork(it) }
        assertNull(ConnectedWifiReader(app).connected())
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }

    @Test
    fun `without precise location the address can't be had, so nothing is asked for`() = runTest {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        onWifi()
        assertNull(ConnectedWifiReader(app).connected())
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }

    @Test
    fun `no answer in time is no access point, and the callback is still removed`() = runTest {
        precise()
        onWifi()
        val reading = async { ConnectedWifiReader(app).connected() }
        runCurrent()
        assertEquals(1, shadowOf(manager).networkCallbacks.size)
        advanceTimeBy(ConnectedWifiReader.TIMEOUT_MILLIS + 1)
        runCurrent()
        assertNull(reading.getCompleted())
        assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }

    @Test
    fun `the network's name is read with the access point`() = runTest {
        precise()
        onWifi()
        val reading = async { ConnectedWifiReader(app).connected() }
        runCurrent()
        shadowOf(manager).networkCallbacks.single()
            .onCapabilitiesChanged(checkNotNull(manager.activeNetwork), capabilities("aa:bb:cc:00:00:01", metered = false, ssid = "ExampleWifi"))
        runCurrent()
        assertEquals(WifiReading("aa:bb:cc:00:00:01", ssid = "ExampleWifi", metered = false), reading.getCompleted())
    }

    @Test
    fun `a network's name comes without Android's quotes, and a withheld or blank one as none`() {
        assertEquals("ExampleWifi", ConnectedWifiReader.networkName("\"ExampleWifi\""))
        // A name Android can't read as text comes as hex, unquoted.
        assertEquals("4578616d706c65", ConnectedWifiReader.networkName("4578616d706c65"))
        for (none in listOf(null, "", "\"\"", "<unknown ssid>", " ")) assertNull(none, ConnectedWifiReader.networkName(none))
    }
}
