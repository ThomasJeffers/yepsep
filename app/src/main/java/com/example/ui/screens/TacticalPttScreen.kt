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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
    val callState by viewModel.callState.collectAsState()
    val floorState by viewModel.floorState.collectAsState()
    val activeSpeaker by viewModel.activeSpeaker.collectAsState()
    val floorBusy by viewModel.floorBusy.collectAsState()
    val profile by viewModel.sipProfile.collectAsState()
    val audioLevel by viewModel.micAudioLevel.collectAsState()

    var isPttHeld by remember { mutableStateOf(false) }
    var isGroupDropdownExpanded by remember { mutableStateOf(false) }

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
    val isTransmitting = floorState == FloorState.GRANTED
    val isReceiving = floorState == FloorState.LISTENING

    // PTT button color calculation
    val pttColor by animateColorAsState(
        targetValue = when {
            !isRegistered -> Color(0xFF475569)
            floorBusy -> HighDensityEmergency
            floorState == FloorState.GRANTED -> HighDensitySecondary
            floorState == FloorState.REQUESTING -> HighDensityWarning
            floorState == FloorState.LISTENING -> Color(0xFF0284C7)
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

    val canHoldPtt = isRegistered && floorState != FloorState.LISTENING && !floorBusy

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalConsoleDark)
    ) {
        // Atmospheric Tactical Background Gradient
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF132238),
                        TacticalConsoleDark,
                        TacticalObsidian
                    ),
                    center = Offset(size.width / 2f, size.height * 0.42f),
                    radius = size.width * 1.15f
                )
            )
        }

        // Dedicated Operator Handset Interface
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==========================================
            // 1. MINIMAL TOP BAR: OPERATOR & CONNECTION
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Identity & Callsign
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalPlateRaised,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalCyanGlow.copy(alpha = 0.5f)),
                        modifier = Modifier.size(34.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = "Radio Identity",
                                tint = TacticalCyanGlow,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "MCPTT RADIO",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = profile.displayName.ifBlank { "UNIT-1" }.uppercase(),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyanGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Simple Connection Status Indicator
                val statusDotColor = when (registrationState) {
                    RegistrationState.REGISTERED -> HighDensitySecondary
                    RegistrationState.AUTHENTICATING,
                    RegistrationState.REGISTERING,
                    RegistrationState.MCPTT_APN_BOUND -> HighDensityWarning
                    RegistrationState.REGISTRATION_FAILED,
                    RegistrationState.NETWORK_UNAVAILABLE -> HighDensityEmergency
                    RegistrationState.UNREGISTERED -> Color(0xFF64748B)
                }

                val statusLabel = when (registrationState) {
                    RegistrationState.REGISTERED -> "READY"
                    RegistrationState.AUTHENTICATING,
                    RegistrationState.REGISTERING -> "CONNECTING"
                    RegistrationState.MCPTT_APN_BOUND -> "STANDBY"
                    RegistrationState.REGISTRATION_FAILED -> "ERROR"
                    RegistrationState.NETWORK_UNAVAILABLE -> "NO CARRIER"
                    RegistrationState.UNREGISTERED -> "OFFLINE"
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TacticalPlateInset,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusDotColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = statusDotColor,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // ==========================================
            // 2. TALKGROUP SELECTOR (CLEAN DROPDOWN ONLY)
            // ==========================================
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = TacticalPlateRaised.copy(alpha = 0.85f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                    modifier = Modifier.clickable { isGroupDropdownExpanded = true }
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "ACTIVE TALKGROUP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyanGlow,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = groupFriendlyName,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.Black,
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select Talkgroup",
                                tint = TacticalCyanGlow,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                // Clean Talkgroups Dropdown: ONLY Selectable Talkgroups
                DropdownMenu(
                    expanded = isGroupDropdownExpanded,
                    onDismissRequest = { isGroupDropdownExpanded = false },
                    modifier = Modifier
                        .background(TacticalPlateSurface)
                        .border(1.dp, TacticalHairlineBorder, RoundedCornerShape(8.dp))
                ) {
                    viewModel.presetGroups.forEach { group ->
                        val isSelected = group.first.equals(profile.targetGroup, ignoreCase = true)
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = group.second,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) TacticalCyanGlow else HighDensityTextPrimary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (isSelected) {
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Selected",
                                            tint = TacticalCyanGlow,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            },
                            onClick = {
                                viewModel.updateProfile(profile.copy(targetGroup = group.first))
                                viewModel.subscribeGroup()
                                isGroupDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // ==========================================
            // 3. CENTER: DOMINANT TACTICAL PTT BUTTON
            // ==========================================
            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer illuminated halo glow when active
                if (isTransmitting || floorState == FloorState.REQUESTING || isReceiving) {
                    Box(
                        modifier = Modifier
                            .size(272.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        (when {
                                            isTransmitting -> HighDensitySecondary
                                            floorState == FloorState.REQUESTING -> HighDensityWarning
                                            isReceiving -> Color(0xFF0284C7)
                                            else -> HighDensitySecondary
                                        }).copy(alpha = haloAlpha * 0.45f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )
                }

                // Machined Handset Outer Bezel
                Surface(
                    shape = CircleShape,
                    color = TacticalPlateInset,
                    border = androidx.compose.foundation.BorderStroke(3.dp, TacticalHairlineBorder),
                    modifier = Modifier.size(244.dp),
                    shadowElevation = 10.dp
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.size(232.dp)) {
                            drawCircle(
                                color = TacticalHairlineBorder.copy(alpha = 0.8f),
                                radius = size.minDimension / 2f - 2f,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(220.dp)
                                .clip(CircleShape)
                                .border(2.dp, TacticalPlateRaised, CircleShape)
                        )
                    }
                }

                // Interactive PTT Dome with press feedback
                Box(
                    modifier = Modifier
                        .size(208.dp)
                        .scale(if (isPttHeld && isTransmitting) pulseScale else 1f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    pttColor.copy(alpha = 0.95f),
                                    pttColor,
                                    Color.Black.copy(alpha = 0.4f)
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
                            modifier = Modifier.size(56.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

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
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Black,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.8.sp
                        )

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = when {
                                isTransmitting -> "AUDIO LIVE"
                                isReceiving -> "RECEIVING AUDIO"
                                floorBusy -> "CHANNEL OCCUPIED"
                                !isRegistered -> "OFFLINE"
                                else -> "HOLD TO TALK"
                            },
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f),
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // ==========================================
            // 4. BELOW BUTTON: SIMPLE DYNAMIC STATUS
            // ==========================================
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val statusMessage = when {
                    floorBusy -> "FLOOR BUSY"
                    isTransmitting -> "YOU HAVE THE FLOOR"
                    isReceiving -> "${activeSpeaker ?: "REMOTE OPERATOR"} IS SPEAKING"
                    floorState == FloorState.REQUESTING -> "REQUESTING FLOOR..."
                    floorState == FloorState.RELEASING -> "RELEASING FLOOR..."
                    isDialogConnected -> "READY TO TALK"
                    isRegistered -> "READY TO TALK"
                    registrationState == RegistrationState.REGISTERING ||
                        registrationState == RegistrationState.AUTHENTICATING -> "CONNECTING TO MCPTT CORE..."
                    else -> "NOT CONNECTED"
                }

                val statusMessageColor = when {
                    floorBusy -> HighDensityEmergency
                    isTransmitting -> HighDensitySecondary
                    floorState == FloorState.REQUESTING -> HighDensityWarning
                    isReceiving -> TacticalCyanGlow
                    isDialogConnected || isRegistered -> Color.White
                    else -> HighDensityTextSecondary
                }

                Text(
                    text = statusMessage,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = statusMessageColor,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp,
                    textAlign = TextAlign.Center
                )

                if (isTransmitting || audioLevel > 0.01f || isReceiving) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TacticalConsoleAudioMeter(audioLevel = if (isReceiving) 0.7f else audioLevel)
                }
            }

            // ==========================================
            // 5. COMPACT ICON-BASED CONTROLS (SESSION & FLOOR)
            // ==========================================
            val isFloorActionEnabled = isRegistered && isDialogConnected && !floorBusy &&
                (floorState == FloorState.IDLE || floorState == FloorState.GRANTED)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Control 1: Session Control (Phone/Session symbol)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isDialogConnected) HighDensityEmergency.copy(alpha = 0.18f) else TacticalPlateRaised,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isDialogConnected) HighDensityEmergency else TacticalHairlineBorder
                    ),
                    modifier = Modifier
                        .clickable {
                            if (isDialogConnected) viewModel.endCallSession() else viewModel.startCall()
                        }
                        .testTag(if (isDialogConnected) "end_call_button" else "initiate_call_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isDialogConnected) Icons.Default.CallEnd else Icons.Default.PhoneInTalk,
                            contentDescription = if (isDialogConnected) "End Session" else "Start Session",
                            tint = if (isDialogConnected) HighDensityEmergency else TacticalCyanGlow,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isDialogConnected) "END SESSION" else "START SESSION",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isDialogConnected) HighDensityEmergency else Color.White,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Control 2: Floor Request Control (Raised Hand symbol)
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (floorState == FloorState.GRANTED) HighDensitySecondary.copy(alpha = 0.22f) else TacticalPlateRaised,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (floorState == FloorState.GRANTED) HighDensitySecondary else TacticalHairlineBorder
                    ),
                    modifier = Modifier
                        .clickable(enabled = isFloorActionEnabled) {
                            if (floorState == FloorState.GRANTED) {
                                viewModel.releaseFloor()
                            } else if (floorState == FloorState.IDLE) {
                                viewModel.requestFloor()
                            }
                        }
                        .testTag("request_floor_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.FrontHand,
                            contentDescription = if (floorState == FloorState.GRANTED) "Release Floor" else "Request Floor",
                            tint = when {
                                floorState == FloorState.GRANTED -> HighDensitySecondary
                                !isFloorActionEnabled -> HighDensityTextSecondary.copy(alpha = 0.4f)
                                else -> TacticalCyanGlow
                            },
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when {
                                floorState == FloorState.GRANTED -> "RELEASE"
                                floorBusy -> "OCCUPIED"
                                else -> "REQUEST"
                            },
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                floorState == FloorState.GRANTED -> HighDensitySecondary
                                !isFloorActionEnabled -> HighDensityTextSecondary.copy(alpha = 0.4f)
                                else -> Color.White
                            },
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
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
            .fillMaxWidth(0.7f)
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(TacticalPlateInset)
            .border(1.dp, TacticalHairlineBorder, RoundedCornerShape(3.dp))
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
