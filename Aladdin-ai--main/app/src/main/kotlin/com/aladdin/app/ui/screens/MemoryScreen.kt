package com.aladdin.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// ─── Phase 6 Item 3: MemoryScreen — delete, clear, search/filter, sort ────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(modifier: Modifier = Modifier) {
    var searchQuery by remember { mutableStateOf("") }
    var sortByDate  by remember { mutableStateOf(true) }
    var showClearDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Int?>(null) }

    // In production these come from VectorMemoryStore / Room DB via ViewModel.
    // Here we seed a rich default list that shows delete/filter in action.
    val memories = remember {
        mutableStateListOf(
            MemItem("User prefers metric units for weather",       "Preference", System.currentTimeMillis() - 60_000),
            MemItem("User's name stored in profile",               "Profile",    System.currentTimeMillis() - 300_000),
            MemItem("Reminder: dentist appointment next Tuesday",  "Reminder",   System.currentTimeMillis() - 3_600_000),
            MemItem("Favorite city: London",                       "Preference", System.currentTimeMillis() - 86_400_000),
            MemItem("Project: Android AI Assistant — Phase 13",    "Project",    System.currentTimeMillis() - 172_800_000),
            MemItem("User likes jazz music",                       "Preference", System.currentTimeMillis() - 259_200_000),
            MemItem("Meeting with Alice on Monday 10am",           "Calendar",   System.currentTimeMillis() - 400_000),
            MemItem("Shopping: buy milk and bread",                "Task",       System.currentTimeMillis() - 1_200_000),
        )
    }

    val typeFilter = remember { mutableStateOf<String?>(null) }
    val allTypes   = memories.map { it.type }.distinct().sorted()

    val displayed = memories
        .filter { m ->
            (searchQuery.isBlank() || m.content.contains(searchQuery, ignoreCase = true)) &&
            (typeFilter.value == null || m.type == typeFilter.value)
        }
        .let { if (sortByDate) it.sortedByDescending { m -> m.timestampMs } else it.sortedBy { m -> m.content } }

    // ── Confirm delete single ─────────────────────────────────────────────────
    deleteTarget?.let { idx ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            title = { Text("Delete memory?") },
            text  = { Text(memories.getOrNull(idx)?.content ?: "") },
            confirmButton = {
                TextButton(onClick = {
                    memories.removeAt(idx)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    // ── Confirm clear all ────────────────────────────────────────────────────
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Filled.DeleteForever, contentDescription = null) },
            title = { Text("Clear all memories?") },
            text  = { Text("This permanently removes all ${memories.size} memories. Cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { memories.clear(); showClearDialog = false }) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Memory") },
            actions = {
                IconButton(onClick = { sortByDate = !sortByDate }) {
                    Icon(
                        if (sortByDate) Icons.Filled.SortByAlpha else Icons.Filled.AccessTime,
                        contentDescription = if (sortByDate) "Sort alphabetically" else "Sort by date"
                    )
                }
                if (memories.isNotEmpty()) {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = "Clear all")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        // ── Search bar ─────────────────────────────────────────────────────
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search memory…") },
            leadingIcon  = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                { IconButton(onClick = { searchQuery = "" }) { Icon(Icons.Filled.Clear, "Clear") } }
            } else null,
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        // ── Type filter chips ──────────────────────────────────────────────
        if (allTypes.isNotEmpty()) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                item {
                    FilterChip(
                        selected = typeFilter.value == null,
                        onClick = { typeFilter.value = null },
                        label = { Text("All") }
                    )
                }
                items(allTypes.size) { i ->
                    val t = allTypes[i]
                    FilterChip(
                        selected = typeFilter.value == t,
                        onClick = { typeFilter.value = if (typeFilter.value == t) null else t },
                        label = { Text(t) }
                    )
                }
            }
        }

        // ── Memory count ───────────────────────────────────────────────────
        Text(
            "${displayed.size} / ${memories.size} memories",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
        )

        if (displayed.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text("No memories found", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (searchQuery.isNotBlank() || typeFilter.value != null) {
                        TextButton(onClick = { searchQuery = ""; typeFilter.value = null }) {
                            Text("Clear filters")
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(displayed, key = { _, m -> m.timestampMs }) { _, memory ->
                    val realIdx = memories.indexOf(memory)
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn() + slideInHorizontally()
                    ) {
                        MemoryCard(
                            memory = memory,
                            onDelete = { if (realIdx >= 0) deleteTarget = realIdx }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: MemItem, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = when (memory.type) {
                    "Reminder"   -> Icons.Filled.Notifications
                    "Preference" -> Icons.Filled.Tune
                    "Project"    -> Icons.Filled.FolderOpen
                    "Calendar"   -> Icons.Filled.CalendarToday
                    "Task"       -> Icons.Filled.CheckCircle
                    "Profile"    -> Icons.Filled.Person
                    else         -> Icons.Filled.Memory
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(memory.content, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = {}, label = {
                        Text(memory.type, style = MaterialTheme.typography.labelSmall)
                    })
                    Text(
                        relativeTime(memory.timestampMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }
            // ── Delete button ────────────────────────────────────────────
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete memory",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }
}

private fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000            -> "Just now"
        diff < 3_600_000         -> "${diff / 60_000} min ago"
        diff < 86_400_000        -> "${diff / 3_600_000} hr ago"
        diff < 604_800_000       -> "${diff / 86_400_000} days ago"
        else                     -> "${diff / 604_800_000} wk ago"
    }
}

private data class MemItem(val content: String, val type: String, val timestampMs: Long)
