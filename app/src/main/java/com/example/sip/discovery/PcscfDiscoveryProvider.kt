package com.example.sip.discovery

import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Clean client-side abstraction for acquiring and selecting the authoritative P-CSCF endpoint.
 *
 * Distinguishes:
 * 1. DISCOVERY / PROVISIONING: Network/platform-provisioned P-CSCF information (PCO/ISIM - unavailable to standard APK).
 * 2. DNS RESOLUTION: A/AAAA resolution of an explicitly supplied/known P-CSCF FQDN over the bound MCPTT cellular network.
 * 3. STATIC FALLBACK: Proven legacy fallback configuration (172.30.104.240:5060) ensuring 100% backward compatibility.
 */
interface PcscfDiscoveryProvider {
    val discoveryState: StateFlow<PcscfDiscoveryState>
    val selectedPcscf: StateFlow<SelectedPcscf?>

    /**
     * Executes P-CSCF acquisition.
     *
     * @param network The bound MCPTT cellular Network, if available.
     * @param explicitPcscfFqdn An explicitly supplied/provisioned P-CSCF FQDN (if any).
     *                          Does NOT guess names like pcscf.<realm> or sip.<realm>.
     * @param legacyFallback The configured fallback endpoint (typically 172.30.104.240:5060).
     */
    suspend fun discover(
        network: Network?,
        explicitPcscfFqdn: String?,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf

    /**
     * Sets an explicit manual override or resets to fallback immediately.
     */
    fun selectManualOverride(endpoint: PcscfEndpoint)
}

/**
 * Standard implementation of PcscfDiscoveryProvider.
 *
 * Implements an honest acquisition model:
 * - If an explicit P-CSCF FQDN is supplied (e.g. from carrier provisioning or user settings),
 *   resolves it using the cellular Network DNS (Network.getAllByName).
 * - Does NOT guess arbitrary hostnames from the IMS realm (e.g. pcscf.<realm>).
 * - If no explicit FQDN is supplied, or DNS returns no addresses, or discovery is unavailable
 *   to this standard APK, transparently selects the legacy fallback with:
 *   "P-CSCF discovery unavailable to this APK; using legacy static fallback".
 */
class DefaultPcscfDiscoveryProvider : PcscfDiscoveryProvider {

    companion object {
        private const val TAG = "PcscfDiscovery"
    }

    private val _discoveryState = MutableStateFlow<PcscfDiscoveryState>(PcscfDiscoveryState.Idle)
    override val discoveryState: StateFlow<PcscfDiscoveryState> = _discoveryState.asStateFlow()

    private val _selectedPcscf = MutableStateFlow<SelectedPcscf?>(null)
    override val selectedPcscf: StateFlow<SelectedPcscf?> = _selectedPcscf.asStateFlow()

    /**
     * Functional DNS resolver interface to facilitate unit testing without real hardware network calls.
     */
    var dnsResolver: (suspend (Network?, String) -> Array<InetAddress>)? = null

    override suspend fun discover(
        network: Network?,
        explicitPcscfFqdn: String?,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf = withContext(Dispatchers.IO) {
        val fqdn = explicitPcscfFqdn?.trim() ?: ""
        Log.i(TAG, "P-CSCF acquisition started. Network: $network, ExplicitFQDN: '$fqdn', LegacyFallback: ${legacyFallback.toHostPort()}")

        val candidates = mutableListOf<PcscfEndpoint>()

        if (fqdn.isNotEmpty()) {
            _discoveryState.value = PcscfDiscoveryState.Discovering(PcscfDiscoverySource.DNS_A_AAAA)
            try {
                Log.i(TAG, "Resolving explicit P-CSCF FQDN: '$fqdn' over cellular network...")
                val addresses: Array<InetAddress> = if (dnsResolver != null) {
                    dnsResolver!!.invoke(network, fqdn)
                } else if (network != null) {
                    network.getAllByName(fqdn)
                } else {
                    emptyArray()
                }

                for (addr in addresses) {
                    val hostStr = addr.hostAddress ?: continue
                    val candidate = PcscfEndpoint(
                        host = hostStr,
                        port = legacyFallback.port,
                        transport = legacyFallback.transport,
                        source = PcscfDiscoverySource.DNS_A_AAAA,
                        priority = 10,
                        weight = 0
                    )
                    if (!candidates.any { it.host == candidate.host && it.port == candidate.port }) {
                        Log.i(TAG, "Resolved P-CSCF address: ${candidate.toHostPort()} from FQDN '$fqdn'")
                        candidates.add(candidate)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DNS resolution for explicit FQDN '$fqdn' failed: ${e.message}")
            }
        }

        val selection: SelectedPcscf
        if (candidates.isNotEmpty()) {
            // Deterministic candidate selection:
            // 1. Prefer IPv4 for compatibility with current IPv4 LTE bearer pool (192.168.102.x)
            // 2. Sort by priority descending, then lexicographical host order
            val sorted = candidates.sortedWith(
                compareBy<PcscfEndpoint> { it.isIpv6 } // false (IPv4) first
                    .thenByDescending { it.priority }
                    .thenBy { it.host }
            )
            val chosen = sorted.first()
            selection = SelectedPcscf(
                endpoint = chosen,
                source = PcscfDiscoverySource.DNS_A_AAAA,
                isFallback = false,
                networkInfo = network?.toString(),
                candidatesEvaluated = sorted,
                statusDetail = "Resolved ${candidates.size} address(es) for explicit FQDN '$fqdn' via cellular DNS"
            )
            Log.i(TAG, "Authoritative P-CSCF [DISCOVERED via DNS]: ${selection.endpoint.toHostPort()}")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Discovered(selection)
        } else {
            // Honest legacy fallback
            val reason = if (fqdn.isEmpty()) {
                "P-CSCF discovery unavailable to this APK; using legacy static fallback"
            } else {
                "DNS resolution for '$fqdn' returned no addresses; using legacy static fallback"
            }
            selection = SelectedPcscf(
                endpoint = legacyFallback.copy(source = PcscfDiscoverySource.STATIC_LEGACY),
                source = PcscfDiscoverySource.STATIC_LEGACY,
                isFallback = true,
                networkInfo = network?.toString(),
                candidatesEvaluated = emptyList(),
                statusDetail = reason
            )
            Log.i(TAG, "Authoritative P-CSCF [STATIC FALLBACK]: ${selection.endpoint.toHostPort()} (Reason: $reason)")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Fallback(selection, reason)
        }

        selection
    }

    override fun selectManualOverride(endpoint: PcscfEndpoint) {
        val selection = SelectedPcscf(
            endpoint = endpoint,
            source = endpoint.source,
            isFallback = endpoint.source == PcscfDiscoverySource.STATIC_LEGACY,
            statusDetail = "Manually selected / updated endpoint"
        )
        Log.i(TAG, "P-CSCF manually set to: ${selection.endpoint.toHostPort()} (${selection.source})")
        _selectedPcscf.value = selection
        if (selection.isFallback) {
            _discoveryState.value = PcscfDiscoveryState.Fallback(selection, "Manual legacy config")
        } else {
            _discoveryState.value = PcscfDiscoveryState.Discovered(selection)
        }
    }
}
