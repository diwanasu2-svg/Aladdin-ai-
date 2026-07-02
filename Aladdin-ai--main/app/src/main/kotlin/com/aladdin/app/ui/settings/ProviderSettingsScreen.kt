package com.aladdin.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Phase 6 fix — Compose-only provider settings screen.
 * Replaces all Fragment-based settings (ProviderSelectionFragment, OllamaSettingsFragment,
 * GeminiSettingsFragment, OpenAISettingsFragment, AnthropicSettingsFragment).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    var selectedProvider by remember { mutableStateOf("Ollama") }
    var ollamaHost by remember { mutableStateOf("http://localhost:11434") }
    var ollamaModel by remember { mutableStateOf("llama3") }
    var geminiApiKey by remember { mutableStateOf("") }
    var openAiApiKey by remember { mutableStateOf("") }
    var anthropicApiKey by remember { mutableStateOf("") }

    val providers = listOf("Ollama", "Gemini", "OpenAI", "Anthropic", "Local (LlamaCpp)", "Local (MLC)")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Provider Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Provider", style = MaterialTheme.typography.titleMedium)
                providers.forEach { provider ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        RadioButton(
                            selected = selectedProvider == provider,
                            onClick = { selectedProvider = provider }
                        )
                        Text(provider, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item { Divider() }
            when (selectedProvider) {
                "Ollama" -> item {
                    Text("Ollama Settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ollamaHost,
                        onValueChange = { ollamaHost = it },
                        label = { Text("Host URL") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ollamaModel,
                        onValueChange = { ollamaModel = it },
                        label = { Text("Model Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "Gemini" -> item {
                    Text("Gemini Settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = geminiApiKey,
                        onValueChange = { geminiApiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("AIza...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "OpenAI" -> item {
                    Text("OpenAI Settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = openAiApiKey,
                        onValueChange = { openAiApiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                "Anthropic" -> item {
                    Text("Anthropic Settings", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = anthropicApiKey,
                        onValueChange = { anthropicApiKey = it },
                        label = { Text("API Key") },
                        placeholder = { Text("sk-ant-...") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                else -> item {
                    Text(
                        "$selectedProvider: On-device model. No API key required.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        // TODO: Implement actual saving logic via ViewModel or SharedPreferences
                        // For now, this just acts as a placeholder callback.
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Save Settings")
                }
            }
        }
    }
}
