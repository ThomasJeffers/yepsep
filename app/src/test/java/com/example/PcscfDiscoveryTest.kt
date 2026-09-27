package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sip.discovery.DefaultPcscfDiscoveryProvider
import com.example.sip.discovery.PcscfConfig
import com.example.sip.discovery.PcscfDiscoveryMethod
import com.example.sip.discovery.PcscfDiscoverySource
import com.example.sip.discovery.PcscfDiscoveryState
import com.example.sip.discovery.PcscfEndpoint
import com.example.sip.discovery.SelectedPcscf
import com.example.sip.engine.McpttSipStack
import com.example.sip.engine.RegistrationState
import com.example.sip.engine.SipDestination
import com.example.sip.engine.SipNextHopResolver
import com.example.sip.model.SipMessage
import com.example.sip.model.SipProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PcscfDiscoveryTest {

    private val testConfig = PcscfConfig(
        method = PcscfDiscoveryMethod.PRECONFIGURED,
        preconfiguredHost = "172.22.0.21",
        preconfiguredPort = 5060,
        dnsFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
        dnsPort = 5060,
        transport = "UDP"
    )

    private val legacyFallback = PcscfEndpoint(
        host = "172.22.0.21",
        port = 5060,
        resolvedIp = "172.22.0.21",
        transport = "UDP",
        source = PcscfDiscoverySource.PRECONFIGURED
    )

    @Test
    fun testDiscoveryLifecycleStartsInDiscoveringState() {
        val provider = DefaultPcscfDiscoveryProvider()
        // Must NOT initialize selected P-CSCF as STATIC_LEGACY/FALLBACK before discovery
        assertNull("Selected P-CSCF must be null before discovery runs", provider.selectedPcscf.value)
        assertTrue("Initial state must be Discovering", provider.discoveryState.value is PcscfDiscoveryState.Discovering)

        // Calling startDiscovery() resets to null and Discovering
        provider.selectManualOverride(PcscfEndpoint(host = "10.0.0.1", port = 5060, source = PcscfDiscoverySource.MANUAL_OVERRIDE))
        assertNotNull(provider.selectedPcscf.value)
        provider.startDiscovery(PcscfDiscoveryMethod.NETWORK_DNS)
        assertNull(provider.selectedPcscf.value)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Discovering)
        assertEquals("DISCOVERING", provider.discoveryState.value.toString())
    }

    @Test
    fun testPreconfiguredDiscoveryMethod() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        val selected = provider.discover(
            method = PcscfDiscoveryMethod.PRECONFIGURED,
            config = testConfig,
            network = null,
            dnsServers = listOf("192.168.102.1")
        )

        assertNotNull(selected)
        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.PRECONFIGURED, selected.source)
        assertEquals(PcscfDiscoverySource.PRECONFIGURED, selected.endpoint.source)
        assertFalse("Preconfigured must not be marked as fallback", selected.isFallback)
        assertEquals(PcscfDiscoveryMethod.PRECONFIGURED, selected.discoveryMethod)
        assertEquals("172.22.0.21", selected.configuredHost)
        assertEquals("172.22.0.21", selected.resolvedAddress)
        assertEquals("172.22.0.21:5060", selected.endpoint.toHostPort())
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.Preconfigured)
        assertEquals("PRECONFIGURED", provider.discoveryState.value.toString())
        assertSameEndpointAndSource(selected)
    }

    @Test
    fun testNetworkDnsDiscoveryResultWinsOverPreconfiguredFallback() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // Inject mock DNS resolver simulating cellular Network DNS resolution for the FQDN
        provider.dnsResolver = { _, fqdn ->
            if (fqdn == "pcscf.ims.mnc070.mcc901.3gppnetwork.org") {
                arrayOf(InetAddress.getByName("172.22.0.21"))
            } else {
                emptyArray()
            }
        }

        val dnsConfig = testConfig.copy(method = PcscfDiscoveryMethod.NETWORK_DNS)
        val selected = provider.discover(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            config = dnsConfig,
            network = null,
            dnsServers = listOf("192.168.102.1")
        )

        // Discovered IP (172.22.0.21) wins over fallback
        assertNotNull(selected)
        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.DNS_RESOLVED, selected.source)
        assertEquals(PcscfDiscoverySource.DNS_RESOLVED, selected.endpoint.source)
        assertFalse("Must not be marked fallback when discovered via DNS", selected.isFallback)
        assertEquals(PcscfDiscoveryMethod.NETWORK_DNS, selected.discoveryMethod)
        assertEquals("pcscf.ims.mnc070.mcc901.3gppnetwork.org", selected.configuredHost)
        assertEquals("172.22.0.21", selected.resolvedAddress)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.DnsResolved)
        assertEquals("DNS_RESOLVED", provider.discoveryState.value.toString())
        assertSameEndpointAndSource(selected)
    }

    @Test
    fun testFallbackSelectedOnlyAfterNetworkDnsFailure() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        // DNS returns no addresses (fails)
        provider.dnsResolver = { _, _ -> emptyArray() }

        val dnsConfig = testConfig.copy(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            dnsFqdn = "pcscf.nonexistent.domain"
        )
        val selected = provider.discover(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            config = dnsConfig,
            network = null,
            dnsServers = listOf("192.168.102.1")
        )

        assertNotNull(selected)
        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.FALLBACK, selected.source)
        assertEquals(PcscfDiscoverySource.FALLBACK, selected.endpoint.source)
        assertTrue("Must be marked fallback after discovery failure", selected.isFallback)
        assertNotNull("Failure reason must be documented", selected.failureReason)
        assertTrue(selected.failureReason!!.contains("returned no addresses") || selected.failureReason!!.contains("No active MCPTT network"))
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.DnsFailed)
        assertEquals("DNS_FAILED", provider.discoveryState.value.toString())
        assertSameEndpointAndSource(selected)
    }

    @Test
    fun testBlankFqdnDoesNotClaimDnsDiscovery() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()

        val testFqdns = listOf("", "   ")
        for (blankFqdn in testFqdns) {
            val blankConfig = testConfig.copy(
                method = PcscfDiscoveryMethod.NETWORK_DNS,
                dnsFqdn = blankFqdn
            )
            val selected = provider.discover(
                method = PcscfDiscoveryMethod.NETWORK_DNS,
                config = blankConfig,
                network = null
            )

            assertNotNull(selected)
            assertNotEquals("Blank FQDN must not claim DNS discovery", PcscfDiscoverySource.DNS_RESOLVED, selected.source)
            assertEquals("172.22.0.21", selected.host)
            assertEquals(5060, selected.port)
            assertEquals(PcscfDiscoverySource.FALLBACK, selected.source)
            assertTrue(selected.isFallback)
            assertEquals("no P-CSCF FQDN provisioned", selected.failureReason)
            assertTrue(provider.discoveryState.value is PcscfDiscoveryState.DnsFailed)
            assertSameEndpointAndSource(selected)
        }
    }

    @Test
    fun testManualOverride() {
        val provider = DefaultPcscfDiscoveryProvider()
        val customEndpoint = PcscfEndpoint(
            host = "10.0.0.1",
            port = 5060,
            resolvedIp = "10.0.0.1",
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

    private fun assertSameEndpointAndSource(selected: SelectedPcscf) {
        assertEquals("Selected source must match selected endpoint source", selected.source, selected.endpoint.source)
        assertEquals("Selected host must match endpoint host", selected.host, selected.endpoint.effectiveHost)
        assertEquals("Selected port must match endpoint port", selected.port, selected.endpoint.port)
    }

    @Test
    fun testDiscoverySourcesHonestyClassification() {
        // Verify standards classification
        assertTrue(PcscfDiscoverySource.PRECONFIGURED.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.PRECONFIGURED.isImplemented)

        assertTrue(PcscfDiscoverySource.DNS_RESOLVED.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.DNS_RESOLVED.isImplemented)

        assertTrue(PcscfDiscoverySource.FALLBACK.isAvailableToStandardApp)
        assertTrue(PcscfDiscoverySource.FALLBACK.isImplemented)

        // Carrier/platform restricted
        assertFalse(PcscfDiscoverySource.DNS_SRV.isImplemented)
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
            fallbackHost = "172.22.0.21",
            fallbackPort = 5060
        )
        assertEquals("172.22.0.21", initialHop.host)
        assertEquals(5060, initialHop.port)

        // 2. In-dialog with Route set: routes to first Route
        val inDialogWithRoute = SipNextHopResolver.resolveInDialogDestination(
            routeSet = listOf("sip:pcscf-edge.ims.net:5060;lr", "sip:scscf.ims.net:6060;lr"),
            remoteTargetUri = "sip:user@10.0.1.5:5060",
            selectedPcscf = null,
            fallbackHost = "172.22.0.21",
            fallbackPort = 5060
        )
        assertEquals("pcscf-edge.ims.net", inDialogWithRoute.host)
        assertEquals(5060, inDialogWithRoute.port)

        // 3. In-dialog with empty Route set: routes to Contact remote target
        val inDialogNoRoute = SipNextHopResolver.resolveInDialogDestination(
            routeSet = emptyList(),
            remoteTargetUri = "sip:contact-peer@192.168.102.7:5062",
            selectedPcscf = null,
            fallbackHost = "172.22.0.21",
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
            defaultPcscfHost = "172.22.0.21",
            defaultPcscfPort = 5060
        )
        assertEquals("192.168.102.20", responseHop.host)
        assertEquals(5088, responseHop.port)
    }

    @Test
    fun testAcceptanceCriterionNetworkDnsDiscoversPcscfAndRoutesRegister() = runBlocking {
        // Acceptance Test:
        // With P-CSCF configuration = NETWORK_DNS, FQDN = pcscf.ims.mnc070.mcc901.3gppnetwork.org
        // The app must discover the current P-CSCF IP through the MCPTT Network and then send REGISTER to the discovered IP:5060.
        val provider = DefaultPcscfDiscoveryProvider()

        provider.dnsResolver = { _, fqdn ->
            if (fqdn == "pcscf.ims.mnc070.mcc901.3gppnetwork.org") {
                arrayOf(InetAddress.getByName("172.22.0.21"))
            } else {
                emptyArray()
            }
        }

        val dnsConfig = PcscfConfig(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            preconfiguredHost = "10.99.99.99", // dummy fallback to prove DNS resolution wins
            preconfiguredPort = 5060,
            dnsFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
            dnsPort = 5060
        )

        val selected = provider.discover(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            config = dnsConfig,
            network = null,
            dnsServers = listOf("192.168.102.1")
        )

        assertEquals("172.22.0.21", selected.host)
        assertEquals(5060, selected.port)
        assertEquals(PcscfDiscoverySource.DNS_RESOLVED, selected.source)
        assertFalse(selected.isFallback)

        // Resolve SIP next-hop destination for out-of-dialog REGISTER request
        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selected,
            fallbackHost = dnsConfig.preconfiguredHost,
            fallbackPort = dnsConfig.preconfiguredPort
        )

        assertEquals("172.22.0.21", dest.host)
        assertEquals(5060, dest.port)
        assertEquals("172.22.0.21:5060", dest.toHostPort())
    }

    @Test
    fun testSingleFlightConcurrentDiscovery() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()
        val resolverCalls = AtomicInteger(0)

        provider.dnsResolver = { _, fqdn ->
            resolverCalls.incrementAndGet()
            delay(50) // simulate resolution time
            arrayOf(InetAddress.getByName("172.22.0.21"))
        }

        val dnsConfig = PcscfConfig(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            preconfiguredHost = "",
            dnsFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org"
        )

        // Launch 5 concurrent discovery requests
        val jobs = (1..5).map {
            async {
                provider.discover(
                    method = PcscfDiscoveryMethod.NETWORK_DNS,
                    config = dnsConfig,
                    network = null
                )
            }
        }

        val results = jobs.awaitAll()
        assertEquals(5, results.size)
        // All jobs return identical discovered endpoint
        results.forEach { res ->
            assertEquals("172.22.0.21", res.host)
            assertEquals(PcscfDiscoverySource.DNS_RESOLVED, res.source)
        }
        // Single-flight: exactly 1 actual DNS resolver execution occurred
        assertEquals(1, resolverCalls.get())
    }

    @Test
    fun testIdempotentDiscovery() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()
        val resolverCalls = AtomicInteger(0)

        provider.dnsResolver = { _, _ ->
            resolverCalls.incrementAndGet()
            arrayOf(InetAddress.getByName("172.22.0.21"))
        }

        val dnsConfig = PcscfConfig(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            preconfiguredHost = "",
            dnsFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org"
        )

        val res1 = provider.discover(PcscfDiscoveryMethod.NETWORK_DNS, dnsConfig, null)
        val res2 = provider.discover(PcscfDiscoveryMethod.NETWORK_DNS, dnsConfig, null)

        assertEquals("172.22.0.21", res1.host)
        assertEquals("172.22.0.21", res2.host)
        assertEquals(1, resolverCalls.get())
    }

    @Test
    fun testDnsFailureWithoutFallbackHostReportsErrorAndNoSelectedEndpoint() = runBlocking {
        val provider = DefaultPcscfDiscoveryProvider()
        provider.dnsResolver = { _, _ -> emptyArray() } // DNS fails

        val dnsConfig = PcscfConfig(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            preconfiguredHost = "", // No preconfigured fallback
            dnsFqdn = "pcscf.nonexistent.fqdn"
        )

        val res = provider.discover(PcscfDiscoveryMethod.NETWORK_DNS, dnsConfig, null)
        assertEquals(PcscfDiscoverySource.ERROR, res.source)
        assertFalse(res.isFallback)
        assertNotNull(res.failureReason)
        assertNull("Selected P-CSCF must be null when discovery fails without fallback", provider.selectedPcscf.value)
        assertTrue(provider.discoveryState.value is PcscfDiscoveryState.DnsFailed)
    }

    @Test
    fun testRegisterRejectsWhileDiscovering() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stack = McpttSipStack()
        val sentPackets = mutableListOf<String>()
        stack.onPacketSent = { rawSip, _, _ -> sentPackets.add(rawSip) }

        val profile = SipProfile(
            pcscfConfig = PcscfConfig(
                method = PcscfDiscoveryMethod.NETWORK_DNS,
                dnsFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
                preconfiguredHost = ""
            ),
            autoRegister = false
        )

        stack.start(context, profile)
        assertTrue("Stack discoveryState must be Discovering", stack.pcscfDiscoveryState.value is PcscfDiscoveryState.Discovering)

        // Attempting to send REGISTER while DISCOVERING must be rejected immediately
        stack.register()
        assertEquals("No SIP packet must be sent while discovery is DISCOVERING", 0, sentPackets.size)
        assertNotEquals(RegistrationState.REGISTERING, stack.registrationState.value)

        stack.stop()
    }

    @Test
    fun testRegisterRejectsWhenDiscoveryFailedWithNoFallback() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stack = McpttSipStack()
        val sentPackets = mutableListOf<String>()
        stack.onPacketSent = { rawSip, _, _ -> sentPackets.add(rawSip) }

        val profile = SipProfile(
            pcscfConfig = PcscfConfig(
                method = PcscfDiscoveryMethod.NETWORK_DNS,
                dnsFqdn = "pcscf.failed.fqdn",
                preconfiguredHost = "" // No fallback
            ),
            autoRegister = false
        )

        stack.start(context, profile)

        // Mock DNS failure on stack's discovery provider
        (stack.pcscfDiscoveryProvider as DefaultPcscfDiscoveryProvider).dnsResolver = { _, _ -> emptyArray() }
        stack.pcscfDiscoveryProvider.discover(
            method = PcscfDiscoveryMethod.NETWORK_DNS,
            config = profile.effectivePcscfConfig,
            network = null
        )

        assertNull(stack.selectedPcscf.value)

        // Attempting to register when no valid P-CSCF endpoint was discovered must fail
        stack.register()
        assertEquals(0, sentPackets.size)
        assertEquals(RegistrationState.REGISTRATION_FAILED, stack.registrationState.value)
        assertNotNull(stack.registrationFailureReason.value)

        stack.stop()
    }
}
