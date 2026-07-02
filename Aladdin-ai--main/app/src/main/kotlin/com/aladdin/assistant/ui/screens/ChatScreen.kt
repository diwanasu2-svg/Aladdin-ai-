package com.aladdin.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.aladdin.assistant.data.model.*
import com.aladdin.assistant.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── Phase 6 Item 1: ChatScreen — Fixed & Verified ───────────────────────────
// Fixes applied:
//   • Auto-scroll to bottom on new messages (rememberLazyListState + LaunchedEffect)
//   • Voice input button wired to viewModel.startVoiceInput()
//   • Recording state management — mic button shows stop icon while recording
//   • Send button properly disabled when empty or loading
//   • FocusRequester applied to TextField for correct focus management
//   • Transcript display from voiceState
//   • Compose state properly scoped with remember / mutableStateOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    conversationId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val messages    by viewModel.activeMessages.collectAsState()
    val isLoading   by viewModel.isLoading.collectAsState()
    val voiceState  by viewModel.voiceState.collectAsState()
    val conversations by viewModel.conversations.collectAsState()

    val conversation  = conversations.find { it.id == conversationId }
    val listState     = rememberLazyListState()
    val scope         = rememberCoroutineScope()
    var inputText     by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    val isRecording = voiceState == VoiceState.LISTENING
    val isProcessing = voiceState == VoiceState.PROCESSING

    // ── Auto-scroll to bottom on new messages ─────────────────────────────────
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // ── When voice transcript arrives, populate text field ────────────────────
    LaunchedEffect(viewModel.voiceTranscript) {
        viewModel.voiceTranscript.collect { transcript ->
            if (transcript.isNotBlank()) {
                inputText = transcript
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text       = conversation?.title ?: "New Conversation",
                            fontWeight = FontWeight.SemiBold,
                            maxLines   = 1
                        )
                        AnimatedVisibility(visible = voiceState != VoiceState.IDLE) {
                            Text(
                                text  = when (voiceState) {
                                    VoiceState.PROCESSING -> "AI is thinking…"
                                    VoiceState.SPEAKING   -> "AI is speaking…"
                                    VoiceState.LISTENING  -> "Listening…"
                                    else                  -> ""
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {}) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            ChatInputBar(
                text             = inputText,
                onTextChange     = { inputText = it },
                onSend           = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendUserMessage(inputText.trim())
                        inputText = ""
                        scope.launch { listState.animateScrollToItem(messages.size) }
                    }
                },
                onVoiceToggle    = {
                    if (isRecording) {
                        viewModel.stopVoiceInput()
                    } else {
                        viewModel.startVoiceInput()
                    }
                },
                isLoading        = isLoading,
                isRecording      = isRecording,
                focusRequester   = focusRequester
            )
        }
    ) { padding ->
        LazyColumn(
            state            = listState,
            modifier         = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding   = PaddingValues(vertical = 12.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }

            if (isProcessing) {
                item { TypingIndicator() }
            }
        }
    }
}

// ─── Message Bubble ───────────────────────────────────────────────────────────
@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == MessageRole.USER
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment   = Alignment.Bottom
    ) {
        if (!isUser) Spacer(Modifier.width(8.dp))

        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            if (!isUser) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(28.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint     = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart     = 20.dp,
                    topEnd       = 20.dp,
                    bottomStart  = if (isUser) 20.dp else 6.dp,
                    bottomEnd    = if (isUser) 6.dp else 20.dp
                ),
                color = if (isUser) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text     = message.content,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color    = if (isUser) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant,
                    style    = MaterialTheme.typography.bodyMedium
                )
            }

            // Timestamp
            Text(
                text  = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
        if (isUser) Spacer(Modifier.width(8.dp))
    }
}

// ─── Typing Indicator ─────────────────────────────────────────────────────────
@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape    = CircleShape,
            color    = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(32.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint     = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Row(
                modifier              = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                repeat(3) { i ->
                    val offsetY by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue  = -6f,
                        animationSpec = infiniteRepeatable(
                            tween(400, delayMillis = i * 133),
                            RepeatMode.Reverse
                        ),
                        label = "dot$i"
                    )
                    Surface(
                        shape    = CircleShape,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.size(8.dp).offset(y = offsetY.dp)
                    ) {}
                }
            }
        }
    }
}

// ─── Chat Input Bar ───────────────────────────────────────────────────────────
@Composable
private fun ChatInputBar(
    text           : String,
    onTextChange   : (String) -> Unit,
    onSend         : () -> Unit,
    onVoiceToggle  : () -> Unit,
    isLoading      : Boolean,
    isRecording    : Boolean,
    focusRequester : FocusRequester
) {
    val pulseAnim by rememberInfiniteTransition(label = "mic_pulse").animateFloat(
        initialValue  = 0.85f,
        targetValue   = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label         = "pulse"
    )

    Surface(
        tonalElevation = 4.dp,
        color          = MaterialTheme.colorScheme.background
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment     = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Voice toggle button ───────────────────────────────────────────
            FilledTonalIconButton(
                onClick  = onVoiceToggle,
                modifier = Modifier.size(48.dp),
                colors   = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isRecording)
                        MaterialTheme.colorScheme.errorContainer
                    else
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    imageVector         = if (isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription  = if (isRecording) "Stop recording" else "Voice input",
                    modifier            = Modifier.scale(if (isRecording) pulseAnim else 1f),
                    tint                = if (isRecording)
                        MaterialTheme.colorScheme.onErrorContainer
                    else
                        MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            // ── Text field ────────────────────────────────────────────────────
            OutlinedTextField(
                value         = text,
                onValueChange = onTextChange,
                modifier      = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder   = {
                    Text(if (isRecording) "Listening…" else "Message Aladdin…")
                },
                shape         = RoundedCornerShape(24.dp),
                maxLines      = 5,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                colors        = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor   = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            )

            // ── Send button ───────────────────────────────────────────────────
            FilledIconButton(
                onClick  = onSend,
                enabled  = text.isNotBlank() && !isLoading,
                modifier = Modifier.size(48.dp),
                colors   = IconButtonDefaults.filledIconButtonColors(
                    containerColor         = MaterialTheme.colorScheme.primary,
                    disabledContainerColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        color       = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send message",
                        tint               = if (text.isNotBlank())
                            MaterialTheme.colorScheme.onPrimary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
