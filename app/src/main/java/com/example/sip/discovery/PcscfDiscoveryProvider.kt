package com.example.sip.discovery

import android.net.Network
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Clean client-side abstraction for acquiring and selecting the authoritative P-CSCF endpoint.
 *
 * Implements two selectable discovery methods:
 * 1. PRECONFIGURED: Single authoritative configuration supporting IPv4 literal or FQDN.
 * 2. NETWORK_DNS: Direct UDP DNS A query of configured P-CSCF FQDN executed on the active MCPTT Network.
 */
interface PcscfDiscoveryProvider {
    val discoveryState: StateFlow<PcscfDiscoveryState>
    val selectedPcscf: StateFlow<SelectedPcscf?>

    /**
     * Resets or initiates the discovery lifecycle to DISCOVERING.
     */
    fun startDiscovery(method: PcscfDiscoveryMethod = PcscfDiscoveryMethod.NETWORK_DNS)

    /**
     * Reports network disconnection or lost status to prevent staying stuck in DISCOVERING.
     */
    fun reportNetworkLost(reason: String)

    /**
     * Selects a preconfigured endpoint synchronously if valid.
     */
    fun selectPreconfiguredImmediately(config: PcscfConfig): SelectedPcscf

    /**
     * Executes P-CSCF discovery according to the selected discovery method.
     *
     * @param method PRECONFIGURED or NETWORK_DNS
     * @param config The PcscfConfig containing preconfigured endpoint and DNS settings
     * @param network The bound MCPTT cellular Network, if available
     * @param dnsServers List of DNS server IPs reported by the MCPTT Network, if available
     */
    suspend fun discover(
        method: PcscfDiscoveryMethod,
        config: PcscfConfig,
        network: Network?,
        dnsServers: List<String> = emptyList()
    ): SelectedPcscf

    /**
     * Backward-compatible overload for legacy test call sites.
     */
    suspend fun discover(
        network: Network?,
        explicitPcscfFqdn: String?,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf

    /**
     * Sets an explicit manual override.
     */
    fun selectManualOverride(endpoint: PcscfEndpoint)
}

/**
 * Standard implementation of PcscfDiscoveryProvider.
 *
 * Implements an honest acquisition model:
 * - PRECONFIGURED: Returns preconfigured host:port with resolved IP if literal or resolvable.
 * - NETWORK_DNS: Directly queries configured DNS servers via UDP on the MCPTT Network.
 *   Does NOT use DnsResolver or InetAddress.getAllByName() / network.getAllByName().
 *   Does NOT require the response source IP to match the queried DNS server IP,
 *   supporting environments where the DNS response source address is rewritten by NAT.
 *   If DNS returns no addresses or resolution fails, reports DNS_FAILED honestly without automatic fallback.
 */
class DefaultPcscfDiscoveryProvider : PcscfDiscoveryProvider {

