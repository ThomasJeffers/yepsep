package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.ui.theme.HighDensitySurfaceVariant
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

    var isPttHeld by remember { mutableStateOf(false) }
    var quickMsgText by remember { mutableStateOf("") }
    var showQuickMsgPanel by remember { mutableStateOf(false) }

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
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isRegistered = registrationState == RegistrationState.REGISTERED
    val isDialogConnected = callState == CallSessionState.CONNECTED

    // PTT button color calculation
    val pttColor by animateColorAsState(
        targetValue = when {
            !isRegistered -> Color(0xFF64748B)
            floorBusy -> HighDensityEmergency
            floorState == FloorState.GRANTED -> HighDensitySecondary
            floorState == FloorState.REQUESTING -> HighDensityWarning
            floorState == FloorState.LISTENING -> Color(0xFF334155)
            floorState == FloorState.RELEASING -> Color(0xFF64748B)
            else -> if (isPttHeld) HighDensityPttRedDark else HighDensityPttRed
        },
        label = "pttColor"
    )

    // Resolve human-friendly group name from presets or clean URI
    val groupFriendlyName = remember(profile.targetGroup, viewModel.presetGroups) {
        viewModel.presetGroups.find { it.first.equals(profile.targetGroup, ignoreCase = true) }?.second
            ?: profile.targetGroup
                .substringAfter("sip:")
                .substringBefore("@")
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ==========================================
        // 1. TOP HEADER: OPERATOR IDENTITY & COMPACT STATUS
        // ==========================================
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ims_status_card"),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Top row: App title & Operator ID
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = HighDensityNavy.copy(alpha = 0.1f),
                            modifier = Modifier.size(28.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Security,
                                    contentDescription = null,
                                    tint = HighDensityNavy,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "MCPTT RADIO",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Black,
                                color = HighDensityNavy,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "TACTICAL COMMS",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityTextSecondary,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Operator Identity Pill
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HighDensitySurfaceVariant,
                        border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "UNIT: ",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityTextSecondary
                            )
                            Text(
                                text = profile.displayName.ifBlank { "UNIT 1" }.uppercase(),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Bottom row: Human-friendly connectivity status
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("apn_network_card"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Status LED + Human-readable text
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        val statusDotColor = when (registrationState) {
                            RegistrationState.REGISTERED -> Color(0xFF22C55E) // Bright tactical green
                            RegistrationState.AUTHENTICATING,
                            RegistrationState.REGISTERING -> HighDensityWarning // Amber
                            RegistrationState.MCPTT_APN_BOUND -> Color(0xFF3B82F6) // Blue
                            RegistrationState.REGISTRATION_FAILED,
                            RegistrationState.NETWORK_UNAVAILABLE -> HighDensityEmergency // Red
                            RegistrationState.UNREGISTERED -> Color(0xFF94A3B8) // Slate gray
                        }

                        // Glow indicator dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            val statusLabel = when (registrationState) {
                                RegistrationState.REGISTERED -> "CONNECTED • READY"
                                RegistrationState.AUTHENTICATING,
                                RegistrationState.REGISTERING -> "CONNECTING TO RADIO CORE..."
                                RegistrationState.MCPTT_APN_BOUND -> "NETWORK READY (PRESS REGISTER)"
                                RegistrationState.REGISTRATION_FAILED -> "REGISTRATION FAILED"
                                RegistrationState.NETWORK_UNAVAILABLE -> "NETWORK UNAVAILABLE"
                                RegistrationState.UNREGISTERED -> "OFFLINE • NOT CONNECTED"
                            }
                            Text(
                                text = statusLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = when (registrationState) {
                                    RegistrationState.REGISTERED -> Color(0xFF15803D)
                                    RegistrationState.REGISTRATION_FAILED -> HighDensityEmergency
                                    RegistrationState.AUTHENTICATING,
                                    RegistrationState.REGISTERING -> HighDensityWarning
                                    else -> HighDensityTextPrimary
                                }
                            )
                            if (registrationState == RegistrationState.REGISTRATION_FAILED && !failureReason.isNullOrBlank()) {
                                Text(
                                    text = failureReason ?: "",
                                    fontSize = 9.sp,
                                    color = HighDensityEmergency,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    // Action buttons (Register if offline, Refresh network)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (registrationState != RegistrationState.REGISTERED) {
                            val isConnecting = registrationState == RegistrationState.AUTHENTICATING ||
                                registrationState == RegistrationState.REGISTERING
                            Button(
                                onClick = { viewModel.registerSip() },
                                enabled = !isConnecting,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = HighDensityNavy,
                                    disabledContainerColor = HighDensityNavy.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                    horizontal = 10.dp,
                                    vertical = 4.dp
                                ),
                                modifier = Modifier
                                    .height(30.dp)
                                    .testTag("register_button")
                            ) {
                                Text(
                                    text = if (isConnecting) "WAIT..." else "CONNECT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        IconButton(
                            onClick = { viewModel.refreshNetwork() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh radio network",
                                tint = HighDensityNavy,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 2. ACTIVE TALKGROUP BANNER
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityNavy.copy(alpha = 0.2f)),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = HighDensityNavy,
                        modifier = Modifier.size(42.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Groups,
                                contentDescription = "Talkgroup",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "ACTIVE TALKGROUP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityNavy,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = groupFriendlyName,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = HighDensityTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Tactical Voice Channel",
                            fontSize = 10.sp,
                            color = HighDensityTextSecondary
                        )
                    }
                }

                // Call / Session Status Badge
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isDialogConnected) HighDensitySecondary.copy(alpha = 0.15f) else HighDensitySurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isDialogConnected) HighDensitySecondary.copy(alpha = 0.5f) else HighDensityBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isDialogConnected) HighDensitySecondary else Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isDialogConnected) "SESSION ACTIVE" else "STANDBY",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDialogConnected) HighDensitySecondary else HighDensityTextSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // ==========================================
        // 3. TACTICAL FLOOR STATUS BANNER & VU METER
        // ==========================================
        val isTransmitting = floorState == FloorState.GRANTED
        val isReceiving = floorState == FloorState.LISTENING

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when {
                    floorBusy -> HighDensityEmergency.copy(alpha = 0.10f)
                    isTransmitting -> HighDensitySecondary.copy(alpha = 0.12f)
                    floorState == FloorState.REQUESTING -> HighDensityWarning.copy(alpha = 0.12f)
                    isReceiving -> Color(0xFF334155).copy(alpha = 0.10f)
                    else -> HighDensitySurface
                }
            ),
            border = androidx.compose.foundation.BorderStroke(
                1.5.dp,
                when {
                    floorBusy -> HighDensityEmergency
                    isTransmitting -> HighDensitySecondary
                    floorState == FloorState.REQUESTING -> HighDensityWarning
                    isReceiving -> Color(0xFF64748B)
                    else -> HighDensityBorder
                }
            ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Status icon + Main Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when {
                            floorBusy -> Icons.Default.Warning
                            isTransmitting -> Icons.Default.Mic
                            floorState == FloorState.REQUESTING -> Icons.Default.CellTower
                            isReceiving -> Icons.Default.VolumeUp
                            floorState == FloorState.RELEASING -> Icons.Default.CellTower
                            isDialogConnected -> Icons.Default.Radio
                            else -> Icons.Default.PhoneInTalk
                        },
                        contentDescription = null,
                        tint = when {
                            floorBusy -> HighDensityEmergency
                            isTransmitting -> HighDensitySecondary
                            floorState == FloorState.REQUESTING -> HighDensityWarning
                            isReceiving -> Color(0xFF0F172A)
                            else -> HighDensityNavy
                        },
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            floorBusy -> "FLOOR BUSY"
                            isTransmitting -> "YOU HAVE THE FLOOR"
                            floorState == FloorState.REQUESTING -> "REQUESTING FLOOR..."
                            isReceiving -> "LISTENING — REMOTE SPEAKER"
                            floorState == FloorState.RELEASING -> "RELEASING FLOOR..."
                            isDialogConnected -> "READY TO TALK"
                            else -> "READY (START CALL TO TALK)"
                        },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Black,
                        color = when {
                            floorBusy -> HighDensityEmergency
                            isTransmitting -> HighDensitySecondary
                            floorState == FloorState.REQUESTING -> HighDensityWarning
                            isReceiving -> Color(0xFF0F172A)
                            else -> HighDensityTextPrimary
                        },
                        letterSpacing = 0.5.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Informative Operator Subtext
                Text(
                    text = when {
                        isTransmitting -> "Transmitting Voice • Release PTT to end"
                        isReceiving -> "${activeSpeaker ?: "Remote Speaker"} is talking"
                        floorBusy -> "Channel occupied • Wait before speaking"
                        floorState == FloorState.REQUESTING -> "Requesting floor allocation..."
                        floorState == FloorState.RELEASING -> "Floor release pending..."
                        isDialogConnected -> "Floor Available • Press and hold PTT to talk"
                        else -> "Session Standby • Start Call to establish channel"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isReceiving) HighDensityNavy else HighDensityTextSecondary,
                    textAlign = TextAlign.Center
                )

                // Tactical Audio Level VU Meter
                if (isTransmitting || audioLevel > 0.01f || isReceiving) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TacticalAudioMeter(audioLevel = if (isReceiving) 0.65f else audioLevel)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 4. MAIN PTT BUTTON (CENTERPIECE OF RADIO)
        // ==========================================
        val canHoldPtt = isRegistered && floorState != FloorState.LISTENING && !floorBusy

        Box(
            modifier = Modifier
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Outer tactical textured bezel
            Surface(
                shape = CircleShape,
                color = HighDensitySurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(3.dp, HighDensityBorder),
                modifier = Modifier.size(220.dp),
                shadowElevation = 6.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Concentric accent ring
                    Box(
                        modifier = Modifier
                            .size(198.dp)
                            .clip(CircleShape)
                            .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                    )
                }
            }

            // Interactive PTT Dome with press feedback & subtle pulse
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(if (isPttHeld && isTransmitting) pulseScale else 1f)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                pttColor.copy(alpha = 0.85f),
                                pttColor
                            )
                        )
                    )
                    .border(5.dp, Color.White.copy(alpha = 0.35f), CircleShape)
                    .testTag("ptt_button")
                    .pointerInput(isRegistered, floorState, floorBusy, callState) {
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
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = when {
                            !isRegistered -> Icons.Default.MicNone
                            floorBusy -> Icons.Default.Warning
                            isReceiving -> Icons.Default.VolumeUp
                            isTransmitting -> Icons.Default.Mic
                            floorState == FloorState.REQUESTING -> Icons.Default.CellTower
                            else -> Icons.Default.Mic
                        },
                        contentDescription = "Push To Talk Button",
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when {
                            !isRegistered -> "NOT READY"
                            floorBusy -> "FLOOR BUSY"
                            isTransmitting -> "SPEAKING"
                            floorState == FloorState.REQUESTING -> "REQUESTING..."
                            isReceiving -> "LISTENING"
                            floorState == FloorState.RELEASING -> "RELEASING"
                            !isDialogConnected -> "PUSH TO TALK"
                            else -> "PUSH TO TALK"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = when {
                            isTransmitting -> "TRANSMITTING"
                            isReceiving -> "RECEIVING AUDIO"
                            floorBusy -> "WAIT FOR FLOOR"
                            !isRegistered -> "CONNECT FIRST"
                            else -> "HOLD TO SPEAK"
                        },
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.8f),
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // ==========================================
        // 5. SESSION & VOICE CONTROLS
        // ==========================================
        val isFloorButtonEnabled = isRegistered &&
            isDialogConnected &&
            !floorBusy &&
            (floorState == FloorState.IDLE || floorState == FloorState.GRANTED)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Call session toggle
            if (isDialogConnected) {
                Button(
                    onClick = { viewModel.endCallSession() },
                    colors = ButtonDefaults.buttonColors(containerColor = HighDensityEmergency),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("end_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "END SESSION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            } else {
                Button(
                    onClick = { viewModel.startCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = HighDensityNavy),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("initiate_call_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneInTalk,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "START SESSION",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            // Floor Request / Release manual button
            Button(
                onClick = {
                    if (floorState == FloorState.GRANTED) {
                        viewModel.releaseFloor()
                    } else if (floorState == FloorState.IDLE) {
                        viewModel.requestFloor()
                    }
                },
                enabled = isFloorButtonEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        floorState == FloorState.GRANTED -> HighDensitySecondary
                        else -> HighDensitySurface
                    },
                    disabledContainerColor = HighDensitySurfaceVariant,
                    contentColor = if (floorState == FloorState.GRANTED) Color.White else HighDensityNavy,
                    disabledContentColor = HighDensityTextSecondary
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (floorState == FloorState.GRANTED) HighDensitySecondary else HighDensityBorder
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("request_floor_button")
            ) {
                Text(
                    text = when {
                        floorState == FloorState.GRANTED -> "RELEASE FLOOR"
                        floorBusy -> "FLOOR BUSY"
                        else -> "REQUEST FLOOR"
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Group Subscribe button
            Button(
                onClick = { viewModel.subscribeGroup() },
                colors = ButtonDefaults.buttonColors(containerColor = HighDensitySurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .weight(0.9f)
                    .height(44.dp)
                    .testTag("subscribe_button")
            ) {
                Icon(
                    imageVector = Icons.Default.RssFeed,
                    contentDescription = null,
                    tint = HighDensityNavy,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "SYNC",
                    color = HighDensityNavy,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ==========================================
        // 6. QUICK MESSAGING (CLEAN COLLAPSIBLE ACTION)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.dp, HighDensityBorder),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Chat,
                            contentDescription = "Tactical Messaging",
                            tint = HighDensityNavy,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "TACTICAL MESSAGING",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighDensityNavy
                        )
                    }

                    IconButton(
                        onClick = { showQuickMsgPanel = !showQuickMsgPanel },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = if (showQuickMsgPanel) Icons.Default.Close else Icons.Default.Chat,
                            contentDescription = "Toggle Tactical Messaging",
                            tint = HighDensityTextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                // Expandable message input box
                AnimatedVisibility(
                    visible = showQuickMsgPanel,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = quickMsgText,
                                onValueChange = { quickMsgText = it },
                                placeholder = {
                                    Text(
                                        "Broadcast message to ${groupFriendlyName}...",
                                        fontSize = 11.sp,
                                        color = HighDensityTextSecondary
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("quick_msg_input"),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = HighDensitySurfaceVariant,
                                    unfocusedContainerColor = HighDensitySurfaceVariant,
                                    focusedBorderColor = HighDensityNavy,
                                    unfocusedBorderColor = HighDensityBorder
                                ),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp)
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
                                    .background(HighDensityNavy, RoundedCornerShape(10.dp))
                                    .size(48.dp)
                                    .testTag("send_msg_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Send,
                                    contentDescription = "Send Message",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ==========================================
        // 7. EMERGENCY SOS BROADCAST ACTION
        // ==========================================
        Button(
            onClick = { viewModel.triggerEmergencyAlert("EMERGENCY SOS ALERT") },
            colors = ButtonDefaults.buttonColors(
                containerColor = HighDensityEmergency,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("emergency_sos_button")
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Emergency Alert",
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "EMERGENCY BROADCAST (SOS)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * Clean tactical segmented VU meter bar representing microphone voice energy or incoming audio.
 */
@Composable
fun TacticalAudioMeter(audioLevel: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(HighDensitySurfaceVariant)
            .border(1.dp, HighDensityBorder, RoundedCornerShape(5.dp))
    ) {
        val activeWidth = size.width * audioLevel.coerceIn(0.05f, 1f)
        val gradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFF22C55E), // Green
                Color(0xFFEAB308), // Yellow
                Color(0xFFEF4444)  // Red
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f)
        )
        drawRect(
            brush = gradient,
            size = Size(activeWidth, size.height)
        )
    }
}
