package com.example

import com.example.sip.discovery.DefaultPcscfDiscoveryProvider
import com.example.sip.discovery.PcscfDiscoverySource
import com.example.sip.discovery.PcscfDiscoveryState
import com.example.sip.discovery.PcscfEndpoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class PcscfDiscoveryTest {

    private val legacyFallback = PcscfEndpoint(
        host = "172.30.104.240",
        port = 5060,
        transport = "UDP",
        source = PcscfDiscoverySource.STATIC_LEGACY
    )

    @Test
    fun testFallbackWhenNoNetworkOrDnsFails() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Discovery without network or DNS failure should return legacy fallback with isFallback = true
        val selected = provider.discover(
            network = null,
            realm = "ims.mnc070.mcc901.3gppnetwork.org",
            legacyFallback = legacyFallback
        )

        assertNotNull(selected)
        assertEquals("172.30.104.240", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.STATIC_LEGACY, selected.source)
        assertTrue(selected.isFallback)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Fallback)
    }

    @Test
    fun testDiscoverySuccessWithMockDnsResolver() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Inject mock DNS resolver returning an IP address for pcscf.<realm>
        provider.dnsResolver = { _, fqdn ->
            if (fqdn.startsWith("pcscf.")) {
                arrayOf(InetAddress.getByName("172.22.0.21"))
            } else {
                emptyArray()
            }
        }

        val selected = provider.discover(
            network = null, // Uses injected resolver
            realm = "ims.mnc070.mcc901.3gppnetwork.org",
            legacyFallback = legacyFallback
        )

        assertNotNull(selected)
        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.DNS_A_AAAA, selected.source)
        assertFalse(selected.isFallback)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovered)
    }

    @Test
    fun testManualOverride() {
        val provider = DefaultPcscfDiscoveryProvider()
        val customEndpoint = PcscfEndpoint(
            host = "10.0.0.1",
            port = 5060,
            transport = "UDP",
            source = PcscfDiscoverySource.MANUAL_OVERRIDE
        )

        provider.selectManualOverride(customEndpoint)
        val selected = provider.selectedPcscf.value
        assertNotNull(selected)
        assertEquals("10.0.0.1", selected!!.host)
        assertEquals(PcscfDiscoverySource.MANUAL_OVERRIDE, selected.source)
        assertFalse(selected.isFallback)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovered)
    }

    @Test
    fun testDiscoverySourcesClassification() {
        // Verify standards classification according to prompt requirements
        assertTrue(PcscfDiscoverySource.STATIC_LEGACY.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.DNS_A_AAAA.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.DNS_SRV.isAvailableToStandardApp)
        assertFalse(PcscfDiscoverySource.DHCP_OPTION_120.isAvailableToStandardApp)
        assertFalse(PcscfDiscoverySource.PCO_PROVISIONED.isAvailableToStandardApp)
        assertFalse(PcscfDiscoverySource.ISIM_EF_PCSCF.isAvailableToStandardApp)
    }
}
