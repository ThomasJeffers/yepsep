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
 * Clean client-side abstraction for discovering and selecting the P-CSCF endpoint.
 */
interface PcscfDiscoveryProvider {
    val discoveryState: StateFlow<PcscfDiscoveryState>
    val selectedPcscf: StateFlow<SelectedPcscf?>

    /**
     * Executes discovery against the active cellular network or falls back cleanly to legacy configuration.
     */
    suspend fun discover(
        network: Network?,
        realm: String,
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
 * Implements 3GPP discovery over the bound cellular Network:
 * 1. Resolves candidate P-CSCF FQDNs using the cellular network's DNS:
 *    - pcscf.<realm>
 *    - sip.<realm>
 * 2. Validates candidates.
 * 3. Selects best candidate deterministically.
 * 4. If DNS discovery fails or network has no DNS mapping, cleanly falls back
 *    to the legacy static P-CSCF, clearly tagged as STATIC_LEGACY with isFallback=true.
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
        realm: String,
        legacyFallback: PcscfEndpoint
    ): SelectedPcscf = withContext(Dispatchers.IO) {
        Log.i(TAG, "P-CSCF discovery started. Network: $network, Realm: $realm, LegacyFallback: ${legacyFallback.toHostPort()}")
        _discoveryState.value = PcscfDiscoveryState.Discovering(PcscfDiscoverySource.DNS_A_AAAA)

        val candidates = mutableListOf<PcscfEndpoint>()

        // 1. Attempt cellular DNS discovery if network is bound or custom resolver is provided
        if ((network != null || dnsResolver != null) && realm.isNotBlank()) {
            val fqdnsToTry = listOf(
                "pcscf.$realm",
                "sip.$realm",
                realm
            )

            for (fqdn in fqdnsToTry) {
                try {
                    Log.d(TAG, "Querying cellular DNS for P-CSCF FQDN: $fqdn")
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
                            port = legacyFallback.port, // 5060 standard SIP
                            transport = legacyFallback.transport,
                            source = PcscfDiscoverySource.DNS_A_AAAA,
                            priority = 10,
                            weight = 0
                        )
                        if (!candidates.any { it.host == candidate.host && it.port == candidate.port }) {
                            Log.i(TAG, "Discovered Candidate P-CSCF via cellular DNS: ${candidate.toHostPort()} ($fqdn)")
                            candidates.add(candidate)
                        }
                    }
                    if (candidates.isNotEmpty()) {
                        break // Found candidates with preferred FQDN
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Cellular DNS lookup for $fqdn did not yield results: ${e.message}")
                }
            }
        } else {
            Log.i(TAG, "No active cellular network bound or realm empty; skipping cellular DNS discovery")
        }

        // 2. Evaluate candidates or fallback
        val selection: SelectedPcscf
        if (candidates.isNotEmpty()) {
            // Deterministic selection: prefer IPv4 for compatibility with current IPv4 bearer, or first candidate
            val bestCandidate = candidates.firstOrNull { !it.isIpv6 } ?: candidates.first()
            selection = SelectedPcscf(
                endpoint = bestCandidate,
                source = bestCandidate.source,
                isFallback = false,
                networkInfo = network?.toString(),
                candidatesEvaluated = candidates,
                statusDetail = "Successfully discovered ${candidates.size} candidate(s) via cellular DNS"
            )
            Log.i(TAG, "Selected P-CSCF [DISCOVERED]: ${selection.endpoint.toHostPort()} from source ${selection.source}")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Discovered(selection)
        } else {
            // Clean legacy fallback
            selection = SelectedPcscf(
                endpoint = legacyFallback.copy(source = PcscfDiscoverySource.STATIC_LEGACY),
                source = PcscfDiscoverySource.STATIC_LEGACY,
                isFallback = true,
                networkInfo = network?.toString(),
                candidatesEvaluated = emptyList(),
                statusDetail = "Cellular DNS returned no P-CSCF records; using legacy static configuration"
            )
            Log.i(TAG, "Falling back to legacy static P-CSCF: ${selection.endpoint.toHostPort()} (Fallback reason: ${selection.statusDetail})")
            _selectedPcscf.value = selection
            _discoveryState.value = PcscfDiscoveryState.Fallback(selection, selection.statusDetail)
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
