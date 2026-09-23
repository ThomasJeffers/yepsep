package com.example.sip.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.example.sip.model.LogDirection
import com.example.sip.model.LogType
import com.example.sip.model.SipMessage
import com.example.sip.model.SipProfile
import com.example.sip.model.SipTrafficLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    RELEASED,
    TAKEN
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

    private var socket: DatagramSocket? = null
    private var listenJob: Job? = null
    private var appContext: Context? = null

    private var profile: SipProfile = SipProfile()
    private var localIp: String = ""
    private var localRtpPort: Int = 0
    private var boundNetwork: Network? = null
    private var networkBindStatus: String = "unbound (default route)"

    private var cseqNumber = 1
    private var activeCallId = ""
    private var activeCallTag = ""
    private var activeCallToTag = ""
    private var pendingFloorRequest = false

    private val _registrationState = MutableStateFlow(RegistrationState.UNREGISTERED)
    val registrationState: StateFlow<RegistrationState> = _registrationState.asStateFlow()

    private val _callState = MutableStateFlow(CallSessionState.IDLE)
    val callState: StateFlow<CallSessionState> = _callState.asStateFlow()

    private val _floorState = MutableStateFlow(FloorState.IDLE)
    val floorState: StateFlow<FloorState> = _floorState.asStateFlow()

    private val _activeSpeaker = MutableStateFlow<String?>(null)
    val activeSpeaker: StateFlow<String?> = _activeSpeaker.asStateFlow()

    private val _negotiatedMedia = MutableStateFlow<NegotiatedMedia?>(null)
    val negotiatedMedia: StateFlow<NegotiatedMedia?> = _negotiatedMedia.asStateFlow()

    private val _boundNetworkFlow = MutableStateFlow<Network?>(null)
    val boundNetworkFlow: StateFlow<Network?> = _boundNetworkFlow.asStateFlow()

    private val _localIpFlow = MutableStateFlow(localIp)
    val localIpFlow: StateFlow<String> = _localIpFlow.asStateFlow()

    private val _networkStatus = MutableStateFlow(networkBindStatus)
    val networkStatus: StateFlow<String> = _networkStatus.asStateFlow()

    private val _trafficLogs = MutableSharedFlow<SipTrafficLog>(extraBufferCapacity = 200)
    val trafficLogs: SharedFlow<SipTrafficLog> = _trafficLogs.asSharedFlow()

    private val _incomingMessages = MutableStateFlow<List<IncomingChatMessage>>(emptyList())
    val incomingMessages: StateFlow<List<IncomingChatMessage>> = _incomingMessages.asStateFlow()

    fun getBoundNetwork(): Network? = boundNetwork
    fun getLocalRtpPort(): Int = if (localRtpPort > 0) localRtpPort else profile.localRtpPort

    fun setLocalRtpPort(port: Int) {
        if (port > 0) {
            localRtpPort = port
            logSystemEvent("LOCAL RTP $localIp:$localRtpPort")
        }
    }

    fun start(context: Context? = null, currentProfile: SipProfile) {
        this.profile = currentProfile
        if (context != null) {
            appContext = context.applicationContext
        }

        try {
            listenJob?.cancel()
            socket?.close()
            val ds = DatagramSocket(null)
            ds.reuseAddress = true

            // bindSocket must run before DatagramSocket is bound to a port
            bindSocketToInternetCellular(appContext, ds)
            ds.bind(InetSocketAddress(profile.localSipPort))
            socket = ds

            localIp = resolveRuntimeLocalIp()
            _localIpFlow.value = localIp

            Log.d(TAG, "SIP Socket on $localIp:${ds.localPort} dest=${profile.sipDestinationLabel()} $networkBindStatus")
            logSystemEvent(
                "LOCAL SIP ${localIp}:${ds.localPort} | ${profile.sipDestinationLabel()} | $networkBindStatus"
            )

            startListeningLoop()

            if (profile.autoRegister) {
                register()
            }
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: e.message ?: "Unknown socket error"
            Log.e(TAG, "Failed to start SIP socket: $errMsg", e)
            logSystemEvent("Error starting SIP socket: $errMsg", isError = true)
            _registrationState.value = RegistrationState.FAILED
        }
    }

    /**
     * Prefer Internet APN (192.168.100.x / NET_CAPABILITY_INTERNET cellular).
     * Do NOT prefer IMS APN — third-party apps cannot use the privileged IMS PDN.
     */
    private fun bindSocketToInternetCellular(context: Context?, ds: DatagramSocket) {
        boundNetwork = null
        networkBindStatus = "unbound (default route)"
        _boundNetworkFlow.value = null
        _networkStatus.value = networkBindStatus

        if (context == null) return
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val networks = cm.allNetworks
            Log.d(TAG, "Scanning ${networks.size} networks for Internet cellular bind...")

            // Pass 0: default/active network if it is non-IMS cellular (or any non-IMS with IPv4)
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                val linkProps = cm.getLinkProperties(active)
                val ifName = linkProps?.interfaceName ?: "active"
                val ipv4 = ipv4FromLink(linkProps)
                val isIms = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS) == true
                if (!isIms && ipv4 != null && tryBind(active, ds, ifName, ipv4)) return
            }

            // Pass 1: cellular + INTERNET (skip IMS). Prefer UE Internet APN addresses.
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                val linkProps = cm.getLinkProperties(net)
                val ifName = linkProps?.interfaceName ?: "unknown"
                val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                val isIms = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
                val ipv4 = linkProps?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    ?.firstOrNull { it.indexOf(':') < 0 && !it.startsWith("127.") }

                Log.d(TAG, "Net $ifName ip=$ipv4 cellular=$isCellular internet=$hasInternet ims=$isIms")

                if (isIms) continue // skip IMS PDN for app SIP
                if (isCellular && hasInternet && ipv4 != null && ipv4.startsWith("192.168.100")) {
                    if (tryBind(net, ds, ifName, ipv4)) return
                }
            }

            // Pass 2: any cellular + INTERNET (non-IMS)
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val linkProps = cm.getLinkProperties(net)
                val ifName = linkProps?.interfaceName ?: "unknown"
                val ipv4 = linkProps?.linkAddresses
                    ?.mapNotNull { it.address.hostAddress }
                    ?.firstOrNull { it.indexOf(':') < 0 && !it.startsWith("127.") }
                if (tryBind(net, ds, ifName, ipv4)) return
            }

            // Pass 3: interface name / 192.168.100 match without IMS
            for (net in networks) {
                val caps = cm.getNetworkCapabilities(net)
                if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS) == true) continue
                val linkProps = cm.getLinkProperties(net) ?: continue
                val ifName = linkProps.interfaceName ?: ""
                for (linkAddr in linkProps.linkAddresses) {
                    val addr = linkAddr.address
                    if (addr.isLoopbackAddress || addr.hostAddress.indexOf(':') >= 0) continue
                    val ipStr = addr.hostAddress
                    if (ipStr.startsWith("192.168.100") ||
                        (ifName.contains("rmnet") && !ipStr.startsWith("192.168.101"))
                    ) {
                        if (tryBind(net, ds, ifName, ipStr)) return
                    }
                }
            }

            logSystemEvent("Using default route for SIP/RTP (Network.bindSocket not required for Direct-AS)")
        } catch (e: Exception) {
            Log.e(TAG, "Error binding socket to cellular network: ${e.message}", e)
            logSystemEvent("Network bind error: ${e.message}", isError = true)
        }
    }

    private fun tryBind(net: Network, ds: DatagramSocket, ifName: String, ipv4: String?): Boolean {
        return try {
            net.bindSocket(ds)
            boundNetwork = net
            _boundNetworkFlow.value = net
            if (ipv4 != null) {
                localIp = ipv4
                _localIpFlow.value = localIp
            }
            networkBindStatus = "bound $ifName ($localIp)"
            _networkStatus.value = networkBindStatus
            Log.i(TAG, "Bound SIP socket to $ifName ($localIp)")
            logSystemEvent("Bound SIP socket to Internet cellular ($ifName - $localIp)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "bindSocket failed on $ifName: ${e.message}")
            false
        }
    }

    private fun ipv4FromLink(linkProps: android.net.LinkProperties?): String? {
        return linkProps?.linkAddresses
            ?.mapNotNull { it.address.hostAddress }
            ?.firstOrNull { it.indexOf(':') < 0 && !it.startsWith("127.") }
    }

    private fun resolveRuntimeLocalIp(): String {
        discoverRoutedLocalIp()?.let { return rememberResolvedIp(it) }
        ipv4FromBoundNetwork()?.let { return rememberResolvedIp(it) }
        getLocalIpAddress(appContext)?.let { return rememberResolvedIp(it) }
        if (localIp.isNotBlank() && localIp != "0.0.0.0") return localIp
        return "0.0.0.0"
    }

    private fun rememberResolvedIp(ip: String): String {
        if (networkBindStatus.startsWith("unbound") && ip != "0.0.0.0") {
            networkBindStatus = "default route ($ip)"
            _networkStatus.value = networkBindStatus
        }
        return ip
    }

    private fun ipv4FromBoundNetwork(): String? {
        val context = appContext ?: return null
        val net = boundNetwork ?: return null
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return ipv4FromLink(cm.getLinkProperties(net))
    }

    private fun discoverRoutedLocalIp(): String? {
        var probe: DatagramSocket? = null
        return try {
            probe = DatagramSocket()
            boundNetwork?.let { net ->
                try {
                    net.bindSocket(probe)
                } catch (_: Exception) {
                }
            }
            val dest = InetAddress.getByName(profile.sipDestinationHost())
            probe.connect(dest, profile.sipDestinationPort())
            val addr = probe.localAddress
            val ip = addr?.hostAddress
            if (addr != null && !addr.isAnyLocalAddress && !addr.isLoopbackAddress &&
                ip != null && ip.indexOf(':') < 0
            ) {
                ip
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Routed local IP probe failed: ${e.message}")
            null
        } finally {
            try {
                probe?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun updateProfile(context: Context? = null, newProfile: SipProfile) {
        val needsRestart = profile.localSipPort != newProfile.localSipPort ||
            profile.pcscfHost != newProfile.pcscfHost ||
            profile.pcscfPort != newProfile.pcscfPort ||
            profile.mcpttAsHost != newProfile.mcpttAsHost ||
            profile.mcpttAsPort != newProfile.mcpttAsPort ||
            profile.sipRoutingMode != newProfile.sipRoutingMode
        this.profile = newProfile
        if (needsRestart) {
            start(context ?: appContext, newProfile)
        }
    }

    private fun startListeningLoop() {
        listenJob?.cancel()
        listenJob = scope.launch {
            val buffer = ByteArray(4096)
            while (socket != null && !socket!!.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    val rawSipText = String(packet.data, 0, packet.length)
                    val remoteAddr = "${packet.address.hostAddress}:${packet.port}"

                    val parsedMsg = SipMessage.parse(rawSipText)

                    val log = SipTrafficLog(
                        direction = LogDirection.INBOUND,
                        type = mapLogType(parsedMsg),
                        methodOrResponse = inboundMethodLabel(parsedMsg),
                        remoteAddress = remoteAddr,
                        summary = inboundSummary(parsedMsg),
                        rawPacket = rawSipText,
                        isMcpttTagged = parsedMsg.isMcpttTagged
                    )
                    _trafficLogs.emit(log)

                    handleIncomingSipMessage(parsedMsg)
                } catch (e: Exception) {
                    if (socket?.isClosed == false) {
                        Log.e(TAG, "Listen error: ${e.message}")
                    }
                }
            }
        }
    }

    private fun handleIncomingSipMessage(msg: SipMessage) {
        if (msg.isResponse) {
            when (msg.method) {
                "REGISTER" -> {
                    when {
                        msg.statusCode in 200..299 -> {
                            _registrationState.value = RegistrationState.REGISTERED
                            logSystemEvent("SIP Registration Successful (200 OK)")
                            if (_callState.value == CallSessionState.IDLE) {
                                logSystemEvent("Auto-joining talkgroup ${profile.targetGroup}")
                                initiateMcpttCall()
                            }
                        }
                        msg.statusCode == 401 || msg.statusCode == 407 -> {
                            logSystemEvent("Digest Auth Challenge (${msg.statusCode}) — resending")
                            handleRegisterAuthChallenge(msg)
                        }
                        msg.statusCode >= 400 -> {
                            _registrationState.value = RegistrationState.FAILED
                            logSystemEvent(
                                "Registration Failed: ${msg.statusCode} ${msg.statusText}",
                                isError = true
                            )
                        }
                    }
                }
                "INVITE" -> {
                    when {
                        msg.statusCode == 200 || msg.statusCode == 202 -> {
                            _callState.value = CallSessionState.CONNECTED
                            activeCallToTag = extractTag(msg.to)
                            applySdpAnswer(msg.body)
                            sendAck(msg)
                            logSystemEvent(
                                "MCPTT Session Connected | media=${_negotiatedMedia.value}"
                            )
                            if (pendingFloorRequest) {
                                pendingFloorRequest = false
                                sendFloorControlMessage("floor-request")
                            }
                        }
                        msg.statusCode in 100..199 -> {
                            if (msg.body.contains("m=audio")) {
                                applySdpAnswer(msg.body)
                            }
                            logSystemEvent("MCPTT Call Progress: ${msg.statusCode} ${msg.statusText}")
                        }
                        msg.statusCode >= 400 -> {
                            _callState.value = CallSessionState.IDLE
                            _negotiatedMedia.value = null
                            pendingFloorRequest = false
                            logSystemEvent(
                                "MCPTT Call Rejected: ${msg.statusCode} ${msg.statusText}",
                                isError = true
                            )
                        }
                    }
                }
                "SUBSCRIBE" -> {
                    if (msg.statusCode in 200..299) {
                        logSystemEvent("Group Event Subscription Active (200 OK)")
                    }
                }
                "INFO" -> {
                    logSystemEvent("RX ${msg.statusCode} ${msg.statusText} for INFO")
                    applyFloorFromBody(msg)
                }
            }
        } else {
            when (msg.method) {
                "INVITE" -> {
                    activeCallId = msg.callId
                    _callState.value = CallSessionState.CONNECTED
                    applySdpAnswer(msg.body)
                    sendResponse(msg, 200, "OK")
                    logSystemEvent("Accepted Incoming MCPTT Call from ${msg.from}")
                }
                "INFO" -> {
                    sendResponse(msg, 200, "OK")
                    applyFloorFromBody(msg)
                }
                "MESSAGE" -> {
                    sendResponse(msg, 200, "OK")
                    val chat = IncomingChatMessage(
                        from = msg.from,
                        text = msg.body.trim()
                    )
                    _incomingMessages.value = (_incomingMessages.value + chat).takeLast(20)
                    logSystemEvent("Received MCPTT SIP Message: ${chat.text}")
                }
                "BYE" -> {
                    sendResponse(msg, 200, "OK")
                    pendingFloorRequest = false
                    _callState.value = CallSessionState.IDLE
                    _floorState.value = FloorState.IDLE
                    _activeSpeaker.value = null
                    _negotiatedMedia.value = null
                    logSystemEvent("MCPTT Call Session Ended by Remote")
                }
                "OPTIONS" -> {
                    sendResponse(msg, 200, "OK")
                }
            }
        }
    }

    private fun applyFloorFromBody(msg: SipMessage) {
        when (msg.floorControlState) {
            "GRANTED" -> {
                _floorState.value = FloorState.GRANTED
                _activeSpeaker.value = profile.mcpttId
                logSystemEvent("Floor GRANTED")
            }
            "TAKEN" -> {
                _floorState.value = FloorState.TAKEN
                _activeSpeaker.value = extractSpeakerFromFloorBody(msg.body) ?: msg.from
                logSystemEvent("Floor TAKEN by ${_activeSpeaker.value}")
            }
            "IDLE", "RELEASE" -> {
                _floorState.value = FloorState.IDLE
                _activeSpeaker.value = null
                logSystemEvent("Floor IDLE")
            }
        }
    }

    private fun extractSpeakerFromFloorBody(body: String): String? {
        for (line in body.lines()) {
            if (line.trim().startsWith("User=", ignoreCase = true)) {
                return line.substringAfter("=").trim()
            }
        }
        return null
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
        var inAudio = false

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            when {
                line.startsWith("c=IN IP4 ") -> mediaIp = line.removePrefix("c=IN IP4 ").trim().substringBefore(" ")
                line.startsWith("m=audio ") -> {
                    inAudio = true
                    val parts = line.split("\\s+".toRegex())
                    if (parts.size >= 2) {
                        rtpPort = parts[1].toIntOrNull()
                    }
                }
                line.startsWith("a=rtcp:") -> {
                    val portPart = line.removePrefix("a=rtcp:").trim().split("\\s+".toRegex()).firstOrNull()
                    rtcpPort = portPart?.toIntOrNull()
                }
            }
        }

        val host = mediaIp
        val port = rtpPort
        if (host != null && port != null) {
            val media = NegotiatedMedia(host, port, rtcpPort ?: (port + 1))
            _negotiatedMedia.value = media
            Log.i(TAG, "Negotiated media: $media")
            logSystemEvent("SDP answer media → ${media.host}:${media.rtpPort}")
            logSystemEvent(
                "=== SDP ANSWER ===\nLOCAL $localIp:${getLocalRtpPort()}\nREMOTE ${media.host}:${media.rtpPort}\n$text"
            )
        } else if (inAudio) {
            logSystemEvent("SDP answer missing c=/m=audio — RTP may fail", isError = true)
        }
    }

    fun register() {
        localIp = resolveRuntimeLocalIp()
        _localIpFlow.value = localIp
        _registrationState.value = RegistrationState.REGISTERING
        val branch = generateBranch()
        val callId = generateCallId()
        val cseq = ++cseqNumber

        val user = extractUser(profile.mcpttId)
        val mcpttTagHeader = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val acceptLine = if (profile.includeMcpttTags) {
            "Accept-Contact: *;+g.3gpp.mcptt;explicit;require\n"
        } else {
            ""
        }

        val rawSip = sipCrlf(
            """
            REGISTER sip:${profile.realm} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=${generateTag()}
            To: <${profile.mcpttId}>
            Call-ID: $callId
            CSeq: $cseq REGISTER
            Contact: <sip:$user@$localIp:${profile.localSipPort}>$mcpttTagHeader
            ${acceptLine}P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Expires: 3600
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_REGISTER, "REGISTER sip:${profile.realm}")
    }

    private fun handleRegisterAuthChallenge(challengeMsg: SipMessage) {
        val wwwAuth = challengeMsg.headers["www-authenticate"]
            ?: challengeMsg.headers["proxy-authenticate"]
            ?: return
        val realm = extractAuthParam(wwwAuth, "realm") ?: profile.realm
        val nonce = extractAuthParam(wwwAuth, "nonce") ?: ""
        val uri = "sip:${profile.realm}"

        val user = extractUser(profile.mcpttId)
        val responseHex = calculateDigestResponse(user, realm, profile.password, "REGISTER", uri, nonce)

        val branch = generateBranch()
        val callId = challengeMsg.callId
        val cseq = ++cseqNumber

        val mcpttTagHeader = if (profile.includeMcpttTags) ";+g.3gpp.mcptt" else ""
        val authHeader =
            "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$responseHex\", algorithm=MD5"

        val rawSip = sipCrlf(
            """
            REGISTER sip:${profile.realm} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=${generateTag()}
            To: <${profile.mcpttId}>
            Call-ID: $callId
            CSeq: $cseq REGISTER
            Contact: <sip:$user@$localIp:${profile.localSipPort}>$mcpttTagHeader
            Authorization: $authHeader
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Expires: 3600
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_REGISTER, "REGISTER (Authenticated)")
    }

    fun subscribeGroup(groupUri: String = profile.targetGroup) {
        val branch = generateBranch()
        val callId = generateCallId()
        val cseq = ++cseqNumber

        val rawSip = sipCrlf(
            """
            SUBSCRIBE $groupUri SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=${generateTag()}
            To: <$groupUri>
            Call-ID: $callId
            CSeq: $cseq SUBSCRIBE
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            Event: poc-settings
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Expires: 3600
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_SUBSCRIBE, "SUBSCRIBE $groupUri")
    }

    fun initiateMcpttCall(targetUri: String = profile.targetGroup) {
        if (_callState.value != CallSessionState.IDLE) return

        _callState.value = CallSessionState.CALLING
        _negotiatedMedia.value = null
        localIp = resolveRuntimeLocalIp()
        _localIpFlow.value = localIp
        activeCallId = generateCallId()
        activeCallTag = generateTag()
        val branch = generateBranch()
        val cseq = ++cseqNumber

        val sdp = """
            v=0
            o=mcptt 10001 10001 IN IP4 $localIp
            s=MCPTT Group Call
            c=IN IP4 $localIp
            t=0 0
            m=audio ${getLocalRtpPort()} RTP/AVP 0 8 101
            a=rtpmap:0 PCMU/8000
            a=rtpmap:8 PCMA/8000
            a=rtpmap:101 telephone-event/8000
            a=sendrecv
            a=mcptt
        """.trimIndent()

        val sdpWire = sipCrlf(sdp).trimEnd('\r', '\n') + "\r\n"
        val headers = """
            INVITE $targetUri SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=$activeCallTag
            To: <$targetUri>
            Call-ID: $activeCallId
            CSeq: $cseq INVITE
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            P-Preferred-Identity: <${profile.mcpttId}>
            P-Access-Network-Info: 3GPP-E-UTRAN-FDD; utran-cell-id-3gpp=2089300000001
            User-Agent: ${profile.userAgent}
            Content-Type: application/sdp
            Content-Length: ${sdpWire.toByteArray().size}
        """.trimIndent()

        val rawSip = assembleSip(headers, sdp)
        logSystemEvent("=== SDP OFFER ===\nLOCAL $localIp SIP:${profile.localSipPort} RTP:${getLocalRtpPort()}\n$sdp")
        sendSipText(rawSip, LogType.SIP_INVITE, "INVITE $targetUri (+g.3gpp.mcptt)")
    }

    fun requestFloor() {
        _floorState.value = FloorState.REQUESTING
        _activeSpeaker.value = profile.mcpttId

        if (_callState.value != CallSessionState.CONNECTED) {
            pendingFloorRequest = true
            initiateMcpttCall()
            return
        }

        sendFloorControlMessage("floor-request")
    }

    fun releaseFloor() {
        pendingFloorRequest = false
        val shouldSendRelease = _callState.value == CallSessionState.CONNECTED &&
            (_floorState.value == FloorState.GRANTED || _floorState.value == FloorState.REQUESTING)
        if (shouldSendRelease) {
            sendFloorControlMessage("floor-release")
        }
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
    }

    private fun sendFloorControlMessage(action: String) {
        val branch = generateBranch()
        val cseq = ++cseqNumber
        val body = "Action=$action"
        val bodyWire = sipCrlf(body)
        val bodyLen = bodyWire.toByteArray().size

        val rawSip = sipCrlf(
            """
            INFO ${profile.targetGroup} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=$activeCallTag
            To: <${profile.targetGroup}>${if (activeCallToTag.isNotEmpty()) ";tag=$activeCallToTag" else ""}
            Call-ID: $activeCallId
            CSeq: $cseq INFO
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Content-Type: text/plain
            Content-Length: $bodyLen
            
            $body
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_INFO, "TX INFO $action")
    }

    fun sendEmergencyAlert(messageText: String = "EMERGENCY SOS ALERT BROADCAST") {
        val branch = generateBranch()
        val cseq = ++cseqNumber
        val callId = generateCallId()

        val body = "[EMERGENCY_ALERT] User: ${profile.displayName} (${profile.mcpttId}) - $messageText"
        val bodyLen = body.toByteArray().size

        val rawSip = sipCrlf(
            """
            MESSAGE ${profile.emergencyGroup} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=${generateTag()}
            To: <${profile.emergencyGroup}>
            Call-ID: $callId
            CSeq: $cseq MESSAGE
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt;+g.3gpp.mcptt.emergency
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require;+g.3gpp.mcptt.emergency
            Priority: emergency
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Content-Type: text/plain
            Content-Length: $bodyLen
            
            $body
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_MESSAGE, "MESSAGE Emergency Alert (+g.3gpp.mcptt.emergency)")
    }

    fun sendSipMessageText(targetUri: String, textMessage: String) {
        val branch = generateBranch()
        val cseq = ++cseqNumber
        val callId = generateCallId()

        val bodyLen = textMessage.toByteArray().size

        val rawSip = sipCrlf(
            """
            MESSAGE $targetUri SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=${generateTag()}
            To: <$targetUri>
            Call-ID: $callId
            CSeq: $cseq MESSAGE
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt
            Accept-Contact: *;+g.3gpp.mcptt;explicit;require
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Content-Type: text/plain
            Content-Length: $bodyLen
            
            $textMessage
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_MESSAGE, "MESSAGE $targetUri")
    }

    fun endCall() {
        if (_callState.value == CallSessionState.IDLE) return

        _callState.value = CallSessionState.DISCONNECTING
        val branch = generateBranch()
        val cseq = ++cseqNumber

        val rawSip = sipCrlf(
            """
            BYE ${profile.targetGroup} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=$activeCallTag
            To: <${profile.targetGroup}>${if (activeCallToTag.isNotEmpty()) ";tag=$activeCallToTag" else ""}
            Call-ID: $activeCallId
            CSeq: $cseq BYE
            Contact: <sip:${extractUser(profile.mcpttId)}@$localIp:${profile.localSipPort}>;+g.3gpp.mcptt
            P-Preferred-Identity: <${profile.mcpttId}>
            User-Agent: ${profile.userAgent}
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_BYE, "BYE ${profile.targetGroup}")

        pendingFloorRequest = false
        _callState.value = CallSessionState.IDLE
        _floorState.value = FloorState.IDLE
        _activeSpeaker.value = null
        _negotiatedMedia.value = null
    }

    private fun sendAck(inviteResponseMsg: SipMessage) {
        val branch = generateBranch()
        val rawSip = sipCrlf(
            """
            ACK ${profile.targetGroup} SIP/2.0
            Via: SIP/2.0/UDP $localIp:${profile.localSipPort};rport;branch=$branch
            From: <${profile.mcpttId}>;tag=$activeCallTag
            To: <${profile.targetGroup}>;tag=$activeCallToTag
            Call-ID: $activeCallId
            CSeq: ${inviteResponseMsg.cseq.split(" ")[0]} ACK
            User-Agent: ${profile.userAgent}
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_ACK, "ACK ${profile.targetGroup}")
    }

    private fun sendResponse(requestMsg: SipMessage, code: Int, text: String) {
        val via = requestMsg.headers["via"] ?: ""
        val from = requestMsg.headers["from"] ?: ""
        val to = requestMsg.headers["to"] ?: ""
        val callId = requestMsg.callId
        val cseq = requestMsg.cseq

        val rawSip = sipCrlf(
            """
            SIP/2.0 $code $text
            Via: $via
            From: $from
            To: $to;tag=${generateTag()}
            Call-ID: $callId
            CSeq: $cseq
            User-Agent: ${profile.userAgent}
            Content-Length: 0
            
            """.trimIndent()
        )

        sendSipText(rawSip, LogType.SIP_RESPONSE, "SIP/2.0 $code $text")
    }

    private fun sendSipText(rawSipText: String, type: LogType, summary: String) {
        scope.launch {
            val destHost = profile.sipDestinationHost()
            val destPort = profile.sipDestinationPort()
            try {
                val bytes = rawSipText.toByteArray()
                val targetAddr = InetAddress.getByName(destHost)
                val packet = DatagramPacket(bytes, bytes.size, targetAddr, destPort)

                val currentSocket = socket
                if (currentSocket == null || currentSocket.isClosed) {
                    throw IllegalStateException("SIP DatagramSocket is closed or not initialized")
                }

                currentSocket.send(packet)
                Log.d(TAG, "Sent SIP to $destHost:$destPort:\n$rawSipText")

                val log = SipTrafficLog(
                    direction = LogDirection.OUTBOUND,
                    type = type,
                    methodOrResponse = summary.split(" ")[0],
                    remoteAddress = "$destHost:$destPort",
                    summary = summary,
                    rawPacket = rawSipText,
                    isMcpttTagged = rawSipText.contains("+g.3gpp.mcptt")
                )
                _trafficLogs.emit(log)
            } catch (e: Exception) {
                val errMsg = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Error sending SIP to $destHost:$destPort: $errMsg", e)

                val errorLog = SipTrafficLog(
                    direction = LogDirection.OUTBOUND,
                    type = type,
                    methodOrResponse = "SEND_FAIL",
                    remoteAddress = "$destHost:$destPort",
                    summary = "[ERROR] Send Failed: $errMsg",
                    rawPacket = "--- FAILED SIP PACKET ---\nTarget: $destHost:$destPort (${profile.sipRoutingMode})\nError: $errMsg\n\n$rawSipText",
                    hasError = true,
                    isMcpttTagged = rawSipText.contains("+g.3gpp.mcptt")
                )
                _trafficLogs.emit(errorLog)
                logSystemEvent("Send failed to $destHost:$destPort: $errMsg", isError = true)
            }
        }
    }

    fun injectSimulatedPacket(rawPacketText: String) {
        scope.launch {
            val parsedMsg = SipMessage.parse(rawPacketText)
            val log = SipTrafficLog(
                direction = LogDirection.INBOUND,
                type = mapLogType(parsedMsg),
                methodOrResponse = if (parsedMsg.isResponse) {
                    "${parsedMsg.statusCode} ${parsedMsg.statusText}"
                } else {
                    parsedMsg.method
                },
                remoteAddress = "${profile.sipDestinationHost()}:${profile.sipDestinationPort()} (Simulated)",
                summary = parsedMsg.startLine,
                rawPacket = rawPacketText,
                isMcpttTagged = parsedMsg.isMcpttTagged
            )
            _trafficLogs.emit(log)
            handleIncomingSipMessage(parsedMsg)
        }
    }

    private fun logSystemEvent(msg: String, isError: Boolean = false) {
        scope.launch {
            val log = SipTrafficLog(
                direction = LogDirection.INBOUND,
                type = LogType.SYSTEM_EVENT,
                methodOrResponse = if (isError) "ERROR" else "INFO",
                remoteAddress = "Local Engine",
                summary = msg,
                rawPacket = msg,
                hasError = isError
            )
            _trafficLogs.emit(log)
        }
    }

    fun stop() {
        listenJob?.cancel()
        listenJob = null
        socket?.close()
        socket = null
        boundNetwork = null
        _boundNetworkFlow.value = null
        pendingFloorRequest = false
        _registrationState.value = RegistrationState.UNREGISTERED
        _callState.value = CallSessionState.IDLE
        _floorState.value = FloorState.IDLE
        _negotiatedMedia.value = null
    }

    private fun getLocalIpAddress(context: Context? = null): String? {
        try {
            if (context != null) {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                if (cm != null) {
                    for (net in cm.allNetworks) {
                        val caps = cm.getNetworkCapabilities(net) ?: continue
                        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) continue
                        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                        ipv4FromLink(cm.getLinkProperties(net))?.let { return it }
                    }
                }
            }

            val interfaces = NetworkInterface.getNetworkInterfaces()
            var fallbackIp: String? = null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr.hostAddress.indexOf(':') < 0) {
                        val ipStr = addr.hostAddress
                        if (name.contains("rmnet") || name.contains("ccmni") || name.contains("pdp")) {
                            if (!ipStr.startsWith("192.168.101")) return ipStr
                        }
                        if (fallbackIp == null) fallbackIp = ipStr
                    }
                }
            }
            return fallbackIp
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP: ${e.message}")
        }
        return null
    }

    private fun mapLogType(msg: SipMessage): LogType {
        if (msg.isResponse) return LogType.SIP_RESPONSE
        return when (msg.method) {
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

    private fun generateBranch() = "z9hG4bK-${UUID.randomUUID().toString().substring(0, 8)}"
    private fun generateCallId() = "${UUID.randomUUID()}@$localIp"
    private fun generateTag() = UUID.randomUUID().toString().substring(0, 8)
    private fun extractUser(sipUri: String): String {
        val clean = sipUri.replace("sip:", "")
        return clean.substringBefore("@")
    }

    private fun extractTag(header: String): String {
        val parts = header.split(";")
        for (p in parts) {
            if (p.trim().startsWith("tag=")) {
                return p.trim().substringAfter("tag=")
            }
        }
        return ""
    }

    private fun extractAuthParam(authHeader: String, paramName: String): String? {
        val regex = Regex("""$paramName="([^"]+)"""")
        return regex.find(authHeader)?.groupValues?.get(1)
    }

    private fun calculateDigestResponse(
        user: String,
        realm: String,
        pass: String,
        method: String,
        uri: String,
        nonce: String
    ): String {
        val md5 = MessageDigest.getInstance("MD5")
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        val ha1 = hex(md5.digest("$user:$realm:$pass".toByteArray()))
        val ha2 = hex(md5.digest("$method:$uri".toByteArray()))
        return hex(md5.digest("$ha1:$nonce:$ha2".toByteArray()))
    }

    private fun inboundMethodLabel(msg: SipMessage): String {
        return if (msg.isResponse) {
            "${msg.statusCode} ${msg.statusText}"
        } else {
            msg.method
        }
    }

    private fun inboundSummary(msg: SipMessage): String {
        val floor = msg.floorControlState
        return when {
            msg.isResponse && msg.method == "INFO" ->
                "RX ${msg.statusCode} ${msg.statusText} (INFO)"
            msg.isResponse && msg.method == "INVITE" ->
                "RX ${msg.statusCode} ${msg.statusText} (INVITE)"
            !msg.isResponse && msg.method == "INFO" && floor != null ->
                "RX INFO floor-${floor.lowercase()}"
            !msg.isResponse && msg.method == "INFO" ->
                "RX INFO"
            !msg.isResponse && msg.method == "MESSAGE" ->
                "RX MESSAGE ${msg.body.trim().take(40)}"
            else -> msg.startLine
        }
    }

    companion object {
        private const val TAG = "McpttSipStack"

        fun sipCrlf(text: String): String {
            return text.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
        }

        /**
         * Build a SIP datagram without interpolating a multiline body into trimIndent().
         * That Kotlin pitfall leaves spaces on the header/body blank line, so AS parsers
         * miss SDP and never create the MCPTT session.
         */
        fun assembleSip(headerBlock: String, body: String = ""): String {
            val head = sipCrlf(headerBlock.trimIndent().trim()).trimEnd('\r', '\n')
            if (body.isBlank()) {
                return "$head\r\n\r\n"
            }
            val bodyWire = sipCrlf(body.trimIndent().trim()).trimEnd('\r', '\n') + "\r\n"
            return "$head\r\n\r\n$bodyWire"
        }
    }
}
