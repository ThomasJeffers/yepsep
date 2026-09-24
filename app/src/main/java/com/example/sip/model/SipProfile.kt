package com.example.sip.model

data class SipProfile(
    val displayName: String = "MCPTT UE-1",
    val apnName: String = "mcptt",
    val apnPrefix: String = "192.168.102.",
    val imsi: String = "901700000052769",
    val mcpttId: String = "sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org",
    val realm: String = "ims.mnc070.mcc901.3gppnetwork.org",
    val password: String = "2D97C18692BDA7F1D44A7275E414FD8D",
    val pcscfHost: String = "172.30.104.240",
    val pcscfPort: Int = 5060,
    val pcscfFqdn: String = "",
    val mcpttAsHost: String = "172.30.104.240",
    val mcpttAsPort: Int = 5070,
    val scscfOrigRoute: String = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:6060;lr",
    val asFallbackUri: String = "sip:172.30.104.240:5070;transport=udp",
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
    fun sipDestinationHost(): String = pcscfHost
    fun sipDestinationPort(): Int = pcscfPort
    fun sipDestinationLabel(): String = "P-CSCF → ${pcscfHost}:${pcscfPort}"
}
