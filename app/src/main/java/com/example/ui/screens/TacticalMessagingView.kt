package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.TacticalConversation
import com.example.data.TacticalMessage
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityBorder
import com.example.ui.theme.HighDensityEmergency
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensitySurface
import com.example.ui.theme.HighDensitySurfaceVariant
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.TacticalCyanGlow
import com.example.ui.theme.TacticalHairlineBorder
import com.example.ui.theme.TacticalPlateRaised
import com.example.ui.theme.TacticalPlateSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TacticalMessagingView(
    viewModel: McpttViewModel,
    modifier: Modifier = Modifier,
    isCompact: Boolean = false
) {
    val conversations by viewModel.messagingRepo.conversations.collectAsState()
    val activeId by viewModel.messagingRepo.activeConversationId.collectAsState()
    val allMessages by viewModel.messagingRepo.messages.collectAsState()
    val profile by viewModel.sipProfile.collectAsState()

    val activeConv = conversations.find { it.id == activeId } ?: conversations.firstOrNull()
    val currentMessages = allMessages[activeId] ?: emptyList()

    var messageInputText by remember { mutableStateOf("") }
    var showNewConvDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Scroll to bottom on new message
    LaunchedEffect(currentMessages.size) {
        if (currentMessages.isNotEmpty()) {
            listState.animateScrollToItem(currentMessages.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = TacticalPlateSurface),
        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header: Section label & New Conversation button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = TacticalCyanGlow.copy(alpha = 0.15f),
                        modifier = Modifier.size(26.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.ChatBubble,
                                contentDescription = null,
                                tint = TacticalCyanGlow,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "SHORT DATA SERVICE (SDS)",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            color = TacticalCyanGlow,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "3GPP Standalone & Group Messaging Net",
                            fontSize = 9.sp,
                            color = HighDensityTextSecondary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TacticalPlateRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                    modifier = Modifier.clickable { showNewConvDialog = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "New Conversation",
                            tint = TacticalCyanGlow,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "NEW CHANNEL",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyanGlow
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Conversation Chips Row (1-to-1 and Groups)
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(conversations) { conv ->
                    val isSelected = conv.id == activeId
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectConversation(conv.id) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (conv.isGroup) Icons.Default.Groups else Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (isSelected) Color.White else TacticalCyanGlow
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = conv.title,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (conv.unreadCount > 0) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(16.dp)
                                            .clip(CircleShape)
                                            .background(HighDensityEmergency),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = conv.unreadCount.toString(),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
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

            Spacer(modifier = Modifier.height(8.dp))

            // Active Thread Subheader
            activeConv?.let { conv ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = TacticalPlateRaised.copy(alpha = 0.6f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(HighDensitySecondary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${conv.title} • ${conv.participants.size} participant(s)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighDensityTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        Text(
                            text = if (conv.isGroup) "GROUP SDS" else "STANDALONE 1:1",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = TacticalCyanGlow,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Message Bubble Stream
            val threadHeight = if (isCompact) 180.dp else 240.dp
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF090E18),
                border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(threadHeight)
            ) {
                if (currentMessages.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tactical messages yet in this channel.\nTransmit first standalone message below.",
                            fontSize = 11.sp,
                            color = HighDensityTextSecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentMessages, key = { it.id }) { msg ->
                            TacticalMessageBubble(msg = msg, myDisplayName = profile.displayName)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Tactical Canned Reply Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val canned = listOf("10-4 Roger", "Standby", "En Route", "Radio Check", "Location Secured")
                items(canned) { text ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = TacticalPlateRaised,
                        border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
                        modifier = Modifier.clickable {
                            activeConv?.let {
                                viewModel.sendTextMessage(it.id, text)
                            }
                        }
                    ) {
                        Text(
                            text = text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TacticalCyanGlow,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Message Input Row (Preserving test tags quick_msg_input & send_msg_button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = messageInputText,
                    onValueChange = { messageInputText = it },
                    placeholder = {
                        Text(
                            text = "Send tactical message to ${activeConv?.title ?: "channel"}...",
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
                        if (messageInputText.isNotBlank()) {
                            val targetUri = activeConv?.id ?: profile.targetGroup
                            viewModel.sendTextMessage(targetUri, messageInputText)
                            messageInputText = ""
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

    // Dialog for creating a new conversation
    if (showNewConvDialog) {
        var newTitle by remember { mutableStateOf("") }
        var newUri by remember { mutableStateOf("sip:") }
        var isGroupSelected by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showNewConvDialog = false },
            title = {
                Text(
                    text = "CREATE TACTICAL CONVERSATION",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = HighDensityTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = "Initialize 3GPP MCData Short Data Service (SDS) conversation thread:",
                        fontSize = 11.sp,
                        color = HighDensityTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Channel / Contact Name", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newUri,
                        onValueChange = { newUri = it },
                        label = { Text("Target SIP URI", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilterChip(
                            selected = !isGroupSelected,
                            onClick = { isGroupSelected = false },
                            label = { Text("1-to-1 Standalone", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = isGroupSelected,
                            onClick = { isGroupSelected = true },
                            label = { Text("Group Broadcast", fontSize = 11.sp) }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newTitle.isNotBlank() && newUri.isNotBlank()) {
                            viewModel.createConversation(newUri.trim(), newTitle.trim(), isGroupSelected)
                            showNewConvDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TacticalCyanGlow)
                ) {
                    Text("CREATE CHANNEL")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewConvDialog = false }) {
                    Text("CANCEL")
                }
            }
        )
    }
}

@Composable
fun TacticalMessageBubble(msg: TacticalMessage, myDisplayName: String) {
    val isMe = msg.isOutgoing
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.US) }
    val timeStr = dateFormat.format(Date(msg.timestamp))

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        // Sender header for incoming group messages
        if (!isMe) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = TacticalCyanGlow.copy(alpha = 0.2f),
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = msg.senderCallsign,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        color = TacticalCyanGlow,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        fontFamily = FontFamily.Monospace
                    )
                }
                Text(
                    text = msg.senderName,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = HighDensityTextSecondary
                )
            }
        }

        // Bubble surface
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp,
                topEnd = 12.dp,
                bottomStart = if (isMe) 12.dp else 2.dp,
                bottomEnd = if (isMe) 2.dp else 12.dp
            ),
            color = if (isMe) Color(0xFF0369A1) else Color(0xFF1E293B),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isMe) Color(0xFF0284C7) else TacticalHairlineBorder
            )
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    text = msg.text,
                    fontSize = 12.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = timeStr,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.65f),
                        fontFamily = FontFamily.Monospace
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "Delivered",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }
        }
    }
}
