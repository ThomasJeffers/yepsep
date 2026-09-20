package com.example

import com.example.sip.engine.RtpAudioEngine
import com.example.sip.model.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SipMessageTest {

    @Test
    fun testParseMcpttInvite() {
        val rawSip = """
            INVITE sip:mcptt_group_fire@ims.mnc001.mcc001.3gppnetwork.org SIP/2.0
            Via: SIP/2.0/UDP 192.168.1.50:5062;rport;branch=z9hG4bK-12345
            From: <sip:mcptt_user1@ims.mnc001.mcc001.3gppnetwork.org>;tag=abc
            To: <sip:mcptt_group_fire@ims.mnc001.mcc001.3gppnetwork.org>
            Call-ID: call-998877@192.168.1.50
            CSeq: 1 INVITE
            Contact: <sip:mcptt_user1@192.168.1.50:5062>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            Content-Type: application/sdp
            Content-Length: 0
            
        """.trimIndent().replace("\n", "\r\n")

        val message = SipMessage.parse(rawSip)

        assertEquals("INVITE", message.method)
        assertEquals("call-998877@192.168.1.50", message.callId)
        assertTrue(message.isMcpttTagged)
        assertEquals("<sip:mcptt_user1@192.168.1.50:5062>;+g.3gpp.mcptt", message.contact)
    }

    @Test
    fun testFloorControlParsing() {
        val rawFloorGrant = """
            INFO sip:mcptt_group_fire@ims.mnc001.mcc001.3gppnetwork.org SIP/2.0
            Via: SIP/2.0/UDP 192.168.1.100:5060;branch=z9hG4bK-777
            From: <sip:mcptt_as@ims.mnc001.mcc001.3gppnetwork.org>;tag=as1
            To: <sip:mcptt_user1@ims.mnc001.mcc001.3gppnetwork.org>
            Call-ID: call-998877@192.168.1.50
            CSeq: 2 INFO
            Content-Type: text/plain
            Content-Length: 22
            
            Action=floor-granted
        """.trimIndent().replace("\n", "\r\n")

        val message = SipMessage.parse(rawFloorGrant)

        assertEquals("INFO", message.method)
        assertEquals("GRANTED", message.floorControlState)
    }

    @Test
    fun testRecordRouteReversalAndSdpExtraction() {
        val raw200Ok = """
            SIP/2.0 200 OK
            Via: SIP/2.0/UDP 192.168.102.2:5062;branch=z9hG4bK-abc123
            Record-Route: <sip:172.22.0.21:5060;lr>
            Record-Route: <sip:scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr>
            From: <sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org>;tag=tag1
            To: <sip:group1@ims.mnc070.mcc901.3gppnetwork.org>;tag=tag2
            Call-ID: call-12345
            CSeq: 100 INVITE
            Contact: <sip:172.30.104.240:5070;transport=udp>
            Content-Type: application/sdp
            Content-Length: 150
            
            v=0
            o=mcptt-as 123 123 IN IP4 172.30.104.240
            s=MCPTT
            c=IN IP4 172.30.104.240
            m=audio 10002 RTP/AVP 0
            a=rtpmap:0 PCMU/8000
        """.trimIndent().replace("\n", "\r\n")

        val message = SipMessage.parse(raw200Ok)

        assertEquals(200, message.statusCode)
        val routeSet = message.extractUacRouteSet()
        assertEquals(2, routeSet.size)
        // Reverse order: S-CSCF, then P-CSCF
        assertEquals("sip:scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr", routeSet[0])
        assertEquals("sip:172.22.0.21:5060;lr", routeSet[1])

        val contact = message.extractContactUri()
        assertEquals("sip:172.30.104.240:5070;transport=udp", contact)

        val (sdpIp, sdpPort) = message.extractSdpMedia()
        assertEquals("172.30.104.240", sdpIp)
        assertEquals(10002, sdpPort)
    }

    @Test
    fun testG711UlawCodecRoundTrip() {
        val testSamples = shortArrayOf(0, 1000, -1000, 16000, -16000, 32000, -32000)
        for (sample in testSamples) {
            val ulaw = RtpAudioEngine.linear16ToUlaw(sample)
            val decoded = RtpAudioEngine.ulawToLinear16(ulaw)
            // mu-law is lossy 8-bit companding; reconstructed sample should be close within quantization tolerance
            val diff = Math.abs(sample - decoded)
            assertTrue("Expected diff < 2000 for $sample, got $diff (decoded: $decoded)", diff < 2000)
        }
    }
}
