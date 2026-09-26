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
     * Resets or initiates the discovery lifecycle to DISCOVERING.
     */
    fun startDiscovery()

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
 *   "no P-CSCF FQDN provisioned" or "DNS resolution for '<fqdn>' returned no addresses".
 */
class DefaultPcscfDiscoveryProvider : PcscfDiscoveryProvider {

    companion object {
        private const val TAG = "PcscfDiscovery"
    }

    private val _discoveryState = MutableStateFlow<PcscfDiscoveryState>(PcscfDiscoveryState.Discovering(PcscfDiscoverySource.DNS_A_AAAA))
    override val discoveryState: StateFlow<PcscfDiscoveryState> = _discoveryState.asStateFlow()

    private val _selectedPcscf = MutableStateFlow<SelectedPcscf?>(null)
    override val selectedPcscf: StateFlow<SelectedPcscf?> = _selectedPcscf.asStateFlow()

    /**
     * Functional DNS resolver interface to facilitate unit testing without real hardware network calls.
     */
    var dnsResolver: (suspend (Network?, String) -> Array<InetAddress>)? = null

    override fun startDiscovery() {
        _selectedPcscf.value = null
        _discoveryState.value = PcscfDiscoveryState.Discovering(PcscfDiscoverySource.DNS_A_AAAA)
    }

    override suspend fun discover(
        network: Network?,
        explicitPcscfFqdn: String?,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf = withContext(Dispatchers.IO) {
        val fqdn = explicitPcscfFqdn?.trim() ?: ""
        Log.i(TAG, "P-CSCF discovery started on MCPTT network")
        Log.i(TAG, "P-CSCF DNS FQDN=${if (fqdn.isNotEmpty()) fqdn else "<none>"}")

        // Log unavailability of carrier/platform-restricted mechanisms honestly
        Log.d(TAG, "P-CSCF DHCP Option 120 unavailable: cellular DHCP options not exposed to non-carrier app")
        Log.d(TAG, "P-CSCF 3GPP NAS PCO unavailable: requires carrier privileges")
        Log.d(TAG, "P-CSCF UICC ISIM EF_PCSCF unavailable: requires carrier privileges (READ_PRIVILEGED_PHONE_STATE)")

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
        } else {
            Log.i(TAG, "P-CSCF DNS discovery: no P-CSCF FQDN provisioned")
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
            Log.i(TAG, "P-CSCF discovery result=${selection.source.name} ${selection.endpoint.toHostPort()}")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Discovered(selection)
        } else {
            // Static legacy fallback is selected ONLY after all implemented discovery fails
            val fallbackReason = if (fqdn.isEmpty()) {
                "no P-CSCF FQDN provisioned"
            } else {
                "DNS resolution for '$fqdn' returned no addresses"
            }
            Log.i(TAG, "P-CSCF fallback selected because=$fallbackReason")

            val fallbackEndpoint = legacyFallback.copy(source = PcscfDiscoverySource.STATIC_LEGACY)
            selection = SelectedPcscf(
                endpoint = fallbackEndpoint,
                source = PcscfDiscoverySource.STATIC_LEGACY,
                isFallback = true,
                networkInfo = network?.toString(),
                candidatesEvaluated = emptyList(),
                statusDetail = fallbackReason
            )
            Log.i(TAG, "P-CSCF discovery result=${selection.source.name} ${selection.endpoint.toHostPort()}")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Fallback(selection, fallbackReason)
        }

        selection
    }

    override fun selectManualOverride(endpoint: PcscfEndpoint) {
        val isLegacy = endpoint.source == PcscfDiscoverySource.STATIC_LEGACY
        val selection = SelectedPcscf(
            endpoint = endpoint,
            source = endpoint.source,
            isFallback = isLegacy,
            statusDetail = if (isLegacy) "Manual legacy static fallback" else "Manual override"
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
