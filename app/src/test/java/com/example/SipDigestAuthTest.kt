package com.example

import com.example.sip.engine.RegistrationState
import com.example.sip.engine.SipAuthHelper
import com.example.sip.model.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

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
}
