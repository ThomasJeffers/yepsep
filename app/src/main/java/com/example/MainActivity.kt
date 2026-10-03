package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.McpttViewModel
import com.example.ui.screens.GroupsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SipInspectorScreen
import com.example.ui.screens.TacticalMessagesScreen
import com.example.ui.screens.TacticalPttScreen
import com.example.ui.theme.HighDensityBackground
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.TacticalConsoleDark
import com.example.ui.theme.TacticalCyanGlow
import com.example.ui.theme.TacticalHairlineBorder
import com.example.ui.theme.TacticalPlateRaised

class MainActivity : ComponentActivity() {
    private val viewModel: McpttViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel = viewModel)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }
}

@Composable
fun MainAppScreen(viewModel: McpttViewModel) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = HighDensityBackground
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> TacticalPttScreen(
                    viewModel = viewModel,
                    onNavigateToTab = { selectedTab = it }
                )
                1 -> SubScreenContainer(title = "TALKGROUPS NET", onBack = { selectedTab = 0 }) {
                    GroupsScreen(viewModel = viewModel)
                }
                2 -> SubScreenContainer(title = "TACTICAL MESSAGING", onBack = { selectedTab = 0 }) {
                    TacticalMessagesScreen(viewModel = viewModel)
                }
                3 -> SubScreenContainer(title = "RADIO CONFIGURATION", onBack = { selectedTab = 0 }) {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateToTab = { selectedTab = it }
                    )
                }
                4 -> SubScreenContainer(title = "SIP INSPECTOR LOGS", onBack = { selectedTab = 0 }) {
                    SipInspectorScreen(viewModel = viewModel)
                }
            }
        }
    }
}

/**
 * Clean container for secondary application screens with a top tactical back button returning to PTT Radio.
 */
@Composable
private fun SubScreenContainer(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit
) {
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(TacticalConsoleDark)
    ) {
        Surface(
            color = TacticalPlateRaised,
            border = androidx.compose.foundation.BorderStroke(1.dp, TacticalHairlineBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to PTT Radio",
                        tint = TacticalCyanGlow
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.5.sp
                )
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}
