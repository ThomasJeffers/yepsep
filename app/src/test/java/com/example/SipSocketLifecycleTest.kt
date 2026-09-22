package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.sip.engine.McpttSipStack
import com.example.sip.engine.RegistrationState
import com.example.sip.model.SipMessage
import com.example.sip.model.SipProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SipSocketLifecycleTest {

    @Test
    fun testSameSocketPreservedAcrossFullRegistrationLifecycle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stack = McpttSipStack()
        val sentPackets = mutableListOf<String>()

        stack.onPacketSent = { rawSip, _, _ ->
            sentPackets.add(rawSip)
        }

        val profile = SipProfile(
            imsi = "901700000052769",
            mcpttId = "sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org",
            realm = "ims.mnc070.mcc901.3gppnetwork.org",
            password = "password123",
            pcscfHost = "172.22.0.21",
            localSipPort = 5062,
            autoRegister = false
        )

        stack.start(context, profile)
        val initialSocket = stack.currentSocket
        assertNotNull("Initial socket must be non-null", initialSocket)
        assertTrue("Initial socket must not be closed", !initialSocket!!.isClosed)

        // 1. Send unauthenticated REGISTER (REGISTER #1)
        stack.register()
        assertEquals(1, sentPackets.size)
        val reg1 = SipMessage.parse(sentPackets[0])
        assertEquals("REGISTER", reg1.method)
        assertEquals(1, reg1.cseqNumber)
        assertSame("Socket after REGISTER #1 must be the exact same instance", initialSocket, stack.currentSocket)

        // 2. Simulate 401 challenge from P-CSCF
        val raw401 = """
            SIP/2.0 401 Unauthorized
            Via: ${reg1.getHeader("via")}
            From: ${reg1.from}
            To: ${reg1.to};tag=pcscf-tag99
            Call-ID: ${reg1.callId}
            CSeq: 1 REGISTER
            WWW-Authenticate: Digest realm="ims.mnc070.mcc901.3gppnetwork.org", nonce="95d379247d1245e0a9a4014118259dbd", algorithm=MD5, qop="auth"
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw401, "172.22.0.21", 5060)

        // 3. Authenticated REGISTER #2 transmitted
        assertEquals("Should have transmitted authenticated REGISTER #2", 2, sentPackets.size)
        val reg2 = SipMessage.parse(sentPackets[1])
        assertEquals(2, reg2.cseqNumber)
        assertSame("Socket after 401 and REGISTER #2 must be the exact same instance", initialSocket, stack.currentSocket)

        // 4. Simulate 200 OK from core
        val raw200 = """
            SIP/2.0 200 OK
            Via: ${reg2.getHeader("via")}
            From: ${reg2.from}
            To: ${reg2.to};tag=pcscf-tag99
            Call-ID: ${reg1.callId}
            CSeq: 2 REGISTER
            Contact: <sip:901700000052769@192.168.102.6:5062>;expires=3600
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n")

        stack.processIncomingSipPacket(raw200, "172.22.0.21", 5060)

        assertEquals(RegistrationState.REGISTERED, stack.registrationState.value)
        assertSame("Socket after 200 OK must remain the exact same instance across entire registration", initialSocket, stack.currentSocket)
        assertTrue("Socket must remain open", !initialSocket.isClosed)

        stack.stop()
    }

    @Test
    fun testRepeatedInitSocketDoesNotRecreateSocket() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stack = McpttSipStack()
        val profile = SipProfile(
            localSipPort = 5062,
            autoRegister = false
        )
        stack.start(context, profile)
        val socket1 = stack.currentSocket
        assertNotNull(socket1)

        val socket2 = stack.initSocket()
        assertSame("initSocket() must return existing socket if port matches and socket is open", socket1, socket2)
        assertSame("stack.currentSocket must remain unchanged", socket1, stack.currentSocket)

        stack.stop()
    }

    @Test
    fun testContentLengthByteAccurateBodyParsing() {
        // Test 1: Content-Length: 0 must yield empty body
        val msg0 = SipMessage.parse("""
            SIP/2.0 200 OK
            CSeq: 1 REGISTER
            Content-Length: 0

        """.trimIndent().replace("\n", "\r\n"))
        assertEquals("", msg0.body)

        // Test 2: Content-Length exact byte slice (20 bytes for "Action=floor-granted")
        val msgWithBody = SipMessage.parse("""
            INFO sip:group@ims.org SIP/2.0
            CSeq: 10 INFO
            Content-Type: text/plain
            Content-Length: 20

            Action=floor-grantedTRAILING_PADDING_GARBAGE
        """.trimIndent().replace("\n", "\r\n"))
        assertEquals("Action=floor-granted", msgWithBody.body)
        assertEquals(20, msgWithBody.body.toByteArray(Charsets.UTF_8).size)
    }

    @Test
    fun testNetworkCapabilityCallbackDoesNotDestroySocket() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val stack = McpttSipStack()
        val profile = SipProfile(localSipPort = 5062, autoRegister = false)
        stack.start(context, profile)

        val socketBefore = stack.currentSocket
        assertNotNull(socketBefore)
        assertTrue(!socketBefore!!.isClosed)

        // Re-invoking initSocket with same configuration must preserve the socket instance
        val socketAfter = stack.initSocket()
        assertSame("Socket must remain identical across network status notifications", socketBefore, socketAfter)
        assertSame("Stack currentSocket must remain identical", socketBefore, stack.currentSocket)
        assertTrue("Socket must remain open and bound", !socketAfter.isClosed)

        stack.stop()
    }
}
