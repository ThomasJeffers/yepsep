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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sip.engine.ApnNetworkStatus
import com.example.sip.model.SipProfile
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityBackground
import com.example.ui.theme.HighDensityBorder
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensitySurface
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
    var pcscfHost by remember(currentProfile) { mutableStateOf(currentProfile.pcscfHost) }
    var pcscfPort by remember(currentProfile) { mutableStateOf(currentProfile.pcscfPort.toString()) }
    var userAgent by remember(currentProfile) { mutableStateOf(currentProfile.userAgent) }
    var localSipPort by remember(currentProfile) { mutableStateOf(currentProfile.localSipPort.toString()) }
    var localRtpPort by remember(currentProfile) { mutableStateOf(currentProfile.localRtpPort.toString()) }
    var targetGroup by remember(currentProfile) { mutableStateOf(currentProfile.targetGroup) }

    var autoRegister by remember(currentProfile) { mutableStateOf(currentProfile.autoRegister) }
    var includeMcpttTags by remember(currentProfile) { mutableStateOf(currentProfile.includeMcpttTags) }
    var autoGrantFloor by remember(currentProfile) { mutableStateOf(currentProfile.autoGrantFloor) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "MCPTT APN & IMS SETTINGS",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityNavy,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Aligned with MCPTT-APN-SETUP.md (Path 2: Dedicated mcptt APN, type=default)",
            fontSize = 11.sp,
            color = HighDensityTextSecondary
        )

        Spacer(modifier = Modifier.height(12.dp))

        // NETWORK ADVISORY & APN STATUS CARD
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityNavy),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = when (netStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> HighDensityNavy
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "CURRENT BINDING: ${netStatus.displaySummary()}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = when (netStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> HighDensityNavy
                        },
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "SIP and RTP sockets bind to the cellular interface with pool 192.168.102.x. Set the phone's preferred mobile data APN to 'mcptt' (type=default) in SIM settings.",
                    fontSize = 11.sp,
                    color = HighDensityTextPrimary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // QUICK PRESET BUTTONS
        Text("QUICK ENVIRONMENT PRESETS", fontSize = 11.sp, color = HighDensityTextSecondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    imsi = "491234567890123"
                    displayName = "MCPTT UE-1"
                    mcpttId = "sip:491234567890123@ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfHost = "172.30.104.240"
                    pcscfPort = "5060"
                    apnName = "mcptt"
                    apnPrefix = "192.168.102."
                    localSipPort = "5062"
                    localRtpPort = "6000"
                    targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org"
                },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
                modifier = Modifier.weight(1f)
            ) {
                Text("UE-1 (IMSI ...123)", fontSize = 10.sp, color = HighDensityTextPrimary, fontWeight = FontWeight.Bold)
            }

            Button(
                onClick = {
                    imsi = "491234567890124"
                    displayName = "MCPTT UE-2"
                    mcpttId = "sip:491234567890124@ims.mnc070.mcc901.3gppnetwork.org"
                    pcscfHost = "172.30.104.240"
                    pcscfPort = "5060"
                    apnName = "mcptt"
                    apnPrefix = "192.168.102."
                    localSipPort = "5064"
                    localRtpPort = "6004"
                    targetGroup = "sip:group1@ims.mnc070.mcc901.3gppnetwork.org"
                },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
                modifier = Modifier.weight(1f)
            ) {
                Text("UE-2 (IMSI ...124)", fontSize = 10.sp, color = HighDensityTextPrimary, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // SECTION 1: MCPTT APN (PATH 2)
        Text("1. MCPTT APN & NETWORK BINDING", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedField("APN Name", apnName) { apnName = it }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedField("APN IPv4 Prefix", apnPrefix) { apnPrefix = it }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION 2: SIP PROXY / P-CSCF
        Text("2. SIP PROXY / P-CSCF (IMS ENTRY POINT)", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(2f)) {
                OutlinedField("P-CSCF Host IP", pcscfHost) { pcscfHost = it }
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedField("P-CSCF Port", pcscfPort) { pcscfPort = it }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION 3: SUBSCRIBER IDENTITY
        Text("3. SUBSCRIBER & IMS CREDENTIALS", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedField("Display Name / Rank", displayName) { displayName = it }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("IMSI / Auth Username", imsi) {
            imsi = it
            mcpttId = "sip:$it@$realm"
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("MCPTT IMPU URI", mcpttId) { mcpttId = it }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("IMS Domain / Realm", realm) { realm = it }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("SIP Digest Password", password) { password = it }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("SIP User-Agent", userAgent) { userAgent = it }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION 4: PORTS & GROUPS
        Text("4. LOCAL PORTS & TARGET TALKGROUP", fontSize = 11.sp, color = HighDensityNavy, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Spacer(modifier = Modifier.height(6.dp))
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
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedField("Target Talkgroup URI", targetGroup) { targetGroup = it }

        Spacer(modifier = Modifier.height(14.dp))

        // TOGGLES
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Auto Register on Start", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Sends REGISTER immediately when app starts", fontSize = 11.sp, color = HighDensityTextSecondary)
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
                    Column {
                        Text("Inject +g.3gpp.mcptt Tags", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Adds feature tag to Contact / Accept-Contact", fontSize = 11.sp, color = HighDensityTextSecondary)
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
                    Column {
                        Text("Auto Grant Floor (Debug Mode)", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = HighDensityTextPrimary)
                        Text("Instantly grants floor locally on 200 OK", fontSize = 11.sp, color = HighDensityTextSecondary)
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
                val updated = currentProfile.copy(
                    displayName = displayName.trim(),
                    apnName = apnName.trim(),
                    apnPrefix = apnPrefix.trim(),
                    imsi = imsi.trim(),
                    mcpttId = mcpttId.trim(),
                    realm = realm.trim(),
                    password = password.trim(),
                    pcscfHost = pcscfHost.trim(),
                    pcscfPort = pcscfPort.toIntOrNull() ?: 5060,
                    userAgent = userAgent.trim(),
                    localSipPort = localSipPort.toIntOrNull() ?: 5062,
                    localRtpPort = localRtpPort.toIntOrNull() ?: 6000,
                    targetGroup = targetGroup.trim(),
                    autoRegister = autoRegister,
                    includeMcpttTags = includeMcpttTags,
                    autoGrantFloor = autoGrantFloor
                )
                viewModel.updateProfile(updated)
                Toast.makeText(context, "MCPTT APN Settings Saved & Reloaded", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_settings_button"),
            colors = ButtonDefaults.buttonColors(containerColor = HighDensityNavy),
            shape = RoundedCornerShape(10.dp)
        ) {
            Icon(Icons.Default.Save, contentDescription = null, tint = androidx.compose.ui.graphics.Color.White)
            Spacer(modifier = Modifier.width(8.dp))
            Text("SAVE & BIND NETWORK", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))
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
        singleLine = true
    )
}
