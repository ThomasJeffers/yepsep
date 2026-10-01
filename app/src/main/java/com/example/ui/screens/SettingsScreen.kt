package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.McpttPreset
import com.example.sip.discovery.PcscfConfig
import com.example.sip.discovery.PcscfDiscoveryMethod
import com.example.sip.engine.ApnNetworkStatus
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityEmergency
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.HighDensityWarning
import com.example.ui.theme.TacticalConsoleDark
import com.example.ui.theme.TacticalCyanGlow
import com.example.ui.theme.TacticalHairlineBorder
import com.example.ui.theme.TacticalPlateInset
import com.example.ui.theme.TacticalPlateRaised
import com.example.ui.theme.TacticalPlateSurface

@Composable
fun SettingsScreen(viewModel: McpttViewModel) {
    val context = LocalContext.current
    val currentProfile by viewModel.sipProfile.collectAsState()
    val netStatus by viewModel.apnNetworkStatus.collectAsState()
    val presets by viewModel.presetManager.presets.collectAsState()
    val selectedPresetId by viewModel.presetManager.selectedPresetId.collectAsState()

    var displayName by remember(currentProfile) { mutableStateOf(currentProfile.displayName) }
    var apnName by remember(currentProfile) { mutableStateOf(currentProfile.apnName) }
    var apnPrefix by remember(currentProfile) { mutableStateOf(currentProfile.apnPrefix) }
    var imsi by remember(currentProfile) { mutableStateOf(currentProfile.imsi) }
    var mcpttId by remember(currentProfile) { mutableStateOf(currentProfile.mcpttId) }
    var realm by remember(currentProfile) { mutableStateOf(currentProfile.realm) }
    var password by remember(currentProfile) { mutableStateOf(currentProfile.password) }
    var pcscfMethod by remember(currentProfile) { mutableStateOf(currentProfile.effectivePcscfConfig.method) }
    var pcscfHost by remember(currentProfile) { mutableStateOf(currentProfile.effectivePcscfConfig.preconfiguredHost) }
    var pcscfPort by remember(currentProfile) { mutableStateOf(currentProfile.effectivePcscfConfig.preconfiguredPort.toString()) }
    var pcscfFqdn by remember(currentProfile) { mutableStateOf(currentProfile.effectivePcscfConfig.dnsFqdn) }
    var pcscfDnsPort by remember(currentProfile) { mutableStateOf(currentProfile.effectivePcscfConfig.dnsPort.toString()) }
    var mcpttAsHost by remember(currentProfile) { mutableStateOf(currentProfile.mcpttAsHost) }
    var mcpttAsPort by remember(currentProfile) { mutableStateOf(currentProfile.mcpttAsPort.toString()) }
    var userAgent by remember(currentProfile) { mutableStateOf(currentProfile.userAgent) }
    var localSipPort by remember(currentProfile) { mutableStateOf(currentProfile.localSipPort.toString()) }
    var localRtpPort by remember(currentProfile) { mutableStateOf(currentProfile.localRtpPort.toString()) }
    var targetGroup by remember(currentProfile) { mutableStateOf(currentProfile.targetGroup) }
    var scscfOrigRoute by remember(currentProfile) { mutableStateOf(currentProfile.scscfOrigRoute) }
    var asFallbackUri by remember(currentProfile) { mutableStateOf(currentProfile.asFallbackUri) }

    var autoRegister by remember(currentProfile) { mutableStateOf(currentProfile.autoRegister) }
    var includeMcpttTags by remember(currentProfile) { mutableStateOf(currentProfile.includeMcpttTags) }
    var autoGrantFloor by remember(currentProfile) { mutableStateOf(currentProfile.autoGrantFloor) }

    var showNewPresetDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val activePreset = presets.find { it.id == selectedPresetId }

    // Helper to load preset into form
    fun loadPresetToForm(p: McpttPreset) {
        displayName = p.displayName
        imsi = p.imsi
        mcpttId = p.mcpttId
        realm = p.realm
        password = p.password
        pcscfMethod = p.pcscfMethod
        pcscfHost = p.pcscfHost
        pcscfPort = p.pcscfPort.toString()
        pcscfFqdn = p.pcscfFqdn
        pcscfDnsPort = p.pcscfDnsPort.toString()
        apnName = p.apnName
        apnPrefix = p.apnPrefix
        localSipPort = p.localSipPort.toString()
        localRtpPort = p.localRtpPort.toString()
        targetGroup = p.targetGroup
        scscfOrigRoute = p.scscfOrigRoute
        asFallbackUri = p.asFallbackUri
        userAgent = p.userAgent
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalConsoleDark)
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // TOP HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = TacticalPlateRaised,
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalCyanGlow.copy(alpha = 0.5f)),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = TacticalCyanGlow,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "RADIO PROFILE & PRESETS",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "Tactical Presets, APN Binding & Core Configuration",
                    fontSize = 11.sp,
                    color = HighDensityTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ==========================================
        // EDITABLE PRESETS MANAGER SECTION
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bookmark,
                            contentDescription = null,
                            tint = TacticalCyanGlow,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "RADIO PRESET PROFILES",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyanGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // "+ NEW PRESET" Button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalPlateRaised,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                        modifier = Modifier.padding(2.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { showNewPresetDialog = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "New Preset",
                                    tint = TacticalCyanGlow,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = "NEW PRESET",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = TacticalCyanGlow
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Scrollable Preset Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(presets) { preset ->
                        val isSelected = preset.id == selectedPresetId
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.selectPreset(preset.id)
                                loadPresetToForm(preset)
                                Toast.makeText(context, "Loaded preset: ${preset.name}", Toast.LENGTH_SHORT).show()
                            },
                            label = {
                                Text(
                                    text = preset.name,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontFamily = FontFamily.Monospace
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = TacticalCyanGlow,
                                selectedLabelColor = Color.White,
                                containerColor = TacticalPlateRaised,
                                labelColor = HighDensityTextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = if (isSelected) TacticalCyanGlow else TacticalHairlineBorder
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Active Preset Action Controls: Apply, Save to Preset, Delete
                activePreset?.let { p ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalPlateInset,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Active Preset: ${p.name}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            if (p.description.isNotBlank()) {
                                Text(
                                    text = p.description,
                                    fontSize = 10.sp,
                                    color = HighDensityTextSecondary
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Apply Preset to Radio
                                Button(
                                    onClick = {
                                        viewModel.applyPreset(p)
                                        Toast.makeText(context, "Applied ${p.name} to Radio Stack", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = HighDensitySecondary),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("APPLY", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                // Save Changes to This Preset
                                Button(
                                    onClick = {
                                        val updatedPreset = p.copy(
                                            displayName = displayName.trim(),
                                            imsi = imsi.trim(),
                                            mcpttId = mcpttId.trim(),
                                            realm = realm.trim(),
                                            password = password.trim(),
                                            pcscfMethod = pcscfMethod,
                                            pcscfHost = pcscfHost.trim(),
                                            pcscfPort = pcscfPort.toIntOrNull() ?: 5060,
                                            pcscfFqdn = pcscfFqdn.trim(),
                                            pcscfDnsPort = pcscfDnsPort.toIntOrNull() ?: 5060,
                                            apnName = apnName.trim(),
                                            apnPrefix = apnPrefix.trim(),
                                            localSipPort = localSipPort.toIntOrNull() ?: 5062,
                                            localRtpPort = localRtpPort.toIntOrNull() ?: 6000,
                                            targetGroup = targetGroup.trim(),
                                            scscfOrigRoute = scscfOrigRoute.trim(),
                                            asFallbackUri = asFallbackUri.trim(),
                                            userAgent = userAgent.trim()
                                        )
                                        viewModel.savePreset(updatedPreset)
                                        Toast.makeText(context, "Saved changes to ${p.name}", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = TacticalCyanGlow),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1.3f).height(38.dp)
                                ) {
                                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("SAVE TO PRESET", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }

                                // Delete Preset
                                IconButton(
                                    onClick = { showDeleteConfirmDialog = true },
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(HighDensityEmergency.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Preset",
                                        tint = HighDensityEmergency,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // NETWORK ADVISORY & INTERFACE STATUS CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = when (netStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> TacticalCyanGlow
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CELLULAR BEARER STATUS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TacticalCyanGlow,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TacticalPlateInset,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = netStatus.displaySummary(),
                        fontSize = 11.sp,
                        color = when (netStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> HighDensityTextPrimary
                        },
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 1: OPERATOR & SUBSCRIBER IDENTITY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "OPERATOR & IMS CREDENTIALS", icon = Icons.Default.Badge)
                Spacer(modifier = Modifier.height(10.dp))
                TacticalOutlinedField("Display Name / Tactical Call-Sign", displayName) { displayName = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("IMSI / Auth Username", imsi) {
                    imsi = it
                    mcpttId = "sip:$it@$realm"
                }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("MCPTT IMPU URI", mcpttId) { mcpttId = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("IMS Realm / Domain", realm) { realm = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("SIP Digest Password", password) { password = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("SIP User-Agent", userAgent) { userAgent = it }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 2: CELLULAR APN & INTERFACE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "CELLULAR APN & LOCAL SOCKETS", icon = Icons.Default.CellTower)
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("APN Name", apnName) { apnName = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("IPv4 Subnet Prefix", apnPrefix) { apnPrefix = it }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("Local SIP Port", localSipPort) { localSipPort = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("Local RTP Port", localRtpPort) { localRtpPort = it }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 3: P-CSCF CORE DISCOVERY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "P-CSCF PROXY DISCOVERY", icon = Icons.Default.Dns)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select 3GPP discovery method across MCPTT cellular network:",
                    fontSize = 11.sp,
                    color = HighDensityTextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = pcscfMethod == PcscfDiscoveryMethod.PRECONFIGURED,
                        onClick = { pcscfMethod = PcscfDiscoveryMethod.PRECONFIGURED },
                        label = { Text("PRECONFIGURED", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TacticalCyanGlow,
                            selectedLabelColor = Color.White,
                            containerColor = TacticalPlateRaised,
                            labelColor = HighDensityTextPrimary
                        ),
                        modifier = Modifier.weight(1f).testTag("method_preconfigured")
                    )
                    FilterChip(
                        selected = pcscfMethod == PcscfDiscoveryMethod.NETWORK_DNS,
                        onClick = { pcscfMethod = PcscfDiscoveryMethod.NETWORK_DNS },
                        label = { Text("NETWORK_DNS", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = TacticalCyanGlow,
                            selectedLabelColor = Color.White,
                            containerColor = TacticalPlateRaised,
                            labelColor = HighDensityTextPrimary
                        ),
                        modifier = Modifier.weight(1f).testTag("method_network_dns")
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(2f)) {
                        TacticalOutlinedField("Preconfigured Host / IP", pcscfHost) { pcscfHost = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("Port", pcscfPort) { pcscfPort = it }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(2f)) {
                        TacticalOutlinedField("P-CSCF FQDN (DNS)", pcscfFqdn) { pcscfFqdn = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        TacticalOutlinedField("DNS Port", pcscfDnsPort) { pcscfDnsPort = it }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 4: TALKGROUP & ROUTING
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "TALKGROUP & S-CSCF ROUTING", icon = Icons.Default.AltRoute)
                Spacer(modifier = Modifier.height(10.dp))
                TacticalOutlinedField("Target Talkgroup URI", targetGroup) { targetGroup = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("S-CSCF Orig-Route URI", scscfOrigRoute) { scscfOrigRoute = it }
                Spacer(modifier = Modifier.height(8.dp))
                TacticalOutlinedField("MCPTT AS Fallback URI", asFallbackUri) { asFallbackUri = it }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 5: OPERATIONAL TOGGLES
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "OPERATIONAL POLICIES", icon = Icons.Default.Tune)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Register on Launch", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Initiates registration sequence upon radio startup", fontSize = 11.sp, color = HighDensityTextSecondary)
                    }
                    Switch(
                        checked = autoRegister,
                        onCheckedChange = { autoRegister = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TacticalCyanGlow, checkedTrackColor = TacticalCyanGlow.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Inject +g.3gpp.mcptt Tags", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Includes MCPTT 3GPP feature tags in SIP headers", fontSize = 11.sp, color = HighDensityTextSecondary)
                    }
                    Switch(
                        checked = includeMcpttTags,
                        onCheckedChange = { includeMcpttTags = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TacticalCyanGlow, checkedTrackColor = TacticalCyanGlow.copy(alpha = 0.5f))
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Grant Floor (Simulation)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Locally grants floor immediately on 200 OK", fontSize = 11.sp, color = HighDensityTextSecondary)
                    }
                    Switch(
                        checked = autoGrantFloor,
                        onCheckedChange = { autoGrantFloor = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = TacticalCyanGlow, checkedTrackColor = TacticalCyanGlow.copy(alpha = 0.5f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SAVE & APPLY CONFIGURATION BUTTON (Preserving test tag save_settings_button)
        Button(
            onClick = {
                val pcscfConfig = PcscfConfig(
                    method = pcscfMethod,
                    preconfiguredHost = pcscfHost.trim(),
                    preconfiguredPort = pcscfPort.toIntOrNull() ?: 5060,
                    dnsFqdn = pcscfFqdn.trim(),
                    dnsPort = pcscfDnsPort.toIntOrNull() ?: 5060
                )
                val updated = currentProfile.copy(
                    displayName = displayName.trim(),
                    apnName = apnName.trim(),
                    apnPrefix = apnPrefix.trim(),
                    imsi = imsi.trim(),
                    mcpttId = mcpttId.trim(),
                    realm = realm.trim(),
                    password = password.trim(),
                    pcscfConfig = pcscfConfig,
                    pcscfHost = pcscfConfig.preconfiguredHost,
                    pcscfPort = pcscfConfig.preconfiguredPort,
                    pcscfFqdn = pcscfConfig.dnsFqdn,
                    mcpttAsHost = mcpttAsHost.trim(),
                    mcpttAsPort = mcpttAsPort.toIntOrNull() ?: 5060,
                    userAgent = userAgent.trim(),
                    localSipPort = localSipPort.toIntOrNull() ?: 5062,
                    localRtpPort = localRtpPort.toIntOrNull() ?: 6000,
                    targetGroup = targetGroup.trim(),
                    scscfOrigRoute = scscfOrigRoute.trim(),
                    asFallbackUri = asFallbackUri.trim(),
                    autoRegister = autoRegister,
                    includeMcpttTags = includeMcpttTags,
                    autoGrantFloor = autoGrantFloor
                )
                viewModel.updateProfile(updated)
                Toast.makeText(context, "Radio Profile & APN Configuration Saved", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("save_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = TacticalCyanGlow),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & APPLY CONFIGURATION", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    // Dialog for creating a new preset
    if (showNewPresetDialog) {
        var presetNameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewPresetDialog = false },
            title = { Text("CREATE NEW RADIO PRESET", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary) },
            text = {
                Column {
                    Text(
                        text = "Current settings will be cloned into a persistent new radio preset profile:",
                        fontSize = 11.sp,
                        color = HighDensityTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        label = { Text("Preset Profile Name") },
                        placeholder = { Text("e.g. Tactical Squad 3") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (presetNameInput.isNotBlank()) {
                            viewModel.createPreset(presetNameInput.trim())
                            showNewPresetDialog = false
                            Toast.makeText(context, "Created preset: $presetNameInput", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalCyanGlow)
                ) {
                    Text("CREATE PRESET")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewPresetDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    // Dialog for confirming preset deletion
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("DELETE PRESET PROFILE", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HighDensityEmergency) },
            text = {
                Text("Are you sure you want to delete preset '${activePreset?.name}'? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        activePreset?.let {
                            val deleted = viewModel.deletePreset(it.id)
                            if (deleted) {
                                Toast.makeText(context, "Preset deleted", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Default preset cannot be deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                        showDeleteConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = HighDensityEmergency)
                ) {
                    Text("DELETE")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TacticalCyanGlow,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = TacticalCyanGlow,
            letterSpacing = 0.5.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun TacticalOutlinedField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp, color = HighDensityTextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = TacticalPlateRaised,
            unfocusedContainerColor = TacticalPlateRaised,
            focusedBorderColor = TacticalCyanGlow,
            unfocusedBorderColor = TacticalHairlineBorder,
            focusedTextColor = HighDensityTextPrimary,
            unfocusedTextColor = HighDensityTextPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        singleLine = true
    )
}
