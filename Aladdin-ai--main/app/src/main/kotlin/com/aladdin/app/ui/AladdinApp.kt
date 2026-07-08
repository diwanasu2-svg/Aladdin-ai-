package com.aladdin.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.aladdin.app.AladdinUiState
import com.aladdin.app.ErrorAction
import com.aladdin.app.ui.screens.ChatScreen
import com.aladdin.app.ui.screens.MemoryScreen
import com.aladdin.app.ui.screens.SettingsScreen
import com.aladdin.app.ui.screens.ToolsScreen

private data class NavItem(val label: String, val icon: ImageVector, val index: Int)

private val NAV_ITEMS = listOf(
    NavItem("Chat",     Icons.Filled.Chat,     0),
    NavItem("Memory",   Icons.Filled.Memory,   1),
    NavItem("Tools",    Icons.Filled.Build,    2),
    NavItem("Settings", Icons.Filled.Settings, 3)
)

@Composable
fun AladdinApp(
    uiState: AladdinUiState,
    onSendMessage: (String) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearConversation: () -> Unit,
    onStartService: () -> Unit,
    onDismissError: () -> Unit = {},
    onErrorAction: (ErrorAction) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NAV_ITEMS.forEach { item ->
                    NavigationBarItem(
                        selected = selectedTab == item.index,
                        onClick = { selectedTab = item.index },
                        icon = { Icon(item.icon, contentDescription = item.label) },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { paddingValues ->
        when (selectedTab) {
            0 -> ChatScreen(
                modifier = Modifier.padding(paddingValues),
                uiState = uiState,
                onSendMessage = onSendMessage,
                onStartListening = onStartListening,
                onStopListening = onStopListening,
                onClearConversation = onClearConversation,
                onDismissError = onDismissError,
                onErrorAction = onErrorAction
            )
            1 -> MemoryScreen(modifier = Modifier.padding(paddingValues))
            2 -> ToolsScreen(modifier = Modifier.padding(paddingValues))
            3 -> SettingsScreen(
                modifier = Modifier.padding(paddingValues),
                onStartService = onStartService
            )
        }
    }
}