    companion object {
        private const val TAG = "PcscfDiscovery"

        /**
         * Builds a standard DNS A query packet (RFC 1035).
         * - Random 16-bit transaction ID
         * - Flags 0x0100 (standard query, recursion desired)
         * - QDCOUNT = 1
         * - QNAME = labels from FQDN
         * - QTYPE = 1 (A)
         * - QCLASS = 1 (IN)
         */
        fun buildDnsQueryPacket(fqdn: String, txId: Int): ByteArray {
            val baos = ByteArrayOutputStream()
            val dos = DataOutputStream(baos)
            dos.writeShort(txId and 0xFFFF)
            dos.writeShort(0x0100) // Flags: recursion desired
            dos.writeShort(1)      // QDCOUNT = 1
            dos.writeShort(0)      // ANCOUNT = 0
            dos.writeShort(0)      // NSCOUNT = 0
            dos.writeShort(0)      // ARCOUNT = 0

            val labels = fqdn.trim().trim('.').split('.')
            for (label in labels) {
                if (label.isEmpty()) continue
                val bytes = label.toByteArray(Charsets.US_ASCII)
                dos.writeByte(bytes.size)
                dos.write(bytes)
            }
            dos.writeByte(0) // Root null label

            dos.writeShort(1) // QTYPE = A (1)
            dos.writeShort(1) // QCLASS = IN (1)
            return baos.toByteArray()
        }

        /**
         * Reads a domain name in wire format (RFC 1035) handling plain labels and compression pointers.
         * Returns a Pair of (decoded domain name, number of bytes consumed in the stream from startOffset).
         */
        fun readDnsName(data: ByteArray, startOffset: Int, totalLength: Int): Pair<String, Int> {
            var offset = startOffset
            var bytesConsumed = 0
            var hasJumped = false
            val labels = mutableListOf<String>()
            var jumps = 0
            val maxJumps = 20

            while (offset < totalLength && jumps < maxJumps) {
                val len = data[offset].toInt() and 0xFF
                if (len == 0) {
                    if (!hasJumped) {
                        bytesConsumed = (offset - startOffset) + 1
                    }
                    break
                } else if ((len and 0xC0) == 0xC0) {
                    if (offset + 1 >= totalLength) break
                    if (!hasJumped) {
                        bytesConsumed = (offset - startOffset) + 2
                        hasJumped = true
                    }
                    val pointerOffset = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                    offset = pointerOffset
                    jumps++
                } else {
                    val labelLen = len
                    offset += 1
                    if (offset + labelLen > totalLength) break
                    val label = String(data, offset, labelLen, Charsets.US_ASCII)
                    labels.add(label)
                    offset += labelLen
                    if (!hasJumped) {
                        bytesConsumed = offset - startOffset
                    }
                }
            }
            if (!hasJumped && bytesConsumed == 0 && offset <= totalLength) {
                bytesConsumed = (offset - startOffset).coerceAtLeast(1)
            }
            val name = labels.joinToString(".")
            return Pair(name, bytesConsumed)
        }

        /**
         * Parses a standard DNS response packet (RFC 1035).
         * Matches transaction ID, validates response flag and rcode == 0,
         * validates question name if expectedFqdn is provided,
         * handles name-compression pointers and CNAME chains,
         * and returns the first A record.
         */
        fun parseDnsResponse(
            data: ByteArray,
            length: Int,
            expectedTxId: Int,
            expectedFqdn: String? = null
        ): List<String> {
            if (length < 12) return emptyList()
            val dis = DataInputStream(ByteArrayInputStream(data, 0, length))
            val txId = dis.readUnsignedShort()
            if (txId != (expectedTxId and 0xFFFF)) {
                Log.w(TAG, "DNS response txId mismatch: expected $expectedTxId, got $txId")
                return emptyList()
            }
            val flags = dis.readUnsignedShort()
            val isResponse = (flags and 0x8000) != 0
            val rcode = flags and 0x000F
            if (!isResponse) {
                Log.w(TAG, "DNS packet is not a response (flags=0x${flags.toString(16)})")
                return emptyList()
            }
            if (rcode != 0) {
                Log.w(TAG, "DNS response returned error rcode=$rcode")
                return emptyList()
            }
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            dis.readUnsignedShort() // nsCount
            dis.readUnsignedShort() // arCount

            var offset = 12

            // Parse Question section
            var questionMatched = true
            for (i in 0 until qdCount) {
                if (offset >= length) return emptyList()
                val (qName, consumed) = readDnsName(data, offset, length)
                offset += consumed
                if (offset + 4 > length) return emptyList()
                offset += 4 // QTYPE (2) + QCLASS (2)

                if (expectedFqdn != null) {
                    val cleanExpected = expectedFqdn.trim().trim('.')
                    if (!qName.equals(cleanExpected, ignoreCase = true)) {
                        Log.w(TAG, "DNS question name mismatch: expected '$cleanExpected', got '$qName'")
                        questionMatched = false
                    }
                }
            }
            if (!questionMatched) {
                return emptyList()
            }

            val aRecordsByName = mutableMapOf<String, MutableList<String>>()
            val cnamesByName = mutableMapOf<String, String>()
            val allARecords = mutableListOf<String>()

            // Parse Answer section
            for (i in 0 until anCount) {
                if (offset >= length) break
                val (recordName, consumedName) = readDnsName(data, offset, length)
                offset += consumedName
                if (offset + 10 > length) break

                val type = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
                val clazz = ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
                val rdLength = ((data[offset + 8].toInt() and 0xFF) shl 8) or (data[offset + 9].toInt() and 0xFF)
                offset += 10
                if (offset + rdLength > length) break

                val rdataOffset = offset
                val normRecordName = recordName.trim().trim('.').lowercase()

                if (type == 1 && clazz == 1 && rdLength == 4) { // A record, IN class, IPv4
                    val b0 = data[rdataOffset].toInt() and 0xFF
                    val b1 = data[rdataOffset + 1].toInt() and 0xFF
                    val b2 = data[rdataOffset + 2].toInt() and 0xFF
                    val b3 = data[rdataOffset + 3].toInt() and 0xFF
                    val ip = "$b0.$b1.$b2.$b3"
                    aRecordsByName.getOrPut(normRecordName) { mutableListOf() }.add(ip)
                    allARecords.add(ip)
                } else if (type == 5 && clazz == 1) { // CNAME record, IN class
                    val (targetName, _) = readDnsName(data, rdataOffset, length)
                    val normTarget = targetName.trim().trim('.').lowercase()
                    cnamesByName[normRecordName] = normTarget
                }

                offset += rdLength
            }

            // Resolve CNAME chains to find the first A record
            val startName = expectedFqdn?.trim()?.trim('.')?.lowercase()
            if (!startName.isNullOrEmpty()) {
                var cur: String = startName
                val visited = mutableSetOf<String>()
                while (cur.isNotEmpty() && visited.add(cur)) {
                    val ips = aRecordsByName[cur]
                    if (!ips.isNullOrEmpty()) {
                        return listOf(ips.first())
                    }
                    val nextTarget = cnamesByName[cur] ?: break
                    cur = nextTarget
                }
            }

            // If not found via exact expectedFqdn chain, check all CNAME chains
            for (cnameStart in cnamesByName.keys) {
                var cur: String = cnameStart
                val visited = mutableSetOf<String>()
                while (cur.isNotEmpty() && visited.add(cur)) {
                    val ips = aRecordsByName[cur]
                    if (!ips.isNullOrEmpty()) {
                        return listOf(ips.first())
                    }
                    val nextTarget = cnamesByName[cur] ?: break
                    cur = nextTarget
                }
            }

            // Return the first A record found in answer section
            if (allARecords.isNotEmpty()) {
                return listOf(allARecords.first())
            }

            return emptyList()
        }
    }

