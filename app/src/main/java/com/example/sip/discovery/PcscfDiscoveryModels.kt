package com.example.sip.discovery

/**
 * 3GPP TS 24.229 & TS 23.228 P-CSCF Acquisition and Discovery Sources.
 * Explicitly distinguishes standards-defined sources and indicates availability
 * to a standard non-carrier Android application.
 */
enum class PcscfDiscoverySource(
    val displayName: String,
    val isAvailableToStandardApp: Boolean,
    val isImplemented: Boolean,
    val description: String
) {
    STATIC_LEGACY(
        displayName = "STATIC_LEGACY",
        isAvailableToStandardApp = true,
        isImplemented = true,
        description = "Configured legacy static P-CSCF address used for backward-compatible fallback"
    ),
    DNS_A_AAAA(
        displayName = "DNS_A_AAAA",
        isAvailableToStandardApp = true,
        isImplemented = true,
        description = "A/AAAA resolution of an explicitly supplied/known P-CSCF FQDN over the bound MCPTT cellular network"
    ),
    DNS_SRV(
        displayName = "DNS_SRV",
        isAvailableToStandardApp = false,
        isImplemented = false,
        description = "RFC 3263 SIP server resolution over cellular DNS (Not currently implemented in this build; marked future/unsupported)"
    ),
    DHCP_OPTION_120(
        displayName = "DHCP_OPTION_120",
        isAvailableToStandardApp = false,
        isImplemented = false,
        description = "RFC 3361/3319 DHCP SIP servers. NOT_AVAILABLE_TO_THIS_APP (Android framework restricts raw cellular DHCP options to standard APKs)"
    ),
    PCO_PROVISIONED(
        displayName = "PCO_PROVISIONED",
        isAvailableToStandardApp = false,
        isImplemented = false,
        description = "TS 24.008 Protocol Configuration Options from Attach/PDN. NOT_AVAILABLE_TO_THIS_APP (Requires carrier privileges)"
    ),
    ISIM_EF_PCSCF(
        displayName = "ISIM_EF_PCSCF",
        isAvailableToStandardApp = false,
        isImplemented = false,
        description = "3GPP TS 31.103 ISIM Elementary File 0x6F09. NOT_AVAILABLE_TO_THIS_APP (Requires READ_PRIVILEGED_PHONE_STATE)"
    ),
    MANUAL_OVERRIDE(
        displayName = "MANUAL_OVERRIDE",
        isAvailableToStandardApp = true,
        isImplemented = true,
        description = "Explicit test injection or operator manual override"
    )
}

/**
 * Represents an individual P-CSCF destination candidate.
 * Formats host correctly according to RFC 3986 / RFC 3261 (e.g. [2001:db8::1] for IPv6).
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
    /**
     * Returns the host string formatted safely for URIs.
     * If the host is an IPv6 literal and not already enclosed in brackets, encloses it in brackets.
     */
    fun formattedHostForUri(): String {
        return if (isIpv6) {
            val clean = host.trim()
            if (clean.startsWith("[") && clean.endsWith("]")) clean else "[$clean]"
        } else {
            host.trim()
        }
    }

    /**
     * Generates a valid SIP URI with IPv6 bracket safety.
     * E.g. sip:[2001:db8::1]:5060;transport=udp
     */
    fun toSipUri(): String = "sip:${formattedHostForUri()}:$port;transport=${transport.lowercase()}"

    /**
     * Generates host:port representation with IPv6 bracket safety.
     * E.g. [2001:db8::1]:5060 or 172.30.104.240:5060
     */
    fun toHostPort(): String = "${formattedHostForUri()}:$port"
}

/**
 * Authoritative runtime object representing the active P-CSCF selected by the acquisition engine.
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
        val tag = if (isFallback) "[FALLBACK: ${source.name}]" else "[DISCOVERED: ${source.name}]"
        return "$tag ${endpoint.toHostPort()}"
    }
}

/**
 * Lifecycle states for P-CSCF acquisition/discovery.
 */
sealed class PcscfDiscoveryState {
    object Idle : PcscfDiscoveryState() {
        override fun toString(): String = "IDLE"
    }

    data class Discovering(val attemptSource: PcscfDiscoverySource = PcscfDiscoverySource.DNS_A_AAAA) : PcscfDiscoveryState() {
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
