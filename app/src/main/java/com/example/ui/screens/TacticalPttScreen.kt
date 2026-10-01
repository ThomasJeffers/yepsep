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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import com.example.sip.engine.CallSessionState
import com.example.sip.engine.FloorState
import com.example.sip.engine.RegistrationState
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityEmergency
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensityPttRed
import com.example.ui.theme.HighDensityPttRedDark
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.HighDensityWarning
import com.example.ui.theme.TacticalConsoleDark
import com.example.ui.theme.TacticalCyanGlow
import com.example.ui.theme.TacticalHairlineBorder
import com.example.ui.theme.TacticalObsidian
import com.example.ui.theme.TacticalPlateInset
import com.example.ui.theme.TacticalPlateRaised
import com.example.ui.theme.TacticalPlateSurface

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

    var isPttHeld by remember { mutableStateOf(false) }
    var quickMsgText by remember { mutableStateOf("") }
    var isMessagingExpanded by remember { mutableStateOf(false) }

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

    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloAlpha"
    )

    val isRegistered = registrationState == RegistrationState.REGISTERED
    val isDialogConnected = callState == CallSessionState.CONNECTED

    // PTT button color calculation
    val pttColor by animateColorAsState(
        targetValue = when {
            !isRegistered -> Color(0xFF475569)
            floorBusy -> HighDensityEmergency
            floorState == FloorState.GRANTED -> HighDensitySecondary
            floorState == FloorState.REQUESTING -> HighDensityWarning
            floorState == FloorState.LISTENING -> Color(0xFF1E293B)
            floorState == FloorState.RELEASING -> Color(0xFF475569)
            else -> if (isPttHeld) HighDensityPttRedDark else HighDensityPttRed
        },
        label = "pttColor"
    )

    val groupFriendlyName = remember(profile.targetGroup, viewModel.presetGroups) {
        viewModel.presetGroups.find { it.first.equals(profile.targetGroup, ignoreCase = true) }?.second
            ?: profile.targetGroup
                .substringAfter("sip:")
                .substringBefore("@")
                .replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalConsoleDark)
    ) {
        // Subtle Mission Control atmospheric gradient background
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF132238),
                        TacticalConsoleDark,
                        TacticalObsidian
                    ),
                    center = Offset(size.width / 2f, size.height * 0.35f),
                    radius = size.width * 0.9f
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ==========================================
            // 1. TOP HEADER: MISSION CONSOLE STATUS
            // ==========================================
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("ims_status_card"),
                colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    // Top row: App designation & Callsign badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = TacticalPlateRaised,
                                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalCyanGlow.copy(alpha = 0.5f)),
                                modifier = Modifier.size(30.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = null,
                                        tint = TacticalCyanGlow,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "MCPTT RADIO CONSOLE",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    letterSpacing = 0.8.sp
                                )
                                Text(
                                    text = "MISSION CRITICAL VOICE & DATA",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TacticalCyanGlow,
                                    letterSpacing = 1.sp
                                )
                            }
                        }

                        // Callsign Badge
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = TacticalPlateInset,
                            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "CALLSIGN: ",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = HighDensityTextSecondary,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = profile.displayName.ifBlank { "UNIT-1" }.uppercase(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White,
                                    fontFamily = FontFamily.Monospace,
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            val statusDotColor = when (registrationState) {
                                RegistrationState.REGISTERED -> HighDensitySecondary
                                RegistrationState.AUTHENTICATING,
                                RegistrationState.REGISTERING -> HighDensityWarning
                                RegistrationState.MCPTT_APN_BOUND -> TacticalCyanGlow
                                RegistrationState.REGISTRATION_FAILED,
                                RegistrationState.NETWORK_UNAVAILABLE -> HighDensityEmergency
                                RegistrationState.UNREGISTERED -> Color(0xFF64748B)
                            }

                            // Glowing LED
                            Box(
                                modifier = Modifier
                                    .size(9.dp)
                                    .clip(CircleShape)
                                    .background(statusDotColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                val statusLabel = when (registrationState) {
                                    RegistrationState.REGISTERED -> "CORE CONNECTED • READY"
                                    RegistrationState.AUTHENTICATING,
                                    RegistrationState.REGISTERING -> "CONNECTING TO MCPTT CORE..."
                                    RegistrationState.MCPTT_APN_BOUND -> "APN BOUND • READY TO REGISTER"
                                    RegistrationState.REGISTRATION_FAILED -> "REGISTRATION FAILED"
                                    RegistrationState.NETWORK_UNAVAILABLE -> "NETWORK CARRIER OFFLINE"
                                    RegistrationState.UNREGISTERED -> "OFFLINE • STANDBY"
                                }
                                Text(
                                    text = statusLabel,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = when (registrationState) {
                                        RegistrationState.REGISTERED -> HighDensitySecondary
                                        RegistrationState.REGISTRATION_FAILED -> HighDensityEmergency
                                        RegistrationState.AUTHENTICATING,
                                        RegistrationState.REGISTERING -> HighDensityWarning
                                        else -> HighDensityTextPrimary
                                    },
                                    fontFamily = FontFamily.Monospace
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

                        // Action buttons
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (registrationState != RegistrationState.REGISTERED) {
                                val isConnecting = registrationState == RegistrationState.AUTHENTICATING ||
                                    registrationState == RegistrationState.REGISTERING
                                Button(
                                    onClick = { viewModel.registerSip() },
                                    enabled = !isConnecting,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = TacticalCyanGlow,
                                        disabledContainerColor = TacticalCyanGlow.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 3.dp),
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
                                    tint = TacticalCyanGlow,
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
                colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = TacticalPlateRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Groups,
                                    contentDescription = "Talkgroup",
                                    tint = TacticalCyanGlow,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "ACTIVE TALKGROUP",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = TacticalCyanGlow,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = groupFriendlyName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Primary Tactical Voice Net",
                                fontSize = 10.sp,
                                color = HighDensityTextSecondary
                            )
                        }
                    }

                    // Channel active pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDialogConnected) HighDensitySecondary.copy(alpha = 0.2f) else TacticalPlateInset,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isDialogConnected) HighDensitySecondary else TacticalHairlineBorder
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (isDialogConnected) HighDensitySecondary else Color(0xFF64748B))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isDialogConnected) "CALL ACTIVE" else "STANDBY",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDialogConnected) HighDensitySecondary else HighDensityTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ==========================================
            // 3. TACTICAL FLOOR STATUS BANNER & AUDIO VU
            // ==========================================
            val isTransmitting = floorState == FloorState.GRANTED
            val isReceiving = floorState == FloorState.LISTENING

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        floorBusy -> HighDensityEmergency.copy(alpha = 0.15f)
                        isTransmitting -> HighDensitySecondary.copy(alpha = 0.15f)
                        floorState == FloorState.REQUESTING -> HighDensityWarning.copy(alpha = 0.15f)
                        isReceiving -> Color(0xFF0284C7).copy(alpha = 0.15f)
                        else -> TacticalPlateSurface
                    }
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when {
                        floorBusy -> HighDensityEmergency
                        isTransmitting -> HighDensitySecondary
                        floorState == FloorState.REQUESTING -> HighDensityWarning
                        isReceiving -> TacticalCyanGlow
                        else -> TacticalHairlineBorder
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
                                isReceiving -> TacticalCyanGlow
                                else -> Color.White
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
                                else -> "READY (CONNECT SESSION TO TALK)"
                            },
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = when {
                                floorBusy -> HighDensityEmergency
                                isTransmitting -> HighDensitySecondary
                                floorState == FloorState.REQUESTING -> HighDensityWarning
                                isReceiving -> TacticalCyanGlow
                                else -> Color.White
                            },
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = when {
                            isTransmitting -> "Transmitting Microphone Audio • Release PTT to end"
                            isReceiving -> "${activeSpeaker ?: "Remote Speaker"} is transmitting"
                            floorBusy -> "Channel occupied • Wait before transmitting"
                            floorState == FloorState.REQUESTING -> "Floor request dispatched to Server"
                            isDialogConnected -> "Floor Available • Press and hold PTT to speak"
                            else -> "Session Standby • Start Call to establish channel"
                        },
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = HighDensityTextSecondary,
                        textAlign = TextAlign.Center
                    )

                    if (isTransmitting || audioLevel > 0.01f || isReceiving) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TacticalConsoleAudioMeter(audioLevel = if (isReceiving) 0.7f else audioLevel)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 4. MAIN PTT BUTTON (MISSION CONTROL CENTERPIECE)
            // ==========================================
            val canHoldPtt = isRegistered && floorState != FloorState.LISTENING && !floorBusy

            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer illuminated halo glow when speaking or requesting
                if (isTransmitting || floorState == FloorState.REQUESTING) {
                    Box(
                        modifier = Modifier
                            .size(232.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        (if (isTransmitting) HighDensitySecondary else HighDensityWarning).copy(alpha = haloAlpha * 0.4f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Machined tactical handset outer bezel
                Surface(
                    shape = CircleShape,
                    color = TacticalPlateInset,
                    border = androidx.compose.foundation.BorderStroke(3.dp, TacticalHairlineBorder),
                    modifier = Modifier.size(216.dp),
                    shadowElevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        // Tick mark ring
                        Canvas(modifier = Modifier.size(204.dp)) {
                            drawCircle(
                                color = TacticalHairlineBorder.copy(alpha = 0.7f),
                                radius = size.minDimension / 2f - 2f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                            )
                        }

                        // Middle recessed groove
                        Box(
                            modifier = Modifier
                                .size(196.dp)
                                .clip(CircleShape)
                                .border(2.dp, TacticalPlateRaised, CircleShape)
                        )
                    }
                }

                // Interactive PTT Dome with press feedback
                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .scale(if (isPttHeld && isTransmitting) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    pttColor.copy(alpha = 0.95f),
                                    pttColor,
                                    Color.Black.copy(alpha = 0.35f)
                                )
                            )
                        )
                        .border(4.dp, Color.White.copy(alpha = 0.35f), CircleShape)
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
                            modifier = Modifier.size(50.dp)
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
                                else -> "PUSH TO TALK"
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.6.sp
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = when {
                                isTransmitting -> "AUDIO LIVE"
                                isReceiving -> "RECEIVING"
                                floorBusy -> "WAIT FOR FLOOR"
                                !isRegistered -> "CONNECT FIRST"
                                else -> "HOLD TO SPEAK"
                            },
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ==========================================
            // 5. SESSION & VOICE CONTROLS (UNCLIPPED 2-COLUMN + SYNC)
            // ==========================================
            val isFloorButtonEnabled = isRegistered &&
                isDialogConnected &&
                !floorBusy &&
                (floorState == FloorState.IDLE || floorState == FloorState.GRANTED)

            // Row 1: The two primary controls side-by-side with 52.dp height and ample horizontal padding
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Control 1: Call Session (START SESSION / END SESSION)
                if (isDialogConnected) {
                    Button(
                        onClick = { viewModel.endCallSession() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = HighDensityEmergency,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("end_call_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CallEnd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "END SESSION",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { viewModel.startCall() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = TacticalCyanGlow,
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .testTag("initiate_call_button")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhoneInTalk,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "START SESSION",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Control 2: Floor Toggle (REQUEST FLOOR / RELEASE FLOOR)
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
                            else -> TacticalPlateRaised
                        },
                        disabledContainerColor = TacticalPlateInset,
                        contentColor = if (floorState == FloorState.GRANTED) Color.White else TacticalCyanGlow,
                        disabledContentColor = HighDensityTextSecondary
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (floorState == FloorState.GRANTED) HighDensitySecondary else TacticalHairlineBorder
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp)
                        .testTag("request_floor_button")
                ) {
                    Text(
                        text = when {
                            floorState == FloorState.GRANTED -> "RELEASE FLOOR"
                            floorBusy -> "FLOOR BUSY"
                            else -> "REQUEST FLOOR"
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Talkgroup Sync / Subscribe button (testTag subscribe_button)
            Button(
                onClick = { viewModel.subscribeGroup() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TacticalPlateRaised,
                    contentColor = TacticalCyanGlow
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("subscribe_button")
            ) {
                Icon(
                    imageVector = Icons.Default.RssFeed,
                    contentDescription = null,
                    tint = TacticalCyanGlow,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SYNC TALKGROUP SUBSCRIPTION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ==========================================
            // 6. FUNCTIONAL TACTICAL MESSAGING (SDS CONVERSATION CONSOLE)
            // ==========================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Header Bar with Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = CircleShape,
                                color = TacticalCyanGlow.copy(alpha = 0.2f),
                                modifier = Modifier.size(26.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Chat,
                                        contentDescription = "Tactical Messaging",
                                        tint = TacticalCyanGlow,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "TACTICAL MESSAGING (SDS)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TacticalCyanGlow,
                                    letterSpacing = 0.5.sp
                                )
                                Text(
                                    text = "Short Data Service • 1:1 & Group Broadcast",
                                    fontSize = 9.sp,
                                    color = HighDensityTextSecondary
                                )
                            }
                        }

                        IconButton(
                            onClick = { isMessagingExpanded = !isMessagingExpanded },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(
                                imageVector = if (isMessagingExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = "Toggle Tactical Messaging console",
                                tint = TacticalCyanGlow
                            )
                        }
                    }

                    // Full Conversation Console when expanded
                    AnimatedVisibility(
                        visible = isMessagingExpanded,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            TacticalMessagingView(viewModel = viewModel, isCompact = true)
                        }
                    }

                    // Always-visible Quick Input row (Guarantees quick_msg_input & send_msg_button test tags exist in tree)
                    if (!isMessagingExpanded) {
                        Spacer(modifier = Modifier.height(10.dp))
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
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    if (quickMsgText.isNotBlank()) {
                                        viewModel.sendTextMessage(profile.targetGroup, quickMsgText)
                                        quickMsgText = ""
                                    }
                                },
                                modifier = Modifier
                                    .background(TacticalCyanGlow, RoundedCornerShape(10.dp))
                                    .size(46.dp)
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
                shape = RoundedCornerShape(12.dp),
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
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/**
 * Tactical multi-segment Console Audio Meter representing microphone energy and incoming audio.
 */
@Composable
fun TacticalConsoleAudioMeter(audioLevel: Float) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(TacticalPlateInset)
            .border(1.dp, TacticalHairlineBorder, RoundedCornerShape(5.dp))
    ) {
        val activeWidth = size.width * audioLevel.coerceIn(0.05f, 1f)
        val gradient = Brush.linearGradient(
            colors = listOf(
                Color(0xFF10B981), // Phosphor green
                Color(0xFFF59E0B), // Amber
                Color(0xFFEF4444)  // Crimson
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
