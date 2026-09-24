package com.example.sip.discovery

/**
 * 3GPP TS 24.229 & TS 23.228 P-CSCF Discovery Sources.
 * Explicitly distinguishes standards-defined sources and indicates availability
 * to a standard non-carrier Android application.
 */
enum class PcscfDiscoverySource(
    val displayName: String,
    val isAvailableToStandardApp: Boolean,
    val description: String
) {
    STATIC_LEGACY(
        displayName = "Static Fallback",
        isAvailableToStandardApp = true,
        description = "Configured legacy static P-CSCF address used for backward-compatible fallback"
    ),
    DNS_A_AAAA(
        displayName = "Cellular DNS (A/AAAA)",
        isAvailableToStandardApp = true,
        description = "Standard 3GPP P-CSCF FQDN resolution over the bound MCPTT cellular network"
    ),
    DNS_SRV(
        displayName = "Cellular DNS (SRV)",
        isAvailableToStandardApp = true,
        description = "RFC 3263 SIP server resolution over the bound MCPTT cellular network"
    ),
    DHCP_OPTION_120(
        displayName = "DHCPv4/v6 Option 120",
        isAvailableToStandardApp = false,
        description = "RFC 3361/3319 DHCP SIP servers. NOT_AVAILABLE_TO_THIS_APP (Android framework restricts raw cellular DHCP options)"
    ),
    PCO_PROVISIONED(
        displayName = "3GPP NAS PCO",
        isAvailableToStandardApp = false,
        description = "TS 24.008 Protocol Configuration Options from Attach/PDN. NOT_AVAILABLE_TO_THIS_APP (Requires carrier privileges)"
    ),
    ISIM_EF_PCSCF(
        displayName = "UICC ISIM EF_PCSCF",
        isAvailableToStandardApp = false,
        description = "3GPP TS 31.103 ISIM Elementary File 0x6F09. NOT_AVAILABLE_TO_THIS_APP (Requires READ_PRIVILEGED_PHONE_STATE)"
    ),
    MANUAL_OVERRIDE(
        displayName = "Manual Override",
        isAvailableToStandardApp = true,
        description = "Explicit test injection or operator manual override"
    )
}

/**
 * Represents an individual P-CSCF destination candidate.
 */
data class PcscfEndpoint(
    val host: String,
    val port: Int = 5060,
    val transport: String = "UDP",
    val source: PcscfDiscoverySource = PcscfDiscoverySource.STATIC_LEGACY,
    val priority: Int = 0,
    val weight: Int = 0,
    val isIpv6: Boolean = host.contains(":")
) {
    fun toSipUri(): String = "sip:$host:$port;transport=${transport.lowercase()}"
    fun toHostPort(): String = "$host:$port"
}

/**
 * Authoritative runtime object representing the active P-CSCF selected by the discovery engine.
 */
data class SelectedPcscf(
    val endpoint: PcscfEndpoint,
    val source: PcscfDiscoverySource,
    val isFallback: Boolean,
    val selectedAt: Long = System.currentTimeMillis(),
    val networkInfo: String? = null,
    val candidatesEvaluated: List<PcscfEndpoint> = emptyList(),
    val statusDetail: String = ""
) {
    val host: String get() = endpoint.host
    val port: Int get() = endpoint.port
    val transport: String get() = endpoint.transport

    fun summary(): String {
        val tag = if (isFallback) "[FALLBACK: ${source.displayName}]" else "[DISCOVERED: ${source.displayName}]"
        return "$tag ${endpoint.toHostPort()}"
    }
}

/**
 * Lifecycle states for P-CSCF discovery.
 */
sealed class PcscfDiscoveryState {
    object Idle : PcscfDiscoveryState() {
        override fun toString(): String = "IDLE"
    }

    data class Discovering(val attemptSource: PcscfDiscoverySource) : PcscfDiscoveryState() {
        override fun toString(): String = "DISCOVERING ($attemptSource)"
    }

    data class Discovered(val selected: SelectedPcscf) : PcscfDiscoveryState() {
        override fun toString(): String = "DISCOVERED (${selected.endpoint.toHostPort()})"
    }

    data class Fallback(val selected: SelectedPcscf, val reason: String) : PcscfDiscoveryState() {
        override fun toString(): String = "FALLBACK (${selected.endpoint.toHostPort()} - $reason)"
    }

    data class Failed(val error: String) : PcscfDiscoveryState() {
        override fun toString(): String = "FAILED ($error)"
    }
}
