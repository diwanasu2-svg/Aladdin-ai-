package com.aladdin.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.aladdin.assistant.data.model.Conversation
import com.aladdin.assistant.viewmodel.MainViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    viewModel: MainViewModel,
    onSelectConversation: (String) -> Unit,
    onBack: () -> Unit
) {
    val conversations by viewModel.conversations.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var selectedConversations by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val isSelectionMode = selectedConversations.isNotEmpty()

    Scaffold(
        topBar = {
            if (isSelectionMode) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selectedConversations = emptySet() }) {
                            Icon(Icons.Default.Close, "Cancel")
                        }
                    },
                    title = { Text("${selectedConversations.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            conversations.filter { it.id in selectedConversations }
                                .forEach { viewModel.pinConversation(it.id, !it.isPinned) }
                            selectedConversations = emptySet()
                        }) { Icon(Icons.Default.PushPin, "Pin") }
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.error)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                    title = { Text("Chat History", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = {}) { Icon(Icons.Default.FilterList, "Filter") }
                    }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.startNewConversation()
                    val id = viewModel.activeConversationId.value ?: return@FloatingActionButton
                    onSelectConversation(id)
                }
            ) {
                Icon(Icons.Default.Add, "New")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                placeholder = { Text("Search conversations...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Clear, null)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            if (conversations.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (searchQuery.isBlank()) {
                        EmptyStateCard(Icons.Outlined.ChatBubbleOutline, "No conversations", "Start a new chat to begin")
                    } else {
                        EmptyStateCard(Icons.Outlined.SearchOff, "No results", "Try a different search term")
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Pinned section
                    val pinned = conversations.filter { it.isPinned }
                    if (pinned.isNotEmpty()) {
                        item {
                            Text("Pinned", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                        }
                        items(pinned, key = { it.id }) { conv ->
                            ConversationListItem(
                                conversation = conv,
                                isSelected = conv.id in selectedConversations,
                                isSelectionMode = isSelectionMode,
                                onSelect = {
                                    selectedConversations = if (conv.id in selectedConversations)
                                        selectedConversations - conv.id else selectedConversations + conv.id
                                },
                                onClick = { onSelectConversation(conv.id) },
                                onLongClick = { selectedConversations = setOf(conv.id) },
                                onPin = { viewModel.pinConversation(conv.id, !conv.isPinned) },
                                onArchive = { viewModel.archiveConversation(conv.id) },
                                onDelete = { viewModel.deleteConversation(conv) }
                            )
                        }
                        item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }
                    }

                    // All other conversations
                    val rest = conversations.filter { !it.isPinned }
                    if (rest.isNotEmpty() && pinned.isNotEmpty()) {
                        item {
                            Text("All Conversations", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp))
                        }
                    }
                    items(rest, key = { it.id }) { conv ->
                        ConversationListItem(
                            conversation = conv,
                            isSelected = conv.id in selectedConversations,
                            isSelectionMode = isSelectionMode,
                            onSelect = {
                                selectedConversations = if (conv.id in selectedConversations)
                                    selectedConversations - conv.id else selectedConversations + conv.id
                            },
                            onClick = { onSelectConversation(conv.id) },
                            onLongClick = { selectedConversations = setOf(conv.id) },
                            onPin = { viewModel.pinConversation(conv.id, !conv.isPinned) },
                            onArchive = { viewModel.archiveConversation(conv.id) },
                            onDelete = { viewModel.deleteConversation(conv) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete ${selectedConversations.size} conversation(s)?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    conversations.filter { it.id in selectedConversations }.forEach { viewModel.deleteConversation(it) }
                    selectedConversations = emptySet()
                    showDeleteDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListItem(
    conversation: Conversation,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onSelect: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    var showContextMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = { if (isSelectionMode) onSelect() else onClick() }, onLongClick = { if (!isSelectionMode) onLongClick() }),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isSelectionMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelect() })
                Spacer(Modifier.width(8.dp))
            } else {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ChatBubble, null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (conversation.isPinned) {
                        Icon(Icons.Default.PushPin, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    Text(conversation.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                if (conversation.summary.isNotBlank()) {
                    Text(conversation.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.6f), maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(conversation.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.4f)
                    )
                    if (conversation.messageCount > 0) {
                        Text("${conversation.messageCount} messages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(0.4f))
                    }
                }
            }

            if (!isSelectionMode) {
                Box {
                    IconButton(onClick = { showContextMenu = true }) {
                        Icon(Icons.Default.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurface.copy(0.5f))
                    }
                    DropdownMenu(expanded = showContextMenu, onDismissRequest = { showContextMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                            leadingIcon = { Icon(Icons.Default.PushPin, null) },
                            onClick = { onPin(); showContextMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Archive") },
                            leadingIcon = { Icon(Icons.Default.Archive, null) },
                            onClick = { onArchive(); showContextMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { onDelete(); showContextMenu = false }
                        )
                    }
                }
            }
        }
    }
}
