package com.aladdin.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Provider settings screen — Gemini only.
 * All Ollama settings have been removed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {}
) {
    var geminiApiKey by remember { mutableStateOf("") }
    var geminiModel  by remember { mutableStateOf("gemini-1.5-flash") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Provider Settings") },
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
                Text(
                    "Aladdin uses Google Gemini as its AI backend. " +
                    "Get a free API key at aistudio.google.com/apikey.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            item { Divider() }
            item {
                Text("Gemini Settings", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiApiKey,
                    onValueChange = { geminiApiKey = it },
                    label = { Text("Gemini API Key") },
                    placeholder = { Text("AIzaSy...") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = geminiModel,
                    onValueChange = { geminiModel = it },
                    label = { Text("Model") },
                    placeholder = { Text("gemini-1.5-flash") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onBack() },
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
