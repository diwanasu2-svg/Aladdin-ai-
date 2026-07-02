package com.aladdin.app.ui.screens

import android.util.Log
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

// ─── Phase 6 Item 4: ToolsScreen — toggles, status indicators, permissions ────

private data class ToolInfo(
    val id: String,
    val name: String,
    val description: String,
    val icon: ImageVector,
    val example: String,
    val requiresPermission: String? = null,
    var enabled: Boolean = true,
    var status: ToolStatus = ToolStatus.ACTIVE
)

enum class ToolStatus { ACTIVE, DISABLED, PERMISSION_REQUIRED, ERROR }

private val TOOL_DEFINITIONS = listOf(
    ToolInfo("weather.fetch",  "Weather",       "Real-time weather and 5-day forecast",           Icons.Filled.WbSunny,      "\"What's the weather in London?\""),
    ToolInfo("reminder.create","Reminders",     "Create and manage timed reminders",              Icons.Filled.Notifications, "\"Remind me to call Sarah at 3pm\""),
    ToolInfo("memory.search",  "Memory Search", "Search your long-term memory",                   Icons.Filled.Search,       "\"What did I say about my project?\""),
    ToolInfo("memory.store",   "Memory Store",  "Save facts to long-term memory",                 Icons.Filled.Save,         "\"Remember that I prefer dark mode\""),
    ToolInfo("search.execute", "Web Search",    "Search the web for up-to-date information",      Icons.Filled.Language,     "\"Search for latest AI news\""),
    ToolInfo("news.fetch",     "News",          "Fetch and summarize top headlines",              Icons.Filled.Article,      "\"What's in the news today?\""),
    ToolInfo("maps.navigate",  "Navigation",    "Get directions and ETAs",                        Icons.Filled.Map,          "\"Navigate to Heathrow Airport\"",  "ACCESS_FINE_LOCATION"),
    ToolInfo("message.send",   "Messaging",     "Send messages via SMS or apps",                  Icons.Filled.Message,      "\"Send a message to John\"",         "SEND_SMS"),
    ToolInfo("music.play",     "Music Control", "Control music playback",                         Icons.Filled.MusicNote,    "\"Play some jazz music\""),
    ToolInfo("app.launch",     "App Launcher",  "Open any installed app by name",                 Icons.Filled.Apps,         "\"Open YouTube\""),
    ToolInfo("calculator",     "Calculator",    "Math calculations and unit conversions",          Icons.Filled.Calculate,    "\"Calculate 15% of 80\""),
    ToolInfo("calendar",       "Calendar",      "Manage events and schedule",                     Icons.Filled.CalendarToday,"\"Add a meeting on Friday at 2pm\"", "READ_CALENDAR"),
    ToolInfo("alarm",          "Alarm",         "Set and manage alarms",                          Icons.Filled.Alarm,        "\"Set an alarm for 7am\""),
    ToolInfo("system_info",    "System Info",   "Battery, storage, network status",               Icons.Filled.PhoneAndroid, "\"What's my battery level?\""),
    ToolInfo("camera.vision",  "Vision",        "Analyze camera feed and screenshots with AI",    Icons.Filled.CameraAlt,    "\"What do you see?\"",               "CAMERA"),
    ToolInfo("contacts",       "Contacts",      "Read and manage contacts",                       Icons.Filled.Contacts,     "\"Call Mom\"",                       "READ_CONTACTS"),
    ToolInfo("location.geo",   "Geofencing",    "Location-based triggers and reminders",          Icons.Filled.PinDrop,      "\"Remind me when I get home\"",      "ACCESS_BACKGROUND_LOCATION"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(modifier: Modifier = Modifier) {
    val tools = remember { TOOL_DEFINITIONS.map { it.copy() }.toMutableStateList() }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var filterEnabled by remember { mutableStateOf<Boolean?>(null) }

    val displayed = when (filterEnabled) {
        true  -> tools.filter { it.enabled }
        false -> tools.filter { !it.enabled }
        null  -> tools.toList()
    }
    val activeCount = tools.count { it.enabled }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Available Tools") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // ── Summary bar ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("$activeCount / ${tools.size} active",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = filterEnabled == null, onClick = { filterEnabled = null },
                    label = { Text("All") })
                FilterChip(selected = filterEnabled == true, onClick = { filterEnabled = if (filterEnabled == true) null else true },
                    label = { Text("On") })
                FilterChip(selected = filterEnabled == false, onClick = { filterEnabled = if (filterEnabled == false) null else false },
                    label = { Text("Off") })
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(displayed, key = { it.id }) { tool ->
                val realIdx = tools.indexOfFirst { it.id == tool.id }
                ToolCard(
                    tool = tool,
                    isExpanded = expandedId == tool.id,
                    onClick = { expandedId = if (expandedId == tool.id) null else tool.id },
                    onToggle = { enabled ->
                        if (realIdx >= 0) {
                            tools[realIdx] = tools[realIdx].copy(
                                enabled = enabled,
                                status = if (enabled) ToolStatus.ACTIVE else ToolStatus.DISABLED
                            )
                            Log.i("ToolsScreen", "Tool ${tool.id} → ${if (enabled) "ENABLED" else "DISABLED"}")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ToolCard(
    tool: ToolInfo,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !tool.enabled -> MaterialTheme.colorScheme.surface
                isExpanded    -> MaterialTheme.colorScheme.primaryContainer
                else          -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(tool.icon, contentDescription = null,
                    tint = if (tool.enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(tool.name, style = MaterialTheme.typography.titleSmall)
                        // ── Status indicator ──────────────────────────────
                        StatusBadge(tool.status, tool.enabled)
                    }
                    Text(tool.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                // ── Toggle ────────────────────────────────────────────────
                Switch(
                    checked = tool.enabled,
                    onCheckedChange = onToggle,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit  = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Tool ID: ${tool.id}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    if (tool.requiresPermission != null) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.tertiary)
                            Spacer(Modifier.width(4.dp))
                            Text("Requires: ${tool.requiresPermission}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Example:", style = MaterialTheme.typography.labelMedium)
                    Text(tool.example, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                    Spacer(Modifier.height(8.dp))
                    if (!tool.enabled) {
                        OutlinedButton(onClick = { onToggle(true) },
                            modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text("Enable Tool")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: ToolStatus, enabled: Boolean) {
    val (label, color) = when {
        !enabled              -> "Off" to MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        status == ToolStatus.ACTIVE              -> "Active" to Color(0xFF4CAF50)
        status == ToolStatus.PERMISSION_REQUIRED -> "Need permission" to Color(0xFFFF9800)
        status == ToolStatus.ERROR               -> "Error" to MaterialTheme.colorScheme.error
        else                                     -> "Disabled" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.extraSmall, color = color.copy(alpha = 0.15f)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
