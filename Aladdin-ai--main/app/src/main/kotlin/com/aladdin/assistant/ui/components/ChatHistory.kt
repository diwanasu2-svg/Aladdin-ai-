package com.aladdin.assistant.ui.components

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
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ChatHistoryDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onClose: () -> Unit
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Conversations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, "Close")
            }
        }

        Button(
            onClick = onNewConversation,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Conversation")
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // Pinned
        val pinned = conversations.filter { it.isPinned }
        if (pinned.isNotEmpty()) {
            Text("Pinned", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            pinned.forEach { conv ->
                DrawerConversationItem(
                    conversation = conv,
                    isActive = conv.id == currentConversationId,
                    onClick = { onSelectConversation(conv.id); onClose() }
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }

        // Others
        val others = conversations.filter { !it.isPinned }
        if (others.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(others, key = { it.id }) { conv ->
                DrawerConversationItem(
                    conversation = conv,
                    isActive = conv.id == currentConversationId,
                    onClick = { onSelectConversation(conv.id); onClose() }
                )
            }
        }
    }
}

@Composable
private fun DrawerConversationItem(
    conversation: Conversation,
    isActive: Boolean,
    onClick: () -> Unit
) {
    NavigationDrawerItem(
        label = {
            Column {
                Text(conversation.title, maxLines = 1, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                if (conversation.summary.isNotBlank()) {
                    Text(conversation.summary, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(0.5f), maxLines = 1)
                }
            }
        },
        selected = isActive,
        onClick = onClick,
        icon = {
            if (conversation.isPinned) Icon(Icons.Default.PushPin, null, modifier = Modifier.size(18.dp))
            else Icon(Icons.Outlined.ChatBubble, null, modifier = Modifier.size(18.dp))
        },
        badge = {
            if (conversation.messageCount > 0) {
                Text(conversation.messageCount.toString(), style = MaterialTheme.typography.labelSmall)
            }
        },
        modifier = Modifier.padding(horizontal = 8.dp)
    )
}

// ─── Conversation Summary Card ─────────────────────────────────────────────────
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ConversationSummaryBottomSheet(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPin: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
            Text(conversation.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()).format(Date(conversation.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(0.5f)
            )

            if (conversation.summary.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(12.dp)) {
                    Text(conversation.summary, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${conversation.messageCount} messages", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.OpenInNew, null)
                Spacer(Modifier.width(8.dp))
                Text("Open Conversation")
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPin, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PushPin, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (conversation.isPinned) "Unpin" else "Pin")
                }
                OutlinedButton(onClick = onArchive, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Archive, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Archive")
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Delete")
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}
