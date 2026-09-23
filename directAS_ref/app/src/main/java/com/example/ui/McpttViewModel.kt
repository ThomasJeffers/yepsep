package com.example.ui

import android.app.Application
import android.content.Intent
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.McpttRepository
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
    val negotiatedMedia = sipStack.negotiatedMedia
    val networkStatus = sipStack.networkStatus
    val localIp = sipStack.localIpFlow
    val incomingMessages = sipStack.incomingMessages
    val micAudioLevel: StateFlow<Float> = audioEngine.micAudioLevel
    val localRtpPort = audioEngine.boundPort
    val rtpTxCount = audioEngine.rtpTxCount
    val rtpRxCount = audioEngine.rtpRxCount

    val allLogs: StateFlow<List<SipTrafficLog>> = repository.logs

    private val _logFilter = MutableStateFlow("ALL")
    val logFilter: StateFlow<String> = _logFilter.asStateFlow()

    val filteredLogs: StateFlow<List<SipTrafficLog>> = combine(allLogs, logFilter) { logs, filter ->
        when (filter) {
            "TX" -> logs.filter { it.direction == LogDirection.OUTBOUND }
            "RX" -> logs.filter { it.direction == LogDirection.INBOUND }
            "MCPTT" -> logs.filter { it.isMcpttTagged }
            "INFO" -> logs.filter {
                it.type == com.example.sip.model.LogType.SIP_INFO ||
                    it.summary.contains("INFO", ignoreCase = true) ||
                    it.methodOrResponse.contains("INFO", ignoreCase = true)
            }
            "ERRORS" -> logs.filter { it.hasError }
            else -> logs
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val presetGroups = listOf(
        "sip:mcptt_group_fire@ims.mnc070.mcc901.3gppnetwork.org" to "Fire Response Group",
        "sip:mcptt_group_police@ims.mnc070.mcc901.3gppnetwork.org" to "Tactical Police Alpha",
        "sip:mcptt_group_medical@ims.mnc070.mcc901.3gppnetwork.org" to "EMS Rescue Command",
        "sip:mcptt_group_emergency@ims.mnc070.mcc901.3gppnetwork.org" to "National Emergency Broadcast"
    )

    private var pttHeld = false
    private var grantTonePlayed = false

    init {
        val currentProfile = repository.sipProfile.value
        sipStack.start(getApplication(), currentProfile)
        val rtpPort = audioEngine.init(0, sipStack.getBoundNetwork(), getApplication())
        sipStack.setLocalRtpPort(rtpPort)

        viewModelScope.launch {
            sipStack.trafficLogs.collect { log ->
                repository.addLog(log)
            }
        }

        viewModelScope.launch {
            combine(sipStack.callState, sipStack.negotiatedMedia) { state, media ->
                state to media
            }.collect { (state, media) ->
                when {
                    state == CallSessionState.CONNECTED && media != null -> {
                        audioEngine.startAudioPlayback()
                        audioEngine.startNatKeepalive(media.host, media.rtpPort)
                    }
                    state == CallSessionState.IDLE || state == CallSessionState.DISCONNECTING -> {
                        audioEngine.stopMicrophoneTransmission()
                        audioEngine.stopNatKeepalive()
                        audioEngine.stopAudioPlayback()
                    }
                }
            }
        }

        viewModelScope.launch {
            combine(sipStack.floorState, sipStack.negotiatedMedia) { state, media ->
                state to media
            }.collect { (state, media) ->
                if (state == FloorState.GRANTED && pttHeld && media != null) {
                    if (!grantTonePlayed) {
                        grantTonePlayed = true
                        audioEngine.playGrantTone()
                    }
                    audioEngine.startMicrophoneTransmission(
                        destHost = media.host,
                        destPort = media.rtpPort
                    )
                } else if (state == FloorState.TAKEN) {
                    grantTonePlayed = false
                    audioEngine.stopMicrophoneTransmission()
                    audioEngine.applyPlaybackRouting()
                } else if (state != FloorState.GRANTED) {
                    grantTonePlayed = false
                    audioEngine.stopMicrophoneTransmission()
                    audioEngine.applyPlaybackRouting()
                }
            }
        }
    }

    fun updateProfile(profile: SipProfile) {
        repository.saveProfile(profile)
        sipStack.updateProfile(getApplication(), profile)
        val rtpPort = audioEngine.init(0, sipStack.getBoundNetwork(), getApplication())
        sipStack.setLocalRtpPort(rtpPort)
    }

    fun registerSip() {
        sipStack.register()
    }

    fun subscribeGroup(groupUri: String = sipProfile.value.targetGroup) {
        sipStack.subscribeGroup(groupUri)
    }

    fun selectTargetGroup(groupUri: String) {
        val updated = sipProfile.value.copy(targetGroup = groupUri)
        updateProfile(updated)
    }

    fun onPttPressed() {
        pttHeld = true
        sipStack.requestFloor()
        val media = sipStack.negotiatedMedia.value
        if (sipStack.floorState.value == FloorState.GRANTED && media != null) {
            audioEngine.startMicrophoneTransmission(media.host, media.rtpPort)
        }
    }

    fun onPttReleased() {
        pttHeld = false
        audioEngine.playReleaseTone()
        sipStack.releaseFloor()
        audioEngine.stopMicrophoneTransmission()
    }

    fun endCallSession() {
        audioEngine.stopMicrophoneTransmission()
        audioEngine.stopNatKeepalive()
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
        val simUser = "sip:mcptt_unit02@ims.mnc070.mcc901.3gppnetwork.org"
        val simTarget = sipProfile.value.targetGroup
        val asHost = sipProfile.value.mcpttAsHost
        val rawSip = """
            INVITE $simTarget SIP/2.0
            Via: SIP/2.0/UDP $asHost:5070;branch=z9hG4bK-sim123
            From: <$simUser>;tag=sim9988
            To: <$simTarget>
            Call-ID: sim-call-${System.currentTimeMillis()}@ims.mnc070.mcc901.3gppnetwork.org
            CSeq: 100 INVITE
            Contact: <$simUser>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            User-Agent: ${sipProfile.value.userAgent}
            Content-Type: application/sdp
            Content-Length: 180
            
            v=0
            o=sim_user 1234 1234 IN IP4 $asHost
            s=Simulated Call
            c=IN IP4 $asHost
            m=audio 10002 RTP/AVP 0
        """.trimIndent().replace("\n", "\r\n")

        sipStack.injectSimulatedPacket(rawSip)
    }

    fun exportSipLogs(): java.io.File? {
        val logs = repository.logs.value
        val app = getApplication<Application>()
        val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: app.filesDir
        if (!dir.exists()) dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        val file = java.io.File(dir, "mcptt-sip-$stamp.txt")
        val media = sipStack.negotiatedMedia.value
        val header = buildString {
            appendLine("MCPTT SIP Inspector export")
            appendLine("time=$stamp")
            appendLine("impu=${sipProfile.value.mcpttId}")
            appendLine("mode=${sipProfile.value.sipDestinationLabel()}")
            appendLine("localSip=${sipStack.localIpFlow.value}:${sipProfile.value.localSipPort}")
            appendLine("localRtp=${sipStack.localIpFlow.value}:${audioEngine.boundPort.value}")
            appendLine("remoteRtp=${media?.host ?: "-"}:${media?.rtpPort ?: 0}")
            appendLine("network=${sipStack.networkStatus.value}")
            appendLine("rtpTx=${audioEngine.rtpTxCount.value} rtpRx=${audioEngine.rtpRxCount.value}")
            appendLine("=".repeat(72))
        }
        val body = logs.asReversed().joinToString("\n\n") { log ->
            val dirLabel = if (log.direction == LogDirection.OUTBOUND) "TX" else "RX"
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                .format(java.util.Date(log.timestamp))
            "$time $dirLabel ${log.methodOrResponse} ${log.remoteAddress}\n${log.summary}\n---\n${log.rawPacket}"
        }
        return try {
            file.writeText(header + "\n" + body + "\n")
            file
        } catch (e: Exception) {
            null
        }
    }

    fun shareSipLogs() {
        val app = getApplication<Application>()
        val file = exportSipLogs() ?: run {
            Toast.makeText(app, "Export failed", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(app, app.packageName + ".fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(Intent.createChooser(intent, "Share SIP log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Toast.makeText(app, "Saved ${file.absolutePath}", Toast.LENGTH_LONG).show()
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.close()
        sipStack.stop()
    }
}
