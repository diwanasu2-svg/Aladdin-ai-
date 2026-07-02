package com.aladdin.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.aladdin.assistant.data.model.VoiceState
import com.aladdin.assistant.ui.components.VoiceAnimationOrb
import com.aladdin.assistant.ui.components.WaveformVisualizer
import com.aladdin.assistant.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToChat: (String) -> Unit
) {
    val voiceState by viewModel.voiceState.collectAsState()
    val voiceAmplitude by viewModel.voiceAmplitude.collectAsState()
    val activeConversationId by viewModel.activeConversationId.collectAsState()

    var transcript by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.Close, "Close")
                    }
                },
                title = { Text("Voice Assistant") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── State label ────────────────────────────────────────────────
            Spacer(Modifier.height(16.dp))
            AnimatedContent(
                targetState = voiceState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "stateLabel"
            ) { state ->
                Text(
                    text = when (state) {
                        VoiceState.IDLE -> "Tap to speak"
                        VoiceState.LISTENING -> "Listening..."
                        VoiceState.PROCESSING -> "Processing..."
                        VoiceState.SPEAKING -> "AI Speaking"
                        VoiceState.WAKE_WORD_DETECTED -> "Wake word detected!"
                        VoiceState.ERROR -> "Something went wrong"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // ── Main voice orb ─────────────────────────────────────────────
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.Center
            ) {
                VoiceAnimationOrb(
                    voiceState = voiceState,
                    amplitude = voiceAmplitude
                )
            }

            // ── Waveform ───────────────────────────────────────────────────
            WaveformVisualizer(
                voiceState = voiceState,
                amplitude = voiceAmplitude,
                modifier = Modifier.fillMaxWidth().height(80.dp)
            )

            Spacer(Modifier.height(16.dp))

            // ── Transcript ─────────────────────────────────────────────────
            AnimatedVisibility(transcript.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        transcript,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Controls ───────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Stop button
                FilledTonalIconButton(
                    onClick = {
                        viewModel.setVoiceState(VoiceState.IDLE)
                        transcript = ""
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Stop, "Stop")
                }

                // Main mic button
                val orbScale by animateFloatAsState(
                    targetValue = if (voiceState == VoiceState.LISTENING) 1.12f else 1f,
                    animationSpec = spring(Spring.DampingRatioMediumBouncy),
                    label = "orbScale"
                )
                Surface(
                    onClick = {
                        when (voiceState) {
                            VoiceState.IDLE -> {
                                viewModel.setVoiceState(VoiceState.LISTENING)
                                transcript = ""
                            }
                            VoiceState.LISTENING -> {
                                if (transcript.isNotBlank()) {
                                    viewModel.startNewConversation()
                                    viewModel.sendUserMessage(transcript)
                                    activeConversationId?.let { onNavigateToChat(it) }
                                } else {
                                    viewModel.setVoiceState(VoiceState.IDLE)
                                }
                            }
                            else -> viewModel.setVoiceState(VoiceState.IDLE)
                        }
                    },
                    shape = CircleShape,
                    color = when (voiceState) {
                        VoiceState.LISTENING -> MaterialTheme.colorScheme.error
                        VoiceState.PROCESSING -> MaterialTheme.colorScheme.secondary
                        VoiceState.SPEAKING -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(80.dp).scale(orbScale)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            when (voiceState) {
                                VoiceState.LISTENING -> Icons.Default.MicOff
                                VoiceState.PROCESSING -> Icons.Default.Autorenew
                                VoiceState.SPEAKING -> Icons.Default.VolumeUp
                                else -> Icons.Default.Mic
                            },
                            contentDescription = "Mic",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                // Chat button
                FilledTonalIconButton(
                    onClick = {
                        activeConversationId?.let { onNavigateToChat(it) }
                            ?: run {
                                viewModel.startNewConversation()
                                activeConversationId?.let { onNavigateToChat(it) }
                            }
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(Icons.Default.Chat, "Chat")
                }
            }
        }
    }
}
