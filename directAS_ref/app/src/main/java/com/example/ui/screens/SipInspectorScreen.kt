package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NorthEast
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SouthWest
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sip.model.LogDirection
import com.example.sip.model.SipTrafficLog
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityBackground
import com.example.ui.theme.HighDensityBorder
import com.example.ui.theme.HighDensityEmergency
import com.example.ui.theme.HighDensityNavy
import com.example.ui.theme.HighDensitySecondary
import com.example.ui.theme.HighDensitySurface
import com.example.ui.theme.HighDensityTerminalBg
import com.example.ui.theme.HighDensityTextPrimary
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.HighDensityWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SipInspectorScreen(viewModel: McpttViewModel) {
    val context = LocalContext.current
    val logs by viewModel.filteredLogs.collectAsState()
    val currentFilter by viewModel.logFilter.collectAsState()
    val profile by viewModel.sipProfile.collectAsState()
    val localIp by viewModel.localIp.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()
    val negotiatedMedia by viewModel.negotiatedMedia.collectAsState()
    val localRtp by viewModel.localRtpPort.collectAsState()
    val rtpTx by viewModel.rtpTxCount.collectAsState()
    val rtpRx by viewModel.rtpRxCount.collectAsState()
    val callState by viewModel.callState.collectAsState()

    var query by remember { mutableStateOf("") }
    var groupByCallId by remember { mutableStateOf(false) }

    val visibleLogs = remember(logs, query) {
        if (query.isBlank()) logs
        else logs.filter {
            it.rawPacket.contains(query, ignoreCase = true) ||
                it.summary.contains(query, ignoreCase = true) ||
                it.methodOrResponse.contains(query, ignoreCase = true) ||
                it.remoteAddress.contains(query, ignoreCase = true)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HighDensityBackground)
            .padding(12.dp)
    ) {
        Text(
            text = "SIP INSPECTOR",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = HighDensityNavy,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(8.dp))
        MediaEndpointCard(
            mode = profile.sipDestinationLabel(),
            localSip = "$localIp:${profile.localSipPort}",
            localRtp = "$localIp:$localRtp",
            remoteSip = "${profile.sipDestinationHost()}:${profile.sipDestinationPort()}",
            remoteRtp = negotiatedMedia?.let { "${it.host}:${it.rtpPort}" } ?: "—",
            network = networkStatus,
            callState = callState.name,
            rtpTx = rtpTx,
            rtpRx = rtpRx
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
            placeholder = { Text("Filter method, Call-ID, From, To…", fontSize = 12.sp) },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("inspector_search"),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = HighDensitySurface,
                unfocusedContainerColor = HighDensitySurface,
                focusedBorderColor = HighDensityNavy,
                unfocusedBorderColor = HighDensityBorder
            )
        )

        Spacer(modifier = Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val filters = listOf("ALL", "TX", "RX", "INFO", "MCPTT", "ERRORS")
            filters.forEach { f ->
                FilterChip(
                    selected = currentFilter == f,
                    onClick = { viewModel.setLogFilter(f) },
                    label = { Text(f, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = HighDensityNavy,
                        selectedLabelColor = Color.White,
                        containerColor = HighDensitySurface,
                        labelColor = HighDensityTextPrimary
                    ),
                    modifier = Modifier.testTag("filter_$f")
                )
            }
            FilterChip(
                selected = groupByCallId,
                onClick = { groupByCallId = !groupByCallId },
                label = { Text("GROUP Call-ID", fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HighDensityNavy,
                    selectedLabelColor = Color.White,
                    containerColor = HighDensitySurface
                )
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val file = viewModel.exportSipLogs()
                    Toast.makeText(
                        context,
                        if (file != null) "Saved ${file.name}" else "Save failed",
                        Toast.LENGTH_LONG
                    ).show()
                },
                modifier = Modifier.testTag("save_logs_button")
            ) {
                Icon(Icons.Default.Save, contentDescription = "Save log file", tint = HighDensityNavy)
            }
            IconButton(
                onClick = { viewModel.shareSipLogs() },
                modifier = Modifier.testTag("share_logs_button")
            ) {
                Icon(Icons.Default.Share, contentDescription = "Share log", tint = HighDensityNavy)
            }
            IconButton(
                onClick = { viewModel.clearTrafficLogs() },
                modifier = Modifier.testTag("clear_logs_button")
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Clear Logs", tint = HighDensityEmergency)
            }
        }

        if (visibleLogs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No SIP traffic yet.\nREGISTER or press PTT.",
                    color = HighDensityTextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else if (groupByCallId) {
            val groups = visibleLogs.groupBy { extractHeader(it.rawPacket, "Call-ID").ifBlank { it.id } }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier.weight(1f)
            ) {
                groups.forEach { (callId, groupLogs) ->
                    item(key = "g-$callId") {
                        Text(
                            text = "Dialog ${callId.take(28)}${if (callId.length > 28) "…" else ""}  (${groupLogs.size})",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighDensityNavy,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    items(groupLogs, key = { it.id }) { log ->
                        SipMessageBubble(log = log, onCopy = { copySip(context, it) })
                    }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                items(visibleLogs, key = { it.id }) { log ->
                    SipMessageBubble(log = log, onCopy = { copySip(context, it) })
                }
            }
        }
    }
}

@Composable
private fun MediaEndpointCard(
    mode: String,
    localSip: String,
    localRtp: String,
    remoteSip: String,
    remoteRtp: String,
    network: String,
    callState: String,
    rtpTx: Long,
    rtpRx: Long
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = HighDensityNavy),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text("ENDPOINTS", fontSize = 10.sp, color = Color.White.copy(0.7f), fontFamily = FontFamily.Monospace)
            Text(mode, fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("Net  $network", fontSize = 11.sp, color = Color.White.copy(0.9f), fontFamily = FontFamily.Monospace)
            Text("SIP  local $localSip    remote $remoteSip", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            Text("RTP  local $localRtp    remote $remoteRtp", fontSize = 11.sp, color = Color.White, fontFamily = FontFamily.Monospace)
            Text("Call $callState    RTP TX $rtpTx  RX $rtpRx", fontSize = 11.sp, color = Color(0xFF4ADE80), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun SipMessageBubble(log: SipTrafficLog, onCopy: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val outbound = log.direction == LogDirection.OUTBOUND
    val accent = bubbleColor(log)
    val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
    val from = extractHeader(log.rawPacket, "From")
    val to = extractHeader(log.rawPacket, "To")
    val callId = extractHeader(log.rawPacket, "Call-ID")
    val cseq = extractHeader(log.rawPacket, "CSeq")

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (outbound) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth(0.96f)
                .clickable { expanded = !expanded }
                .testTag("sip_card_${log.id}"),
            colors = CardDefaults.cardColors(containerColor = HighDensitySurface),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, accent),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (outbound) 14.dp else 4.dp,
                bottomEnd = if (outbound) 4.dp else 14.dp
            )
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (outbound) Icons.Default.NorthEast else Icons.Default.SouthWest,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (outbound) "TX" else "RX", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = log.methodOrResponse.ifBlank { log.summary }.take(42),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HighDensityTextPrimary,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Text(timeStr, fontSize = 10.sp, color = HighDensityTextSecondary, fontFamily = FontFamily.Monospace)
                }
                Text(log.summary, fontSize = 11.sp, color = HighDensityTextSecondary, fontFamily = FontFamily.Monospace)
                HeaderLine("From", shortUri(from))
                HeaderLine("To", shortUri(to))
                if (cseq.isNotBlank()) HeaderLine("CSeq", cseq)
                if (callId.isNotBlank()) HeaderLine("Call-ID", callId.take(36) + if (callId.length > 36) "…" else "")
                Text(log.remoteAddress, fontSize = 10.sp, color = HighDensityTextSecondary, fontFamily = FontFamily.Monospace)

                if (expanded) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = HighDensityTerminalBg),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("RAW SIP", fontSize = 10.sp, color = Color.Gray, fontFamily = FontFamily.Monospace)
                                IconButton(onClick = { onCopy(log.rawPacket) }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                            Text(
                                text = log.rawPacket,
                                fontSize = 11.sp,
                                color = Color(0xFF4ADE80),
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderLine(label: String, value: String) {
    if (value.isBlank()) return
    Text("$label  $value", fontSize = 10.sp, color = HighDensityTextPrimary, fontFamily = FontFamily.Monospace)
}

private fun bubbleColor(log: SipTrafficLog): Color {
    if (log.hasError) return HighDensityEmergency
    val token = (log.methodOrResponse + " " + log.summary).uppercase()
    val code = Regex("\\b([1-6]\\d\\d)\\b").find(token)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        code in 200..299 -> HighDensitySecondary
        code in 100..199 -> HighDensityWarning
        code != null && code >= 300 -> HighDensityEmergency
        log.direction == LogDirection.OUTBOUND -> HighDensityNavy
        else -> Color(0xFF2563EB)
    }
}

private fun extractHeader(raw: String, name: String): String {
    val prefix = "$name:"
    return raw.lineSequence()
        .firstOrNull { it.trimStart().startsWith(prefix, ignoreCase = true) }
        ?.substringAfter(":")
        ?.trim()
        .orEmpty()
}

private fun shortUri(value: String): String {
    val m = Regex("sip:[^>;\\s]+").find(value)
    return m?.value ?: value.take(48)
}

private fun copySip(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("SIP Packet", text))
    Toast.makeText(context, "Copied SIP packet", Toast.LENGTH_SHORT).show()
}
