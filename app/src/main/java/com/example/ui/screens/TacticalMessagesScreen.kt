package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.McpttViewModel
import com.example.ui.theme.HighDensityTextSecondary
import com.example.ui.theme.TacticalConsoleDark
import com.example.ui.theme.TacticalCyanGlow
import com.example.ui.theme.TacticalPlateRaised

@Composable
fun TacticalMessagesScreen(viewModel: McpttViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalConsoleDark)
            .padding(14.dp)
    ) {
        // Screen Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = TacticalPlateRaised,
                modifier = Modifier.size(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Chat,
                        contentDescription = "Tactical Messaging",
                        tint = TacticalCyanGlow,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "TACTICAL MESSAGING (SDS)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.6.sp
                )
                Text(
                    text = "Short Data Service • 1:1 & Group Standalone Messages",
                    fontSize = 11.sp,
                    color = HighDensityTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Full Messaging Console (contains quick_msg_input & send_msg_button)
        TacticalMessagingView(
            viewModel = viewModel,
            modifier = Modifier.fillMaxSize(),
            isCompact = false
        )
    }
}
