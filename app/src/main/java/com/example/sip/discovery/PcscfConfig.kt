package com.example.sip.discovery

/**
 * 3GPP TS 24.229 P-CSCF Discovery Methods.
 */
enum class PcscfDiscoveryMethod(val displayName: String) {
    PRECONFIGURED("PRECONFIGURED"),
    NETWORK_DNS("NETWORK_DNS")
}

/**
 * Authoritative single configuration location for P-CSCF settings.
 *
 * Supports two selectable discovery methods:
 * 1. PRECONFIGURED:
 *    - Preconfigured host (IPv4 literal or FQDN provisioned via runtime configuration)
 *    - Configurable port (default 5060)
 *    - Transport (default UDP)
 * 2. NETWORK_DNS:
 *    - P-CSCF FQDN (e.g. pcscf.ims.mnc070.mcc901.3gppnetwork.org)
 *    - Resolution executed strictly via the active cellular MCPTT Network
 *    - Fallback to preconfigured parameters if DNS fails
 */
data class PcscfConfig(
    val method: PcscfDiscoveryMethod = PcscfDiscoveryMethod.PRECONFIGURED,
    val preconfiguredHost: String = "",
    val preconfiguredPort: Int = 5060,
    val dnsFqdn: String = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
    val dnsPort: Int = 5060,
    val transport: String = "UDP"
) {
    /**
     * Converts preconfigured parameters to a PcscfEndpoint.
     */
    fun toPreconfiguredEndpoint(source: PcscfDiscoverySource = PcscfDiscoverySource.PRECONFIGURED): PcscfEndpoint {
        val h = preconfiguredHost.trim()
        val isLiteral = isIpv4Literal(h) || h.contains(":")
        return PcscfEndpoint(
            host = h,
            port = preconfiguredPort,
            resolvedIp = if (isLiteral) h else null,
            source = source,
            timestamp = System.currentTimeMillis(),
            transport = transport
        )
    }

    companion object {
        fun isIpv4Literal(host: String): Boolean {
            val parts = host.split(".")
            if (parts.size != 4) return false
            return parts.all { it.toIntOrNull() in 0..255 }
        }
    }
}
