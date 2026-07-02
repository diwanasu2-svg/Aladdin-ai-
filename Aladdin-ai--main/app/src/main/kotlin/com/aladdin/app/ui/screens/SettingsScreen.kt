package com.aladdin.app.ui.screens

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aladdin_settings")

object AladdinPrefs {
    val WAKE_WORD      = booleanPreferencesKey("wake_word_enabled")
    val CONTINUOUS     = booleanPreferencesKey("continuous_listening")
    val BACKGROUND     = booleanPreferencesKey("background_mode")
    val NOISE_SUPPRESS = booleanPreferencesKey("noise_suppression")
    val OFFLINE_TTS    = booleanPreferencesKey("offline_tts")
    val AUTO_DOWNLOAD  = booleanPreferencesKey("auto_download_models")
    val DARK_THEME     = booleanPreferencesKey("dark_theme")
    val PROACTIVE      = booleanPreferencesKey("proactive_suggestions")
    val LOCATION       = booleanPreferencesKey("location_awareness")
    val MOOD_DETECT    = booleanPreferencesKey("mood_detection")
}

// ─── Phase 6 Item 2: SettingsScreen with DataStore persistence + Save + Theme ─

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onThemeChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var wakeWordEnabled     by remember { mutableStateOf(true) }
    var continuousListening by remember { mutableStateOf(true) }
    var backgroundMode      by remember { mutableStateOf(true) }
    var noiseSuppression    by remember { mutableStateOf(true) }
    var offlineTts          by remember { mutableStateOf(true) }
    var autoDownloadModels  by remember { mutableStateOf(true) }
    var darkTheme           by remember { mutableStateOf(false) }
    var proactiveSugg       by remember { mutableStateOf(true) }
    var locationAware       by remember { mutableStateOf(true) }
    var moodDetection       by remember { mutableStateOf(true) }
    var isSaving            by remember { mutableStateOf(false) }
    var saveResult          by remember { mutableStateOf<Boolean?>(null) }

    // Load from DataStore on first composition
    LaunchedEffect(Unit) {
        try {
            val prefs = context.dataStore.data.first()
            wakeWordEnabled     = prefs[AladdinPrefs.WAKE_WORD]      ?: true
            continuousListening = prefs[AladdinPrefs.CONTINUOUS]     ?: true
            backgroundMode      = prefs[AladdinPrefs.BACKGROUND]     ?: true
            noiseSuppression    = prefs[AladdinPrefs.NOISE_SUPPRESS] ?: true
            offlineTts          = prefs[AladdinPrefs.OFFLINE_TTS]    ?: true
            autoDownloadModels  = prefs[AladdinPrefs.AUTO_DOWNLOAD]  ?: true
            darkTheme           = prefs[AladdinPrefs.DARK_THEME]     ?: false
            proactiveSugg       = prefs[AladdinPrefs.PROACTIVE]      ?: true
            locationAware       = prefs[AladdinPrefs.LOCATION]       ?: true
            moodDetection       = prefs[AladdinPrefs.MOOD_DETECT]    ?: true
            Log.i("SettingsScreen", "Preferences loaded from DataStore")
        } catch (e: Exception) {
            Log.e("SettingsScreen", "Failed to load prefs: ${e.message}")
        }
    }

    fun save() {
        scope.launch {
            isSaving = true
            saveResult = null
            try {
                context.dataStore.edit { prefs ->
                    prefs[AladdinPrefs.WAKE_WORD]      = wakeWordEnabled
                    prefs[AladdinPrefs.CONTINUOUS]     = continuousListening
                    prefs[AladdinPrefs.BACKGROUND]     = backgroundMode
                    prefs[AladdinPrefs.NOISE_SUPPRESS] = noiseSuppression
                    prefs[AladdinPrefs.OFFLINE_TTS]    = offlineTts
                    prefs[AladdinPrefs.AUTO_DOWNLOAD]  = autoDownloadModels
                    prefs[AladdinPrefs.DARK_THEME]     = darkTheme
                    prefs[AladdinPrefs.PROACTIVE]      = proactiveSugg
                    prefs[AladdinPrefs.LOCATION]       = locationAware
                    prefs[AladdinPrefs.MOOD_DETECT]    = moodDetection
                }
                onThemeChanged?.invoke(darkTheme)
                saveResult = true
                Toast.makeText(context, "Settings saved", Toast.LENGTH_SHORT).show()
                Log.i("SettingsScreen", "Preferences saved to DataStore")
            } catch (e: Exception) {
                saveResult = false
                Toast.makeText(context, "Failed to save settings", Toast.LENGTH_SHORT).show()
                Log.e("SettingsScreen", "Save failed: ${e.message}")
            } finally {
                isSaving = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // Save result banner
        AnimatedVisibility(visible = saveResult != null) {
            saveResult?.let { ok ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (ok) Icons.Filled.CheckCircle else Icons.Filled.Error,
                            contentDescription = null, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ok) "Settings saved successfully" else "Save failed — please retry",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Phase 6 Item 6: Theme switching ──────────────────────────────
            item {
                SettingsSection("Appearance") {
                    SettingsToggle(
                        title = "Dark Theme",
                        subtitle = "Switch between dark and light mode — takes effect immediately",
                        icon = Icons.Filled.DarkMode,
                        checked = darkTheme,
                        onCheckedChange = { darkTheme = it }
                    )
                }
            }

            item {
                SettingsSection("Voice") {
                    SettingsToggle("Wake Word Detection",
                        "Listen for \"Aladdin\" even when screen is off",
                        Icons.Filled.Mic, wakeWordEnabled) { wakeWordEnabled = it }
                    SettingsToggle("Continuous Listening",
                        "Keep mic active in background",
                        Icons.Filled.Hearing, continuousListening) { continuousListening = it }
                    SettingsToggle("Noise Suppression",
                        "RNNoise real-time audio enhancement",
                        Icons.Filled.VolumeOff, noiseSuppression) { noiseSuppression = it }
                    SettingsToggle("Offline TTS (Piper)",
                        "Use local Piper TTS — works without internet",
                        Icons.Filled.VolumeUp, offlineTts) { offlineTts = it }
                }
            }

            item {
                SettingsSection("Intelligence") {
                    SettingsToggle("Proactive Suggestions",
                        "Let Aladdin suggest actions based on context",
                        Icons.Filled.AutoAwesome, proactiveSugg) { proactiveSugg = it }
                    SettingsToggle("Location Awareness",
                        "Use GPS for contextual, location-based responses",
                        Icons.Filled.LocationOn, locationAware) { locationAware = it }
                    SettingsToggle("Mood Detection",
                        "Adapt tone and responses based on your detected mood",
                        Icons.Filled.Mood, moodDetection) { moodDetection = it }
                }
            }

            item {
                SettingsSection("Service") {
                    SettingsToggle("Background Mode",
                        "Keep assistant alive when app is closed",
                        Icons.Filled.PlayArrow, backgroundMode) { backgroundMode = it }
                    SettingsToggle("Auto-Download Models",
                        "Download Vosk, Piper, MiniLM on first launch",
                        Icons.Filled.Download, autoDownloadModels) { autoDownloadModels = it }
                    OutlinedButton(
                        onClick = onStartService,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Restart Background Service")
                    }
                }
            }

            // ── Phase 6 Item 2: Save button with loading state ───────────────
            item {
                Button(
                    onClick = ::save,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp), strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Saving…")
                    } else {
                        Icon(Icons.Filled.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Save Settings")
                    }
                }
            }

            item {
                SettingsSection("About") {
                    InfoRow("Version", "1.0.0 — Phase 13 Complete")
                    InfoRow("STT Engine", "Vosk (offline)")
                    InfoRow("TTS Engine", "Piper (offline) / Android TTS")
                    InfoRow("LLM", "Gemini 1.5 Flash / Local")
                    InfoRow("Memory", "MiniLM embeddings + SQLite")
                    InfoRow("Wake Word", "TFLite keyword spotter")
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp))
            content()
        }
    }
}

@Composable
private fun SettingsToggle(
    title: String, subtitle: String, icon: ImageVector,
    checked: Boolean, onCheckedChange: (Boolean) -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface)
    }
}
