package com.example.sip.engine

import android.content.Context
import android.net.Network
import android.util.Log
import com.example.sip.discovery.DefaultPcscfDiscoveryProvider
import com.example.sip.discovery.PcscfDiscoveryProvider
import com.example.sip.discovery.PcscfDiscoverySource
import com.example.sip.discovery.PcscfDiscoveryState
import com.example.sip.discovery.PcscfEndpoint
import com.example.sip.discovery.SelectedPcscf
import com.example.sip.model.LogDirection
import com.example.sip.model.LogType
import com.example.sip.model.SipMessage
import com.example.sip.model.SipProfile
import com.example.sip.model.SipTrafficLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.UUID

class McpttSipStack(
    private val externalScope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "McpttSipStack"
    }

    private val scope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var profile: SipProfile = SipProfile()
        private set

    var apnManager: McpttApnNetworkManager? = null
        private set

    private var sipSocket: DatagramSocket? = null
    private var listeningJob: kotlinx.coroutines.Job? = null

    private val _registrationState = MutableStateFlow(RegistrationState.UNREGISTERED)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _registrationFailureReason = MutableStateFlow<String?>(null)
    val registrationFailureReason: StateFlow<String?> = _registrationFailureReason.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionState.IDLE)
    val callState: StateFlow<CallSessionState> = _callState.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _floorBusy = MutableStateFlow(false)
    val floorBusy: StateFlow<Boolean> = _floorBusy.asStateFlow()

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()

    private val _trafficLogs = MutableSharedFlow<SipTrafficLog>(replay = 50)
    val trafficLogs: SharedFlow<SipTrafficLog> = _trafficLogs.asSharedFlow()

    // Dialog state for active MCPTT call / session
    private var activeCallId: String? = null
    private var activeCallFromTag: String? = null
    private var activeCallToTag: String? = null
    private var activeRemoteTargetUri: String? = null
    private var activeRouteSet: List<String> = emptyList()
    private var dialogCSeq: Int = 100

    // Remote SDP audio endpoint
    private var remoteMediaIp: String? = null
    private var remoteMediaPort: Int? = null

    // Registration transaction state
    private var registerCallId: String = ""
    private var registerFromTag: String = ""
    private var registerCSeq: Int = 1
    private var registerAuthAttempts = 0

    // P-CSCF Discovery provider abstraction
    val pcscfDiscoveryProvider: PcscfDiscoveryProvider = DefaultPcscfDiscoveryProvider()
    val selectedPcscf: StateFlow<SelectedPcscf?> = pcscfDiscoveryProvider.selectedPcscf
    val pcscfDiscoveryState: StateFlow<PcscfDiscoveryState> = pcscfDiscoveryProvider.discoveryState

    /**
     * Authoritative P-CSCF host for initial/out-of-dialog IMS requests.
     */
    val currentPcscfHost: String
        get() = selectedPcscf.value?.host ?: profile.pcscfHost

    /**
     * Authoritative P-CSCF port for initial/out-of-dialog IMS requests.
     */
    val currentPcscfPort: Int
        get() = selectedPcscf.value?.port ?: profile.pcscfPort

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

    var onMediaNegotiated: ((remoteIp: String, remotePort: Int) -> Unit)? = null
    var onFloorGranted: (() -> Unit)? = null
    var onPacketSent: ((rawSip: String, destHost: String, destPort: Int) -> Unit)? = null

    val currentSocket: DatagramSocket?
        get() = sipSocket

    fun triggerFloorBusy() {
        scope.launch {
            _floorBusy.value = true
            delay(1500)
            _floorBusy.value = false
        }
    }

    fun injectSimulatedPacket(rawSip: String) {
        handleIncomingPacket(rawSip, profile.pcscfHost, profile.pcscfPort, sipSocket)
    }

    fun start(context: Context, initialProfile: SipProfile) {
        this.profile = initialProfile

        // Seed initial P-CSCF state with legacy static fallback immediately
        pcscfDiscoveryProvider.selectManualOverride(
            PcscfEndpoint(
                host = initialProfile.pcscfHost,
                port = initialProfile.pcscfPort,
                transport = initialProfile.transport,
                source = PcscfDiscoverySource.STATIC_LEGACY
            )
        )

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

        // Observe cellular APN network status to update socket binding and trigger acquisition
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

                        // Trigger P-CSCF acquisition over the bound cellular network
                        scope.launch {
                            val legacyFallback = PcscfEndpoint(
                                host = profile.pcscfHost,
                                port = profile.pcscfPort,
                                transport = profile.transport,
                                source = PcscfDiscoverySource.STATIC_LEGACY
                            )
                            pcscfDiscoveryProvider.discover(
                                network = netMgr.activeNetwork,
                                explicitPcscfFqdn = profile.pcscfFqdn.ifBlank { null },
                                legacyFallback = legacyFallback
                            )
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
                // Wait briefly for network / acquisition or proceed with fallback
                delay(1200)
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

        // Re-run acquisition with updated legacy fallback / FQDN
        scope.launch {
            val legacyFallback = PcscfEndpoint(
                host = newProfile.pcscfHost,
                port = newProfile.pcscfPort,
                transport = newProfile.transport,
                source = PcscfDiscoverySource.STATIC_LEGACY
            )
            pcscfDiscoveryProvider.discover(
                network = apnManager?.activeNetwork,
                explicitPcscfFqdn = newProfile.pcscfFqdn.ifBlank { null },
                legacyFallback = legacyFallback
            )
        }
    }

    fun setRegistrationStateForTest(state: RegistrationState) {
        _registrationState.value = state
    }

    fun setCallStateForTest(state: CallSessionState) {
        _callState.value = state
    }

    fun setFloorStateForTest(state: FloorState, speaker: String? = null) {
        _floorState.value = state
        _activeSpeaker.value = speaker
    }

    fun applyFloorFromBodyForTest(msg: SipMessage) {
        applyFloorFromBody(msg)
    }

    fun stop() {
        listeningJob?.cancel()
        listeningJob = null
        try {
            sipSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing socket on stop: ${e.message}")
        }
        sipSocket = null
        apnManager?.stopMonitoring()
    }

    @Synchronized
    fun initSocket(): DatagramSocket {
        if (sipSocket != null && !sipSocket!!.isClosed) {
            return sipSocket!!
        }
        try {
            val port = profile.localSipPort
            val socket = DatagramSocket(null)
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            sipSocket = socket

            val sockId = System.identityHashCode(socket).toString(16)
            Log.i(TAG, "SIP SOCKET CREATED id=$sockId localPort=$port")

            apnManager?.activeNetwork?.let { net ->
                try {
                    net.bindSocket(socket)
                    Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$net")
                } catch (e: Exception) {
                    Log.w(TAG, "Initial network bind failed for socket id=$sockId: ${e.message}")
                }
            }

            startListening(socket)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create/bind SIP DatagramSocket on port ${profile.localSipPort}: ${e.message}")
            try {
                val fallbackSocket = DatagramSocket()
                sipSocket = fallbackSocket
                val sockId = System.identityHashCode(fallbackSocket).toString(16)
                Log.i(TAG, "SIP SOCKET CREATED (dynamic fallback) id=$sockId port=${fallbackSocket.localPort}")
                apnManager?.activeNetwork?.let { net ->
                    try {
                        net.bindSocket(fallbackSocket)
                        Log.i(TAG, "SIP SOCKET NETWORK BIND id=$sockId network=$net")
                    } catch (be: Exception) {
                        Log.w(TAG, "Network bind failed for dynamic socket id=$sockId: ${be.message}")
                    }
                }
                startListening(fallbackSocket)
            } catch (fe: Exception) {
                Log.e(TAG, "Critical: Could not initialize SIP socket: ${fe.message}")
                throw fe
            }
        }
        return sipSocket!!
    }

    @Synchronized
    private fun recreateSocket() {
        listeningJob?.cancel()
        listeningJob = null
        try {
            sipSocket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing previous socket during recreate: ${e.message}")
        }
        sipSocket = null
        initSocket()
    }

    private fun startListening(socket: DatagramSocket) {
        val sockId = System.identityHashCode(socket).toString(16)
        listeningJob?.cancel()
        listeningJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(65535)
            Log.i(TAG, "SIP listener started on socket id=$sockId localPort=${socket.localPort}")
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

        val logType = determineLogType(msg)
        logTraffic(rawSip, LogDirection.INBOUND, "$remoteHost:$remotePort", logType, methodOrStatus)

        if (msg.isResponse) {
            handleResponse(msg, sockId)
        } else {
            handleRequest(msg, remoteHost, remotePort)
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

                        // Extract RFC 3261 dialog state: remote target Contact URI and Route set
                        activeCallToTag = extractTag(msg.to)
                        activeRemoteTargetUri = msg.extractContactUri().ifEmpty {
                            profile.asFallbackUri.ifEmpty { profile.targetGroup }
                        }
                        activeRouteSet = msg.extractUacRouteSet()

                        // Extract SDP media information
                        val sdpText = if (msg.body.contains("m=audio")) msg.body else msg.rawText
                        applySdpAnswer(sdpText)

                        // Send ACK following dialog state
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
                        val sdpText = if (msg.body.contains("m=audio")) msg.body else if (msg.rawText.contains("m=audio")) msg.rawText else ""
                        if (sdpText.isNotEmpty()) {
                            applySdpAnswer(sdpText)
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

    private fun handleRequest(msg: SipMessage, packetSourceHost: String, packetSourcePort: Int) {
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

                val sdpText = if (msg.body.contains("m=audio")) msg.body else msg.rawText
                applySdpAnswer(sdpText)
                send200OkForInvite(msg, packetSourceHost, packetSourcePort)
            }
            "INFO" -> {
                // Floor Control Action from AS
                Log.i(TAG, "Received in-dialog INFO: floorAction=${msg.floorControlState}, body=${msg.body}")
                sendResponse(200, "OK", msg, packetSourceHost, packetSourcePort)
                applyFloorFromBody(msg)
            }
            "BYE" -> {
                Log.i(TAG, "Received BYE - Call terminated by remote")
                sendResponse(200, "OK", msg, packetSourceHost, packetSourcePort)
                _callState.value = CallSessionState.IDLE
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                _negotiatedMedia.value = null
                pendingFloorRequest = false
                resetDialogState()
            }
            "MESSAGE" -> {
                Log.i(TAG, "Received SIP MESSAGE from ${msg.from}: ${msg.body}")
                sendResponse(200, "OK", msg, packetSourceHost, packetSourcePort)
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
        val state = msg.floorControlState
        val speaker = extractSpeakerFromInfo(msg.body)
        Log.i(TAG, "Applying floor state: state=$state speaker=$speaker")
        when (state) {
            "GRANTED" -> {
                _floorState.value = FloorState.GRANTED
                _activeSpeaker.value = profile.displayName
                onFloorGranted?.invoke()
            }
            "TAKEN" -> {
                _floorState.value = FloorState.LISTENING
                _activeSpeaker.value = formatSpeakerDisplay(speaker) ?: "Remote Speaker"
            }
            "IDLE", "RELEASE" -> {
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
            }
            "DENIED" -> {
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
            }
        }
    }

    private fun applySdpAnswer(sdp: String) {
        val (ip, port) = SipMessage.extractSdpMediaFromText(sdp)
        if (ip == null && port == null) {
            Log.w(TAG, "applySdpAnswer: no SDP media found in input (${sdp.take(60)})")
            return
        }
        val host = ip ?: profile.mcpttAsHost
        val rtpPort = port
        if (rtpPort == null || rtpPort <= 0) {
            Log.e(TAG, "applySdpAnswer: missing or invalid audio port ($rtpPort)! DO NOT route to SIP port.")
            logSystemEvent("SDP answer missing audio port (m=audio) — RTP audio failed", isError = true)
            return
        }

        var rtcpPort: Int? = null
        for (rawLine in sdp.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("a=rtcp:", ignoreCase = true)) {
                val portPart = line.substring(7).trim().split(Regex("\\s+")).firstOrNull()
                rtcpPort = portPart?.toIntOrNull()
            }
        }

        val media = NegotiatedMedia(
            host = host,
            rtpPort = rtpPort,
            rtcpPort = rtcpPort ?: (rtpPort + 1),
            codec = "PCMU/8000"
        )
        _negotiatedMedia.value = media
        remoteMediaIp = host
        remoteMediaPort = rtpPort
        Log.i(TAG, "Negotiated media: $media")
        logSystemEvent(
            "SDP answer media → ${media.host}:${media.rtpPort}",
            mediaInfo = "${media.host}:${media.rtpPort}"
        )
        logSystemEvent(
            "=== SDP ANSWER ===\nLOCAL ${getLocalIpAddress()}:${_localRtpPort.value}\nREMOTE ${media.host}:${media.rtpPort}\n$sdp",
            mediaInfo = "${media.host}:${media.rtpPort}"
        )
        onMediaNegotiated?.invoke(host, rtpPort)
    }

    fun register() {
        registerAuthAttempts = 0
        _registrationFailureReason.value = null
        _registrationState.value = RegistrationState.REGISTERING
        registerCallId = "reg-" + UUID.randomUUID().toString().replace("-", "").take(12) + "@" + profile.realm
        registerFromTag = "reg-" + UUID.randomUUID().toString().replace("-", "").take(8)
        registerCSeq = 1

        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        Log.i(TAG, "REGISTER SEND via ${dest.description}:\n  callId=$registerCallId\n  cseq=$registerCSeq")
        sendRegisterPacket(registerCSeq, authHeader = null, destination = dest)
    }

    private fun sendRegisterPacket(cseq: Int, authHeader: String?, destination: SipDestination) {
        val sockId = sipSocket?.let { System.identityHashCode(it).toString(16) } ?: "unknown"
        if (!authHeader.isNullOrBlank()) {
            Log.i(TAG, "SIP TX id=$sockId method=REGISTER cseq=$cseq authorization=true dest=${destination.toHostPort()}")
        } else {
            Log.i(TAG, "SIP TX id=$sockId method=REGISTER cseq=$cseq dest=${destination.toHostPort()}")
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

        sendRawSip(sipPacket, destination.host, destination.port, LogType.SIP_REGISTER, "REGISTER")
    }

    private fun handleRegister401(msg: SipMessage, sockId: String) {
        // Guard against repeated 401 infinite loop
        if (registerAuthAttempts >= 1) {
            Log.e(TAG, "Registration failed: Repeated 401 challenge received after sending credentials. Stopping retry loop.")
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
        val opaque = SipAuthHelper.extractAuthParam(authHeader, "opaque")

        if (nonce.isEmpty()) {
            Log.e(TAG, "Registration failed: 401 challenge missing nonce")
            _registrationState.value = RegistrationState.REGISTRATION_FAILED
            _registrationFailureReason.value = "401 challenge missing nonce"
            return
        }

        Log.i(TAG, "DIGEST AUTH id=$sockId realm=$realm noncePresent=${nonce.isNotBlank()}")

        _registrationState.value = RegistrationState.AUTHENTICATING

        // RFC 3261: Increment CSeq for the authenticated REGISTER (1 -> 2)
        registerCSeq++

        val uri = "sip:${profile.realm}"
        val authUsername = if (profile.imsi.contains("@")) profile.imsi else "${profile.imsi}@$realm"

        val authHeaderValue = SipAuthHelper.buildAuthorizationHeader(
            username = authUsername,
            realm = realm,
            password = profile.password,
            nonce = nonce,
            uri = uri,
            method = "REGISTER",
            rawQop = rawQop,
            opaque = opaque
        )

        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRegisterPacket(registerCSeq, authHeaderValue, dest)
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

        logSystemEvent(
            "=== SDP OFFER ===\nLOCAL $localIp SIP:${profile.localSipPort} RTP:$rtpPortToOffer\n$sdpBody",
            mediaInfo = "$localIp:$rtpPortToOffer"
        )

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

        // Initial INVITE is out-of-dialog: next-hop is P-CSCF
        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_INVITE, "INVITE")
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

        // ACK is in-dialog: resolve next-hop via dialog route set or remote target URI
        val dest = SipNextHopResolver.resolveInDialogDestination(
            routeSet = activeRouteSet,
            remoteTargetUri = activeRemoteTargetUri,
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_ACK, "ACK")
    }

    private fun send200OkForInvite(inviteMsg: SipMessage, packetSourceHost: String, packetSourcePort: Int) {
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

        // Response to INVITE: resolve via transaction top Via
        val dest = SipNextHopResolver.resolveResponseDestination(
            requestMsg = inviteMsg,
            packetSourceHost = packetSourceHost,
            packetSourcePort = packetSourcePort,
            defaultPcscfHost = profile.pcscfHost,
            defaultPcscfPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_RESPONSE, "200 OK")
    }

    fun requestFloor() {
        if (_floorState.value == FloorState.REQUESTING ||
            _floorState.value == FloorState.GRANTED ||
            _floorState.value == FloorState.RELEASING ||
            _floorState.value == FloorState.LISTENING ||
            _floorBusy.value) {
            Log.w(TAG, "requestFloor ignored: invalid state ${_floorState.value}, busy=${_floorBusy.value}")
            return
        }

        if (_callState.value == CallSessionState.CONNECTED) {
            _floorState.value = FloorState.REQUESTING
            sendFloorControlMessage("floor-request")
        } else {
            // Not in session yet: initiate call and queue floor request
            pendingFloorRequest = true
            initiateMcpttCall()
        }
    }

    fun releaseFloor() {
        if (_floorState.value == FloorState.GRANTED || _floorState.value == FloorState.REQUESTING) {
            _floorState.value = FloorState.RELEASING
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

        // INFO is in-dialog: resolve next-hop via dialog route set or remote target URI
        val dest = SipNextHopResolver.resolveInDialogDestination(
            routeSet = activeRouteSet,
            remoteTargetUri = activeRemoteTargetUri,
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_INFO, "INFO")
    }

    fun sendInDialogFloorAction(actionName: String) {
        sendFloorControlMessage(actionName)
    }

    fun endCall() {
        pendingFloorRequest = false
        val callId = activeCallId ?: return
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

        // BYE is in-dialog: resolve next-hop via dialog route set or remote target URI
        val dest = SipNextHopResolver.resolveInDialogDestination(
            routeSet = activeRouteSet,
            remoteTargetUri = activeRemoteTargetUri,
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_BYE, "BYE")

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

        // Initial SUBSCRIBE is out-of-dialog: next-hop is P-CSCF
        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_SUBSCRIBE, "SUBSCRIBE")
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

        // Standalone MESSAGE is out-of-dialog: next-hop is P-CSCF
        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_MESSAGE, "MESSAGE")
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

        // Standalone MESSAGE is out-of-dialog: next-hop is P-CSCF
        val dest = SipNextHopResolver.resolveOutOfDialogDestination(
            selectedPcscf = selectedPcscf.value,
            fallbackHost = profile.pcscfHost,
            fallbackPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_MESSAGE, "MESSAGE")
    }

    private fun sendResponse(
        code: Int,
        text: String,
        requestMsg: SipMessage,
        packetSourceHost: String,
        packetSourcePort: Int
    ) {
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

        // Responses MUST route via top Via / transaction origin, NOT blindly to P-CSCF
        val dest = SipNextHopResolver.resolveResponseDestination(
            requestMsg = requestMsg,
            packetSourceHost = packetSourceHost,
            packetSourcePort = packetSourcePort,
            defaultPcscfHost = profile.pcscfHost,
            defaultPcscfPort = profile.pcscfPort
        )
        sendRawSip(sipPacket, dest.host, dest.port, LogType.SIP_RESPONSE, "$code $text")
    }

    private fun sendRawSip(
        rawSip: String,
        destHost: String,
        destPort: Int,
        logType: LogType = LogType.SYSTEM_EVENT,
        methodOrResponse: String = ""
    ) {
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
            Log.e(TAG, "Cannot send SIP message: sipSocket is null or closed")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val sockId = System.identityHashCode(socket).toString(16)
                // Use cellular network resolution if bound, else standard InetAddress
                val inetAddr = apnManager?.activeNetwork?.getByName(destHost) ?: InetAddress.getByName(destHost)
                val bytes = rawSip.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(bytes, bytes.size, inetAddr, destPort)

                socket.send(packet)
                Log.i(TAG, "SIP TX id=$sockId bytes=${bytes.size} dest=$destHost:$destPort")
                logTraffic(rawSip, LogDirection.OUTBOUND, "$destHost:$destPort", logType, methodOrResponse)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending SIP packet to $destHost:$destPort: ${e.message}")
            }
        }
    }

    private fun determineLogType(msg: SipMessage): LogType {
        return if (msg.isResponse) {
            LogType.SIP_RESPONSE
        } else {
            when (msg.method.uppercase()) {
                "REGISTER" -> LogType.SIP_REGISTER
                "SUBSCRIBE" -> LogType.SIP_SUBSCRIBE
                "INVITE" -> LogType.SIP_INVITE
                "MESSAGE" -> LogType.SIP_MESSAGE
                "INFO" -> LogType.SIP_INFO
                "BYE" -> LogType.SIP_BYE
                "ACK" -> LogType.SIP_ACK
                else -> LogType.SYSTEM_EVENT
            }
        }
    }

    private fun logTraffic(
        rawText: String,
        direction: LogDirection,
        remoteAddress: String,
        type: LogType,
        methodOrResponse: String
    ) {
        val firstLine = rawText.lines().firstOrNull()?.trim() ?: ""
        val isMcptt = rawText.contains("+g.3gpp.mcptt", ignoreCase = true) ||
                rawText.contains("mcptt", ignoreCase = true)
        val hasErr = direction == LogDirection.INBOUND && (
                firstLine.startsWith("SIP/2.0 4") ||
                        firstLine.startsWith("SIP/2.0 5") ||
                        firstLine.startsWith("SIP/2.0 6")
                )

        val sdpMedia = if (rawText.contains("m=audio")) {
            SipMessage.extractSdpMediaSummary(rawText)
        } else null

        val log = SipTrafficLog(
            timestamp = System.currentTimeMillis(),
            direction = direction,
            type = type,
            methodOrResponse = methodOrResponse.ifBlank { firstLine },
            remoteAddress = remoteAddress,
            summary = firstLine,
            rawPacket = rawText,
            isMcpttTagged = isMcptt,
            hasError = hasErr,
            negotiatedMedia = sdpMedia
        )
        scope.launch {
            _trafficLogs.emit(log)
        }
    }

    fun logSystemEvent(msg: String, isError: Boolean = false, mediaInfo: String? = null) {
        scope.launch {
            val log = SipTrafficLog(
                timestamp = System.currentTimeMillis(),
                direction = LogDirection.INBOUND,
                type = LogType.SYSTEM_EVENT,
                methodOrResponse = if (isError) "ERROR" else "INFO",
                remoteAddress = "Local Engine",
                summary = msg.lines().firstOrNull() ?: msg,
                rawPacket = msg,
                hasError = isError,
                negotiatedMedia = mediaInfo
            )
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
}