    private val _discoveryState = MutableStateFlow<PcscfDiscoveryState>(PcscfDiscoveryState.Discovering(PcscfDiscoveryMethod.NETWORK_DNS))
    override val discoveryState: StateFlow<PcscfDiscoveryState> = _discoveryState.asStateFlow()

    private val _selectedPcscf = MutableStateFlow<SelectedPcscf?>(null)
    override val selectedPcscf: StateFlow<SelectedPcscf?> = _selectedPcscf.asStateFlow()

    private val singleFlightMutex = Mutex()
    private var inFlightDeferred: CompletableDeferred<SelectedPcscf>? = null
    private var inFlightKey: String? = null
    private var lastResolvedKey: String? = null
    private var lastResolvedResult: SelectedPcscf? = null

    /**
     * Functional DNS resolver interface to facilitate unit testing without real hardware network calls.
     */
    var dnsResolver: (suspend (Network?, String) -> Array<InetAddress>)? = null

    override fun startDiscovery(method: PcscfDiscoveryMethod) {
        synchronized(this) {
            lastResolvedKey = null
            lastResolvedResult = null
            inFlightKey = null
            inFlightDeferred?.cancel()
            inFlightDeferred = null
        }
        _selectedPcscf.value = null
        _discoveryState.value = PcscfDiscoveryState.Discovering(method)
    }

    override fun reportNetworkLost(reason: String) {
        synchronized(this) {
            inFlightDeferred?.cancel()
            inFlightDeferred = null
            inFlightKey = null
            lastResolvedKey = null
            lastResolvedResult = null
        }
        if (_discoveryState.value is PcscfDiscoveryState.Discovering) {
            _selectedPcscf.value = null
            _discoveryState.value = PcscfDiscoveryState.Failed("Network unavailable: $reason")
        }
    }

