package com.example.sip.engine

import android.content.Context
import android.util.Log
import com.example.sip.model.LogDirection
import com.example.sip.model.LogType
import com.example.sip.model.SipMessage
import com.example.sip.model.SipProfile
import com.example.sip.model.SipTrafficLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.security.MessageDigest
import java.util.UUID

enum class RegistrationState {
    UNREGISTERED,
    REGISTERING,
    REGISTERED,
    FAILED
}

enum class CallSessionState {
    IDLE,
    CALLING,
    CONNECTED,
    DISCONNECTING
}

enum class FloorState {
    IDLE,
    REQUESTING,
    GRANTED,
    TAKEN
}

class McpttSipStack {
    private val scope = CoroutineScope(Dispatchers.IO)

    private var sipSocket: DatagramSocket? = null
    private var listenJob: Job? = null

    private val _registrationState = MutableStateFlow(RegistrationState.UNREGISTERED)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionState.IDLE)
    val callState: StateFlow<CallSessionState> = _callState.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()

    private val _trafficLogs = MutableSharedFlow<SipTrafficLog>(replay = 50)
    val trafficLogs: SharedFlow<SipTrafficLog> = _trafficLogs.asSharedFlow()

    var profile = SipProfile()
        private set

    var apnManager: McpttApnNetworkManager? = null
        private set

    // SIP Dialog and Session State
    private var registerCallId = UUID.randomUUID().toString()
    private var registerFromTag = generateTag()
    private var registerCSeq = 1
    private var lastAuthNonce = ""
    private var lastAuthRealm = ""
    private var lastAuthQop = ""
    private var lastAuthOpaque = ""

    // Active Call Dialog State
    private var activeCallId: String? = null
    private var activeCallFromTag: String? = null
    private var activeCallToTag: String? = null
    private var activeRemoteTargetUri: String? = null
    private var activeRouteSet: List<String> = emptyList()
    private var dialogCSeq = 100

    // Remote RTP Target
    var remoteMediaIp: String? = null
        private set
    var remoteMediaPort: Int? = null
        private set

    var onMediaNegotiated: ((host: String, port: Int) -> Unit)? = null
    var onFloorGranted: (() -> Unit)? = null

    fun start(context: Context, initialProfile: SipProfile) {
        this.profile = initialProfile
        val netMgr = McpttApnNetworkManager(context.applicationContext, scope)
        this.apnManager = netMgr
        netMgr.updateConfig(initialProfile.apnName, initialProfile.apnPrefix)
        netMgr.startMonitoring()

        initSocket()

        if (initialProfile.autoRegister) {
            scope.launch {
                delay(1500)
                register()
            }
        }
    }

    fun updateProfile(context: Context, newProfile: SipProfile) {
        val portChanged = newProfile.localSipPort != profile.localSipPort
        val apnChanged = newProfile.apnName != profile.apnName || newProfile.apnPrefix != profile.apnPrefix
        this.profile = newProfile

        if (apnChanged) {
            apnManager?.updateConfig(newProfile.apnName, newProfile.apnPrefix)
        }

        if (portChanged) {
            initSocket()
        }
    }

    private fun initSocket() {
        listenJob?.cancel()
        sipSocket?.close()

        try {
            val socket = DatagramSocket(profile.localSipPort)
            apnManager?.bindSocket(socket)
            sipSocket = socket
            Log.i(TAG, "SIP Stack bound to UDP port ${profile.localSipPort} on MCPTT network")
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind SIP socket to port ${profile.localSipPort}: ${e.message}", e)
        }
    }

    private fun startListening() {
        listenJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive) {
                try {
                    val socket = sipSocket ?: break
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val rawSip = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    handleIncomingPacket(rawSip, packet.address.hostAddress ?: "", packet.port)
                } catch (e: Exception) {
                    if (isActive) {
                        Log.w(TAG, "Socket receive error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleIncomingPacket(rawSip: String, remoteHost: String, remotePort: Int) {
        val msg = SipMessage.parse(rawSip)
        logTraffic(rawSip, LogDirection.INBOUND, "$remoteHost:$remotePort")

        if (msg.isResponse) {
            handleResponse(msg)
        } else {
            handleRequest(msg)
        }
    }

    private fun handleResponse(msg: SipMessage) {
        when (msg.method) {
            "REGISTER" -> {
                when (msg.statusCode) {
                    200 -> {
                        _registrationState.value = RegistrationState.REGISTERED
                        Log.i(TAG, "SIP REGISTER 200 OK - Registered to S-CSCF")
                    }
                    401 -> {
                        _registrationState.value = RegistrationState.REGISTERING
                        Log.i(TAG, "SIP REGISTER 401 Unauthorized - Challenging with MD5 digest")
                        handleRegister401(msg)
                    }
                    else -> {
                        if (msg.statusCode >= 400) {
                            _registrationState.value = RegistrationState.FAILED
                            Log.e(TAG, "SIP REGISTER failed with status ${msg.statusCode} ${msg.statusText}")
                        }
                    }
                }
            }
            "INVITE" -> {
                when (msg.statusCode) {
                    200 -> {
                        _callState.value = CallSessionState.CONNECTED
                        Log.i(TAG, "SIP INVITE 200 OK - Call established")

                        // Extract dialog state
                        activeCallToTag = extractTag(msg.to)
                        activeRemoteTargetUri = msg.extractContactUri().ifEmpty { profile.targetGroup }
                        activeRouteSet = msg.extractUacRouteSet()

                        // Extract SDP media information
                        val (sdpIp, sdpPort) = msg.extractSdpMedia()
                        if (sdpIp != null && sdpPort != null) {
                            remoteMediaIp = sdpIp
                            remoteMediaPort = sdpPort
                            Log.i(TAG, "Negotiated remote media: $sdpIp:$sdpPort")
                            onMediaNegotiated?.invoke(sdpIp, sdpPort)
                        } else {
                            // Fallback to P-CSCF / AS host if not parsed
                            remoteMediaIp = profile.pcscfHost
                            remoteMediaPort = profile.localRtpPort
                            onMediaNegotiated?.invoke(remoteMediaIp!!, remoteMediaPort!!)
                        }

                        // Send ACK
                        sendAck(msg)

                        if (profile.autoGrantFloor) {
                            _floorState.value = FloorState.GRANTED
                            _activeSpeaker.value = profile.displayName
                            onFloorGranted?.invoke()
                        }
                    }
                    180, 183 -> {
                        _callState.value = CallSessionState.CALLING
                        Log.i(TAG, "SIP INVITE ${msg.statusCode} ${msg.statusText} (Ringing/Session Progress)")
                    }
                    else -> {
                        if (msg.statusCode >= 400) {
                            _callState.value = CallSessionState.IDLE
                            _floorState.value = FloorState.IDLE
                            Log.e(TAG, "SIP INVITE failed with status ${msg.statusCode} ${msg.statusText}")
                        }
                    }
                }
            }
            "INFO" -> {
                Log.d(TAG, "In-dialog INFO response: ${msg.statusCode} ${msg.statusText}")
            }
            "BYE" -> {
                _callState.value = CallSessionState.IDLE
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
            }
        }
    }

    private fun handleRequest(msg: SipMessage) {
        when (msg.method) {
            "INVITE" -> {
                // Incoming call from AS / peer
                Log.i(TAG, "Received incoming INVITE from ${msg.from}")
                _callState.value = CallSessionState.CONNECTED
                activeCallId = msg.callId
                activeCallFromTag = extractTag(msg.from)
                activeCallToTag = generateTag()
                activeRemoteTargetUri = msg.extractContactUri().ifEmpty { msg.from }
                activeRouteSet = msg.extractUacRouteSet()

                val (sdpIp, sdpPort) = msg.extractSdpMedia()
                if (sdpIp != null && sdpPort != null) {
                    remoteMediaIp = sdpIp
                    remoteMediaPort = sdpPort
                    onMediaNegotiated?.invoke(sdpIp, sdpPort)
                }

                send200OkForInvite(msg)
            }
            "INFO" -> {
                // Floor Control Action from AS
                val floorAction = msg.floorControlState
                Log.i(TAG, "Received in-dialog INFO: floorAction=$floorAction, body=${msg.body}")
                when (floorAction) {
                    "GRANTED" -> {
                        _floorState.value = FloorState.GRANTED
                        _activeSpeaker.value = "${profile.displayName} (Floor Granted)"
                        onFloorGranted?.invoke()
                    }
                    "TAKEN" -> {
                        _floorState.value = FloorState.TAKEN
                        _activeSpeaker.value = extractSpeakerFromInfo(msg.body) ?: "Remote Unit"
                    }
                    "RELEASE", "IDLE" -> {
                        _floorState.value = FloorState.IDLE
                        _activeSpeaker.value = null
                    }
                }
                sendResponse(200, "OK", msg)
            }
            "BYE" -> {
                Log.i(TAG, "Received BYE - Call terminated by remote")
                sendResponse(200, "OK", msg)
                _callState.value = CallSessionState.IDLE
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                resetDialogState()
            }
            "MESSAGE" -> {
                Log.i(TAG, "Received SIP MESSAGE from ${msg.from}: ${msg.body}")
                sendResponse(200, "OK", msg)
            }
        }
    }

    fun register() {
        _registrationState.value = RegistrationState.REGISTERING
        registerCallId = UUID.randomUUID().toString() + "@" + profile.realm
        registerFromTag = generateTag()
        registerCSeq = 1

        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)

        val mcpttContactParam = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val acceptContact = if (profile.includeMcpttTags) {
            "Accept-Contact: *;+g.3gpp.mcptt;explicit;require\r\n"
        } else ""

        val sipPacket = buildString {
            append("REGISTER sip:${profile.realm} SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${profile.mcpttId}>;tag=$registerFromTag\r\n")
            append("To: <${profile.mcpttId}>\r\n")
            append("Call-ID: $registerCallId\r\n")
            append("CSeq: $registerCSeq REGISTER\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append("Expires: 3600\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append(acceptContact)
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun handleRegister401(msg: SipMessage) {
        val authHeader = msg.getHeader("www-authenticate").ifEmpty { msg.getHeader("proxy-authenticate") }
        val realm = extractAuthParam(authHeader, "realm").ifEmpty { profile.realm }
        val nonce = extractAuthParam(authHeader, "nonce")
        val qop = extractAuthParam(authHeader, "qop")
        val opaque = extractAuthParam(authHeader, "opaque")

        lastAuthRealm = realm
        lastAuthNonce = nonce
        lastAuthQop = qop
        lastAuthOpaque = opaque

        registerCSeq++
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val uri = "sip:${profile.realm}"
        val authUsername = "${profile.imsi}@${profile.realm}"

        val authDigest = computeDigestResponse(
            username = authUsername,
            realm = realm,
            password = profile.password,
            method = "REGISTER",
            uri = uri,
            nonce = nonce,
            qop = qop
        )

        val mcpttContactParam = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val acceptContact = if (profile.includeMcpttTags) {
            "Accept-Contact: *;+g.3gpp.mcptt;explicit;require\r\n"
        } else ""

        val sipPacket = buildString {
            append("REGISTER sip:${profile.realm} SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            // RFC 3261: Must retain the exact same From tag and Call-ID on 401 re-attempt
            append("From: <${profile.mcpttId}>;tag=$registerFromTag\r\n")
            append("To: <${profile.mcpttId}>\r\n")
            append("Call-ID: $registerCallId\r\n")
            append("CSeq: $registerCSeq REGISTER\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append("Expires: 3600\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append(acceptContact)
            append("Authorization: $authDigest\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun initiateMcpttCall(targetUri: String = profile.targetGroup) {
        _callState.value = CallSessionState.CALLING
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        activeCallId = UUID.randomUUID().toString() + "@" + profile.realm
        activeCallFromTag = generateTag()
        dialogCSeq = 100

        val mcpttContactParam = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val acceptContact = if (profile.includeMcpttTags) {
            "Accept-Contact: *;+g.3gpp.mcptt;explicit;require\r\n"
        } else ""

        val sdpBody = buildString {
            append("v=0\r\n")
            append("o=${profile.imsi} 1000 1000 IN IP4 $localIp\r\n")
            append("s=MCPTT Session\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio ${profile.localRtpPort} RTP/AVP 0\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=sendrecv\r\n")
        }

        val sipPacket = buildString {
            append("INVITE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <$targetUri>\r\n")
            append("Call-ID: $activeCallId\r\n")
            append("CSeq: $dialogCSeq INVITE\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append(acceptContact)
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdpBody.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(sdpBody)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun sendAck(okMsg: SipMessage) {
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val targetUri = activeRemoteTargetUri ?: profile.targetGroup
        val toTag = activeCallToTag ?: extractTag(okMsg.to)

        val routeHeaders = buildRouteHeader(activeRouteSet)

        val sipPacket = buildString {
            append("ACK $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            if (routeHeaders.isNotEmpty()) {
                append(routeHeaders)
            }
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <${profile.targetGroup}>;tag=$toTag\r\n")
            append("Call-ID: $activeCallId\r\n")
            append("CSeq: $dialogCSeq ACK\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun send200OkForInvite(inviteMsg: SipMessage) {
        val localIp = getLocalIpAddress()
        val sdpBody = buildString {
            append("v=0\r\n")
            append("o=${profile.imsi} 2000 2000 IN IP4 $localIp\r\n")
            append("s=MCPTT Session\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio ${profile.localRtpPort} RTP/AVP 0\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=sendrecv\r\n")
        }

        val mcpttContactParam = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""

        val sipPacket = buildString {
            append("SIP/2.0 200 OK\r\n")
            for (v in inviteMsg.getHeaders("via")) {
                append("Via: $v\r\n")
            }
            for (rr in inviteMsg.getHeaders("record-route")) {
                append("Record-Route: $rr\r\n")
            }
            append("From: ${inviteMsg.from}\r\n")
            append("To: ${inviteMsg.to};tag=$activeCallToTag\r\n")
            append("Call-ID: ${inviteMsg.callId}\r\n")
            append("CSeq: ${inviteMsg.cseq}\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: application/sdp\r\n")
            append("Content-Length: ${sdpBody.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
            append(sdpBody)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun requestFloor() {
        _floorState.value = FloorState.REQUESTING
        sendInDialogFloorAction("floor-request")
    }

    fun releaseFloor() {
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
        sendInDialogFloorAction("floor-release")
    }

    private fun sendInDialogFloorAction(actionName: String) {
        val callId = activeCallId ?: return
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val targetUri = activeRemoteTargetUri ?: profile.targetGroup
        dialogCSeq++

        val routeHeaders = buildRouteHeader(activeRouteSet)
        val body = "Action=$actionName\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val sipPacket = buildString {
            append("INFO $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            if (routeHeaders.isNotEmpty()) {
                append(routeHeaders)
            }
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <${profile.targetGroup}>;tag=${activeCallToTag ?: ""}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $dialogCSeq INFO\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n\r\n")
            append(body)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun endCall() {
        val callId = activeCallId ?: return
        _callState.value = CallSessionState.DISCONNECTING
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val targetUri = activeRemoteTargetUri ?: profile.targetGroup
        dialogCSeq++

        val routeHeaders = buildRouteHeader(activeRouteSet)

        val sipPacket = buildString {
            append("BYE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            if (routeHeaders.isNotEmpty()) {
                append(routeHeaders)
            }
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <${profile.targetGroup}>;tag=${activeCallToTag ?: ""}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $dialogCSeq BYE\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
        _callState.value = CallSessionState.IDLE
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
        resetDialogState()
    }

    fun subscribeGroup(groupUri: String = profile.targetGroup) {
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val callId = UUID.randomUUID().toString() + "@" + profile.realm

        val sipPacket = buildString {
            append("SUBSCRIBE $groupUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${profile.mcpttId}>;tag=${generateTag()}\r\n")
            append("To: <$groupUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 SUBSCRIBE\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>\r\n")
            append("Event: conference\r\n")
            append("Accept: application/conference-info+xml\r\n")
            append("Expires: 3600\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun sendEmergencyAlert(customNote: String = "EMERGENCY SOS ALERT") {
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val callId = UUID.randomUUID().toString() + "@" + profile.realm
        val target = profile.emergencyGroup

        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n")
            append("<mcptt-emergency-alert xmlns=\"urn:3gpp:ns:mcptt:emergency:1.0\">\r\n")
            append("  <originator>${profile.mcpttId}</originator>\r\n")
            append("  <timestamp>${System.currentTimeMillis()}</timestamp>\r\n")
            append("  <alert-type>EMERGENCY_SOS</alert-type>\r\n")
            append("  <note>$customNote</note>\r\n")
            append("</mcptt-emergency-alert>\r\n")
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)

        val sipPacket = buildString {
            append("MESSAGE $target SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${profile.mcpttId}>;tag=${generateTag()}\r\n")
            append("To: <$target>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt;+g.3gpp.mcptt.emergency\r\n")
            append("Accept-Contact: *;+g.3gpp.mcptt;+g.3gpp.mcptt.emergency;explicit;require\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: application/vnd.3gpp.mcptt-info+xml\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n\r\n")
            append(body)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun sendSipMessageText(targetUri: String, text: String) {
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val callId = UUID.randomUUID().toString() + "@" + profile.realm
        val bodyBytes = text.toByteArray(Charsets.UTF_8)

        val sipPacket = buildString {
            append("MESSAGE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            append("From: <${profile.mcpttId}>;tag=${generateTag()}\r\n")
            append("To: <$targetUri>\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: 1 MESSAGE\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n\r\n")
            append(text)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun sendResponse(code: Int, text: String, requestMsg: SipMessage) {
        val sipPacket = buildString {
            append("SIP/2.0 $code $text\r\n")
            for (v in requestMsg.getHeaders("via")) {
                append("Via: $v\r\n")
            }
            append("From: ${requestMsg.from}\r\n")
            val toTag = if (requestMsg.to.contains("tag=")) "" else ";tag=${generateTag()}"
            append("To: ${requestMsg.to}$toTag\r\n")
            append("Call-ID: ${requestMsg.callId}\r\n")
            append("CSeq: ${requestMsg.cseq}\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun sendRawSip(rawSip: String, destHost: String, destPort: Int) {
        scope.launch {
            try {
                val bytes = rawSip.toByteArray(Charsets.UTF_8)
                val targetAddr = InetAddress.getByName(destHost)
                val packet = DatagramPacket(bytes, bytes.size, targetAddr, destPort)
                sipSocket?.send(packet)
                logTraffic(rawSip, LogDirection.OUTBOUND, "$destHost:$destPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SIP datagram to $destHost:$destPort: ${e.message}", e)
            }
        }
    }

    fun injectSimulatedPacket(rawSip: String) {
        scope.launch {
            handleIncomingPacket(rawSip, "127.0.0.1", profile.pcscfPort)
        }
    }

    private fun logTraffic(rawText: String, direction: LogDirection, endpoint: String) {
        val lines = rawText.lines()
        val firstLine = lines.firstOrNull()?.trim() ?: "UNKNOWN"
        val isMcptt = rawText.contains("mcptt", ignoreCase = true)
        val hasErr = rawText.startsWith("SIP/2.0 4") || rawText.startsWith("SIP/2.0 5") || rawText.startsWith("SIP/2.0 6")

        val methodOrResponse = firstLine.split(" ").firstOrNull() ?: "SIP"
        val logType = when {
            firstLine.startsWith("REGISTER", ignoreCase = true) -> LogType.SIP_REGISTER
            firstLine.startsWith("INVITE", ignoreCase = true) -> LogType.SIP_INVITE
            firstLine.startsWith("SUBSCRIBE", ignoreCase = true) -> LogType.SIP_SUBSCRIBE
            firstLine.startsWith("MESSAGE", ignoreCase = true) -> LogType.SIP_MESSAGE
            firstLine.startsWith("INFO", ignoreCase = true) -> LogType.SIP_INFO
            firstLine.startsWith("BYE", ignoreCase = true) -> LogType.SIP_BYE
            firstLine.startsWith("ACK", ignoreCase = true) -> LogType.SIP_ACK
            firstLine.startsWith("SIP/2.0", ignoreCase = true) -> LogType.SIP_RESPONSE
            else -> LogType.SYSTEM_EVENT
        }

        val log = SipTrafficLog(
            direction = direction,
            type = logType,
            methodOrResponse = methodOrResponse,
            remoteAddress = endpoint,
            summary = firstLine,
            rawPacket = rawText,
            isMcpttTagged = isMcptt,
            hasError = hasErr
        )
        scope.launch {
            _trafficLogs.emit(log)
        }
    }

    private fun getLocalIpAddress(): String {
        return apnManager?.boundIp ?: "192.168.102.2"
    }

    private fun resetDialogState() {
        activeCallId = null
        activeCallFromTag = null
        activeCallToTag = null
        activeRemoteTargetUri = null
        activeRouteSet = emptyList()
        remoteMediaIp = null
        remoteMediaPort = null
    }

    private fun generateTag(): String = UUID.randomUUID().toString().take(8)

    private fun extractTag(headerVal: String): String {
        val idx = headerVal.indexOf("tag=")
        if (idx == -1) return ""
        return headerVal.substring(idx + 4).substringBefore(";").trim()
    }

    private fun buildRouteHeader(routes: List<String>): String {
        if (routes.isEmpty()) return ""
        return routes.joinToString(separator = "\r\n") { "Route: <$it>" } + "\r\n"
    }

    private fun extractSpeakerFromInfo(body: String): String? {
        for (line in body.lines()) {
            if (line.trim().startsWith("speaker=", ignoreCase = true)) {
                return line.substringAfter("=").trim()
            }
        }
        return null
    }

    private fun extractAuthParam(authHeader: String, paramName: String): String {
        val pattern = Regex("""$paramName=["']?([^"',]+)["']?""", RegexOption.IGNORE_CASE)
        val match = pattern.find(authHeader)
        return match?.groupValues?.get(1) ?: ""
    }

    private fun computeDigestResponse(
        username: String,
        realm: String,
        password: String,
        method: String,
        uri: String,
        nonce: String,
        qop: String
    ): String {
        val ha1 = md5Hex("$username:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        val cnonce = UUID.randomUUID().toString().take(8)
        val nc = "00000001"

        val response = if (qop.isNotEmpty()) {
            md5Hex("$ha1:$nonce:$nc:$cnonce:auth:$ha2")
        } else {
            md5Hex("$ha1:$nonce:$ha2")
        }

        return buildString {
            append("Digest username=\"$username\", ")
            append("realm=\"$realm\", ")
            append("nonce=\"$nonce\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=MD5")
            if (qop.isNotEmpty()) {
                append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            }
            if (lastAuthOpaque.isNotEmpty()) {
                append(", opaque=\"$lastAuthOpaque\"")
            }
        }
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "McpttSipStack"
    }
}
