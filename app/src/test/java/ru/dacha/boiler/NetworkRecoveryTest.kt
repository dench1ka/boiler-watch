package ru.dacha.boiler

import android.net.NetworkCapabilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25, 35])
class NetworkRecoveryTest {
    private lateinit var recovery: NetworkRecovery
    private var events = 0
    private val network = ShadowNetwork.newInstance(1)
    private fun capabilities(valid: Boolean) = ShadowNetworkCapabilities.newInstance().apply {
        shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (valid) shadowOf(this).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
    @Before fun setup() { events = 0; recovery = NetworkRecovery(RuntimeEnvironment.getApplication()) { events++ } }
    @After fun cleanup() { recovery.stop() }
    @Test fun captiveOrUnvalidatedNetworkDoesNotStartDelivery() {
        recovery.callback.onCapabilitiesChanged(network, capabilities(false))
        assertEquals(0, events)
        recovery.callback.onCapabilitiesChanged(network, capabilities(true))
        assertEquals(1, events)
    }
    @Test fun repeatedCapabilitiesDoNotFloodDeliveryButReconnectionRetries() {
        repeat(3) { recovery.callback.onCapabilitiesChanged(network, capabilities(true)) }
        assertEquals(1, events)
        recovery.callback.onLost(network)
        recovery.callback.onCapabilitiesChanged(network, capabilities(true))
        assertEquals(2, events)
    }
    @Test fun internetReturningOnSameNetworkTriggersDelivery() {
        recovery.callback.onCapabilitiesChanged(network, capabilities(true))
        recovery.callback.onCapabilitiesChanged(network, capabilities(false))
        recovery.callback.onCapabilitiesChanged(network, capabilities(true))
        assertEquals(2, events)
    }
}
