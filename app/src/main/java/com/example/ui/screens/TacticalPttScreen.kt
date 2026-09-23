package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.sip.engine.ApnNetworkStatus
import com.example.sip.engine.CallSessionState
import com.example.sip.engine.FloorState
import com.example.sip.engine.RegistrationState
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityBackground
import com.example.ui.theme.HighDensityBorder
import com.example.ui.theme.HighDensityEmergency
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensityPttRed
import com.example.ui.theme.HighDensityPttRedDark
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensitySurface
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.HighDensityWarning

@Composable
fun TacticalPttScreen(viewModel: McpttViewModel) {
    val context = LocalContext.current
    val registrationState by viewModel.registrationState.collectAsState()
    val failureReason by viewModel.registrationFailureReason.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val floorState by viewModel.floorState.collectAsState()
    val activeSpeaker by viewModel.activeSpeaker.collectAsState()
    val floorBusy by viewModel.floorBusy.collectAsState()
    val profile by viewModel.sipProfile.collectAsState()
    val audioLevel by viewModel.micAudioLevel.collectAsState()
    val networkStatus by viewModel.apnNetworkStatus.collectAsState()
    val negotiatedMedia by viewModel.negotiatedMedia.collectAsState()
    val rtpTxCount by viewModel.rtpTxCount.collectAsState()
    val rtpRxCount by viewModel.rtpRxCount.collectAsState()
    val boundRtpPort by viewModel.boundRtpPort.collectAsState()

    var isPttHeld by remember { mutableStateOf(false) }
    var quickMsgText by remember { mutableStateOf("") }
    var showApnHelpDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isRegistered = registrationState == RegistrationState.REGISTERED

    val pttColor by animateColorAsState(
        targetValue = when {
            !isRegistered -> Color(0xFF64748B)
            floorBusy -> HighDensityEmergency
            floorState == FloorState.GRANTED -> HighDensitySecondary
            floorState == FloorState.REQUESTING -> HighDensityWarning
            floorState == FloorState.LISTENING -> Color(0xFF475569)
            floorState == FloorState.RELEASING -> Color(0xFF64748B)
            else -> if (isPttHeld) HighDensityPttRedDark else HighDensityPttRed
        },
        label = "pttColor"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .padding(14.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // TOP: MCPTT APN Network Status Card (Conforms to MCPTT-APN-SETUP.md)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("apn_network_card"),
            colors = CardDefaults.cardColors(
                containerColor = when (networkStatus) {
                    is ApnNetworkStatus.Bound -> HighDensitySurface
                    is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning.copy(alpha = 0.15f)
                    else -> HighDensitySurface
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when (networkStatus) {
                    is ApnNetworkStatus.Bound -> HighDensitySecondary
                    is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                    else -> HighDensityBorder
                }
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = "APN Status",
                        tint = when (networkStatus) {
                            is ApnNetworkStatus.Bound -> HighDensitySecondary
                            is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                            else -> HighDensityTextSecondary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NET: ",
                                fontSize = 11.sp,
                                color = HighDensityTextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = when (val s = networkStatus) {
                                    is ApnNetworkStatus.Bound -> "bound ${s.ip}:${profile.localSipPort} [${s.apnName}]"
                                    is ApnNetworkStatus.NoMcpttPdn -> "No mcptt PDN (${s.detectedIp ?: "none"})"
                                    is ApnNetworkStatus.Scanning -> "Scanning mcptt PDN..."
                                    is ApnNetworkStatus.Disconnected -> "Cellular Disconnected"
                                },
                                fontSize = 11.sp,
                                color = when (networkStatus) {
                                    is ApnNetworkStatus.Bound -> HighDensitySecondary
                                    is ApnNetworkStatus.NoMcpttPdn -> HighDensityWarning
                                    else -> HighDensityTextPrimary
                                },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (networkStatus is ApnNetworkStatus.NoMcpttPdn) {
                            Text(
                                text = "Tap here for APN setup help",
                                fontSize = 10.sp,
                                color = HighDensityWarning,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .pointerInput(Unit) {
                                        detectTapGestures { showApnHelpDialog = true }
                                    }
                            )
                        } else {
                            Text(
                                text = "P-CSCF: ${profile.pcscfHost}:${profile.pcscfPort}",
                                fontSize = 10.sp,
                                color = HighDensityTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { viewModel.refreshNetwork() },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Refresh APN binding",
                        tint = HighDensityNavy,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // IMS REGISTRATION BANNER
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ims_status_card"),
            colors = CardDefaults.cardColors(containerColor = HighDensityNavy),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (registrationState) {
                            RegistrationState.REGISTERED -> Icons.Default.CheckCircle
                            RegistrationState.AUTHENTICATING -> Icons.Default.WarningAmber
                            RegistrationState.REGISTERING -> Icons.Default.WarningAmber
                            RegistrationState.MCPTT_APN_BOUND -> Icons.Default.CellTower
                            RegistrationState.REGISTRATION_FAILED -> Icons.Default.Warning
                            RegistrationState.NETWORK_UNAVAILABLE -> Icons.Default.Warning
                            RegistrationState.UNREGISTERED -> Icons.Default.Warning
                        },
                        contentDescription = "IMS Status",
                        tint = when (registrationState) {
                            RegistrationState.REGISTERED -> Color(0xFF4ADE80)
                            RegistrationState.AUTHENTICATING -> HighDensityWarning
                            RegistrationState.REGISTERING -> HighDensityWarning
                            RegistrationState.MCPTT_APN_BOUND -> Color(0xFF60A5FA)
                            RegistrationState.REGISTRATION_FAILED -> HighDensityEmergency
                            RegistrationState.NETWORK_UNAVAILABLE -> Color(0xFFEF4444)
                            RegistrationState.UNREGISTERED -> Color.Gray
                        },
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "3GPP MCPTT SIP / S-CSCF",
                            fontSize = 10.sp,
                            color = Color.White.copy(alpha = 0.7f),
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = when (registrationState) {
                                RegistrationState.REGISTERED -> "REGISTERED"
                                RegistrationState.AUTHENTICATING -> "AUTHENTICATING (401 MD5)"
                                RegistrationState.REGISTERING -> "REGISTERING..."
                                RegistrationState.MCPTT_APN_BOUND -> "MCPTT APN BOUND"
                                RegistrationState.NETWORK_UNAVAILABLE -> "NETWORK UNAVAILABLE"
                                RegistrationState.REGISTRATION_FAILED -> "REGISTRATION FAILED"
                                RegistrationState.UNREGISTERED -> "UNREGISTERED"
                            },
                            fontSize = 13.sp,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        if (registrationState == RegistrationState.REGISTRATION_FAILED && !failureReason.isNullOrBlank()) {
                            Text(
                                text = failureReason ?: "",
                                fontSize = 10.sp,
                                color = HighDensityEmergency,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                }

                if (registrationState != RegistrationState.REGISTERED) {
                    val isAuthenticating = registrationState == RegistrationState.AUTHENTICATING || registrationState == RegistrationState.REGISTERING
                    Button(
                        onClick = { viewModel.registerSip() },
                        enabled = !isAuthenticating,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            disabledContainerColor = Color.White.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.testTag("register_button")
                    ) {
                        Text(
                            if (isAuthenticating) "WAIT..." else "REGISTER",
                            fontSize = 11.sp,
                            color = HighDensityNavy,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // CHANNEL & SESSION STATUS
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ACTIVE TALKGROUP",
                        fontSize = 10.sp,
                        color = HighDensityTextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = profile.targetGroup.substringAfter("sip:").substringBefore("@"),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityTextPrimary
                    )
                    Text(
                        text = "IMSI: ${profile.imsi} • Call: ${callState.name}",
                        fontSize = 10.sp,
                        color = HighDensityNavy,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (negotiatedMedia != null)
                            "RTP: ${negotiatedMedia?.host}:${negotiatedMedia?.rtpPort} | TX: $rtpTxCount RX: $rtpRxCount"
                        else
                            "RTP Port: $boundRtpPort (Idle) | TX: $rtpTxCount RX: $rtpRxCount",
                        fontSize = 9.sp,
                        color = HighDensityTextSecondary,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (callState == CallSessionState.CONNECTED)
                                HighDensitySecondary.copy(alpha = 0.15f)
                            else
                                HighDensityNavy.copy(alpha = 0.1f)
                        )
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (callState == CallSessionState.CONNECTED) "ACTIVE CALL" else "IDLE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (callState == CallSessionState.CONNECTED) HighDensitySecondary else HighDensityNavy
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // FLOOR CONTROL BANNER
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    floorBusy -> HighDensityEmergency.copy(alpha = 0.20f)
                    floorState == FloorState.GRANTED -> HighDensitySecondary.copy(alpha = 0.15f)
                    floorState == FloorState.REQUESTING -> HighDensityWarning.copy(alpha = 0.15f)
                    floorState == FloorState.LISTENING -> Color(0xFF334155).copy(alpha = 0.5f)
                    else -> HighDensitySurface
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                when {
                    floorBusy -> HighDensityEmergency
                    floorState == FloorState.GRANTED -> HighDensitySecondary
                    floorState == FloorState.REQUESTING -> HighDensityWarning
                    floorState == FloorState.LISTENING -> Color(0xFF64748B)
                    else -> HighDensityBorder
                }
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        floorBusy -> "⚠ FLOOR BUSY - REQUEST DENIED"
                        floorState == FloorState.GRANTED -> "● FLOOR GRANTED - TRANSMITTING AUDIO"
                        floorState == FloorState.REQUESTING -> "▲ REQUESTING FLOOR (INFO)..."
                        floorState == FloorState.LISTENING -> "■ LISTENING - ${activeSpeaker?.let { "$it IS " } ?: "REMOTE "}SPEAKING"
                        floorState == FloorState.RELEASING -> "▼ RELEASING FLOOR..."
                        callState == CallSessionState.CONNECTED -> "Floor Idle / Ready to Transmit"
                        else -> "Call Session Idle"
                    },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        floorBusy -> HighDensityEmergency
                        floorState == FloorState.GRANTED -> HighDensitySecondary
                        floorState == FloorState.REQUESTING -> HighDensityWarning
                        floorState == FloorState.LISTENING -> Color(0xFF94A3B8)
                        else -> HighDensityTextSecondary
                    },
                    fontFamily = FontFamily.Monospace
                )

                if (activeSpeaker != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (floorState == FloorState.GRANTED) "Transmitting: $activeSpeaker" else "Active Speaker: $activeSpeaker",
                        fontSize = 11.sp,
                        color = HighDensityTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (floorState == FloorState.GRANTED || audioLevel > 0f) {
                    Spacer(modifier = Modifier.height(8.dp))
                    AudioMeterBar(audioLevel = audioLevel)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // GIANT PTT BUTTON
        val canHoldPtt = isRegistered && floorState != FloorState.LISTENING && !floorBusy
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(if (isPttHeld && floorState == FloorState.GRANTED) pulseScale else 1f)
                .clip(CircleShape)
                .background(pttColor)
                .border(8.dp, HighDensitySurface, CircleShape)
                .testTag("ptt_button")
                .pointerInput(isRegistered, floorState, floorBusy) {
                    if (canHoldPtt) {
                        detectTapGestures(
                            onPress = {
                                isPttHeld = true
                                viewModel.onPttPressed()
                                tryAwaitRelease()
                                isPttHeld = false
                                viewModel.onPttReleased()
                            }
                        )
                    } else if (isRegistered) {
                        detectTapGestures(
                            onTap = {
                                viewModel.onPttPressed()
                            }
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = when {
                        !isRegistered -> Icons.Default.MicNone
                        floorBusy -> Icons.Default.Warning
                        floorState == FloorState.LISTENING -> Icons.Default.VolumeUp
                        floorState == FloorState.GRANTED -> Icons.Default.Mic
                        floorState == FloorState.REQUESTING -> Icons.Default.CellTower
                        isPttHeld -> Icons.Default.Mic
                        else -> Icons.Default.MicNone
                    },
                    contentDescription = "Push To Talk",
                    tint = Color.White,
                    modifier = Modifier.size(50.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = when {
                        !isRegistered -> "NOT REGISTERED"
                        floorBusy -> "BUSY"
                        floorState == FloorState.GRANTED -> "SPEAK NOW"
                        floorState == FloorState.REQUESTING -> "WAIT..."
                        floorState == FloorState.LISTENING -> "LISTENING"
                        floorState == FloorState.RELEASING -> "RELEASING"
                        callState != CallSessionState.CONNECTED -> "START CALL"
                        else -> "PUSH TO TALK"
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // SESSION ACTION BUTTONS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (callState == CallSessionState.CONNECTED) {
                Button(
                    onClick = { viewModel.endCallSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = HighDensityEmergency),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("end_call_button")
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("END SESSION", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.startCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = HighDensityNavy),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("initiate_call_button")
                ) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("START CALL", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Button(
                onClick = { viewModel.subscribeGroup() },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityNavy),
                modifier = Modifier
                    .weight(1f)
                    .testTag("subscribe_button")
            ) {
                Text("SUBSCRIBE", color = HighDensityNavy, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // QUICK MESSAGE BROADCAST & EMERGENCY SOS
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = quickMsgText,
                    onValueChange = { quickMsgText = it },
                    placeholder = { Text("Send SIP MESSAGE (+g.3gpp.mcptt)", fontSize = 11.sp, color = HighDensityTextSecondary) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_msg_input"),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = HighDensitySurface,
                        unfocusedContainerColor = HighDensitySurface,
                        focusedBorderColor = HighDensityNavy,
                        unfocusedBorderColor = HighDensityBorder
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (quickMsgText.isNotBlank()) {
                            viewModel.sendTextMessage(profile.targetGroup, quickMsgText)
                            quickMsgText = ""
                        }
                    },
                    modifier = Modifier
                        .background(HighDensityNavy, RoundedCornerShape(8.dp))
                        .testTag("send_msg_button")
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send", tint = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // EMERGENCY BROADCAST BUTTON
            Button(
                onClick = { viewModel.triggerEmergencyAlert("EMERGENCY SOS ALERT") },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensityEmergency),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("emergency_sos_button")
            ) {
                Icon(Icons.Default.Warning, contentDescription = "SOS", tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "EMERGENCY BROADCAST (+g.3gpp.mcptt.emergency)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }
    }

    if (showApnHelpDialog) {
        AlertDialog(
            onDismissRequest = { showApnHelpDialog = false },
            title = { Text("MCPTT APN Configuration", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "To route SIP and RTP packets over Open5GS MCPTT PDN (Path 2):\n\n" +
                    "1. In Android Settings -> Network -> SIMs -> Access Point Names (APNs):\n" +
                    "2. Add or select APN with Name='mcptt', APN='mcptt', APN Type='default'.\n" +
                    "3. Set 'mcptt' as preferred active mobile data APN.\n" +
                    "4. Confirm UE IP is assigned from pool 192.168.102.x.\n" +
                    "5. P-CSCF destination is 172.30.104.240:5060.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showApnHelpDialog = false }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
fun AudioMeterBar(audioLevel: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(HighDensityBorder)
    ) {
        val activeWidth = size.width * audioLevel.coerceIn(0.05f, 1f)
        drawRect(
            color = if (audioLevel > 0.7f) HighDensityEmergency else HighDensitySecondary,
            size = androidx.compose.ui.geometry.Size(activeWidth, size.height)
        )
    }
}