    override fun selectPreconfiguredImmediately(config: PcscfConfig): SelectedPcscf {
        val rawHost = config.preconfiguredHost.trim()
        val port = config.preconfiguredPort
        val isLiteral = PcscfConfig.isIpv4Literal(rawHost) || rawHost.contains(":")
        val endpoint = PcscfEndpoint(
            host = rawHost,
            port = port,
            resolvedIp = if (isLiteral) rawHost else null,
            source = PcscfDiscoverySource.PRECONFIGURED,
            timestamp = System.currentTimeMillis(),
            transport = config.transport
        )
        val selection = SelectedPcscf(
            endpoint = endpoint,
            source = PcscfDiscoverySource.PRECONFIGURED,
            isFallback = false,
            discoveryMethod = PcscfDiscoveryMethod.PRECONFIGURED,
            configuredHost = rawHost,
            resolvedAddress = if (isLiteral) rawHost else null,
            selectedNetwork = null,
            dnsServers = emptyList(),
            failureReason = null,
            networkInfo = null,
            candidatesEvaluated = listOf(endpoint),
            statusDetail = "Preconfigured P-CSCF: ${endpoint.toHostPort()}"
        )
        _selectedPcscf.value = selection
        _discoveryState.value = PcscfDiscoveryState.Preconfigured(selection)
        return selection
    }

