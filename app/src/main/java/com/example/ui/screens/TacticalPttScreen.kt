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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FrontHand
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
fun TacticalPttScreen(
    viewModel: McpttViewModel,
    onNavigateToTab: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val registrationState by viewModel.registrationState.collectAsState()
    val callState by viewModel.callState.collectAsState()
    val floorState by viewModel.floorState.collectAsState()
    val activeSpeaker by viewModel.activeSpeaker.collectAsState()
    val floorBusy by viewModel.floorBusy.collectAsState()
    val profile by viewModel.sipProfile.collectAsState()
    val audioLevel by viewModel.micAudioLevel.collectAsState()

    var isPttHeld by remember { mutableStateOf(false) }
    var isWrenchExpanded by remember { mutableStateOf(false) }

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
    val isConnecting = registrationState == RegistrationState.REGISTERING ||
        registrationState == RegistrationState.AUTHENTICATING
    val isDialogConnected = callState == CallSessionState.CONNECTED
    val isTransmitting = floorState == FloorState.GRANTED
    val isReceiving = floorState == FloorState.LISTENING

    // PTT button color calculation based on clear radio states
    val pttColor by animateColorAsState(
        targetValue = when {
            !isRegistered && !isConnecting -> Color(0xFF475569) // Error / Not ready
            isConnecting -> HighDensityWarning // Connecting / Registering
            floorBusy && !isTransmitting -> HighDensityEmergency // Channel occupied
            isTransmitting -> HighDensitySecondary // Speaking -> Tactical Green
            floorState == FloorState.REQUESTING -> HighDensityWarning // Requesting floor -> Amber
            isReceiving -> Color(0xFF0284C7) // Listening/Receiving -> Radio Cyan/Blue
            floorState == FloorState.RELEASING -> Color(0xFF475569) // Floor released / Idle
            else -> if (isPttHeld) HighDensityPttRedDark else HighDensityPttRed // Connected / Ready / Idle
        },
        animationSpec = tween(300),
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

        // Main Handset Interface: PTT is the only primary control on screen
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ==========================================
            // 1. TOP HEADER: SMALL MCPTT RADIO / CALLSIGN ONLY (NO READY/ERROR SIGNS)
            // ==========================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCPTT RADIO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = " • ",
                    fontSize = 12.sp,
                    color = HighDensityTextSecondary
                )
                Text(
                    text = profile.displayName.ifBlank { "UNIT-1" }.uppercase(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = TacticalCyanGlow,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ==========================================
            // 2. TALKGROUP: PLAIN TEXT ONLY (NOT INTERACTIVE)
            // ==========================================
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                Text(
                    text = "ACTIVE TALKGROUP",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TacticalCyanGlow,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = groupFriendlyName,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ==========================================
            // 3. CENTER: ARTISTIC/TACTICAL PTT BUTTON (CENTERPIECE)
            // ==========================================
            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                // Outer illuminated halo glow when speaking, requesting, or receiving
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

                // Interactive Tactile PTT Dome with press feedback
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
                                isTransmitting -> Icons.Default.Mic
                                isReceiving -> Icons.Default.VolumeUp
                                floorState == FloorState.REQUESTING -> Icons.Default.CellTower
                                floorBusy -> Icons.Default.Warning
                                isConnecting -> Icons.Default.CellTower
                                !isRegistered -> Icons.Default.MicNone
                                else -> Icons.Default.Mic
                            },
                            contentDescription = "Push To Talk Button",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = when {
                                isTransmitting -> "SPEAKING"
                                isReceiving -> "LISTENING"
                                floorState == FloorState.REQUESTING -> "REQUESTING..."
                                floorState == FloorState.RELEASING -> "RELEASING"
                                floorBusy -> "FLOOR BUSY"
                                isConnecting -> "CONNECTING..."
                                !isRegistered -> "NOT READY"
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
                                floorState == FloorState.REQUESTING -> "WAIT FOR FLOOR"
                                floorBusy -> "CHANNEL OCCUPIED"
                                isConnecting -> "STANDBY"
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
            // 4. BELOW BUTTON: ONE CONCISE STATUS MESSAGE
            // ==========================================
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val statusMessage = when {
                    floorBusy && !isTransmitting -> "FLOOR BUSY"
                    isTransmitting -> "YOU HAVE THE FLOOR"
                    isReceiving -> "${activeSpeaker ?: "REMOTE OPERATOR"} IS SPEAKING"
                    floorState == FloorState.REQUESTING -> "REQUESTING FLOOR..."
                    floorState == FloorState.RELEASING -> "RELEASING FLOOR..."
                    isDialogConnected -> "READY TO TALK"
                    isRegistered -> "READY TO TALK"
                    isConnecting -> "CONNECTING TO MCPTT CORE..."
                    else -> "NOT CONNECTED"
                }

                val statusMessageColor = when {
                    floorBusy && !isTransmitting -> HighDensityEmergency
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
            // 5. DOCKED TOOLBAR: ONLY ONE BUTTON (WRENCH EMOJI ONLY)
            // ==========================================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Secondary Options Panel (Session, Talkgroups, App Screens)
                AnimatedVisibility(
                    visible = isWrenchExpanded,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = TacticalPlateSurface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                    ) {
                        WrenchDockedPanel(
                            viewModel = viewModel,
                            isDialogConnected = isDialogConnected,
                            floorState = floorState,
                            floorBusy = floorBusy,
                            isRegistered = isRegistered,
                            registrationState = registrationState,
                            currentGroup = profile.targetGroup,
                            onNavigateToTab = onNavigateToTab,
                            onClose = { isWrenchExpanded = false }
                        )
                    }
                }

                // The Single Wrench Button (No text, only 🔧 emoji)
                Surface(
                    onClick = { isWrenchExpanded = !isWrenchExpanded },
                    shape = CircleShape,
                    color = if (isWrenchExpanded) TacticalCyanGlow.copy(alpha = 0.25f) else TacticalPlateRaised,
                    border = androidx.compose.foundation.BorderStroke(
                        1.5.dp,
                        if (isWrenchExpanded) TacticalCyanGlow else TacticalHairlineBorder
                    ),
                    modifier = Modifier.size(54.dp),
                    shadowElevation = 4.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "🔧",
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * The unified panel opened by the Wrench button:
 * Houses Session Controls, Talkgroups, and App Screens navigation (PTT Radio, Talkgroups, Messages, Settings, Inspector).
 */
@Composable
private fun WrenchDockedPanel(
    viewModel: McpttViewModel,
    isDialogConnected: Boolean,
    floorState: FloorState,
    floorBusy: Boolean,
    isRegistered: Boolean,
    registrationState: RegistrationState,
    currentGroup: String,
    onNavigateToTab: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Panel Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🔧",
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RADIO CONTROLS & NAVIGATION",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TacticalCyanGlow,
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Close",
                    tint = HighDensityTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // SECTION 1: SESSION CONTROLS
        Text(
            text = "SESSION & FLOOR CONTROLS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Start/End Session Button
            if (isDialogConnected) {
                Button(
                    onClick = {
                        viewModel.endCallSession()
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HighDensityEmergency,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("end_call_button")
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("END SESSION", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Button(
                    onClick = {
                        viewModel.startCall()
                        onClose()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = TacticalCyanGlow,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .testTag("initiate_call_button")
                ) {
                    Icon(Icons.Default.PhoneInTalk, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("START SESSION", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            // Floor Request Button
            val isFloorEnabled = isRegistered && isDialogConnected && !floorBusy &&
                (floorState == FloorState.IDLE || floorState == FloorState.GRANTED)

            Button(
                onClick = {
                    if (floorState == FloorState.GRANTED) {
                        viewModel.releaseFloor()
                    } else if (floorState == FloorState.IDLE) {
                        viewModel.requestFloor()
                    }
                    onClose()
                },
                enabled = isFloorEnabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (floorState == FloorState.GRANTED) HighDensitySecondary else TacticalPlateRaised,
                    disabledContainerColor = TacticalPlateInset,
                    contentColor = if (floorState == FloorState.GRANTED) Color.White else TacticalCyanGlow,
                    disabledContentColor = HighDensityTextSecondary.copy(alpha = 0.4f)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (floorState == FloorState.GRANTED) HighDensitySecondary else TacticalHairlineBorder
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("request_floor_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FrontHand,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (floorState == FloorState.GRANTED) Color.White else TacticalCyanGlow
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (floorState == FloorState.GRANTED) "RELEASE FLOOR" else "REQUEST FLOOR",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Subscription Sync Button
        Button(
            onClick = {
                viewModel.subscribeGroup()
                onClose()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = TacticalPlateRaised,
                contentColor = TacticalCyanGlow
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .testTag("subscribe_button")
        ) {
            Icon(Icons.Default.RssFeed, contentDescription = null, modifier = Modifier.size(14.dp), tint = TacticalCyanGlow)
            Spacer(modifier = Modifier.width(6.dp))
            Text("SYNC TALKGROUP SUBSCRIPTION", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 2: TALKGROUP SELECTION
        Text(
            text = "TALKGROUP SELECTION",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(viewModel.presetGroups) { group ->
                val isSelected = group.first.equals(currentGroup, ignoreCase = true)
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        val prof = viewModel.sipProfile.value
                        viewModel.updateProfile(prof.copy(targetGroup = group.first))
                        viewModel.subscribeGroup()
                        onClose()
                    },
                    label = {
                        Text(
                            text = group.second,
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

        Spacer(modifier = Modifier.height(14.dp))

        // SECTION 3: APP SCREENS NAVIGATION (PTT Radio, Talkgroups, Messages, Settings, Inspector)
        Text(
            text = "APPLICATION SCREENS",
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityTextSecondary,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Navigation Destination Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Tab 0: PTT Radio
            Button(
                onClick = {
                    onNavigateToTab(0)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = TacticalCyanGlow.copy(alpha = 0.25f),
                    contentColor = TacticalCyanGlow
                ),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalCyanGlow),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("tab_ptt")
            ) {
                Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("PTT", fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }

            // Tab 1: Talkgroups
            Button(
                onClick = {
                    onNavigateToTab(1)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalPlateRaised),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("tab_groups")
            ) {
                Icon(Icons.Default.Groups, contentDescription = null, tint = TacticalCyanGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("GROUPS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TacticalCyanGlow, fontFamily = FontFamily.Monospace)
            }

            // Tab 2: Messages
            Button(
                onClick = {
                    onNavigateToTab(2)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalPlateRaised),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("tab_messages")
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = TacticalCyanGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("MSGS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TacticalCyanGlow, fontFamily = FontFamily.Monospace)
            }

            // Tab 3: Settings
            Button(
                onClick = {
                    onNavigateToTab(3)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalPlateRaised),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("tab_settings")
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = TacticalCyanGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("CONFIG", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TacticalCyanGlow, fontFamily = FontFamily.Monospace)
            }

            // Tab 4: Inspector
            Button(
                onClick = {
                    onNavigateToTab(4)
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalPlateRaised),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .testTag("tab_inspector")
            ) {
                Icon(Icons.Default.Code, contentDescription = null, tint = TacticalCyanGlow, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("LOGS", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TacticalCyanGlow, fontFamily = FontFamily.Monospace)
            }
        }

        if (registrationState != RegistrationState.REGISTERED) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = {
                    viewModel.registerSip()
                    onClose()
                },
                colors = ButtonDefaults.buttonColors(containerColor = TacticalCyanGlow),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .testTag("register_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("CONNECT / REGISTER SIP", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
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
            .fillMaxWidth(0.65f)
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
