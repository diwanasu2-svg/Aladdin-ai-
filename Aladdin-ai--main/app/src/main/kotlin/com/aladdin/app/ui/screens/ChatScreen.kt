package com.aladdin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aladdin.app.AladdinUiState
import com.aladdin.app.ChatMessage
import com.aladdin.app.ErrorAction
import kotlinx.coroutines.delay

// ─── Phase 2: Partial Transcript UI + Streaming Response Display ──────────────
//
// New in Phase 2:
//  • [PartialTranscriptBanner] — live what-the-user-is-saying overlay
//  • [StreamingResponseCard] — LLM tokens shown as they arrive (typing effect)
//  • VoiceButton pulse animation while SPEAKING state is active (barge-in hint)
//  • Status subtitle shows "Tap mic to interrupt" during SPEAKING state

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    uiState: AladdinUiState,
    onSendMessage: (String) -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onClearConversation: () -> Unit,
    onDismissError: () -> Unit = {},
    onErrorAction: (ErrorAction) -> Unit = {}
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll when messages or streaming response changes
    LaunchedEffect(uiState.messages.size, uiState.streamingResponse) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {

        // ─── Top bar ──────────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Column {
                    Text("Aladdin", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = uiState.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            actions = {
                IconButton(onClick = onClearConversation) {
                    Icon(Icons.Filled.Delete, contentDescription = "Clear chat")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // ─── Download progress ────────────────────────────────────────────────
        AnimatedVisibility(visible = uiState.downloadingModel != null) {
            uiState.downloadingModel?.let { modelName ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Downloading $modelName… ${uiState.downloadProgress}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    LinearProgressIndicator(
                        progress = { uiState.downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }

        // ─── Offline banner ───────────────────────────────────────────────────
        // Reliability: NetworkMonitor was wired up but nothing ever showed its
        // state to the user — you'd only find out something needed the internet
        // when a feature silently failed. Now it's visible up front.
        AnimatedVisibility(visible = uiState.isOffline) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.CloudOff, contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "No internet — voice chat still works if your AI server is on the same WiFi. " +
                        "Cloud features (news, web search) need a connection.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        // ─── Error banner ─────────────────────────────────────────────────────
        AnimatedVisibility(visible = uiState.errorMessage != null) {
            uiState.errorMessage?.let { error ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                text = error,
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                            IconButton(
                                onClick = onDismissError,
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Close, contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (uiState.errorAction != ErrorAction.NONE) {
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = { onErrorAction(uiState.errorAction) }) {
                                Text(
                                    when (uiState.errorAction) {
                                        ErrorAction.OPEN_APP_SETTINGS -> "Open Settings"
                                        ErrorAction.RETRY_MODEL_DOWNLOAD -> "Retry"
                                        ErrorAction.NONE -> ""
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // ─── Phase 2: Live partial transcript banner ──────────────────────────
        AnimatedVisibility(
            visible = uiState.partialTranscript.isNotBlank(),
            enter = fadeIn() + slideInVertically(),
            exit  = fadeOut() + slideOutVertically()
        ) {
            PartialTranscriptBanner(text = uiState.partialTranscript)
        }

        // ─── Message list ─────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.messages.isEmpty() && uiState.streamingResponse.isBlank()) {
                item { EmptyStateMessage() }
            }

            items(uiState.messages, key = { it.id }) { message ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 3 })
                ) {
                    ChatBubble(message = message)
                }
            }

            // Phase 2: live streaming response bubble
            if (uiState.streamingResponse.isNotBlank()) {
                item {
                    StreamingResponseCard(text = uiState.streamingResponse)
                }
            }

            if (uiState.isThinking && uiState.streamingResponse.isBlank()) {
                item { TypingIndicator() }
            }
        }

        // ─── Input row ────────────────────────────────────────────────────────
        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Type a message…") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (inputText.isNotBlank()) { onSendMessage(inputText.trim()); inputText = "" }
                        }
                    ),
                    shape = RoundedCornerShape(24.dp),
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))

                AnimatedVisibility(visible = inputText.isNotBlank()) {
                    FilledIconButton(onClick = {
                        onSendMessage(inputText.trim()); inputText = ""
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send")
                    }
                }

                AnimatedVisibility(visible = inputText.isBlank()) {
                    VoiceButton(
                        isListening = uiState.isListening,
                        isSpeaking  = uiState.isSpeaking,
                        onStartListening = onStartListening,
                        onStopListening  = onStopListening
                    )
                }
            }
        }
    }
}

// ─── Phase 2: Partial transcript banner ──────────────────────────────────────

@Composable
private fun PartialTranscriptBanner(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 2
            )
        }
    }
}

// ─── Phase 2: Live streaming response card ────────────────────────────────────

@Composable
private fun StreamingResponseCard(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Blinking cursor
                StreamingCursor()
            }
        }
    }
}

@Composable
private fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor_alpha"
    )
    Box(
        modifier = Modifier
            .size(width = 2.dp, height = 14.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
    )
}

// ─── Existing components (unchanged from Phase 1) ─────────────────────────────

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.isUser
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text("A", color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.width(8.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd   = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp
            ),
            color = when {
                message.isError -> MaterialTheme.colorScheme.errorContainer
                isUser          -> MaterialTheme.colorScheme.primary
                else            -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                color = when {
                    message.isError -> MaterialTheme.colorScheme.onErrorContainer
                    isUser          -> MaterialTheme.colorScheme.onPrimary
                    else            -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun VoiceButton(
    isListening: Boolean,
    isSpeaking: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = if (isListening || isSpeaking) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val containerColor = when {
        isSpeaking  -> MaterialTheme.colorScheme.tertiary   // speaking = tap to interrupt
        isListening -> MaterialTheme.colorScheme.error
        else        -> MaterialTheme.colorScheme.primary
    }

    FilledIconButton(
        onClick = { if (isListening || isSpeaking) onStopListening() else onStartListening() },
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = containerColor)
    ) {
        Icon(
            imageVector = when {
                isSpeaking  -> Icons.Filled.Stop
                isListening -> Icons.Filled.MicOff
                else        -> Icons.Filled.Mic
            },
            contentDescription = when {
                isSpeaking  -> "Tap to interrupt"
                isListening -> "Stop listening"
                else        -> "Start listening"
            }
        )
    }
}

@Composable
private fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("A", color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp,
                bottomStart = 16.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) { repeat(3) { index -> TypingDot(delayMs = index * 150L) } }
        }
    }
}

@Composable
private fun TypingDot(delayMs: Long) {
    val infiniteTransition = rememberInfiniteTransition(label = "dot_$delayMs")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = -6f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, delayMillis = delayMs.toInt(), easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dot_offset_$delayMs"
    )
    Box(
        modifier = Modifier.size(8.dp).offset(y = offsetY.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    )
}

@Composable
private fun EmptyStateMessage() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Filled.Mic, contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "Say \"Aladdin\" or type a message",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "I can help with weather, reminders, news, web search, and more.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
