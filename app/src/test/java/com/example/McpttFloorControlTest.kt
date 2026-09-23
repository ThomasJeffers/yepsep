package com.example

import com.example.sip.engine.FloorState
import com.example.sip.engine.RtpAudioEngine
import com.example.sip.model.SipHeader
import com.example.sip.model.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpttFloorControlTest {

    @Test
    fun testSipMessageFloorParsing() {
        val grantedInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = listOf(
                SipHeader("Content-Type", "application/resource-lists+xml")
            ),
            body = "Action=floor-granted\r\nspeaker=sip:208950000000001@ims.mnc070.mcc901.3gppnetwork.org\r\n"
        )
        assertEquals(FloorState.GRANTED, grantedInfo.floorControlState)

        val takenInfo = SipMessage(
            startLine = "INFO sip:ue2@192.168.102.3 SIP/2.0",
            headers = listOf(
                SipHeader("Content-Type", "application/resource-lists+xml")
            ),
            body = "Action=floor-taken\r\nspeaker=sip:208950000000002@ims.mnc070.mcc901.3gppnetwork.org\r\n"
        )
        assertEquals(FloorState.TAKEN, takenInfo.floorControlState)

        val idleInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = listOf(
                SipHeader("Content-Type", "application/resource-lists+xml")
            ),
            body = "Action=floor-idle\r\n"
        )
        assertEquals(FloorState.IDLE, idleInfo.floorControlState)

        val denyInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = listOf(
                SipHeader("Content-Type", "application/resource-lists+xml")
            ),
            body = "Action=floor-deny\r\nreason=floor-taken\r\n"
        )
        assertEquals(FloorState.DENIED, denyInfo.floorControlState)
    }

    @Test
    fun testAudioEngineFloorGating() {
        val engine = RtpAudioEngine()
        var floorGranted = false
        engine.isFloorGranted = { floorGranted }

        assertFalse("Engine should report floor not granted initially", engine.isFloorGranted())

        floorGranted = true
        assertTrue("Engine should report floor granted when true", engine.isFloorGranted())

        floorGranted = false
        assertFalse("Engine should report floor not granted when revoked", engine.isFloorGranted())
    }

    @Test
    fun testPcmuCodecRoundtrip() {
        val testSamples = shortArrayOf(0, 100, -100, 1000, -1000, 8000, -8000, 32000, -32000)
        for (sample in testSamples) {
            val ulaw = RtpAudioEngine.linearToUlaw(sample)
            val decoded = RtpAudioEngine.ulawToLinear(ulaw.toInt() and 0xFF)
            // U-law is lossy 8-bit companding, verify round-trip error is within ~10% dynamic range
            val diff = Math.abs(sample - decoded)
            val maxAllowedDiff = (Math.abs(sample.toInt()) * 0.15 + 100).toInt()
            assertTrue("Diff $diff for sample $sample should be within acceptable bound $maxAllowedDiff", diff <= maxAllowedDiff)
        }
    }
}
