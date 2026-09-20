package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.McpttRepository
import com.example.sip.engine.ApnNetworkStatus
import com.example.sip.engine.CallSessionState
import com.example.sip.engine.FloorState
import com.example.sip.engine.McpttSipStack
import com.example.sip.engine.RegistrationState
import com.example.sip.engine.RtpAudioEngine
import com.example.sip.model.LogDirection
import com.example.sip.model.SipProfile
import com.example.sip.model.SipTrafficLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class McpttViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = McpttRepository(application)
    val sipStack = McpttSipStack()
    val audioEngine = RtpAudioEngine()

    val sipProfile: StateFlow<SipProfile> = repository.sipProfile
    val registrationState: StateFlow<RegistrationState> = sipStack.registrationState
    val callState: StateFlow<CallSessionState> = sipStack.callState
    val floorState: StateFlow<FloorState> = sipStack.floorState
    val activeSpeaker: StateFlow<String?> = sipStack.activeSpeaker
    val micAudioLevel: StateFlow<Float> = audioEngine.micAudioLevel

    val apnNetworkStatus: StateFlow<ApnNetworkStatus> =
        sipStack.apnManager?.networkStatus ?: MutableStateFlow(ApnNetworkStatus.Scanning)

    val allLogs: StateFlow<List<SipTrafficLog>> = repository.logs

    private val _logFilter = MutableStateFlow("ALL")
    val logFilter: StateFlow<String> = _logFilter.asStateFlow()

    val filteredLogs: StateFlow<List<SipTrafficLog>> = combine(allLogs, logFilter) { logs, filter ->
        when (filter) {
            "TX" -> logs.filter { it.direction == LogDirection.OUTBOUND }
            "RX" -> logs.filter { it.direction == LogDirection.INBOUND }
            "MCPTT" -> logs.filter { it.isMcpttTagged }
            "ERRORS" -> logs.filter { it.hasError }
            else -> logs
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val presetGroups = listOf(
        "sip:group1@ims.mnc070.mcc901.3gppnetwork.org" to "Group 1 - Tactical Ops",
        "sip:group2@ims.mnc070.mcc901.3gppnetwork.org" to "Group 2 - Fire / Rescue",
        "sip:mcptt_group_police@ims.mnc070.mcc901.3gppnetwork.org" to "Police Tactical Command",
        "sip:emergency@ims.mnc070.mcc901.3gppnetwork.org" to "Emergency Broadcast"
    )

    init {
        val currentProfile = repository.sipProfile.value
        sipStack.start(getApplication(), currentProfile)
        audioEngine.init(currentProfile.localRtpPort, sipStack.apnManager)

        // Wire media negotiation from SIP INVITE 200 OK
        sipStack.onMediaNegotiated = { remoteIp, remotePort ->
            audioEngine.setRemoteMediaTarget(remoteIp, remotePort)
        }

        // Wire floor grant callback
        sipStack.onFloorGranted = {
            audioEngine.playGrantTone()
            audioEngine.startMicrophoneTransmission()
        }

        viewModelScope.launch {
            sipStack.trafficLogs.collect { log ->
                repository.addLog(log)
            }
        }
    }

    fun updateProfile(profile: SipProfile) {
        repository.saveProfile(profile)
        sipStack.updateProfile(getApplication(), profile)
        audioEngine.init(profile.localRtpPort, sipStack.apnManager)
    }

    fun registerSip() {
        sipStack.register()
    }

    fun refreshNetwork() {
        sipStack.apnManager?.refreshNetworkBinding()
    }

    fun subscribeGroup(groupUri: String = sipProfile.value.targetGroup) {
        sipStack.subscribeGroup(groupUri)
    }

    fun selectTargetGroup(groupUri: String) {
        val updated = sipProfile.value.copy(targetGroup = groupUri)
        updateProfile(updated)
    }

    fun startCall() {
        sipStack.initiateMcpttCall()
    }

    fun onPttPressed() {
        if (callState.value != CallSessionState.CONNECTED) {
            // Initiate session first if not established
            sipStack.initiateMcpttCall()
        }
        sipStack.requestFloor()
    }

    fun onPttReleased() {
        audioEngine.playReleaseTone()
        audioEngine.stopMicrophoneTransmission()
        sipStack.releaseFloor()
    }

    fun endCallSession() {
        audioEngine.stopMicrophoneTransmission()
        audioEngine.stopAudioPlayback()
        sipStack.endCall()
    }

    fun triggerEmergencyAlert(customNote: String = "EMERGENCY SOS ALERT") {
        audioEngine.playEmergencyTone()
        sipStack.sendEmergencyAlert(customNote)
    }

    fun sendTextMessage(targetUri: String, text: String) {
        sipStack.sendSipMessageText(targetUri, text)
    }

    fun setLogFilter(filter: String) {
        _logFilter.value = filter
    }

    fun clearTrafficLogs() {
        repository.clearLogs()
    }

    fun simulateIncomingMcpttInvite() {
        val simUser = "sip:491234567890124@${sipProfile.value.realm}"
        val simTarget = sipProfile.value.targetGroup
        val rawSip = """
            INVITE $simTarget SIP/2.0
            Via: SIP/2.0/UDP ${sipProfile.value.pcscfHost}:5060;branch=z9hG4bK-sim123;rport
            Record-Route: <sip:${sipProfile.value.pcscfHost}:5060;lr>
            From: <$simUser>;tag=sim9988
            To: <$simTarget>
            Call-ID: sim-call-${System.currentTimeMillis()}@${sipProfile.value.realm}
            CSeq: 100 INVITE
            Contact: <$simUser>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            User-Agent: ${sipProfile.value.userAgent}
            Content-Type: application/sdp
            Content-Length: 180
            
            v=0
            o=sim_user 1234 1234 IN IP4 ${sipProfile.value.pcscfHost}
            s=MCPTT Call
            c=IN IP4 ${sipProfile.value.pcscfHost}
            m=audio 6002 RTP/AVP 0
            a=rtpmap:0 PCMU/8000
        """.trimIndent().replace("\n", "\r\n")

        sipStack.injectSimulatedPacket(rawSip)
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.close()
        sipStack.apnManager?.stopMonitoring()
    }
}
