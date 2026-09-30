package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.example.sip.discovery.PcscfConfig
import com.example.sip.discovery.PcscfDiscoveryMethod
import com.example.sip.engine.ApnNetworkStatus
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityBackground
import com.example.ui.theme.HighDensityBorder
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensitySurface
import com.example.ui.theme.HighDensitySurfaceVariant
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.HighDensityWarning

@Composable
fun SettingsScreen(viewModel: McpttViewModel) {
    val context = LocalContext.current
    val currentProfile by viewModel.sipProfile.collectAsState()
    val netStatus by viewModel.apnNetworkStatus.collectAsState()

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // TOP HEADER
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = HighDensityNavy.copy(alpha = 0.1f),
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = HighDensityNavy,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "RADIO CONFIGURATION",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = HighDensityNavy,
                    letterSpacing = 0.5.sp
                )
                Text(
                    text = "Mission Critical Comms Profile & Network Settings",
                    fontSize = 11.sp,
                    color = HighDensityTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // NETWORK ADVISORY & STATUS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = when (netStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> HighDensityNavy
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CELLULAR INTERFACE STATUS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityNavy,
                        letterSpacing = 0.5.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = HighDensitySurfaceVariant,
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

        // QUICK PRESETS
        SettingsSectionHeader(title = "PRESET CONFIGURATIONS", icon = Icons.Default.FlashOn)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    imsi = "491234567890123"
                    displayName = "MCPTT UE-1"
                    mcpttId = "sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfMethod = PcscfDiscoveryMethod.PRECONFIGURED
                    pcscfPort = "5060"
                    pcscfFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfDnsPort = "5060"
                    apnName = "mcptt"
                    apnPrefix = "192.168.102."
                    localSipPort = "5062"
                    localRtpPort = "6000"
                    targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org"
                    scscfOrigRoute = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr"
                },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityNavy.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Tactical Unit 1", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    imsi = "491234567890124"
                    displayName = "MCPTT UE-2"
                    mcpttId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfMethod = PcscfDiscoveryMethod.PRECONFIGURED
                    pcscfPort = "5060"
                    pcscfFqdn = "pcscf.ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfDnsPort = "5060"
                    apnName = "mcptt"
                    apnPrefix = "192.168.102."
                    localSipPort = "5064"
                    localRtpPort = "6004"
                    targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org"
                    scscfOrigRoute = "sip:orig@scscf.ims.mnc070.mcc901.3gppnetwork.org:5060;lr"
                },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityNavy.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text("Tactical Unit 2", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 1: OPERATOR & SUBSCRIBER IDENTITY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "OPERATOR & IMS CREDENTIALS", icon = Icons.Default.Badge)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedField("Display Name / Tactical Call-Sign", displayName) { displayName = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("IMSI / Auth Username", imsi) {
                    imsi = it
                    mcpttId = "sip:$it@$realm"
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("MCPTT IMPU URI", mcpttId) { mcpttId = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("IMS Realm / Domain", realm) { realm = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("SIP Digest Password", password) { password = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("SIP User-Agent", userAgent) { userAgent = it }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 2: CELLULAR APN & INTERFACE
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
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
                        OutlinedField("APN Name", apnName) { apnName = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedField("IPv4 Subnet Prefix", apnPrefix) { apnPrefix = it }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedField("Local SIP Port", localSipPort) { localSipPort = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedField("Local RTP Port", localRtpPort) { localRtpPort = it }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 3: P-CSCF CORE DISCOVERY
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "P-CSCF PROXY DISCOVERY", icon = Icons.Default.Dns)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Select discovery mechanism across cellular radio interface:",
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
                        label = { Text("PRECONFIGURED", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HighDensityNavy,
                            selectedLabelColor = Color.White
                        ),
                        modifier = Modifier.weight(1f).testTag("method_preconfigured")
                    )
                    FilterChip(
                        selected = pcscfMethod == PcscfDiscoveryMethod.NETWORK_DNS,
                        onClick = { pcscfMethod = PcscfDiscoveryMethod.NETWORK_DNS },
                        label = { Text("NETWORK_DNS", fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = HighDensityNavy,
                            selectedLabelColor = Color.White
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
                        OutlinedField("Preconfigured Host / IP", pcscfHost) { pcscfHost = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedField("Port", pcscfPort) { pcscfPort = it }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(modifier = Modifier.weight(2f)) {
                        OutlinedField("P-CSCF FQDN (DNS)", pcscfFqdn) { pcscfFqdn = it }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedField("DNS Port", pcscfDnsPort) { pcscfDnsPort = it }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 4: TALKGROUP & ROUTING
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                SettingsSectionHeader(title = "TALKGROUP & S-CSCF ROUTING", icon = Icons.Default.AltRoute)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedField("Target Talkgroup URI", targetGroup) { targetGroup = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("S-CSCF Orig-Route URI", scscfOrigRoute) { scscfOrigRoute = it }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedField("MCPTT AS Fallback URI", asFallbackUri) { asFallbackUri = it }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 5: OPERATIONAL TOGGLES
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
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
                        colors = SwitchDefaults.colors(checkedThumbColor = HighDensityNavy, checkedTrackColor = HighDensityNavy.copy(alpha = 0.5f))
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
                        colors = SwitchDefaults.colors(checkedThumbColor = HighDensityNavy, checkedTrackColor = HighDensityNavy.copy(alpha = 0.5f))
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
                        colors = SwitchDefaults.colors(checkedThumbColor = HighDensityNavy, checkedTrackColor = HighDensityNavy.copy(alpha = 0.5f))
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SAVE BUTTON
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
                .height(50.dp)
                .testTag("save_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = HighDensityNavy),
            shape = RoundedCornerShape(12.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & APPLY CONFIGURATION", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = HighDensityNavy,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityNavy,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun OutlinedField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 11.sp, color = HighDensityTextSecondary) },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = HighDensitySurface,
            unfocusedContainerColor = HighDensitySurface,
            focusedBorderColor = HighDensityNavy,
            unfocusedBorderColor = HighDensityBorder,
            focusedTextColor = HighDensityTextPrimary,
            unfocusedTextColor = HighDensityTextPrimary
        ),
        shape = RoundedCornerShape(10.dp),
        singleLine = true
    )
}
