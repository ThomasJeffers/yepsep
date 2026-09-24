package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.McpttRepository
import com.example.sip.engine.ApnNetworkStatus
import com.example.sip.engine.CallSessionState
import com.example.sip.engine.FloorState
import com.example.sip.engine.IncomingChatMessage
import com.example.sip.engine.McpttSipStack
import com.example.sip.engine.NegotiatedMedia
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class McpttViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = McpttRepository(application)
    val sipStack = McpttSipStack()
    val audioEngine = RtpAudioEngine()

    val sipProfile: StateFlow<SipProfile> = repository.sipProfile
    val registrationState: StateFlow<RegistrationState> = sipStack.registrationState
    val registrationFailureReason: StateFlow<String?> = sipStack.registrationFailureReason
    val callState: StateFlow<CallSessionState> = sipStack.callState
    val floorState: StateFlow<FloorState> = sipStack.floorState
    val activeSpeaker: StateFlow<String?> = sipStack.activeSpeaker
    val floorBusy: StateFlow<Boolean> = sipStack.floorBusy
    val micAudioLevel: StateFlow<Float> = audioEngine.micAudioLevel

    val negotiatedMedia: StateFlow<NegotiatedMedia?> = sipStack.negotiatedMedia
    val rtpTxCount: StateFlow<Long> = audioEngine.rtpTxCount
    val rtpRxCount: StateFlow<Long> = audioEngine.rtpRxCount
    val boundRtpPort: StateFlow<Int> = audioEngine.boundPort
    val incomingMessages: StateFlow<List<IncomingChatMessage>> = sipStack.incomingMessages

    private var pttHeld = false
    private var grantTonePlayed = false

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
        audioEngine.setApplication(application)
        audioEngine.isFloorGranted = { sipStack.floorState.value == FloorState.GRANTED }
        audioEngine.init(currentProfile.localRtpPort, sipStack.apnManager)
        sipStack.setLocalRtpPort(audioEngine.boundPort.value)
        sipStack.start(getApplication(), currentProfile)

        // Wire media negotiation from SIP INVITE / 200 OK
        sipStack.onMediaNegotiated = { remoteIp, remotePort ->
            audioEngine.setRemoteMediaTarget(remoteIp, remotePort)
            if (pttHeld && sipStack.floorState.value == FloorState.GRANTED) {
                audioEngine.startMicrophoneTransmission(remoteIp, remotePort)
            }
        }

        // Wire floor grant callback
        sipStack.onFloorGranted = {
            if (!grantTonePlayed) {
                grantTonePlayed = true
                audioEngine.playGrantTone()
            }
            val media = sipStack.negotiatedMedia.value
            if (media != null) {
                audioEngine.startMicrophoneTransmission(media.host, media.rtpPort)
            }
        }

        viewModelScope.launch {
            audioEngine.boundPort.collect { port ->
                sipStack.setLocalRtpPort(port)
            }
        }

        viewModelScope.launch {
            sipStack.apnManager?.networkStatus?.collect { netStatus ->
                if (netStatus is ApnNetworkStatus.Bound) {
                    audioEngine.bindToNetwork(sipStack.apnManager?.activeNetwork)
                }
            }
        }

        viewModelScope.launch {
            combine(sipStack.callState, sipStack.negotiatedMedia) { state, media ->
                state to media
            }.collect { (state, media) ->
                if (state == CallSessionState.CONNECTED && media != null) {
                    audioEngine.startAudioPlayback()
                    audioEngine.sendMediaBindingProbe(media.host, media.rtpPort)
                } else if (state == CallSessionState.IDLE || state == CallSessionState.DISCONNECTING) {
                    audioEngine.stopMicrophoneTransmission()
                    audioEngine.stopNatKeepalive()
                    audioEngine.stopAudioPlayback()
                    grantTonePlayed = false
                }
            }
        }

        viewModelScope.launch {
            combine(sipStack.floorState, sipStack.negotiatedMedia) { state, media ->
                state to media
            }.collect { (state, media) ->
                when (state) {
                    FloorState.GRANTED -> {
                        if (media != null) {
                            if (!grantTonePlayed) {
                                grantTonePlayed = true
                                audioEngine.playGrantTone()
                            }
                            audioEngine.startMicrophoneTransmission(media.host, media.rtpPort)
                        }
                    }
                    FloorState.LISTENING -> {
                        grantTonePlayed = false
                        audioEngine.stopMicrophoneTransmission()
                        audioEngine.flushPlayback()
                        audioEngine.applyPlaybackRouting()
                    }
                    FloorState.IDLE, FloorState.RELEASING -> {
                        grantTonePlayed = false
                        audioEngine.stopMicrophoneTransmission()
                        audioEngine.flushPlayback()
                        audioEngine.applyPlaybackRouting()
                    }
                    FloorState.REQUESTING -> {
                        // Awaiting grant confirmation from AS before unmuting
                    }
                }
            }
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
        audioEngine.bindToNetwork(sipStack.apnManager?.activeNetwork)
        sipStack.setLocalRtpPort(audioEngine.boundPort.value)
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

    fun requestFloor() {
        if (registrationState.value != RegistrationState.REGISTERED) {
            android.util.Log.w("McpttViewModel", "requestFloor ignored: client not REGISTERED (${registrationState.value})")
            return
        }
        if (callState.value != CallSessionState.CONNECTED) {
            android.util.Log.w("McpttViewModel", "requestFloor ignored: call session is not CONNECTED (${callState.value})")
            return
        }
        if (sipStack.floorState.value == FloorState.LISTENING || sipStack.floorBusy.value) {
            android.util.Log.w("McpttViewModel", "requestFloor rejected: floor is currently busy or held by ${activeSpeaker.value}")
            sipStack.triggerFloorBusy()
            return
        }
        if (sipStack.floorState.value == FloorState.REQUESTING || sipStack.floorState.value == FloorState.GRANTED) {
            android.util.Log.w("McpttViewModel", "requestFloor ignored: already in state ${sipStack.floorState.value}")
            return
        }
        pttHeld = true
        sipStack.requestFloor()
    }

    fun releaseFloor() {
        val wasHolding = sipStack.floorState.value == FloorState.GRANTED || sipStack.floorState.value == FloorState.REQUESTING
        pttHeld = false
        grantTonePlayed = false
        audioEngine.stopMicrophoneTransmission()
        if (wasHolding) {
            audioEngine.playReleaseTone()
            sipStack.releaseFloor()
            audioEngine.flushPlayback()
            audioEngine.applyPlaybackRouting()
        }
    }

    fun onPttPressed() {
        if (registrationState.value != RegistrationState.REGISTERED) {
            android.util.Log.w("McpttViewModel", "PTT pressed: client is not REGISTERED (${registrationState.value})")
            return
        }

        // When FLOOR == GRANTED: audio transmit enabled
        if (sipStack.floorState.value == FloorState.GRANTED) {
            pttHeld = true
            val media = sipStack.negotiatedMedia.value
            if (media != null) {
                if (!grantTonePlayed) {
                    grantTonePlayed = true
                    audioEngine.playGrantTone()
                }
                audioEngine.startMicrophoneTransmission(media.host, media.rtpPort)
            }
            return
        }

        // When FLOOR != GRANTED: PTT button must NOT cause microphone transmission.
        // It may initiate the already-existing call/request workflow, but never bypass floor ownership.
        if (sipStack.floorState.value == FloorState.LISTENING || sipStack.floorBusy.value) {
            android.util.Log.w("McpttViewModel", "PTT pressed while floor is taken/busy - rejecting request")
            sipStack.triggerFloorBusy()
            return
        }

        if (callState.value != CallSessionState.CONNECTED) {
            pttHeld = true
            sipStack.requestFloor()
            return
        }

        if (sipStack.floorState.value == FloorState.IDLE) {
            pttHeld = true
            sipStack.requestFloor()
            return
        }
    }

    fun onPttReleased() {
        val wasHolding = sipStack.floorState.value == FloorState.GRANTED || sipStack.floorState.value == FloorState.REQUESTING
        pttHeld = false
        grantTonePlayed = false
        audioEngine.stopMicrophoneTransmission()

        if (wasHolding) {
            audioEngine.playReleaseTone()
            sipStack.releaseFloor()
            audioEngine.flushPlayback()
        }
    }

    fun endCallSession() {
        pttHeld = false
        grantTonePlayed = false
        audioEngine.stopMicrophoneTransmission()
        audioEngine.stopNatKeepalive()
        audioEngine.stopAudioPlayback()
        sipStack.endCall()
    }

    fun onAppBackgrounded() {
        if (sipStack.floorState.value == FloorState.GRANTED || sipStack.floorState.value == FloorState.REQUESTING) {
            android.util.Log.i("McpttViewModel", "App backgrounded while holding/requesting floor - releasing cleanly")
            onPttReleased()
        }
    }

    fun onAppResumed() {
        val media = sipStack.negotiatedMedia.value
        if (sipStack.callState.value == CallSessionState.CONNECTED && media != null) {
            audioEngine.startAudioPlayback()
            audioEngine.sendMediaBindingProbe(media.host, media.rtpPort)
        }
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

    /**
     * Formats all currently logged or filtered SIP traffic into a standardized text dump for analysis,
     * comparing directly against exp5_uac.py packet traces.
     */
    fun buildTrafficLogExportText(filteredOnly: Boolean = false): String {
        val logsToExport = if (filteredOnly) filteredLogs.value else allLogs.value
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val profile = sipProfile.value

        return buildString {
            append("================================================================================\n")
            append("MCPTT CLIENT - SIP & MEDIA TRAFFIC INSPECTOR LOG EXPORT\n")
            append("Generated: ${dateFormat.format(Date())}\n")
            append("Subscriber IMSI: ${profile.imsi} | IMPU: ${profile.mcpttId}\n")
            append("P-CSCF: ${profile.pcscfHost}:${profile.pcscfPort} | Local SIP Port: ${profile.localSipPort}\n")
            append("Local APN IPv4: ${sipStack.apnManager?.boundIp ?: "Unbound"}\n")
            append("Total Packets Exported: ${logsToExport.size}\n")
            append("================================================================================\n\n")

            if (logsToExport.isEmpty()) {
                append("No SIP traffic records captured.\n")
            } else {
                // Export in chronological order (oldest to newest) for easy flow sequence analysis
                logsToExport.reversed().forEachIndexed { index, log ->
                    val dirStr = if (log.direction == LogDirection.OUTBOUND) "TX >>>>>>>>" else "<<<<<<<< RX"
                    append("--------------------------------------------------------------------------------\n")
                    append("#${index + 1} | [${dateFormat.format(Date(log.timestamp))}] $dirStr ${log.remoteAddress}\n")
                    append("SUMMARY: ${log.summary}\n")
                    append("TYPE: ${log.type} | MCPTT_TAGGED: ${log.isMcpttTagged} | ERROR: ${log.hasError}\n")
                    append("--------------------------------------------------------------------------------\n")
                    append(log.rawPacket.trimEnd())
                    append("\n\n")
                }
            }
            append("================================================================================\n")
            append("END OF LOG EXPORT\n")
            append("================================================================================\n")
        }
    }

    /**
     * Writes the SIP traffic log to a temporary .txt file in the app's cache directory
     * and triggers the system share sheet via Intent.ACTION_SEND with FileProvider.
     */
    fun shareSipTrafficLogs(context: Context, filteredOnly: Boolean = false): Boolean {
        return try {
            val exportText = buildTrafficLogExportText(filteredOnly)
            val cacheDir = File(context.cacheDir, "sip_exports")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(cacheDir, "mcptt_sip_traffic_$timeStamp.txt")
            file.writeText(exportText, Charsets.UTF_8)

            val authority = "${context.packageName}.fileprovider"
            val fileUri = FileProvider.getUriForFile(context, authority, file)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "MCPTT SIP Traffic Log ($timeStamp)")
                putExtra(Intent.EXTRA_TEXT, "Attached is the MCPTT SIP trace log from $timeStamp (${if (filteredOnly) "Filtered" else "All"} packets).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share SIP Traffic Log (.txt)")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        } catch (e: Exception) {
            android.util.Log.e("McpttViewModel", "Failed to share SIP traffic logs: ${e.message}", e)
            false
        }
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
