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
    TAKEN
}

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

    fun start(context: Context, initialProfile: SipProfile) {
        this.profile = initialProfile
        val netMgr = McpttApnNetworkManager(context.applicationContext, scope)
        this.apnManager = netMgr
        netMgr.updateConfig(initialProfile.apnName, initialProfile.apnPrefix)
        netMgr.startMonitoring()

        initSocket()

        // Observe cellular APN network status to update socket binding and state
        scope.launch {
            netMgr.networkStatus.collect { netStatus ->
                when (netStatus) {
                    is ApnNetworkStatus.Bound -> {
                        val currentLocalAddr = sipSocket?.localAddress?.hostAddress
                        if (currentLocalAddr != netStatus.ip) {
                            Log.i(TAG, "APN network bound to ${netStatus.ip}. Updating SIP socket binding to match ${netStatus.ip}:${profile.localSipPort}")
                            initSocket(netStatus.ip)
                        } else {
                            sipSocket?.let { netMgr.bindSocket(it) }
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
            initSocket()
        }
    }

    private fun initSocket(targetIp: String? = null) {
        try {
            listenJob?.cancel()
            sipSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous socket: ${e.message}")
        }

        try {
            val socket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = false
            }

            // Pre-bind socket to cellular network interface if active
            apnManager?.bindSocket(socket)

            val localIp = targetIp ?: getLocalIpAddress()
            val bindAddr = try {
                if (localIp.isNotBlank() && localIp != "0.0.0.0") {
                    InetAddress.getByName(localIp)
                } else null
            } catch (e: Exception) {
                null
            }

            var boundDirectly = false
            if (bindAddr != null) {
                try {
                    val socketAddress = InetSocketAddress(bindAddr, profile.localSipPort)
                    socket.bind(socketAddress)
                    boundDirectly = true
                    Log.i(TAG, "SIP Stack bound directly to local IP: $localIp:${profile.localSipPort}")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bind directly to $localIp:${profile.localSipPort} (${e.message}), binding to wildcard port")
                }
            }

            if (!boundDirectly) {
                socket.bind(InetSocketAddress(profile.localSipPort))
                Log.i(TAG, "SIP Stack bound to wildcard UDP port ${profile.localSipPort}")
            }

            // Bind to cellular network interface
            apnManager?.bindSocket(socket)
            sipSocket = socket

            Log.i(TAG, "SIP Stack ready on ${socket.localAddress?.hostAddress}:${socket.localPort} (MCPTT net bound: ${apnManager?.activeNetwork != null})")
            startListening()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize SIP socket on port ${profile.localSipPort}: ${e.message}", e)
        }
    }

    private fun startListening() {
        listenJob?.cancel()
        listenJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            while (isActive) {
                try {
                    val socket = sipSocket
                    if (socket == null || socket.isClosed) {
                        delay(200)
                        continue
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val bytesRead = packet.length
                    if (bytesRead <= 0) continue

                    val sourceIp = packet.address?.hostAddress ?: "unknown"
                    val sourcePort = packet.port

                    Log.i(TAG, "SIP RX UDP: source=$sourceIp:$sourcePort bytes=$bytesRead")

                    val rawSip = String(packet.data, 0, bytesRead, Charsets.UTF_8)
                    handleIncomingPacket(rawSip, sourceIp, sourcePort)
                } catch (e: Exception) {
                    if (isActive && sipSocket?.isClosed == false) {
                        Log.w(TAG, "Socket receive error: ${e.message}")
                    }
                }
            }
        }
    }

    fun processIncomingSipPacket(rawSip: String, remoteHost: String = profile.pcscfHost, remotePort: Int = profile.pcscfPort) {
        handleIncomingPacket(rawSip, remoteHost, remotePort)
    }

    private fun handleIncomingPacket(rawSip: String, remoteHost: String, remotePort: Int) {
        val msg = SipMessage.parse(rawSip)

        val methodOrStatus = if (msg.isResponse) "${msg.statusCode} ${msg.statusText}".trim() else msg.method
        Log.i(TAG, "SIP RX parsed: method/status=$methodOrStatus Call-ID=${msg.callId} CSeq=${msg.cseq}")

        logTraffic(rawSip, LogDirection.INBOUND, "$remoteHost:$remotePort")

        if (msg.isResponse) {
            handleResponse(msg)
        } else {
            handleRequest(msg)
        }
    }

    private fun handleResponse(msg: SipMessage) {
        val method = msg.method.ifEmpty {
            val cseq = msg.getHeader("cseq")
            cseq.trim().split(Regex("\\s+")).getOrNull(1)?.uppercase() ?: ""
        }

        if (msg.statusCode == 401 && (registerCallId.isEmpty() || msg.callId == registerCallId)) {
            handleRegister401(msg)
            return
        }

        when (method) {
            "REGISTER" -> {
                when (msg.statusCode) {
                    200 -> {
                        _registrationState.value = RegistrationState.REGISTERED
                        _registrationFailureReason.value = null
                        registerAuthAttempts = 0
                        Log.i(TAG, "SIP RX:\n  status=200\n  callId=${msg.callId}\n  cseq=${msg.cseq}")
                        Log.i(TAG, "REGISTRATION STATE:\n  REGISTERED")
                    }
                    401 -> {
                        handleRegister401(msg)
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
                when (msg.statusCode) {
                    200 -> {
                        _callState.value = CallSessionState.CONNECTED
                        Log.i(TAG, "SIP INVITE 200 OK - Call established")

                        // Extract dialog state
                        activeCallToTag = extractTag(msg.to)
                        activeRemoteTargetUri = msg.extractContactUri().ifEmpty {
                            profile.asFallbackUri.ifEmpty { profile.targetGroup }
                        }
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

    private fun handleRegister401(msg: SipMessage) {
        val viaHeader = msg.getHeader("via")
        Log.i(TAG, "SIP RX:\n  status=401\n  callId=${msg.callId}\n  cseq=${msg.cseq}\n  source=$viaHeader")

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

        Log.i(TAG, "DIGEST CHALLENGE:\n  realm=$realm\n  noncePresent=${nonce.isNotEmpty()}\n  qop=$qop\n  algorithm=MD5")

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

        Log.i(TAG, "AUTHENTICATED REGISTER SEND:\n  callId=$registerCallId\n  cseq=$registerCSeq\n  authorizationPresent=true")
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
        onPacketSent?.invoke(rawSip, destHost, destPort)
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

    private fun md5Hex(input: String): String {
        return SipAuthHelper.md5Hex(input)
    }

    companion object {
        private const val TAG = "McpttSipStack"
    }
}