    override suspend fun discover(
        method: PcscfDiscoveryMethod,
        config: PcscfConfig,
        network: Network?,
        dnsServers: List<String>
    ): SelectedPcscf = withContext(Dispatchers.IO) {
        val cacheKey = "${method.name}|${config.preconfiguredHost.trim()}:${config.preconfiguredPort}|${config.dnsFqdn.trim()}:${config.dnsPort}|${network?.hashCode()}"

        var deferredToAwait: CompletableDeferred<SelectedPcscf>? = null
        var shouldExecute = false

        singleFlightMutex.withLock {
            val cached = lastResolvedResult
            if (lastResolvedKey == cacheKey && cached != null && cached.source != PcscfDiscoverySource.ERROR) {
                Log.d(TAG, "P-CSCF discovery: returning cached result for key $cacheKey")
                return@withContext cached
            }

            val existing = inFlightDeferred
            if (existing != null && existing.isActive) {
                if (inFlightKey != cacheKey) {
                    Log.i(TAG, "Cancelling stale in-flight discovery deferred for previous key $inFlightKey (new key $cacheKey)")
                    existing.cancel()
                    inFlightDeferred = null
                    inFlightKey = null
                    val newDeferred = CompletableDeferred<SelectedPcscf>()
                    inFlightDeferred = newDeferred
                    inFlightKey = cacheKey
                    deferredToAwait = newDeferred
                    shouldExecute = true
                } else {
                    Log.d(TAG, "P-CSCF discovery: joining in-flight job for key $cacheKey")
                    deferredToAwait = existing
                }
            } else {
                val newDeferred = CompletableDeferred<SelectedPcscf>()
                inFlightDeferred = newDeferred
                inFlightKey = cacheKey
                deferredToAwait = newDeferred
                shouldExecute = true
            }
        }

        if (!shouldExecute) {
            val result = try {
                deferredToAwait!!.await()
            } catch (e: CancellationException) {
                coroutineContext.ensureActive()
                return@withContext discover(method, config, network, dnsServers)
            }
            return@withContext result
        }

        try {
            val result = when (method) {
                PcscfDiscoveryMethod.PRECONFIGURED -> discoverPreconfigured(config, network, dnsServers)
                PcscfDiscoveryMethod.NETWORK_DNS -> discoverNetworkDns(config, network, dnsServers)
            }
            singleFlightMutex.withLock {
                lastResolvedKey = cacheKey
                lastResolvedResult = result
                if (inFlightDeferred === deferredToAwait) {
                    inFlightDeferred = null
                    inFlightKey = null
                }
            }
            deferredToAwait!!.complete(result)
            result
        } catch (e: Throwable) {
            singleFlightMutex.withLock {
                if (inFlightDeferred === deferredToAwait) {
                    inFlightDeferred = null
                    inFlightKey = null
                }
            }
            deferredToAwait!!.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun discoverPreconfigured(
        config: PcscfConfig,
        network: Network?,
        dnsServers: List<String>
    ): SelectedPcscf {
        val rawHost = config.preconfiguredHost.trim()
        val port = config.preconfiguredPort
        Log.i(TAG, "P-CSCF discovery started: PRECONFIGURED host=$rawHost:$port")

        val isLiteral = PcscfConfig.isIpv4Literal(rawHost) || rawHost.contains(":")
        var resolvedIp: String? = null

        if (isLiteral) {
            resolvedIp = rawHost
        } else if (rawHost.isNotEmpty()) {
            try {
                val ips: List<String> = if (dnsResolver != null) {
                    dnsResolver!!.invoke(network, rawHost).mapNotNull { it.hostAddress }
                } else if (dnsServers.isNotEmpty()) {
                    resolveWithUdpDns(network, rawHost, dnsServers)
                } else {
                    emptyList()
                }
                resolvedIp = ips.firstOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not resolve preconfigured FQDN '$rawHost': ${e.message}")
            }
        }

        val endpoint = PcscfEndpoint(
            host = rawHost,
            port = port,
            resolvedIp = resolvedIp,
            source = PcscfDiscoverySource.PRECONFIGURED,
            timestamp = System.currentTimeMillis(),
            transport = config.transport
        )

        val selection = SelectedPcscf(
            endpoint = endpoint,
            source = PcscfDiscoverySource.PRECONFIGURED,
            isFallback = false,
            discoveryMethod = PcscfDiscoveryMethod.PRECONFIGURED,
            configuredHost = rawHost,
            resolvedAddress = resolvedIp ?: rawHost,
            selectedNetwork = network?.toString(),
            dnsServers = dnsServers,
            failureReason = null,
            networkInfo = network?.toString(),
            candidatesEvaluated = listOf(endpoint),
            statusDetail = "Preconfigured P-CSCF: ${endpoint.toHostPort()}"
        )

        Log.i(TAG, "P-CSCF discovery result=PRECONFIGURED ${selection.endpoint.toHostPort()}")
        _selectedPcscf.value = selection
        _discoveryState.value = PcscfDiscoveryState.Preconfigured(selection)
        return selection
    }

    private suspend fun discoverNetworkDns(
        config: PcscfConfig,
        network: Network?,
        dnsServers: List<String>
    ): SelectedPcscf {
        val fqdn = config.dnsFqdn.trim()
        val port = config.dnsPort
        Log.i(TAG, "P-CSCF discovery started on MCPTT network via UDP DNS")
        Log.i(TAG, "P-CSCF DNS FQDN=${if (fqdn.isNotEmpty()) fqdn else "<none>"}, servers=$dnsServers")

        _discoveryState.value = PcscfDiscoveryState.Discovering(PcscfDiscoveryMethod.NETWORK_DNS)

        val candidates = mutableListOf<PcscfEndpoint>()
        var failureReason: String? = null

        if (fqdn.isNotEmpty()) {
            try {
                Log.i(TAG, "Resolving P-CSCF FQDN: '$fqdn' over MCPTT cellular network (network=$network)...")
                val resolvedIps: List<String> = if (dnsResolver != null) {
                    dnsResolver!!.invoke(network, fqdn).mapNotNull { it.hostAddress }
                } else {
                    resolveWithUdpDns(network, fqdn, dnsServers)
                }

                for (hostStr in resolvedIps) {
                    val candidate = PcscfEndpoint(
                        host = fqdn,
                        port = port,
                        resolvedIp = hostStr,
                        source = PcscfDiscoverySource.DNS_RESOLVED,
                        timestamp = System.currentTimeMillis(),
                        transport = config.transport,
                        priority = 10,
                        weight = 0
                    )
                    if (!candidates.any { it.resolvedIp == candidate.resolvedIp && it.port == candidate.port }) {
                        Log.i(TAG, "Resolved P-CSCF address: ${candidate.toHostPort()} from FQDN '$fqdn'")
                        candidates.add(candidate)
                    }
                }

                if (candidates.isEmpty()) {
                    failureReason = if (network == null && dnsResolver == null) {
                        "No active MCPTT network bound for DNS"
                    } else {
                        "DNS resolution for '$fqdn' returned no addresses"
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failureReason = "DNS resolution for '$fqdn' failed: ${e.message}"
                Log.w(TAG, failureReason)
            }
        } else {
            failureReason = "no P-CSCF FQDN provisioned"
            Log.i(TAG, "P-CSCF DNS discovery: $failureReason")
        }

        val selection: SelectedPcscf
        if (candidates.isNotEmpty()) {
            // Deterministic candidate selection:
            // 1. Prefer IPv4 for compatibility with current IPv4 LTE bearer pool
            // 2. Sort by priority descending, then lexicographical IP order
            val sorted = candidates.sortedWith(
                compareBy<PcscfEndpoint> { it.isIpv6 }
                    .thenByDescending { it.priority }
                    .thenBy { it.resolvedIp ?: it.host }
            )
            val chosen = sorted.first()
            selection = SelectedPcscf(
                endpoint = chosen,
                source = PcscfDiscoverySource.DNS_RESOLVED,
                isFallback = false,
                discoveryMethod = PcscfDiscoveryMethod.NETWORK_DNS,
                configuredHost = fqdn,
                resolvedAddress = chosen.resolvedIp,
                selectedNetwork = network?.toString(),
                dnsServers = dnsServers,
                failureReason = null,
                networkInfo = network?.toString(),
                candidatesEvaluated = sorted,
                statusDetail = "Resolved ${candidates.size} address(es) for FQDN '$fqdn' via MCPTT cellular DNS"
            )
            Log.i(TAG, "DNS_RESOLVED: P-CSCF discovery result=DNS_RESOLVED ${selection.endpoint.toHostPort()}")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.DnsResolved(selection, fqdn)
        } else {
            // DNS resolution failed: NETWORK_DNS requires explicit DNS_FAILED / ERROR without automatic fallback
            val reason = failureReason ?: "DNS resolution failed"
            Log.w(TAG, "P-CSCF DNS failed ($reason) for FQDN '$fqdn'; reporting DNS_FAILED")
            val errorEndpoint = PcscfEndpoint(
                host = fqdn,
                port = port,
                resolvedIp = null,
                source = PcscfDiscoverySource.ERROR,
                timestamp = System.currentTimeMillis(),
                transport = config.transport
            )
            selection = SelectedPcscf(
                endpoint = errorEndpoint,
                source = PcscfDiscoverySource.ERROR,
                isFallback = false,
                discoveryMethod = PcscfDiscoveryMethod.NETWORK_DNS,
                configuredHost = fqdn,
                resolvedAddress = null,
                selectedNetwork = network?.toString(),
                dnsServers = dnsServers,
                failureReason = reason,
                networkInfo = network?.toString(),
                candidatesEvaluated = emptyList(),
                statusDetail = "DNS failed: $reason"
            )
            Log.i(TAG, "P-CSCF discovery result=ERROR: $reason")
            _selectedPcscf.value = null
            _discoveryState.value = PcscfDiscoveryState.DnsFailed(selection, reason, null)
        }

        return selection
    }

    /**
     * Resolves an FQDN to IPv4 addresses by sending standard UDP DNS queries to each configured DNS server.
     * Iterates sequentially over the supplied DNS servers (using 2000ms timeout per server).
     */
    private fun resolveWithUdpDns(
        network: Network?,
        fqdn: String,
        dnsServers: List<String>
    ): List<String> {
        if (dnsServers.isEmpty()) {
            Log.w(TAG, "No DNS servers supplied for UDP DNS query of '$fqdn'")
            return emptyList()
        }
        for (server in dnsServers) {
            val trimmedServer = server.trim()
            if (trimmedServer.isEmpty()) continue
            Log.i(TAG, "Attempting DNS A resolution for '$fqdn' via server $trimmedServer:53...")
            val ips = queryDnsOverUdp(network, trimmedServer, fqdn, timeoutMs = 2000)
            if (ips.isNotEmpty()) {
                Log.i(TAG, "DNS resolution succeeded for '$fqdn' via $trimmedServer: $ips")
                return ips
            }
        }
        return emptyList()
    }

    /**
     * Sends a direct UDP DNS A query packet to the target server and parses the response.
     * Accepts responses even if the response source IP differs from the server IP (NAT/rewriting).
     */
    private fun queryDnsOverUdp(
        network: Network?,
        dnsServerIp: String,
        fqdn: String,
        timeoutMs: Int = 2000
    ): List<String> {
        val txId = Random.nextInt(1, 0xFFFF)
        val queryPacket = buildDnsQueryPacket(fqdn, txId)

        var socket: DatagramSocket? = null
        return try {
            val serverAddr = InetAddress.getByName(dnsServerIp)
            socket = DatagramSocket()
            if (network != null) {
                try {
                    network.bindSocket(socket)
                    Log.d(TAG, "Bound DNS DatagramSocket to network $network")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not bind DNS socket to network $network: ${e.message}")
                }
            }
            socket.soTimeout = timeoutMs

            val sendPacket = DatagramPacket(queryPacket, queryPacket.size, serverAddr, 53)
            socket.send(sendPacket)
            Log.i(TAG, "Sent DNS A query for '$fqdn' (txId=$txId) to $dnsServerIp:53")

            receiveDnsResponse(
                socket = socket,
                expectedTxId = txId,
                fqdn = fqdn,
                queriedServerIp = dnsServerIp,
                timeoutMs = timeoutMs,
                expectedSourcePort = 53
            )
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "DNS query to $dnsServerIp:53 for '$fqdn' timed out after ${timeoutMs}ms")
            emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "DNS query to $dnsServerIp:53 for '$fqdn' failed: ${e.message}")
            emptyList()
        } finally {
            socket?.close()
        }
    }

    /**
     * Receives and validates DNS response packets from the socket until timeout.
     * Accepts responses from any source address (e.g. source-translated on OGS path)
     * as long as UDP source port == 53 (or expectedSourcePort in test), txId matches,
     * question name matches, and packet contains a valid IPv4 A record.
     */
    internal fun receiveDnsResponse(
        socket: DatagramSocket,
        expectedTxId: Int,
        fqdn: String,
        queriedServerIp: String,
        timeoutMs: Int = 2000,
        expectedSourcePort: Int = 53
    ): List<String> {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remainingMs = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(1)
            socket.soTimeout = remainingMs

            val recvBuffer = ByteArray(1500)
            val recvPacket = DatagramPacket(recvBuffer, recvBuffer.size)
            try {
                socket.receive(recvPacket)
            } catch (e: SocketTimeoutException) {
                break
            }

            if (expectedSourcePort > 0 && recvPacket.port != expectedSourcePort) {
                Log.w(TAG, "Ignoring UDP packet from unexpected port: ${recvPacket.port}")
                continue
            }

            val replySource = recvPacket.address?.hostAddress
            Log.i(TAG, "Received DNS response (${recvPacket.length} bytes) from $replySource:${recvPacket.port}")

            if (replySource != null && replySource != queriedServerIp) {
                Log.w(TAG, "DNS reply source address $replySource differs from queried server $queriedServerIp")
            }

            // Accept response if transaction ID and question name match; do NOT require matching source IP
            val ips = parseDnsResponse(recvBuffer, recvPacket.length, expectedTxId, fqdn)
            if (ips.isNotEmpty()) {
                Log.i(TAG, "Parsed ${ips.size} IPv4 A record(s) from DNS response: $ips")
                return ips
            }
        }
        return emptyList()
    }

    override suspend fun discover(
        network: Network?,
        explicitPcscfFqdn: String?,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf {
        val fqdn = explicitPcscfFqdn?.trim() ?: ""
        val method = if (fqdn.isNotEmpty()) PcscfDiscoveryMethod.NETWORK_DNS else PcscfDiscoveryMethod.PRECONFIGURED
        val config = PcscfConfig(
            method = method,
            preconfiguredHost = legacyFallback.host,
            preconfiguredPort = legacyFallback.port,
            dnsFqdn = fqdn,
            dnsPort = legacyFallback.port,
            transport = legacyFallback.transport
        )
        return discover(method, config, network, emptyList())
    }

    override fun selectManualOverride(endpoint: PcscfEndpoint) {
        val isFallback = endpoint.source == PcscfDiscoverySource.FALLBACK || endpoint.source == PcscfDiscoverySource.STATIC_LEGACY
        val selection = SelectedPcscf(
            endpoint = endpoint,
            source = endpoint.source,
            isFallback = isFallback,
            configuredHost = endpoint.host,
            resolvedAddress = endpoint.resolvedIp,
            statusDetail = if (isFallback) "Manual static fallback" else "Manual override"
        )
        Log.i(TAG, "P-CSCF manually set to: ${selection.endpoint.toHostPort()} (${selection.source.name})")
        _selectedPcscf.value = selection
        if (selection.isFallback) {
            _discoveryState.value = PcscfDiscoveryState.Fallback(selection, "Manual legacy config")
        } else {
            _discoveryState.value = PcscfDiscoveryState.Discovered(selection)
        }
    }
}
