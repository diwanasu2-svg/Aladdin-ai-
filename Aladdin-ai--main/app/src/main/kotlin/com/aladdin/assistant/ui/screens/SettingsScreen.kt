package com.aladdin.assistant.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.aladdin.assistant.data.model.*
import com.aladdin.assistant.ui.theme.AccentColors
import com.aladdin.assistant.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    var showProviderSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                },
                title = { Text("Settings", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {

            // ── AI Provider ────────────────────────────────────────────────
            SettingsSection("AI Provider") {
                SettingsItem(
                    icon = Icons.Outlined.SmartToy,
                    title = "AI Provider",
                    subtitle = SUPPORTED_PROVIDERS.find { it.id == settings.aiProvider }?.name ?: settings.aiProvider,
                    onClick = { showProviderSheet = true }
                )
                SettingsItem(
                    icon = Icons.Outlined.ModelTraining,
                    title = "Model",
                    subtitle = settings.aiModel,
                    onClick = {}
                )
                SettingsTextField(
                    icon = Icons.Outlined.Key,
                    title = "API Key",
                    value = if (settings.apiKey.isBlank()) "" else "••••••••••••",
                    placeholder = "Enter API key",
                    onValueChange = { viewModel.updateSettings(settings.copy(apiKey = it)) }
                )
            }

            // ── Voice Settings ─────────────────────────────────────────────
            SettingsSection("Voice") {
                SettingsSwitch(
                    icon = Icons.Outlined.Mic,
                    title = "Voice Enabled",
                    subtitle = "Enable voice input and output",
                    checked = settings.voiceEnabled,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(voiceEnabled = it)) }
                )
                SettingsSwitch(
                    icon = Icons.Outlined.Hearing,
                    title = "Wake Word",
                    subtitle = "\"${settings.wakeWord}\"",
                    checked = settings.wakeWordEnabled,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(wakeWordEnabled = it)) }
                )
                SettingsSlider(
                    icon = Icons.Outlined.Speed,
                    title = "Speech Rate",
                    value = settings.speechRate,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    onValueChange = { viewModel.updateSettings(settings.copy(speechRate = it)) }
                )
                SettingsSlider(
                    icon = Icons.Outlined.GraphicEq,
                    title = "Voice Pitch",
                    value = settings.voicePitch,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    onValueChange = { viewModel.updateSettings(settings.copy(voicePitch = it)) }
                )
                SettingsItem(
                    icon = Icons.Outlined.Language,
                    title = "Language",
                    subtitle = settings.language,
                    onClick = { showLanguageSheet = true }
                )
            }

            // ── Appearance ─────────────────────────────────────────────────
            SettingsSection("Appearance") {
                SettingsItem(
                    icon = Icons.Outlined.Palette,
                    title = "Theme",
                    subtitle = settings.appTheme.lowercase().replaceFirstChar { it.uppercase() },
                    onClick = { showThemeSheet = true }
                )
                SettingsSwitch(
                    icon = Icons.Outlined.AutoFixHigh,
                    title = "Dynamic Color",
                    subtitle = "Material You adaptive colors",
                    checked = settings.dynamicColor,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(dynamicColor = it)) }
                )
                // Accent color picker
                SettingsRow(icon = Icons.Outlined.ColorLens, title = "Accent Color") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AccentColors.forEach { (name, color) ->
                            Surface(
                                onClick = { viewModel.updateSettings(settings.copy(accentColor = name)) },
                                shape = CircleShape,
                                color = color,
                                modifier = Modifier.size(28.dp),
                                border = if (settings.accentColor == name)
                                    BorderStroke(2.dp, MaterialTheme.colorScheme.onSurface)
                                else null
                            ) {}
                        }
                    }
                }
            }

            // ── Notifications ──────────────────────────────────────────────
            SettingsSection("Notifications") {
                SettingsSwitch(
                    icon = Icons.Outlined.Notifications,
                    title = "Notifications",
                    subtitle = "AI replies and status updates",
                    checked = settings.notificationsEnabled,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(notificationsEnabled = it)) }
                )
                SettingsSwitch(
                    icon = Icons.Outlined.Vibration,
                    title = "Haptic Feedback",
                    checked = settings.hapticFeedback,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(hapticFeedback = it)) }
                )
                SettingsSwitch(
                    icon = Icons.Outlined.VolumeUp,
                    title = "Sound Effects",
                    checked = settings.soundEffects,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(soundEffects = it)) }
                )
            }

            // ── Privacy ────────────────────────────────────────────────────
            SettingsSection("Privacy & Security") {
                SettingsSwitch(
                    icon = Icons.Outlined.VisibilityOff,
                    title = "Privacy Mode",
                    subtitle = "Disable analytics and crash reporting",
                    checked = settings.privacyMode,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(privacyMode = it)) }
                )
                SettingsItem(
                    icon = Icons.Outlined.Timer,
                    title = "Auto-delete History",
                    subtitle = when (settings.autoDeleteHistory) {
                        0 -> "Never"
                        7 -> "After 7 days"
                        30 -> "After 30 days"
                        90 -> "After 90 days"
                        else -> "Custom"
                    },
                    onClick = {}
                )
            }

            // ── Background ─────────────────────────────────────────────────
            SettingsSection("Background Assistant") {
                SettingsSwitch(
                    icon = Icons.Outlined.Apps,
                    title = "Background Mode",
                    subtitle = "Run Aladdin in the background",
                    checked = settings.backgroundAssistant,
                    onCheckedChange = { viewModel.updateSettings(settings.copy(backgroundAssistant = it)) }
                )
            }

            // ── Data Management ────────────────────────────────────────────
            SettingsSection("Data Management") {
                SettingsItem(
                    icon = Icons.Outlined.Backup,
                    title = "Backup Conversations",
                    subtitle = "Export all chats to file",
                    onClick = {}
                )
                SettingsItem(
                    icon = Icons.Outlined.RestorePage,
                    title = "Restore from Backup",
                    onClick = {}
                )
                SettingsItem(
                    icon = Icons.Outlined.DeleteSweep,
                    title = "Clear All History",
                    subtitle = "Permanently delete all conversations",
                    isDestructive = true,
                    onClick = { showDeleteConfirm = true }
                )
            }

            // ── About ──────────────────────────────────────────────────────
            SettingsSection("About") {
                SettingsItem(icon = Icons.Outlined.Info, title = "Version", subtitle = "6.0.0 (Phase 6)", onClick = {})
                SettingsItem(icon = Icons.Outlined.Policy, title = "Privacy Policy", onClick = {})
                SettingsItem(icon = Icons.Outlined.Gavel, title = "Terms of Service", onClick = {})
                SettingsItem(icon = Icons.Outlined.BugReport, title = "Report a Bug", onClick = {})
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Bottom sheets ──────────────────────────────────────────────────────────
    if (showProviderSheet) {
        ModalBottomSheet(onDismissRequest = { showProviderSheet = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("AI Provider", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                SUPPORTED_PROVIDERS.forEach { provider ->
                    ListItem(
                        headlineContent = { Text(provider.name) },
                        supportingContent = { Text(provider.models.joinToString(", ").take(50) + "…") },
                        leadingContent = {
                            RadioButton(
                                selected = settings.aiProvider == provider.id,
                                onClick = {
                                    viewModel.updateSettings(settings.copy(
                                        aiProvider = provider.id,
                                        aiModel = provider.models.first()
                                    ))
                                    showProviderSheet = false
                                }
                            )
                        }
                    )
                    HorizontalDivider()
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showThemeSheet) {
        ModalBottomSheet(onDismissRequest = { showThemeSheet = false }) {
            Column(Modifier.padding(24.dp)) {
                Text("App Theme", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                listOf("SYSTEM" to "System Default", "LIGHT" to "Light", "DARK" to "Dark").forEach { (key, label) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = {
                            RadioButton(
                                selected = settings.appTheme == key,
                                onClick = {
                                    viewModel.updateSettings(settings.copy(appTheme = key))
                                    showThemeSheet = false
                                }
                            )
                        }
                    )
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete All History?") },
            text = { Text("This will permanently delete all conversations and messages. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    isDestructive: Boolean = false,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(title, color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = {
            Icon(icon, null, tint = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(0.4f))
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun SettingsSwitch(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) }
    )
}

@Composable
private fun SettingsSlider(
    icon: ImageVector,
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(String.format("%.1f", value), style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange, steps = steps, modifier = Modifier.padding(start = 36.dp))
    }
}

@Composable
private fun SettingsTextField(
    icon: ImageVector,
    title: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onValueChange(it) },
            placeholder = { Text(placeholder) },
            modifier = Modifier.fillMaxWidth().padding(start = 36.dp, top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            singleLine = true
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    content: @Composable () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = content
    )
}
