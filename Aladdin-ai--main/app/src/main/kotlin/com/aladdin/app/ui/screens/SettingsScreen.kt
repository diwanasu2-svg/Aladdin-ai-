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
import com.aladdin.app.provider.ProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onStartService: () -> Unit,
    onThemeChanged: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val providerConfig = remember { ProviderConfig(context) }
    var geminiApiKey       by remember { mutableStateOf(providerConfig.geminiApiKey) }
    var geminiModel        by remember { mutableStateOf(providerConfig.geminiModel) }
    var providerSaveResult by remember { mutableStateOf<Boolean?>(null) }
    var testingGemini      by remember { mutableStateOf(false) }
    var geminiTestResult   by remember { mutableStateOf<String?>(null) }
    var useOnDeviceFallback by remember { mutableStateOf(providerConfig.preferredProvider == "local") }

    /** Validate the Gemini key by calling the models list endpoint. */
    fun testGemini() {
        scope.launch {
            testingGemini = true
            geminiTestResult = null
            val key = geminiApiKey.trim()
            if (key.isBlank()) {
                geminiTestResult = "❌ Please enter a Gemini API key first."
                testingGemini = false
                return@launch
            }
            val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
            val result = withContext(Dispatchers.IO) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.requestMethod = "GET"
                    val code = conn.responseCode
                    conn.disconnect()
                    code
                } catch (e: Exception) { -1 }
            }
            testingGemini = false
            geminiTestResult = when (result) {
                200  -> "✅ Gemini key is valid and working."
                400  -> "❌ Bad request — check your API key format."
                401, 403 -> "❌ Invalid or expired API key. Get a fresh one at aistudio.google.com/apikey."
                429  -> "⚠️ Rate limit hit — key is valid but quota is exhausted. Try again later."
                -1   -> "⚠️ Network error — check your internet connection."
                else -> "⚠️ Unexpected response (HTTP $result). Key may still work."
            }
            Toast.makeText(context, geminiTestResult, Toast.LENGTH_LONG).show()
        }
    }

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
            item {
                SettingsSection("Appearance") {
                    SettingsToggle(
                        title = "Dark Theme",
                        subtitle = "Switch between dark and light mode",
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

            // ── AI Provider — Gemini only ──────────────────────────────────────
            item {
                SettingsSection("AI Provider — Gemini") {
                    Text(
                        "Aladdin uses Google Gemini as its AI brain. Paste your free API key " +
                        "from aistudio.google.com/apikey below, then tap Save.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("Gemini API Key") },
                        placeholder = { Text("AIzaSy...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = geminiModel,
                        onValueChange = { geminiModel = it },
                        label = { Text("Gemini Model") },
                        placeholder = { Text("gemini-1.5-flash") },
                        supportingText = { Text("gemini-1.5-flash (fast/free) or gemini-1.5-pro") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = {
                                providerConfig.geminiApiKey = geminiApiKey.trim()
                                providerConfig.geminiModel  = geminiModel.trim().ifBlank { "gemini-1.5-flash" }
                                providerSaveResult = true
                                Toast.makeText(context, "Gemini settings saved", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Save")
                        }
                        OutlinedButton(
                            onClick = ::testGemini,
                            enabled = !testingGemini,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (testingGemini) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.NetworkCheck, contentDescription = null)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text("Test Gemini")
                        }
                    }
                    geminiTestResult?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    SettingsToggle(
                        title = "Use fully on-device model instead",
                        subtitle = "No internet needed, fully offline — may be slow on some devices",
                        icon = Icons.Filled.PhoneAndroid,
                        checked = useOnDeviceFallback
                    ) { checked ->
                        useOnDeviceFallback = checked
                        providerConfig.preferredProvider = if (checked) "local" else "gemini"
                        Toast.makeText(
                            context,
                            if (checked) "Switched to on-device model (offline)" else "Switched to Gemini",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

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
                    InfoRow("Version", "1.0.0")
                    InfoRow("STT Engine", "Whisper.cpp (offline)")
                    InfoRow("TTS Engine", "Android TTS")
                    InfoRow("LLM", "Gemini 1.5 Flash (cloud)")
                    InfoRow("Memory", "MiniLM embeddings + SQLite")
                    InfoRow("Wake Word", "Neural ONNX classifier")
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
