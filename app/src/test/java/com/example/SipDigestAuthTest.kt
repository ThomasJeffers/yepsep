package com.example

import com.example.sip.engine.RegistrationState
import com.example.sip.engine.SipAuthHelper
import com.example.sip.model.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SipDigestAuthTest {

    @Test
    fun testParseWwwAuthenticateFoldedHeaders() {
        // Test challenge format returned by Kamailio / IMS core with line folding
        val raw401 = """
            SIP/2.0 401 Unauthorized
            Via: SIP/2.0/UDP 192.168.102.4:5062;branch=z9hG4bK-abc12345;rport=5062
            From: <sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org>;tag=reg-tag1
            To: <sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org>;tag=kam-tag2
            Call-ID: reg-testcall123@ims.mnc070.mcc901.3gppnetwork.org
            CSeq: 1 REGISTER
            WWW-Authenticate:
             Digest realm="ims.mnc070.mcc901.3gppnetwork.org",
             nonce="67890abcdef1234567890abcdef12345",
             algorithm=MD5,
             qop="auth"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        val msg = SipMessage.parse(raw401)
        assertEquals(401, msg.statusCode)
        assertEquals("REGISTER", msg.method)
        assertEquals("reg-testcall123@ims.mnc070.mcc901.3gppnetwork.org", msg.callId)

        val authHeader = msg.getHeader("www-authenticate")
        assertTrue("WWW-Authenticate should not be empty", authHeader.isNotEmpty())

        val realm = SipAuthHelper.extractAuthParam(authHeader, "realm")
        val nonce = SipAuthHelper.extractAuthParam(authHeader, "nonce")
        val qop = SipAuthHelper.selectQop(SipAuthHelper.extractAuthParam(authHeader, "qop"))
        val algorithm = SipAuthHelper.extractAuthParam(authHeader, "algorithm")

        assertEquals("ims.mnc070.mcc901.3gppnetwork.org", realm)
        assertEquals("67890abcdef1234567890abcdef12345", nonce)
        assertEquals("auth", qop)
        assertEquals("MD5", algorithm)
    }

    @Test
    fun testParseWwwAuthenticateSingleLineAndQuotes() {
        val raw401 = """
            SIP/2.0 401 Unauthorized
            Via: SIP/2.0/UDP 192.168.102.4:5062;branch=z9hG4bK-abc12345
            From: <sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org>;tag=tag1
            To: <sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org>;tag=tag2
            Call-ID: reg-test1
            CSeq: 1 REGISTER
            WWW-Authenticate: Digest realm="ims.mnc070.mcc901.3gppnetwork.org", nonce="MzA1NzY0MTI4OTI0", algorithm=MD5, qop="auth,auth-int", opaque="op123"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        val msg = SipMessage.parse(raw401)
        val authHeader = msg.getHeader("www-authenticate")

        val realm = SipAuthHelper.extractAuthParam(authHeader, "realm")
        val nonce = SipAuthHelper.extractAuthParam(authHeader, "nonce")
        val qop = SipAuthHelper.selectQop(SipAuthHelper.extractAuthParam(authHeader, "qop"))
        val opaque = SipAuthHelper.extractAuthParam(authHeader, "opaque")

        assertEquals("ims.mnc070.mcc901.3gppnetwork.org", realm)
        assertEquals("MzA1NzY0MTI4OTI0", nonce)
        assertEquals("auth", qop)
        assertEquals("op123", opaque)
    }

    @Test
    fun testMd5DigestCalculationMatchingPythonUac() {
        // Verify MD5 calculations match exp5_uac.py and RFC 2617
        val username = "491234567890123@ims.mnc070.mcc901.3gppnetwork.org"
        val realm = "ims.mnc070.mcc901.3gppnetwork.org"
        val password = "secret_k_password"
        val method = "REGISTER"
        val uri = "sip:ims.mnc070.mcc901.3gppnetwork.org"
        val nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093"
        val qop = "auth"
        val nc = "00000001"
        val cnonce = "0a4f113b"

        // Step-by-step RFC 2617 calculation
        fun md5(s: String): String {
            val md = MessageDigest.getInstance("MD5")
            val b = md.digest(s.toByteArray(Charsets.UTF_8))
            return b.joinToString("") { "%02x".format(it) }
        }

        val expectedHa1 = md5("$username:$realm:$password")
        val expectedHa2 = md5("$method:$uri")
        val expectedResponse = md5("$expectedHa1:$nonce:$nc:$cnonce:$qop:$expectedHa2")

        val (actualResponse, _) = SipAuthHelper.calculateDigestResponse(
            username = username,
            realm = realm,
            password = password,
            method = method,
            uri = uri,
            nonce = nonce,
            qop = qop,
            nc = nc,
            cnonce = cnonce
        )

        assertEquals(expectedResponse, actualResponse)
    }

    @Test
    fun testBuildAuthorizationHeaderFormatting() {
        val username = "491234567890123@ims.mnc070.mcc901.3gppnetwork.org"
        val realm = "ims.mnc070.mcc901.3gppnetwork.org"
        val password = "testpassword"
        val method = "REGISTER"
        val uri = "sip:ims.mnc070.mcc901.3gppnetwork.org"
        val nonce = "nonce12345"
        val qop = "auth"
        val cnonce = "c1234567"

        val authHeader = SipAuthHelper.buildAuthorizationHeader(
            username = username,
            realm = realm,
            password = password,
            method = method,
            uri = uri,
            nonce = nonce,
            rawQop = qop,
            opaque = "opq999",
            nc = "00000001",
            forcedCnonce = cnonce
        )

        assertTrue("Must start with Digest", authHeader.startsWith("Digest "))
        assertTrue("Must contain username", authHeader.contains("username=\"$username\""))
        assertTrue("Must contain realm", authHeader.contains("realm=\"$realm\""))
        assertTrue("Must contain nonce", authHeader.contains("nonce=\"$nonce\""))
        assertTrue("Must contain uri", authHeader.contains("uri=\"$uri\""))
        assertTrue("Must contain response", authHeader.contains("response="))
        assertTrue("Must contain algorithm=MD5", authHeader.contains("algorithm=MD5"))
        assertTrue("Must contain qop=auth", authHeader.contains("qop=auth"))
        assertTrue("Must contain nc=00000001", authHeader.contains("nc=00000001"))
        assertTrue("Must contain cnonce", authHeader.contains("cnonce=\"$cnonce\""))
        assertTrue("Must contain opaque", authHeader.contains("opaque=\"opq999\""))
    }

    @Test
    fun testRegistrationStateLifecycleTransitions() {
        // Validate enum states required by the UI specification
        assertEquals(RegistrationState.REGISTERED, RegistrationState.valueOf("REGISTERED"))
        assertEquals(RegistrationState.AUTHENTICATING, RegistrationState.valueOf("AUTHENTICATING"))
        assertEquals(RegistrationState.REGISTERING, RegistrationState.valueOf("REGISTERING"))
        assertEquals(RegistrationState.MCPTT_APN_BOUND, RegistrationState.valueOf("MCPTT_APN_BOUND"))
        assertEquals(RegistrationState.NETWORK_UNAVAILABLE, RegistrationState.valueOf("NETWORK_UNAVAILABLE"))
        assertEquals(RegistrationState.REGISTRATION_FAILED, RegistrationState.valueOf("REGISTRATION_FAILED"))
        assertEquals(RegistrationState.UNREGISTERED, RegistrationState.valueOf("UNREGISTERED"))

        assertTrue(RegistrationState.REGISTERED.isRegistered)
        assertFalse(RegistrationState.AUTHENTICATING.isRegistered)
        assertFalse(RegistrationState.REGISTERING.isRegistered)
        assertFalse(RegistrationState.REGISTRATION_FAILED.isRegistered)
    }

    @Test
    fun testEndToEndRegistrationFlowWith401ChallengeAnd200Ok() {
        // Items 5, 6, 7, 8, 9, 10, 12, 13, 15 from Section 18
        val stack = com.example.sip.engine.McpttSipStack()
        val sentPackets = mutableListOf<String>()
        val trafficLogs = mutableListOf<com.example.sip.model.SipTrafficLog>()

        stack.onPacketSent = { rawSip, _, _ ->
            sentPackets.add(rawSip)
        }

        val profile = com.example.sip.model.SipProfile(
            imsi = "901700000052769",
            mcpttId = "sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org",
            realm = "ims.mnc070.mcc901.3gppnetwork.org",
            password = "password123",
            pcscfHost = "172.22.0.21",
            localSipPort = 5062,
            autoRegister = false
        )

        // Inject initial unauthenticated REGISTER (REGISTER #1)
        stack.register()

        assertEquals("Should have sent REGISTER #1", 1, sentPackets.size)
        val reg1 = SipMessage.parse(sentPackets[0])
        assertEquals("REGISTER", reg1.method)
        assertEquals("1 REGISTER", reg1.cseq)
        val callId1 = reg1.callId
        val fromTag1 = reg1.from.substringAfter("tag=").substringBefore(";")
        val via1 = reg1.getHeader("via")
        val branch1 = via1.substringAfter("branch=").substringBefore(";")

        assertTrue("Contact must contain IMSI", reg1.contact.contains("901700000052769"))
        assertTrue("Contact must contain +g.3gpp.mcptt", reg1.contact.contains("+g.3gpp.mcptt"))
        assertFalse("REGISTER #1 must not contain Authorization", reg1.headers.containsKey("authorization"))

        // Simulate 401 Unauthorized response from P-CSCF
        val raw401 = """
            SIP/2.0 401 Unauthorized
            Via: $via1
            From: ${reg1.from}
            To: ${reg1.to};tag=pcscf-tag99
            Call-ID: $callId1
            CSeq: 1 REGISTER
            WWW-Authenticate: Digest realm="ims.mnc070.mcc901.3gppnetwork.org", nonce="95d379247d1245e0a9a4014118259dbd", algorithm=MD5, qop="auth"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw401, "172.22.0.21", 5060)

        // Verify REGISTER #2 was transmitted
        assertEquals("Should have transmitted authenticated REGISTER #2", 2, sentPackets.size)
        val reg2 = SipMessage.parse(sentPackets[1])

        // Item 5: CSeq increment 1 -> 2
        assertEquals("CSeq must increment to 2", "2 REGISTER", reg2.cseq)

        // Item 6: Same Call-ID across retry
        assertEquals("Call-ID must be identical across 401 retry", callId1, reg2.callId)

        // Item 7: Same From tag across retry
        val fromTag2 = reg2.from.substringAfter("tag=").substringBefore(";")
        assertEquals("From tag must be identical across 401 retry", fromTag1, fromTag2)

        // Item 8: New Via branch on retry
        val via2 = reg2.getHeader("via")
        val branch2 = via2.substringAfter("branch=").substringBefore(";")
        assertTrue("Via branch must be non-empty", branch2.isNotEmpty())
        assertTrue("Via branch must be different on retry", branch1 != branch2)

        // Item 4 & 9: Authorization header present in REGISTER #2
        val authHeader = reg2.getHeader("authorization")
        assertTrue("Authorization header must be present in REGISTER #2", authHeader.isNotEmpty())
        assertTrue("Authorization must contain Digest", authHeader.startsWith("Digest "))
        assertTrue("Authorization must contain correct username", authHeader.contains("username=\"901700000052769@ims.mnc070.mcc901.3gppnetwork.org\""))
        assertTrue("Authorization must contain nonce", authHeader.contains("nonce=\"95d379247d1245e0a9a4014118259dbd\""))
        assertTrue("Authorization must contain response", authHeader.contains("response="))
        assertTrue("Authorization must contain qop=auth", authHeader.contains("qop=auth"))

        // Item 10: Authenticated REGISTER -> 200 OK -> REGISTERED
        val raw200 = """
            SIP/2.0 200 OK
            Via: $via2
            From: ${reg2.from}
            To: ${reg2.to};tag=pcscf-tag99
            Call-ID: $callId1
            CSeq: 2 REGISTER
            Contact: <sip:901700000052769@192.168.102.6:5062>;expires=3600
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw200, "172.22.0.21", 5060)

        assertEquals("Stack state must transition to REGISTERED", RegistrationState.REGISTERED, stack.registrationState.value)
        assertTrue(stack.registrationState.value.isRegistered)
    }

    @Test
    fun testSecond401DoesNotCreateInfiniteLoop() {
        // Item 11: second 401 terminates retry loop and reports failure
        val stack = com.example.sip.engine.McpttSipStack()
        val sentPackets = mutableListOf<String>()
        stack.onPacketSent = { rawSip, _, _ ->
            sentPackets.add(rawSip)
        }

        stack.register()
        assertEquals(1, sentPackets.size)
        val reg1 = SipMessage.parse(sentPackets[0])

        // First 401 challenge
        val raw401_1 = """
            SIP/2.0 401 Unauthorized
            Via: ${reg1.getHeader("via")}
            From: ${reg1.from}
            To: ${reg1.to}
            Call-ID: ${reg1.callId}
            CSeq: 1 REGISTER
            WWW-Authenticate: Digest realm="ims.mnc070.mcc901.3gppnetwork.org", nonce="nonce1", algorithm=MD5, qop="auth"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw401_1, "172.22.0.21", 5060)
        assertEquals("Should have sent REGISTER #2", 2, sentPackets.size)

        // Second 401 challenge (credentials rejected by core)
        val reg2 = SipMessage.parse(sentPackets[1])
        val raw401_2 = """
            SIP/2.0 401 Unauthorized
            Via: ${reg2.getHeader("via")}
            From: ${reg2.from}
            To: ${reg2.to}
            Call-ID: ${reg2.callId}
            CSeq: 2 REGISTER
            WWW-Authenticate: Digest realm="ims.mnc070.mcc901.3gppnetwork.org", nonce="nonce2", algorithm=MD5, qop="auth"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw401_2, "172.22.0.21", 5060)

        // Must NOT send a 3rd REGISTER
        assertEquals("Must NOT send a 3rd REGISTER on repeated 401", 2, sentPackets.size)
        assertEquals("State must be REGISTRATION_FAILED", RegistrationState.REGISTRATION_FAILED, stack.registrationState.value)
        assertNotNull("Failure reason must be set", stack.registrationFailureReason.value)
        assertTrue("Failure reason must mention authentication", stack.registrationFailureReason.value!!.contains("Authentication failed"))
    }
}
