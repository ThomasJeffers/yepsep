package com.example.sip.discovery

import android.annotation.TargetApi
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Clean client-side abstraction for acquiring and selecting the authoritative P-CSCF endpoint.
 *
 * Implements two selectable discovery methods:
 * 1. PRECONFIGURED: Single authoritative configuration supporting IPv4 literal or FQDN.
 * 2. NETWORK_DNS: A/AAAA resolution of configured P-CSCF FQDN executed on the active MCPTT Network.
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
 * - NETWORK_DNS: Resolves configured FQDN strictly on the MCPTT cellular Network.
 *   Does NOT use plain InetAddress.getAllByName() without network binding.
 *   If DNS returns no addresses or resolution fails, falls back honestly to runtime PRECONFIGURED with full diagnostics.
 * - Non-carrier accessible cellular mechanisms (DHCP Option 120, PCO, ISIM) are reported as unavailable.
 *   Application-level DNS on the MCPTT Network is NOT cellular DHCP Option 120 or NAS PCO.
 */
class DefaultPcscfDiscoveryProvider : PcscfDiscoveryProvider {

    companion object {
        private const val TAG = "PcscfDiscovery"
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
            return@withContext deferredToAwait!!.await()
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
            // Preconfigured host is an FQDN: attempt resolution over MCPTT network if available
            try {
                val addrs = if (dnsResolver != null) {
                    dnsResolver!!.invoke(network, rawHost)
                } else if (network != null) {
                    network.getAllByName(rawHost)
                } else {
                    emptyArray()
                }
                resolvedIp = addrs.firstOrNull()?.hostAddress
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
        Log.i(TAG, "P-CSCF discovery started on MCPTT network")
        Log.i(TAG, "P-CSCF DNS FQDN=${if (fqdn.isNotEmpty()) fqdn else "<none>"}")

        // Log unavailability of carrier/platform-restricted mechanisms honestly
        Log.i(TAG, "P-CSCF DHCP Option 120 unavailable: cellular DHCP options not exposed by public Android APIs to non-carrier apps")
        Log.i(TAG, "P-CSCF 3GPP NAS PCO unavailable: requires carrier privileges")
        Log.i(TAG, "P-CSCF UICC ISIM EF_PCSCF unavailable: requires carrier privileges (READ_PRIVILEGED_PHONE_STATE)")

        _discoveryState.value = PcscfDiscoveryState.Discovering(PcscfDiscoveryMethod.NETWORK_DNS)

        val candidates = mutableListOf<PcscfEndpoint>()
        var failureReason: String? = null

        if (fqdn.isNotEmpty()) {
            try {
                Log.i(TAG, "Resolving P-CSCF FQDN: '$fqdn' over MCPTT cellular network (network=$network)...")
                val addresses: Array<InetAddress> = if (dnsResolver != null) {
                    dnsResolver!!.invoke(network, fqdn)
                } else if (network != null) {
                    resolveNetworkDns(network, fqdn)
                } else {
                    Log.w(TAG, "No MCPTT Network instance available for network-bound DNS resolution")
                    emptyArray()
                }

                for (addr in addresses) {
                    val hostStr = addr.hostAddress ?: continue
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
                    failureReason = if (network == null) {
                        "No active MCPTT network bound for DNS"
                    } else {
                        "DNS resolution for '$fqdn' returned no addresses"
                    }
                }
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
            // 1. Prefer IPv4 for compatibility with current IPv4 LTE bearer pool (192.168.102.x)
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

    private suspend fun resolveNetworkDns(network: Network, fqdn: String): Array<InetAddress> {
        return withTimeoutOrNull(5000L) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    resolveWithDnsResolver(network, fqdn)
                } catch (e: Exception) {
                    Log.w(TAG, "DnsResolver query failed (${e.message}), falling back to network.getAllByName")
                    network.getAllByName(fqdn)
                }
            } else {
                network.getAllByName(fqdn)
            }
        } ?: run {
            Log.w(TAG, "DNS resolution timed out after 5000ms for '$fqdn' on network $network")
            emptyArray()
        }
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private suspend fun resolveWithDnsResolver(network: Network, fqdn: String): Array<InetAddress> =
        suspendCancellableCoroutine { cont ->
            val signal = android.os.CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            val callback = object : DnsResolver.Callback<List<InetAddress>> {
                override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                    if (cont.isActive) cont.resume(answer.toTypedArray())
                }
                override fun onError(error: DnsResolver.DnsException) {
                    if (cont.isActive) cont.resumeWithException(error)
                }
            }
            DnsResolver.getInstance().query(
                network,
                fqdn,
                DnsResolver.FLAG_EMPTY,
                Dispatchers.IO.asExecutor(),
                signal,
                callback
            )
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
