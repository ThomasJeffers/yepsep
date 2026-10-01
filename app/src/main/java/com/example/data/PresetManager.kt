package com.example.data

import android.content.Context
import android.content.SharedPreferences
import com.example.sip.discovery.PcscfConfig
import com.example.sip.discovery.PcscfDiscoveryMethod
import com.example.sip.model.SipProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class McpttPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val displayName: String,
    val imsi: String,
    val mcpttId: String,
    val realm: String,
    val password: String,
    val pcscfMethod: PcscfDiscoveryMethod,
    val pcscfHost: String,
    val pcscfPort: Int = 5060,
    val pcscfFqdn: String,
    val pcscfDnsPort: Int = 5060,
    val apnName: String = "mcptt",
    val apnPrefix: String = "192.168.102.",
    val localSipPort: Int = 5062,
    val localRtpPort: Int = 6000,
    val targetGroup: String = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
    val scscfOrigRoute: String = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr",
    val asFallbackUri: String = "",
    val userAgent: String = "MCPTT-Exp5-UAC",
    val isDeletable: Boolean = true
)

class PresetManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("mcptt_presets_store", Context.MODE_PRIVATE)

    private val _presets = MutableStateFlow<List<McpttPreset>>(emptyList())
    val presets: StateFlow<List<McpttPreset>> = _presets.asStateFlow()

    private val _selectedPresetId = MutableStateFlow<String?>(null)
    val selectedPresetId: StateFlow<String?> = _selectedPresetId.asStateFlow()

    init {
        loadPresets()
    }

    private fun loadPresets() {
        val raw = prefs.getString("presets_list_json", null)
        val list = if (!raw.isNullOrBlank()) {
            try {
                parsePresets(raw)
            } catch (e: Exception) {
                defaultPresets()
            }
        } else {
            defaultPresets()
        }
        _presets.value = list
        if (_selectedPresetId.value == null && list.isNotEmpty()) {
            _selectedPresetId.value = list.first().id
        }
    }

    private fun defaultPresets(): List<McpttPreset> {
        return listOf(
            McpttPreset(
                id = "preset_ue1",
                name = "Tactical Unit 1 (Alpha)",
                description = "Primary field responder on IMS mnc070 mcc901",
                displayName = "MCPTT UE-1",
                imsi = "491234567890123",
                mcpttId = "sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org",
                realm = "ims.mnc070.mcc901.3gppnetwork.org",
                password = "password123",
                pcscfMethod = PcscfDiscoveryMethod.PRECONFIGURED,
                pcscfHost = "172.22.0.21",
                pcscfPort = 5060,
                pcscfFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
                pcscfDnsPort = 5060,
                apnName = "mcptt",
                apnPrefix = "192.168.102.",
                localSipPort = 5062,
                localRtpPort = 6000,
                targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                scscfOrigRoute = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr",
                isDeletable = false
            ),
            McpttPreset(
                id = "preset_ue2",
                name = "Tactical Unit 2 (Bravo)",
                description = "Secondary field responder on IMS mnc070 mcc901",
                displayName = "MCPTT UE-2",
                imsi = "491234567890124",
                mcpttId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org",
                realm = "ims.mnc070.mcc901.3gppnetwork.org",
                password = "password123",
                pcscfMethod = PcscfDiscoveryMethod.PRECONFIGURED,
                pcscfHost = "172.22.0.21",
                pcscfPort = 5060,
                pcscfFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
                pcscfDnsPort = 5060,
                apnName = "mcptt",
                apnPrefix = "192.168.102.",
                localSipPort = 5064,
                localRtpPort = 6004,
                targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                scscfOrigRoute = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr",
                isDeletable = false
            ),
            McpttPreset(
                id = "preset_dns_testbed",
                name = "Cellular DNS Testbed",
                description = "Standard 3GPP P-CSCF discovery via cellular DNS A queries",
                displayName = "MCPTT Cellular UE",
                imsi = "901700000052769",
                mcpttId = "sip:901700000052769@ims.mnc070.mcc901.3gppnetwork.org",
                realm = "ims.mnc070.mcc901.3gppnetwork.org",
                password = "password123",
                pcscfMethod = PcscfDiscoveryMethod.NETWORK_DNS,
                pcscfHost = "",
                pcscfPort = 5060,
                pcscfFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org",
                pcscfDnsPort = 5060,
                apnName = "mcptt",
                apnPrefix = "192.168.102.",
                localSipPort = 5062,
                localRtpPort = 6000,
                targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org",
                scscfOrigRoute = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr",
                isDeletable = true
            )
        )
    }

    fun selectPreset(id: String) {
        _selectedPresetId.value = id
    }

    fun savePreset(preset: McpttPreset) {
        val current = _presets.value.toMutableList()
        val index = current.indexOfFirst { it.id == preset.id }
        if (index >= 0) {
            current[index] = preset
        } else {
            current.add(preset)
        }
        _presets.value = current
        _selectedPresetId.value = preset.id
        persistPresets(current)
    }

    fun createPresetFrom(name: String, baseProfile: SipProfile): McpttPreset {
        val newPreset = McpttPreset(
            id = "preset_" + UUID.randomUUID().toString().take(8),
            name = name.trim().ifBlank { "Custom Profile ${System.currentTimeMillis() % 1000}" },
            description = "Custom tactical radio preset",
            displayName = baseProfile.displayName,
            imsi = baseProfile.imsi,
            mcpttId = baseProfile.mcpttId,
            realm = baseProfile.realm,
            password = baseProfile.password,
            pcscfMethod = baseProfile.effectivePcscfConfig.method,
            pcscfHost = baseProfile.effectivePcscfConfig.preconfiguredHost,
            pcscfPort = baseProfile.effectivePcscfConfig.preconfiguredPort,
            pcscfFqdn = baseProfile.effectivePcscfConfig.dnsFqdn,
            pcscfDnsPort = baseProfile.effectivePcscfConfig.dnsPort,
            apnName = baseProfile.apnName,
            apnPrefix = baseProfile.apnPrefix,
            localSipPort = baseProfile.localSipPort,
            localRtpPort = baseProfile.localRtpPort,
            targetGroup = baseProfile.targetGroup,
            scscfOrigRoute = baseProfile.scscfOrigRoute,
            asFallbackUri = baseProfile.asFallbackUri,
            userAgent = baseProfile.userAgent,
            isDeletable = true
        )

        val updated = _presets.value + newPreset
        _presets.value = updated
        _selectedPresetId.value = newPreset.id
        persistPresets(updated)
        return newPreset
    }

    fun deletePreset(id: String): Boolean {
        val current = _presets.value
        val target = current.find { it.id == id } ?: return false
        if (!target.isDeletable && current.size <= 2) return false

        val updated = current.filter { it.id != id }
        _presets.value = updated
        if (_selectedPresetId.value == id) {
            _selectedPresetId.value = updated.firstOrNull()?.id
        }
        persistPresets(updated)
        return true
    }

    fun toSipProfile(preset: McpttPreset, currentProfile: SipProfile): SipProfile {
        val pcscfConfig = PcscfConfig(
            method = preset.pcscfMethod,
            preconfiguredHost = preset.pcscfHost,
            preconfiguredPort = preset.pcscfPort,
            dnsFqdn = preset.pcscfFqdn,
            dnsPort = preset.pcscfDnsPort
        )
        return currentProfile.copy(
            displayName = preset.displayName,
            imsi = preset.imsi,
            mcpttId = preset.mcpttId,
            realm = preset.realm,
            password = preset.password,
            pcscfConfig = pcscfConfig,
            pcscfHost = preset.pcscfHost,
            pcscfPort = preset.pcscfPort,
            pcscfFqdn = preset.pcscfFqdn,
            apnName = preset.apnName,
            apnPrefix = preset.apnPrefix,
            localSipPort = preset.localSipPort,
            localRtpPort = preset.localRtpPort,
            targetGroup = preset.targetGroup,
            scscfOrigRoute = preset.scscfOrigRoute,
            asFallbackUri = preset.asFallbackUri,
            userAgent = preset.userAgent
        )
    }

    private fun persistPresets(list: List<McpttPreset>) {
        try {
            val array = JSONArray()
            for (p in list) {
                val obj = JSONObject()
                obj.put("id", p.id)
                obj.put("name", p.name)
                obj.put("description", p.description)
                obj.put("displayName", p.displayName)
                obj.put("imsi", p.imsi)
                obj.put("mcpttId", p.mcpttId)
                obj.put("realm", p.realm)
                obj.put("password", p.password)
                obj.put("pcscfMethod", p.pcscfMethod.name)
                obj.put("pcscfHost", p.pcscfHost)
                obj.put("pcscfPort", p.pcscfPort)
                obj.put("pcscfFqdn", p.pcscfFqdn)
                obj.put("pcscfDnsPort", p.pcscfDnsPort)
                obj.put("apnName", p.apnName)
                obj.put("apnPrefix", p.apnPrefix)
                obj.put("localSipPort", p.localSipPort)
                obj.put("localRtpPort", p.localRtpPort)
                obj.put("targetGroup", p.targetGroup)
                obj.put("scscfOrigRoute", p.scscfOrigRoute)
                obj.put("asFallbackUri", p.asFallbackUri)
                obj.put("userAgent", p.userAgent)
                obj.put("isDeletable", p.isDeletable)
                array.put(obj)
            }
            prefs.edit().putString("presets_list_json", array.toString()).apply()
        } catch (e: Exception) {
            android.util.Log.e("PresetManager", "Failed to persist presets: ${e.message}")
        }
    }

    private fun parsePresets(jsonStr: String): List<McpttPreset> {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<McpttPreset>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                McpttPreset(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    description = obj.optString("description", ""),
                    displayName = obj.getString("displayName"),
                    imsi = obj.getString("imsi"),
                    mcpttId = obj.getString("mcpttId"),
                    realm = obj.getString("realm"),
                    password = obj.getString("password"),
                    pcscfMethod = try {
                        PcscfDiscoveryMethod.valueOf(obj.getString("pcscfMethod"))
                    } catch (e: Exception) {
                        PcscfDiscoveryMethod.PRECONFIGURED
                    },
                    pcscfHost = obj.optString("pcscfHost", ""),
                    pcscfPort = obj.optInt("pcscfPort", 5060),
                    pcscfFqdn = obj.optString("pcscfFqdn", ""),
                    pcscfDnsPort = obj.optInt("pcscfDnsPort", 5060),
                    apnName = obj.optString("apnName", "mcptt"),
                    apnPrefix = obj.optString("apnPrefix", "192.168.102."),
                    localSipPort = obj.optInt("localSipPort", 5062),
                    localRtpPort = obj.optInt("localRtpPort", 6000),
                    targetGroup = obj.optString("targetGroup", "sip:group1@ims.mnc070.mcc901.3gppnetwork.org"),
                    scscfOrigRoute = obj.optString("scscfOrigRoute", ""),
                    asFallbackUri = obj.optString("asFallbackUri", ""),
                    userAgent = obj.optString("userAgent", "MCPTT-Exp5-UAC"),
                    isDeletable = obj.optBoolean("isDeletable", true)
                )
            )
        }
        return if (list.isNotEmpty()) list else defaultPresets()
    }
}
