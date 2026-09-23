package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.sip.model.SipProfile
import com.example.sip.model.SipRoutingMode
import com.example.sip.model.SipTrafficLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class McpttRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mcptt_prefs", Context.MODE_PRIVATE)

    private val _sipProfile = MutableStateFlow(loadProfile())
    val sipProfile: StateFlow<SipProfile> = _sipProfile.asStateFlow()

    private val _logs = MutableStateFlow<List<SipTrafficLog>>(emptyList())
    val logs: StateFlow<List<SipTrafficLog>> = _logs.asStateFlow()

    fun loadProfile(): SipProfile {
        var pcscf = prefs.getString("pcscf_host", "172.30.104.240") ?: "172.30.104.240"
        if (pcscf == "172.22.0.21") {
            pcscf = "172.30.104.240"
        }
        var asPort = prefs.getInt("mcptt_as_port", 5070)
        if (asPort == 5060 && !prefs.contains("mcptt_as_port_v2")) {
            asPort = 5070
        }

        return SipProfile(
            displayName = prefs.getString("display_name", "MCPTT UE-1") ?: "MCPTT UE-1",
            mcpttId = prefs.getString("mcptt_id", "sip:mcptt_user1@ims.mnc070.mcc901.3gppnetwork.org")
                ?: "sip:mcptt_user1@ims.mnc070.mcc901.3gppnetwork.org",
            realm = prefs.getString("realm", "ims.mnc070.mcc901.3gppnetwork.org")
                ?: "ims.mnc070.mcc901.3gppnetwork.org",
            password = prefs.getString("password", "password123") ?: "password123",
            pcscfHost = pcscf,
            pcscfPort = prefs.getInt("pcscf_port", 5060),
            mcpttAsHost = prefs.getString("mcptt_as_host", "172.30.104.240") ?: "172.30.104.240",
            mcpttAsPort = asPort,
            sipRoutingMode = SipRoutingMode.DIRECT_AS,
            userAgent = prefs.getString("user_agent", "MCPTT-Android-PoC/1.0") ?: "MCPTT-Android-PoC/1.0",
            localSipPort = prefs.getInt("local_sip_port", 5062),
            localRtpPort = prefs.getInt("local_rtp_port", 40000),
            targetGroup = prefs.getString("target_group", "sip:mcptt_group_fire@ims.mnc070.mcc901.3gppnetwork.org")
                ?: "sip:mcptt_group_fire@ims.mnc070.mcc901.3gppnetwork.org",
            emergencyGroup = prefs.getString(
                "emergency_group",
                "sip:mcptt_group_emergency@ims.mnc070.mcc901.3gppnetwork.org"
            ) ?: "sip:mcptt_group_emergency@ims.mnc070.mcc901.3gppnetwork.org",
            transport = prefs.getString("transport", "UDP") ?: "UDP",
            autoRegister = prefs.getBoolean("auto_register", false),
            includeMcpttTags = prefs.getBoolean("include_mcptt_tags", true),
            autoGrantFloor = prefs.getBoolean("auto_grant_floor", false)
        )
    }

    fun saveProfile(profile: SipProfile) {
        prefs.edit().apply {
            putString("display_name", profile.displayName)
            putString("mcptt_id", profile.mcpttId)
            putString("realm", profile.realm)
            putString("password", profile.password)
            putString("pcscf_host", profile.pcscfHost)
            putInt("pcscf_port", profile.pcscfPort)
            putString("mcptt_as_host", profile.mcpttAsHost)
            putInt("mcptt_as_port", profile.mcpttAsPort)
            putBoolean("mcptt_as_port_v2", true)
            putString("sip_routing_mode", profile.sipRoutingMode.name)
            putString("user_agent", profile.userAgent)
            putInt("local_sip_port", profile.localSipPort)
            putInt("local_rtp_port", profile.localRtpPort)
            putString("target_group", profile.targetGroup)
            putString("emergency_group", profile.emergencyGroup)
            putString("transport", profile.transport)
            putBoolean("auto_register", profile.autoRegister)
            putBoolean("include_mcptt_tags", profile.includeMcpttTags)
            putBoolean("auto_grant_floor", profile.autoGrantFloor)
            apply()
        }
        _sipProfile.value = profile
    }

    fun addLog(log: SipTrafficLog) {
        val current = _logs.value.toMutableList()
        current.add(0, log)
        if (current.size > 200) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
