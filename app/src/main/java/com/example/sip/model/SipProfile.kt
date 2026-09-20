package com.example.sip.model

data class SipProfile(
    val displayName: String = "MCPTT UE-1",
    val apnName: String = "mcptt",
    val apnPrefix: String = "192.168.102.",
    val imsi: String = "491234567890123",
    val mcpttId: String = "sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org",
    val realm: String = "ims.mnc070.mcc901.3gppnetwork.org",
    val password: String = "password123",
    val pcscfHost: String = "172.30.104.240",
    val pcscfPort: Int = 5060,
    val userAgent: String = "MCPTT-Android-PoC/1.0",
    val localSipPort: Int = 5062,
    val localRtpPort: Int = 6000,
    val targetGroup: String = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
    val emergencyGroup: String = "sip:emergency@ims.mnc070.mcc901.3gppnetwork.org",
    val transport: String = "UDP",
    val autoRegister: Boolean = true,
    val includeMcpttTags: Boolean = true,
    val autoGrantFloor: Boolean = false
)
