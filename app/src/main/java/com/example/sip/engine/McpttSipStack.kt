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
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID

enum class RegistrationState {
    NETWORK_UNAVAILABLE,
    MCPTT_APN_BOUND,
    UNREGISTERED,
    REGISTERING,
    AUTHENTICATING,
    REGISTERED,
    REGISTRATION_FAILED;

    val isRegistered: Boolean get() = this == REGISTERED
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
    RELEASING,
    LISTENING
}

data class NegotiatedMedia(
    val host: String,
    val rtpPort: Int,
    val rtcpPort: Int = rtpPort + 1
)

data class IncomingChatMessage(
    val from: String,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class McpttSipStack {
    private val scope = CoroutineScope(Dispatchers.IO)

    private var sipSocket: DatagramSocket? = null
    private var listenJob: Job? = null

    private val _registrationState = MutableStateFlow(RegistrationState.UNREGISTERED)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _registrationFailureReason = MutableStateFlow<String?>(null)
    val registrationFailureReason: StateFlow<String?> = _registrationFailureReason.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionState.IDLE)
    val callState: StateFlow<CallSessionState> = _callState.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()

    private val _floorBusy = MutableStateFlow(false)
    val floorBusy: StateFlow<Boolean> = _floorBusy.asStateFlow()
    private var floorBusyJob: Job? = null

    fun triggerFloorBusy() {
        _floorBusy.value = true
        floorBusyJob?.cancel()
        floorBusyJob = scope.launch {
            delay(2500)
            _floorBusy.value = false
        }
    }

    var profile = SipProfile()
        private set

    var apnManager: McpttApnNetworkManager? = null
        private set

    private val _negotiatedMedia = MutableStateFlow<NegotiatedMedia?>(null)
    val negotiatedMedia: StateFlow<NegotiatedMedia?> = _negotiatedMedia.asStateFlow()

    private val _incomingMessages = MutableStateFlow<List<IncomingChatMessage>>(emptyList())
    val incomingMessages: StateFlow<List<IncomingChatMessage>> = _incomingMessages.asStateFlow()

    private val _localRtpPort = MutableStateFlow(profile.localRtpPort)
    val localRtpPort: StateFlow<Int> = _localRtpPort.asStateFlow()

    fun setLocalRtpPort(port: Int) {
        _localRtpPort.value = port
    }

    private var pendingFloorRequest = false

    private val _trafficLogs = MutableSharedFlow<SipTrafficLog>(replay = 50)
    val trafficLogs: SharedFlow<SipTrafficLog> = _trafficLogs.asSharedFlow()

    // SIP Dialog and Session State
    private var registerCallId = UUID.randomUUID().toString()
    private var registerFromTag = generateTag()
    private var registerCSeq = 1
    private var registerAuthAttempts = 0
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
    var onPacketSent: ((rawSip: String, destHost: String, destPort: Int) -> Unit)? = null

    val currentSocket: DatagramSocket?
        get() = sipSocket

    fun start(context: Context, initialProfile: SipProfile) {
        this.profile = initialProfile
        val netMgr = McpttApnNetworkManager(context.applicationContext, scope)
        this.apnManager = netMgr
        netMgr.updateConfig(initialProfile.apnName, initialProfile.apnPrefix)

        // Initialize ONE stable DatagramSocket for the lifetime of this stack
        initSocket()

        // Handle genuine network transitions without destroying the transport unless rebinding fails
        netMgr.onNetworkChanged = { newNet ->
            val socket = sipSocket
            if (socket != null && !socket.isClosed && newNet != null) {
                val sockId = System.identityHashCode(socket).toString(16)
                try {
                    newNet.bindSocket(socket)
                    Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$newNet")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not rebind socket id=$sockId to changed network $newNet (${e.message}), recreating socket")
                    recreateSocket()
                }
            }
        }

        netMgr.startMonitoring()

        // Observe cellular APN network status to update socket binding and state without recreating transport
        scope.launch {
            netMgr.networkStatus.collect { netStatus ->
                when (netStatus) {
                    is ApnNetworkStatus.Bound -> {
                        val socket = sipSocket
                        if (socket != null && !socket.isClosed) {
                            val sockId = System.identityHashCode(socket).toString(16)
                            netMgr.activeNetwork?.let { net ->
                                try {
                                    net.bindSocket(socket)
                                    Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$net")
                                } catch (e: Exception) {
                                    Log.w(TAG, "Could not bind existing socket id=$sockId to network $net: ${e.message}")
                                }
                            }
                        }
                        if (_registrationState.value == RegistrationState.UNREGISTERED ||
                            _registrationState.value == RegistrationState.NETWORK_UNAVAILABLE) {
                            _registrationState.value = RegistrationState.MCPTT_APN_BOUND
                        }
                    }
                    is ApnNetworkStatus.Disconnected -> {
                        if (_registrationState.value != RegistrationState.REGISTERED) {
                            _registrationState.value = RegistrationState.NETWORK_UNAVAILABLE
                        }
                    }
                    else -> {}
                }
            }
        }

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
            Log.i(TAG, "SIP local port changed to ${newProfile.localSipPort}, recreating socket")
            recreateSocket()
        }
    }

    fun stop() {
        listenJob?.cancel()
        try {
            sipSocket?.close()
        } catch (_: Exception) {}
        sipSocket = null
        apnManager?.stopMonitoring()
    }

    @Synchronized
    fun recreateSocket(): DatagramSocket {
        listenJob?.cancel()
        try {
            sipSocket?.close()
        } catch (_: Exception) {}
        sipSocket = null
        return initSocket()
    }

    @Synchronized
    fun initSocket(): DatagramSocket {
        val existing = sipSocket
        if (existing != null && !existing.isClosed && existing.localPort == profile.localSipPort) {
            val sockId = System.identityHashCode(existing).toString(16)
            apnManager?.activeNetwork?.let { net ->
                try {
                    net.bindSocket(existing)
                    Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$net")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bind existing socket id=$sockId to network $net: ${e.message}")
                }
            }
            return existing
        }

        try {
            listenJob?.cancel()
            existing?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous socket: ${e.message}")
        }

        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = false
        }
        val sockId = System.identityHashCode(socket).toString(16)
        Log.i(TAG, "SIP SOCKET CREATE id=$sockId")

        socket.bind(InetSocketAddress(profile.localSipPort))
        val localIp = socket.localAddress?.hostAddress ?: "0.0.0.0"
        Log.i(TAG, "SIP SOCKET BIND id=$sockId local=$localIp:${socket.localPort}")

        // Bind to active MCPTT network interface if available
        apnManager?.activeNetwork?.let { net ->
            try {
                net.bindSocket(socket)
                Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$net")
            } catch (e: Exception) {
                Log.w(TAG, "Could not bind socket id=$sockId to network $net: ${e.message}")
            }
        }

        sipSocket = socket
        startListening(socket)
        return socket
    }

    private fun startListening(socket: DatagramSocket) {
        listenJob?.cancel()
        val sockId = System.identityHashCode(socket).toString(16)
        listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            while (isActive && !socket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val bytesRead = packet.length
                    if (bytesRead <= 0) continue

                    val sourceIp = packet.address?.hostAddress ?: "unknown"
                    val sourcePort = packet.port

                    val rawSip = String(packet.data, 0, bytesRead, Charsets.UTF_8)
                    handleIncomingPacket(rawSip, sourceIp, sourcePort, socket)
                } catch (e: Exception) {
                    if (isActive && !socket.isClosed) {
                        Log.w(TAG, "Socket id=$sockId receive error: ${e.message}")
                    }
                }
            }
        }
    }

    fun processIncomingSipPacket(rawSip: String, remoteHost: String = profile.pcscfHost, remotePort: Int = profile.pcscfPort) {
        handleIncomingPacket(rawSip, remoteHost, remotePort, sipSocket)
    }

    private fun handleIncomingPacket(rawSip: String, remoteHost: String, remotePort: Int, socket: DatagramSocket? = sipSocket) {
        val sockId = socket?.let { System.identityHashCode(it).toString(16) } ?: "unknown"
        val msg = SipMessage.parse(rawSip)

        val methodOrStatus = if (msg.isResponse) "${msg.statusCode} ${msg.statusText}".trim() else msg.method
        Log.i(TAG, "SIP RX parsed: id=$sockId method/status=$methodOrStatus Call-ID=${msg.callId} CSeq=${msg.cseq}")

        logTraffic(rawSip, LogDirection.INBOUND, "$remoteHost:$remotePort")

        if (msg.isResponse) {
            handleResponse(msg, sockId)
        } else {
            handleRequest(msg)
        }
    }

    private fun handleResponse(msg: SipMessage, sockId: String) {
        val method = msg.method.ifEmpty {
            val cseq = msg.getHeader("cseq")
            cseq.trim().split(Regex("\\s+")).getOrNull(1)?.uppercase() ?: ""
        }

        if (msg.statusCode == 401 && (registerCallId.isEmpty() || msg.callId == registerCallId)) {
            Log.i(TAG, "SIP RX id=$sockId status=401 callId=${msg.callId} cseq=${msg.cseqNumber}")
            handleRegister401(msg, sockId)
            return
        }

        when (method) {
            "REGISTER" -> {
                when (msg.statusCode) {
                    200 -> {
                        _registrationState.value = RegistrationState.REGISTERED
                        _registrationFailureReason.value = null
                        registerAuthAttempts = 0
                        Log.i(TAG, "SIP RX id=$sockId status=200 callId=${msg.callId} cseq=${msg.cseqNumber}")
                        Log.i(TAG, "REGISTRATION STATE:\n  REGISTERED")
                    }
                    401 -> {
                        Log.i(TAG, "SIP RX id=$sockId status=401 callId=${msg.callId} cseq=${msg.cseqNumber}")
                        handleRegister401(msg, sockId)
                    }
                    else -> {
                        if (msg.statusCode >= 400) {
                            _registrationState.value = RegistrationState.REGISTRATION_FAILED
                            _registrationFailureReason.value = "SIP ${msg.statusCode} ${msg.statusText}"
                            registerAuthAttempts = 0
                            Log.e(TAG, "SIP REGISTER failed with status ${msg.statusCode} ${msg.statusText}")
                        }
                    }
                }
            }
            "INVITE" -> {
                when {
                    msg.statusCode == 200 || msg.statusCode == 202 -> {
                        _callState.value = CallSessionState.CONNECTED
                        Log.i(TAG, "SIP INVITE ${msg.statusCode} ${msg.statusText} - Call established")

                        // Extract dialog state
                        activeCallToTag = extractTag(msg.to)
                        activeRemoteTargetUri = msg.extractContactUri().ifEmpty {
                            profile.asFallbackUri.ifEmpty { profile.targetGroup }
                        }
                        activeRouteSet = msg.extractUacRouteSet()

                        // Extract SDP media information
                        applySdpAnswer(msg.body)

                        // Send ACK
                        sendAck(msg)

                        if (pendingFloorRequest) {
                            pendingFloorRequest = false
                            sendFloorControlMessage("floor-request")
                        } else if (profile.autoGrantFloor) {
                            _floorState.value = FloorState.GRANTED
                            _activeSpeaker.value = profile.displayName
                            onFloorGranted?.invoke()
                        }
                    }
                    msg.statusCode in 100..199 -> {
                        if (msg.body.contains("m=audio")) {
                            applySdpAnswer(msg.body)
                        }
                        _callState.value = CallSessionState.CALLING
                        Log.i(TAG, "SIP INVITE ${msg.statusCode} ${msg.statusText} (Session Progress)")
                    }
                    else -> {
                        if (msg.statusCode >= 400) {
                            _callState.value = CallSessionState.IDLE
                            _floorState.value = FloorState.IDLE
                            _activeSpeaker.value = null
                            _negotiatedMedia.value = null
                            pendingFloorRequest = false
                            Log.e(TAG, "SIP INVITE failed with status ${msg.statusCode} ${msg.statusText}")
                        }
                    }
                }
            }
            "INFO" -> {
                Log.d(TAG, "In-dialog INFO response: ${msg.statusCode} ${msg.statusText}")
                applyFloorFromBody(msg)
            }
            "BYE" -> {
                _callState.value = CallSessionState.IDLE
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                _negotiatedMedia.value = null
                pendingFloorRequest = false
                resetDialogState()
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

                applySdpAnswer(msg.body)
                send200OkForInvite(msg)
            }
            "INFO" -> {
                // Floor Control Action from AS
                Log.i(TAG, "Received in-dialog INFO: floorAction=${msg.floorControlState}, body=${msg.body}")
                sendResponse(200, "OK", msg)
                applyFloorFromBody(msg)
            }
            "BYE" -> {
                Log.i(TAG, "Received BYE - Call terminated by remote")
                sendResponse(200, "OK", msg)
                _callState.value = CallSessionState.IDLE
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                _negotiatedMedia.value = null
                pendingFloorRequest = false
                resetDialogState()
            }
            "MESSAGE" -> {
                Log.i(TAG, "Received SIP MESSAGE from ${msg.from}: ${msg.body}")
                sendResponse(200, "OK", msg)
                val sender = msg.from.substringAfter("sip:").substringBefore("@").ifBlank { msg.from }
                val chat = IncomingChatMessage(
                    from = sender,
                    text = msg.body.trim()
                )
                _incomingMessages.value = _incomingMessages.value + chat
            }
        }
    }

    private fun applyFloorFromBody(msg: SipMessage) {
        val speaker = extractSpeakerFromInfo(msg.body)
        when (msg.floorControlState) {
            "GRANTED" -> {
                val isSelf = isSpeakerSelf(speaker)
                if (isSelf || (_floorState.value == FloorState.REQUESTING && speaker == null)) {
                    _floorState.value = FloorState.GRANTED
                    _activeSpeaker.value = profile.displayName.ifBlank { profile.mcpttId }
                    onFloorGranted?.invoke()
                    Log.i(TAG, "Floor GRANTED to self")
                } else {
                    _floorState.value = FloorState.LISTENING
                    _activeSpeaker.value = formatSpeakerDisplay(speaker) ?: "Remote User"
                    Log.i(TAG, "Floor GRANTED to remote user: ${_activeSpeaker.value}")
                }
            }
            "TAKEN" -> {
                val formattedSpeaker = formatSpeakerDisplay(speaker) ?: "Remote User"
                if (_floorState.value == FloorState.REQUESTING) {
                    Log.w(TAG, "Floor request denied: floor taken by $formattedSpeaker")
                    triggerFloorBusy()
                    _floorState.value = FloorState.LISTENING
                    _activeSpeaker.value = formattedSpeaker
                } else if (_floorState.value != FloorState.GRANTED) {
                    _floorState.value = FloorState.LISTENING
                    _activeSpeaker.value = formattedSpeaker
                    Log.i(TAG, "Floor TAKEN by ${_activeSpeaker.value} (now LISTENING)")
                }
            }
            "DENIED" -> {
                Log.w(TAG, "Floor request explicitly DENIED")
                triggerFloorBusy()
                _floorState.value = if (_activeSpeaker.value != null) FloorState.LISTENING else FloorState.IDLE
            }
            "IDLE", "RELEASE" -> {
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                Log.i(TAG, "Floor returned to IDLE")
            }
        }
    }

    private fun applySdpAnswer(sdpBody: String) {
        var text = sdpBody
        val v0 = text.indexOf("v=0")
        if (v0 > 0) {
            text = text.substring(v0)
        }
        if (text.isBlank() || !text.contains("m=audio")) return
        var mediaIp: String? = null
        var rtpPort: Int? = null
        var rtcpPort: Int? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("c=IN IP4 ", ignoreCase = true) -> {
                    mediaIp = line.substringAfter("c=IN IP4 ").trim().split(Regex("""\s+""")).firstOrNull()
                }
                line.startsWith("m=audio ", ignoreCase = true) -> {
                    val parts = line.substringAfter("m=audio ").trim().split(Regex("""\s+"""))
                    rtpPort = parts.firstOrNull()?.toIntOrNull()
                }
                line.startsWith("a=rtcp:", ignoreCase = true) -> {
                    val portPart = line.substringAfter("a=rtcp:").trim().split(Regex("""\s+""")).firstOrNull()
                    rtcpPort = portPart?.toIntOrNull()
                }
            }
        }

        val host = mediaIp ?: profile.pcscfHost
        val port = rtpPort ?: profile.localRtpPort
        val media = NegotiatedMedia(host, port, rtcpPort ?: (port + 1))
        _negotiatedMedia.value = media
        remoteMediaIp = host
        remoteMediaPort = port
        Log.i(TAG, "Negotiated media: $media")
        onMediaNegotiated?.invoke(host, port)
    }

    fun register() {
        registerAuthAttempts = 0
        _registrationFailureReason.value = null
        _registrationState.value = RegistrationState.REGISTERING
        registerCallId = "reg-" + UUID.randomUUID().toString().replace("-", "").take(12) + "@" + profile.realm
        registerFromTag = "reg-" + UUID.randomUUID().toString().replace("-", "").take(8)
        registerCSeq = 1

        Log.i(TAG, "REGISTER SEND:\n  callId=$registerCallId\n  cseq=$registerCSeq")
        sendRegisterPacket(registerCSeq, authHeader = null)
    }

    private fun sendRegisterPacket(cseq: Int, authHeader: String?) {
        val sockId = sipSocket?.let { System.identityHashCode(it).toString(16) } ?: "unknown"
        if (!authHeader.isNullOrBlank()) {
            Log.i(TAG, "SIP TX id=$sockId method=REGISTER cseq=$cseq authorization=true")
        } else {
            Log.i(TAG, "SIP TX id=$sockId method=REGISTER cseq=$cseq")
        }

        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().replace("-", "").take(12)
        val uri = "sip:${profile.realm}"

        val mcpttContactParam = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val acceptContact = if (profile.includeMcpttTags) {
            "Accept-Contact: *;+g.3gpp.mcptt;explicit;require\r\n"
        } else ""

        val authLine = if (!authHeader.isNullOrBlank()) {
            "Authorization: $authHeader\r\n"
        } else ""

        val sipPacket = buildString {
            append("REGISTER $uri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            // RFC 3261: Retain exact same From tag and Call-ID on 401 challenge re-attempt
            append("From: <${profile.mcpttId}>;tag=$registerFromTag\r\n")
            append("To: <${profile.mcpttId}>\r\n")
            append("Call-ID: $registerCallId\r\n")
            append("CSeq: $cseq REGISTER\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append("Expires: 3600\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append(acceptContact)
            if (authLine.isNotEmpty()) {
                append(authLine)
            }
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    private fun handleRegister401(msg: SipMessage, sockId: String) {
        val viaHeader = msg.getHeader("via")

        // Guard against repeated 401 infinite loop
        if (registerAuthAttempts >= 1) {
            Log.e(TAG, "Registration failed: Repeated 401 challenge received after sending credentials. Stopping retry loop to prevent packet flood.")
            _registrationState.value = RegistrationState.REGISTRATION_FAILED
            _registrationFailureReason.value = "Authentication failed: 401 Unauthorized (credentials rejected)"
            return
        }
        registerAuthAttempts++

        val authHeaders = msg.getHeaders("www-authenticate").ifEmpty { msg.getHeaders("proxy-authenticate") }
        val authHeader = if (authHeaders.isNotEmpty()) authHeaders.joinToString(", ") else msg.getHeader("www-authenticate").ifEmpty { msg.getHeader("proxy-authenticate") }
        if (authHeader.isBlank()) {
            Log.e(TAG, "Registration failed: 401 response missing WWW-Authenticate header")
            _registrationState.value = RegistrationState.REGISTRATION_FAILED
            _registrationFailureReason.value = "401 missing WWW-Authenticate"
            return
        }

        val realm = SipAuthHelper.extractAuthParam(authHeader, "realm").ifEmpty { profile.realm }
        val nonce = SipAuthHelper.extractAuthParam(authHeader, "nonce")
        val rawQop = SipAuthHelper.extractAuthParam(authHeader, "qop")
        val qop = SipAuthHelper.selectQop(rawQop).ifEmpty { "auth" }
        val opaque = SipAuthHelper.extractAuthParam(authHeader, "opaque")

        if (nonce.isEmpty()) {
            Log.e(TAG, "Registration failed: 401 challenge missing nonce")
            _registrationState.value = RegistrationState.REGISTRATION_FAILED
            _registrationFailureReason.value = "401 challenge missing nonce"
            return
        }

        Log.i(TAG, "DIGEST AUTH id=$sockId realm=$realm noncePresent=${nonce.isNotBlank()}")

        // Display AUTHENTICATING (401 MD5) in UI
        _registrationState.value = RegistrationState.AUTHENTICATING

        // RFC 3261: Increment CSeq for the authenticated REGISTER (1 -> 2)
        registerCSeq++

        val uri = "sip:${profile.realm}"
        val authUsername = if (profile.imsi.contains("@")) profile.imsi else "${profile.imsi}@$realm"

        val authHeaderValue = SipAuthHelper.buildAuthorizationHeader(
            username = authUsername,
            realm = realm,
            password = profile.password,
            method = "REGISTER",
            uri = uri,
            nonce = nonce,
            rawQop = qop,
            opaque = opaque,
            nc = "00000001"
        )

        sendRegisterPacket(registerCSeq, authHeader = authHeaderValue)
        _registrationState.value = RegistrationState.REGISTERING
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

        val rtpPortToOffer = _localRtpPort.value
        val sdpBody = buildString {
            append("v=0\r\n")
            append("o=${profile.imsi} 10001 10001 IN IP4 $localIp\r\n")
            append("s=MCPTT Session\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio $rtpPortToOffer RTP/AVP 0 8 101\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=rtpmap:8 PCMA/8000\r\n")
            append("a=rtpmap:101 telephone-event/8000\r\n")
            append("a=sendrecv\r\n")
            append("a=mcptt\r\n")
        }

        val routeHeader = if (profile.scscfOrigRoute.isNotBlank()) {
            "Route: <${profile.scscfOrigRoute.trim()}>\r\n"
        } else ""

        val sipPacket = buildString {
            append("INVITE $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            if (routeHeader.isNotEmpty()) {
                append(routeHeader)
            }
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <$targetUri>\r\n")
            append("Call-ID: $activeCallId\r\n")
            append("CSeq: $dialogCSeq INVITE\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>$mcpttContactParam\r\n")
            append(acceptContact)
            append("P-Preferred-Identity: <${profile.mcpttId}>\r\n")
            append("P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=2089300000001\r\n")
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
        val rtpPortToOffer = _localRtpPort.value
        val sdpBody = buildString {
            append("v=0\r\n")
            append("o=${profile.imsi} 2000 2000 IN IP4 $localIp\r\n")
            append("s=MCPTT Session\r\n")
            append("c=IN IP4 $localIp\r\n")
            append("t=0 0\r\n")
            append("m=audio $rtpPortToOffer RTP/AVP 0 8 101\r\n")
            append("a=rtpmap:0 PCMU/8000\r\n")
            append("a=rtpmap:8 PCMA/8000\r\n")
            append("a=rtpmap:101 telephone-event/8000\r\n")
            append("a=sendrecv\r\n")
            append("a=mcptt\r\n")
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
        if (_floorState.value == FloorState.REQUESTING ||
            _floorState.value == FloorState.GRANTED ||
            _floorState.value == FloorState.RELEASING) {
            Log.w(TAG, "requestFloor ignored: already in state ${_floorState.value}")
            return
        }
        if (_floorState.value == FloorState.LISTENING) {
            Log.w(TAG, "requestFloor rejected: floor is currently held by ${_activeSpeaker.value}")
            triggerFloorBusy()
            return
        }

        _floorState.value = FloorState.REQUESTING
        _activeSpeaker.value = profile.displayName.ifBlank { profile.mcpttId }

        if (_callState.value != CallSessionState.CONNECTED) {
            pendingFloorRequest = true
            initiateMcpttCall()
            return
        }

        sendFloorControlMessage("floor-request")
    }

    fun releaseFloor() {
        pendingFloorRequest = false
        val wasTransmitting = _floorState.value == FloorState.GRANTED || _floorState.value == FloorState.REQUESTING
        _floorState.value = FloorState.RELEASING
        if (_callState.value == CallSessionState.CONNECTED && wasTransmitting) {
            sendFloorControlMessage("floor-release")
        }
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
    }

    fun sendFloorControlMessage(action: String) {
        val callId = activeCallId ?: return
        val localIp = getLocalIpAddress()
        val branch = "z9hG4bK-" + UUID.randomUUID().toString().take(12)
        val targetUri = activeRemoteTargetUri ?: profile.targetGroup
        dialogCSeq++

        val routeHeaders = buildRouteHeader(activeRouteSet)
        val body = "Action=$action\r\n"
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val toTag = activeCallToTag

        val sipPacket = buildString {
            append("INFO $targetUri SIP/2.0\r\n")
            append("Via: SIP/2.0/UDP $localIp:${profile.localSipPort};branch=$branch;rport\r\n")
            append("Max-Forwards: 70\r\n")
            if (routeHeaders.isNotEmpty()) {
                append(routeHeaders)
            }
            append("From: <${profile.mcpttId}>;tag=$activeCallFromTag\r\n")
            append("To: <${profile.targetGroup}>${if (!toTag.isNullOrEmpty()) ";tag=$toTag" else ""}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $dialogCSeq INFO\r\n")
            append("Contact: <sip:${profile.imsi}@$localIp:${profile.localSipPort}>${if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""}\r\n")
            append("Accept-Contact: *;+g.3gpp.mcptt;explicit;require\r\n")
            append("P-Preferred-Identity: <${profile.mcpttId}>\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Type: text/plain\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n\r\n")
            append(body)
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
    }

    fun sendInDialogFloorAction(actionName: String) {
        sendFloorControlMessage(actionName)
    }

    fun endCall() {
        pendingFloorRequest = false
        val callId = activeCallId ?: return
        _callState.value = CallSessionState.DISCONNECTING

        if (_floorState.value == FloorState.GRANTED || _floorState.value == FloorState.REQUESTING) {
            try {
                sendFloorControlMessage("floor-release")
            } catch (e: Exception) {
                Log.w(TAG, "Best effort floor release on endCall failed: ${e.message}")
            }
        }

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
            append("To: <${profile.targetGroup}>${if (!activeCallToTag.isNullOrEmpty()) ";tag=$activeCallToTag" else ""}\r\n")
            append("Call-ID: $callId\r\n")
            append("CSeq: $dialogCSeq BYE\r\n")
            append("User-Agent: ${profile.userAgent}\r\n")
            append("Content-Length: 0\r\n\r\n")
        }

        sendRawSip(sipPacket, profile.pcscfHost, profile.pcscfPort)
        _callState.value = CallSessionState.IDLE
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
        _negotiatedMedia.value = null
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
        onPacketSent?.invoke(rawSip, destHost, destPort)

        if (sipSocket == null || sipSocket?.isClosed == true) {
            try {
                initSocket()
            } catch (e: Exception) {
                Log.w(TAG, "Lazy socket initialization in sendRawSip failed: ${e.message}")
            }
        }

        val socket = sipSocket
        if (socket == null || socket.isClosed) {
            Log.e(TAG, "Cannot send SIP: SIP socket unavailable")
            return
        }
        val sockId = System.identityHashCode(socket).toString(16)

        scope.launch(Dispatchers.IO) {
            try {
                val bytes = rawSip.toByteArray(Charsets.UTF_8)
                val targetAddr = InetAddress.getByName(destHost)
                val packet = DatagramPacket(bytes, bytes.size, targetAddr, destPort)
                socket.send(packet)
                logTraffic(rawSip, LogDirection.OUTBOUND, "$destHost:$destPort")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send SIP datagram id=$sockId to $destHost:$destPort: ${e.message}", e)
            }
        }
    }

    fun injectSimulatedPacket(rawSip: String) {
        scope.launch {
            handleIncomingPacket(rawSip, "127.0.0.1", profile.pcscfPort, sipSocket)
        }
    }

    private fun logTraffic(rawText: String, direction: LogDirection, endpoint: String) {
        val lines = rawText.lines()
        val firstLine = lines.firstOrNull()?.trim() ?: "UNKNOWN"
        val isMcptt = rawText.contains("mcptt", ignoreCase = true)
        val hasErr = rawText.startsWith("SIP/2.0 4") || rawText.startsWith("SIP/2.0 5") || rawText.startsWith("SIP/2.0 6")

        val hasAuth = rawText.contains("Authorization:", ignoreCase = true)
        val methodOrResponse = when {
            firstLine.startsWith("SIP/2.0 401", ignoreCase = true) -> "401"
            firstLine.startsWith("SIP/2.0 200", ignoreCase = true) -> "200 OK"
            firstLine.startsWith("SIP/2.0", ignoreCase = true) -> {
                val parts = firstLine.split(" ", limit = 3)
                if (parts.size >= 2) parts[1] else "SIP"
            }
            firstLine.startsWith("REGISTER", ignoreCase = true) && hasAuth -> "REGISTER + Authorization"
            else -> firstLine.split(" ").firstOrNull() ?: "SIP"
        }
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

    fun getLocalIpAddress(): String {
        val netIp = apnManager?.boundIp
        if (!netIp.isNullOrBlank() && netIp != "0.0.0.0") {
            return netIp
        }
        val socketIp = sipSocket?.localAddress?.hostAddress
        if (!socketIp.isNullOrBlank() && socketIp != "0.0.0.0") {
            return socketIp
        }
        return "192.168.102.6"
    }

    private fun resetDialogState() {
        activeCallId = null
        activeCallFromTag = null
        activeCallToTag = null
        activeRemoteTargetUri = null
        activeRouteSet = emptyList()
        remoteMediaIp = null
        remoteMediaPort = null
        _negotiatedMedia.value = null
        pendingFloorRequest = false
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
            val trimmed = line.trim()
            if (trimmed.startsWith("speaker=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim()
            }
            if (trimmed.startsWith("user=", ignoreCase = true)) {
                return trimmed.substringAfter("=").trim()
            }
        }
        return null
    }

    private fun isSpeakerSelf(speaker: String?): Boolean {
        if (speaker.isNullOrBlank()) return false
        val clean = speaker.trim().removeSurrounding("<", ">")
        val user = clean.substringAfter("sip:").substringBefore("@")
        val myUser = profile.imsi.substringAfter("sip:").substringBefore("@")
        val myMcptt = profile.mcpttId.substringAfter("sip:").substringBefore("@")
        return user == myUser || user == myMcptt || clean == profile.mcpttId || clean == profile.imsi
    }

    private fun formatSpeakerDisplay(speaker: String?): String? {
        if (speaker.isNullOrBlank()) return null
        val clean = speaker.trim().removeSurrounding("<", ">")
        val user = clean.substringAfter("sip:").substringBefore("@")
        return user.ifBlank { clean }
    }

    private fun md5Hex(input: String): String {
        return SipAuthHelper.md5Hex(input)
    }

    companion object {
        private const val TAG = "McpttSipStack"
    }
}
