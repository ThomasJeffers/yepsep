package com.example.sip.model

import com.example.sip.discovery.PcscfConfig
import com.example.sip.discovery.PcscfDiscoveryMethod
import com.example.sip.discovery.PcscfEndpoint

data class SipProfile(
    val displayName: String = "MCPTT UE-1",
    val apnName: String = "mcptt",
    val apnPrefix: String = "192.168.102.",
    val imsi: String = "901700000052769",
    val mcpttId: String = "sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org",
    val realm: String = "ims.mnc070.mcc901.3gppnetwork.org",
    val password: String = "2D97C18692BDA7F1D44A7275E414FD8D",
    val pcscfConfig: PcscfConfig = PcscfConfig(),
    val pcscfHost: String = pcscfConfig.preconfiguredHost,
    val pcscfPort: Int = pcscfConfig.preconfiguredPort,
    val pcscfFqdn: String = pcscfConfig.dnsFqdn,
    val mcpttAsHost: String = "mcptt-as.ims.mnc070.mcc901.3gppnetwork.org",
    val mcpttAsPort: Int = 5070,
    val scscfOrigRoute: String = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:6060;lr",
    val asFallbackUri: String = "",
    val userAgent: String = "MCPTT-Exp5-UAC",
    val localSipPort: Int = 5062,
    val localRtpPort: Int = 6000,
    val targetGroup: String = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
    val emergencyGroup: String = "sip:emergency@ims.mnc070.mcc901.3gppnetwork.org",
    val transport: String = "UDP",
    val autoRegister: Boolean = true,
    val includeMcpttTags: Boolean = true,
    val autoGrantFloor: Boolean = false
) {
    val pcscfDiscoveryMethod: PcscfDiscoveryMethod get() = effectivePcscfConfig.method

    val effectivePcscfConfig: PcscfConfig get() {
        return if (pcscfConfig.preconfiguredHost != pcscfHost ||
            pcscfConfig.preconfiguredPort != pcscfPort ||
            pcscfConfig.dnsFqdn != pcscfFqdn) {
            pcscfConfig.copy(
                preconfiguredHost = pcscfHost,
                preconfiguredPort = pcscfPort,
                dnsFqdn = pcscfFqdn
            )
        } else {
            pcscfConfig
        }
    }

    val pcscfEndpoint: PcscfEndpoint get() = effectivePcscfConfig.toPreconfiguredEndpoint()

    fun sipDestinationHost(): String = effectivePcscfConfig.preconfiguredHost
    fun sipDestinationPort(): Int = effectivePcscfConfig.preconfiguredPort
    fun sipDestinationLabel(): String = "P-CSCF → ${sipDestinationHost()}:${sipDestinationPort()}"
}
