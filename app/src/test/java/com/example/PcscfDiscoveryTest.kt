package com.example

import com.example.sip.discovery.DefaultPcscfDiscoveryProvider
import com.example.sip.discovery.PcscfDiscoverySource
import com.example.sip.discovery.PcscfDiscoveryState
import com.example.sip.discovery.PcscfEndpoint
import com.example.sip.engine.SipDestination
import com.example.sip.engine.SipNextHopResolver
import com.example.sip.model.SipMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun testDiscoveryLifecycleStartsInDiscoveringState() {
        val provider = DefaultPcscfDiscoveryProvider()
        // Must NOT initialize selected P-CSCF as STATIC_LEGACY before discovery
        assertNull("Selected P-CSCF must be null before discovery runs", provider.selectedPcscf.value)
        assertTrue("Initial state must be Discovering", provider.discoveryState.value is PcscfDiscoveryState.Discovering)

        // Calling startDiscovery() resets to null and Discovering
        provider.selectManualOverride(PcscfEndpoint(host = "10.0.0.1", port = 5060, source = PcscfDiscoverySource.MANUAL_OVERRIDE))
        assertNotNull(provider.selectedPcscf.value)
        provider.startDiscovery()
        assertNull(provider.selectedPcscf.value)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovering)
    }

    @Test
    fun testDiscoveryResultWinsOverStaticFallback() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Inject mock DNS resolver returning an IP address for an explicit FQDN
        provider.dnsResolver = { _, fqdn ->
            if (fqdn == "pcscf.ims.custom.org") {
                arrayOf(InetAddress.getByName("172.22.0.21"))
            } else {
                emptyArray()
            }
        }

        val selected = provider.discover(
            network = null,
            explicitPcscfFqdn = "pcscf.ims.custom.org",
            legacyFallback = legacyFallback
        )

        // Discovery result MUST win over static fallback
        assertNotNull(selected)
        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.DNS_A_AAAA, selected.source)
        assertEquals(PcscfDiscoverySource.DNS_A_AAAA, selected.endpoint.source)
        assertFalse("Must not be marked fallback when discovered via DNS", selected.isFallback)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovered)
        assertSameEndpointAndSource(selected)
    }

    @Test
    fun testStaticFallbackSelectedOnlyAfterDiscoveryFailure() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Explicit FQDN configured, but DNS returns no addresses (fails)
        provider.dnsResolver = { _, _ -> emptyArray() }

        val selected = provider.discover(
            network = null,
            explicitPcscfFqdn = "pcscf.nonexistent.domain",
            legacyFallback = legacyFallback
        )

        assertNotNull(selected)
        assertEquals("172.30.104.240", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.STATIC_LEGACY, selected.source)
        assertEquals(PcscfDiscoverySource.STATIC_LEGACY, selected.endpoint.source)
        assertTrue("Must be marked fallback after discovery failure", selected.isFallback)
        assertTrue(selected.statusDetail.contains("returned no addresses"))
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Fallback)
        assertSameEndpointAndSource(selected)
    }

    @Test
    fun testBlankFqdnDoesNotClaimDnsDiscovery() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Blank or null FQDN MUST NOT claim DNS discovery
        val testFqdns = listOf(null, "", "   ")
        for (blankFqdn in testFqdns) {
            val selected = provider.discover(
                network = null,
                explicitPcscfFqdn = blankFqdn,
                legacyFallback = legacyFallback
            )

            assertNotNull(selected)
            assertNotEquals("Blank FQDN must not claim DNS discovery", PcscfDiscoverySource.DNS_A_AAAA, selected.source)
            assertEquals("172.30.104.240", selected.host)
            assertEquals(5060, selected.port)
            assertEquals(PcscfDiscoverySource.STATIC_LEGACY, selected.source)
            assertEquals(PcscfDiscoverySource.STATIC_LEGACY, selected.endpoint.source)
            assertTrue(selected.isFallback)
            assertTrue("Status must clearly report no P-CSCF FQDN provisioned", selected.statusDetail.contains("no P-CSCF FQDN provisioned"))
            assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Fallback)
            assertSameEndpointAndSource(selected)
        }
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
        assertEquals(PcscfDiscoverySource.MANUAL_OVERRIDE, selected.endpoint.source)
        assertFalse(selected.isFallback)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovered)
        assertSameEndpointAndSource(selected)
    }

    private fun assertSameEndpointAndSource(selected: com.example.sip.discovery.SelectedPcscf) {
        assertEquals("Selected source must match selected endpoint source", selected.source, selected.endpoint.source)
        assertEquals("Selected host must match endpoint host", selected.host, selected.endpoint.host)
        assertEquals("Selected port must match endpoint port", selected.port, selected.endpoint.port)
    }

    @Test
    fun testDiscoverySourcesHonestyClassification() {
        // Verify standards classification according to prompt requirements
        assertTrue(PcscfDiscoverySource.STATIC_LEGACY.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.STATIC_LEGACY.isImplemented)

        assertTrue(PcscfDiscoverySource.DNS_A_AAAA.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.DNS_A_AAAA.isImplemented)

        // DNS_SRV is not yet implemented; marked false
        assertFalse(PcscfDiscoverySource.DNS_SRV.isImplemented)

        // Carrier/platform restricted
        assertFalse(PcscfDiscoverySource.DHCP_OPTION_120.isAvailableToStandardApp)
        assertFalse(PcscfDiscoverySource.PCO_PROVISIONED.isAvailableToStandardApp)
        assertFalse(PcscfDiscoverySource.ISIM_EF_PCSCF.isAvailableToStandardApp)
    }

    @Test
    fun testIpv6EndpointFormatting() {
        val ipv6Endpoint = PcscfEndpoint(
            host = "2001:db8::1",
            port = 5060,
            transport = "UDP"
        )
        assertTrue(ipv6Endpoint.isIpv6)
        assertEquals("[2001:db8::1]:5060", ipv6Endpoint.toHostPort())
        assertEquals("sip:[2001:db8::1]:5060;transport=udp", ipv6Endpoint.toSipUri())
    }

    @Test
    fun testSipNextHopResolverRouting() {
        // 1. Initial / out-of-dialog: routes to P-CSCF
        val initialHop = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = null,
            fallbackHost = "172.30.104.240",
            fallbackPort = 5060
        )
        assertEquals("172.30.104.240", initialHop.host)
        assertEquals(5060, initialHop.port)

        // 2. In-dialog with Route set: routes to first Route
        val inDialogWithRoute = SipNextHopResolver.resolveInDialogDestination(
            routeSet = listOf("sip:pcscf-edge.ims.net:5060;lr", "sip:scscf.ims.net:6060;lr"),
            remoteTargetUri = "sip:user@10.0.1.5:5060",
            selectedPcscf = null,
            fallbackHost = "172.30.104.240",
            fallbackPort = 5060
        )
        assertEquals("pcscf-edge.ims.net", inDialogWithRoute.host)
        assertEquals(5060, inDialogWithRoute.port)

        // 3. In-dialog with empty Route set: routes to Contact remote target
        val inDialogNoRoute = SipNextHopResolver.resolveInDialogDestination(
            routeSet = emptyList(),
            remoteTargetUri = "sip:contact-peer@192.168.102.7:5062",
            selectedPcscf = null,
            fallbackHost = "172.30.104.240",
            fallbackPort = 5060
        )
        assertEquals("192.168.102.7", inDialogNoRoute.host)
        assertEquals(5062, inDialogNoRoute.port)

        // 4. Response routing: routes according to top Via received/rport
        val rawRequest = "INFO sip:me@domain SIP/2.0\r\n" +
                "Via: SIP/2.0/UDP 10.0.0.99:5060;received=192.168.102.20;rport=5088;branch=z9hG4bK-abc\r\n" +
                "From: <sip:sender@domain>;tag=11\r\n" +
                "To: <sip:me@domain>;tag=22\r\n" +
                "Call-ID: call-123\r\n" +
                "CSeq: 10 INFO\r\n\r\n"
        val parsedMsg = SipMessage.parse(rawRequest)
        val responseHop = SipNextHopResolver.resolveResponseDestination(
            requestMsg = parsedMsg,
            packetSourceHost = "192.168.102.20",
            packetSourcePort = 5088,
            defaultPcscfHost = "172.30.104.240",
            defaultPcscfPort = 5060
        )
        assertEquals("192.168.102.20", responseHop.host)
        assertEquals(5088, responseHop.port)
    }
}
