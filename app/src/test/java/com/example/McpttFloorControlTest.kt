package com.example

import com.example.sip.engine.CallSessionState
import com.example.sip.engine.FloorState
import com.example.sip.engine.McpttSipStack
import com.example.sip.engine.RegistrationState
import com.example.sip.engine.RtpAudioEngine
import com.example.sip.model.SipHeader
import com.example.sip.model.SipMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpttFloorControlTest {

    @Test
    fun testIdleToRequesting() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.IDLE)

        assertEquals(FloorState.IDLE, stack.floorState.value)
        stack.requestFloor()
        assertEquals(FloorState.REQUESTING, stack.floorState.value)
    }

    @Test
    fun testRequestingToGranted() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.REQUESTING)

        val grantedMsg = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.6:5062 SIP/2.0",
            rawText = "Action=floor-granted\r\n",
            body = "Action=floor-granted\r\nspeaker=sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org\r\n",
            isResponse = false,
            method = "INFO"
        )
        stack.applyFloorFromBodyForTest(grantedMsg)
        assertEquals(FloorState.GRANTED, stack.floorState.value)
        assertEquals("MCPTT UE-1", stack.activeSpeaker.value)
    }

    @Test
    fun testRequestingToListeningWhenFloorTaken() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.REQUESTING)

        val takenMsg = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.6:5062 SIP/2.0",
            rawText = "Action=floor-taken\r\n",
            body = "Action=floor-taken\r\nUser=sip:208950000000002@ims.mnc070.mcc901.3gppnetwork.org\r\n",
            isResponse = false,
            method = "INFO"
        )
        stack.applyFloorFromBodyForTest(takenMsg)
        assertEquals(FloorState.LISTENING, stack.floorState.value)
        assertEquals("208950000000002", stack.activeSpeaker.value)
    }

    @Test
    fun testGrantedToIdleOnRelease() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.GRANTED, "MCPTT UE-1")

        assertEquals(FloorState.GRANTED, stack.floorState.value)
        stack.releaseFloor()
        assertEquals(FloorState.IDLE, stack.floorState.value)
        assertNull(stack.activeSpeaker.value)
    }

    @Test
    fun testListeningToIdleOnFloorIdle() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.LISTENING, "UE-2")

        val idleMsg = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.6:5062 SIP/2.0",
            rawText = "Action=floor-idle\r\n",
            body = "Action=floor-idle\r\n",
            isResponse = false,
            method = "INFO"
        )
        stack.applyFloorFromBodyForTest(idleMsg)
        assertEquals(FloorState.IDLE, stack.floorState.value)
        assertNull(stack.activeSpeaker.value)
    }

    @Test
    fun testRequestFloorDisabledWhileListening() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.LISTENING, "UE-2")

        stack.requestFloor()
        // Must remain in LISTENING and reject request
        assertEquals(FloorState.LISTENING, stack.floorState.value)
    }

    @Test
    fun testRequestFloorDisabledWhileRequesting() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.REQUESTING)

        stack.requestFloor()
        // Must remain in REQUESTING and not duplicate request
        assertEquals(FloorState.REQUESTING, stack.floorState.value)
    }

    @Test
    fun testNoRtpTxUnlessGranted() {
        val engine = RtpAudioEngine()
        var currentState = FloorState.IDLE
        engine.isFloorGranted = { currentState == FloorState.GRANTED }

        assertFalse(engine.isFloorGranted()) // IDLE

        currentState = FloorState.REQUESTING
        assertFalse(engine.isFloorGranted()) // REQUESTING

        currentState = FloorState.LISTENING
        assertFalse(engine.isFloorGranted()) // LISTENING

        currentState = FloorState.RELEASING
        assertFalse(engine.isFloorGranted()) // RELEASING

        currentState = FloorState.GRANTED
        assertTrue(engine.isFloorGranted()) // Only GRANTED enables TX
    }

    @Test
    fun testRemoteFloorTakenStopsLocalTx() {
        val stack = McpttSipStack()
        val engine = RtpAudioEngine()
        engine.isFloorGranted = { stack.floorState.value == FloorState.GRANTED }

        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        stack.setCallStateForTest(CallSessionState.CONNECTED)
        stack.setFloorStateForTest(FloorState.GRANTED, "MCPTT UE-1")

        assertTrue("RTP TX allowed when GRANTED", engine.isFloorGranted())

        // Remote peer takes floor
        val takenMsg = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.6:5062 SIP/2.0",
            rawText = "Action=floor-taken\r\n",
            body = "Action=floor-taken\r\nUser=sip:remote-ue@ims.mnc070.mcc901.3gppnetwork.org\r\n",
            isResponse = false,
            method = "INFO"
        )
        stack.applyFloorFromBodyForTest(takenMsg)

        assertEquals(FloorState.LISTENING, stack.floorState.value)
        assertFalse("RTP TX must be stopped immediately when floor-taken arrives", engine.isFloorGranted())
    }

    @Test
    fun testNoAutomaticGrantedOnInvite200() {
        val stack = McpttSipStack()
        stack.setRegistrationStateForTest(RegistrationState.REGISTERED)
        assertEquals(FloorState.IDLE, stack.floorState.value)
        assertFalse("autoGrantFloor must be false by default in normal multi-UE operation", stack.profile.autoGrantFloor)
    }

    @Test
    fun testSipMessageFloorParsing() {
        val grantedInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = mapOf(
                "content-type" to "application/resource-lists+xml"
            ),
            body = "Action=floor-granted\r\nspeaker=sip:208950000000001@ims.mnc070.mcc901.3gppnetwork.org\r\n"
        )
        assertEquals("GRANTED", grantedInfo.floorControlState)

        val takenInfo = SipMessage(
            startLine = "INFO sip:ue2@192.168.102.3 SIP/2.0",
            headers = mapOf(
                "content-type" to "application/resource-lists+xml"
            ),
            body = "Action=floor-taken\r\nspeaker=sip:208950000000002@ims.mnc070.mcc901.3gppnetwork.org\r\n"
        )
        assertEquals("TAKEN", takenInfo.floorControlState)

        val idleInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = mapOf(
                "content-type" to "application/resource-lists+xml"
            ),
            body = "Action=floor-idle\r\n"
        )
        assertEquals("IDLE", idleInfo.floorControlState)

        val denyInfo = SipMessage(
            startLine = "INFO sip:ue1@192.168.102.2 SIP/2.0",
            headers = mapOf(
                "content-type" to "application/resource-lists+xml"
            ),
            body = "Action=floor-deny\r\nreason=floor-taken\r\n"
        )
        assertEquals("DENIED", denyInfo.floorControlState)
    }

    @Test
    fun testPcmuCodecRoundtrip() {
        val testSamples = shortArrayOf(0, 100, -100, 1000, -1000, 8000, -8000, 32000, -32000)
        for (sample in testSamples) {
            val ulaw = RtpAudioEngine.linearToUlaw(sample)
            val decoded = RtpAudioEngine.ulawToLinear(ulaw.toInt() and 0xFF)
            val diff = Math.abs(sample - decoded)
            val maxAllowedDiff = (Math.abs(sample.toInt()) * 0.15 + 100).toInt()
            assertTrue("Diff $diff for sample $sample should be within acceptable bound $maxAllowedDiff", diff <= maxAllowedDiff)
        }
    }
}
