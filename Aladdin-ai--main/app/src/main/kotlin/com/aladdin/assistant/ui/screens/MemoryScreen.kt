package com.aladdin.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
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
import com.aladdin.assistant.viewmodel.MainViewModel

data class MemoryEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: MemoryCategory,
    val createdAt: Long = System.currentTimeMillis(),
    val importance: Int = 1 // 1-3
)

enum class MemoryCategory(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    PERSONAL("Personal", Icons.Outlined.Person),
    PREFERENCES("Preferences", Icons.Outlined.Tune),
    FACTS("Facts", Icons.Outlined.Lightbulb),
    TASKS("Tasks", Icons.Outlined.Task),
    REMINDERS("Reminders", Icons.Outlined.Alarm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var memories by remember {
        mutableStateOf(listOf(
            MemoryEntry("1", "User name", "Prefers to be called by first name", MemoryCategory.PERSONAL, importance = 2),
            MemoryEntry("2", "Preferred language", "English (US)", MemoryCategory.PREFERENCES),
            MemoryEntry("3", "AI style", "Prefers concise, direct answers", MemoryCategory.PREFERENCES, importance = 3),
            MemoryEntry("4", "Location", "Uses metric system", MemoryCategory.FACTS),
            MemoryEntry("5", "Morning briefing", "Asks for weather and news summary daily", MemoryCategory.TASKS, importance = 2)
        ))
    }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<MemoryCategory?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = memories.filter {
        (selectedCategory == null || it.category == selectedCategory) &&
        (searchQuery.isBlank() || it.title.contains(searchQuery, ignoreCase = true) || it.content.contains(searchQuery, ignoreCase = true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } },
                title = { Text("Memory", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, "Add memory")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, "Add")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Search
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search memories...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Clear, null)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(24.dp),
                singleLine = true
            )

            // Category filter chips
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 4.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                        label = { Text("All") }
                    )
                }
                items(MemoryCategory.values().toList()) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        label = { Text(cat.label) },
                        leadingIcon = { Icon(cat.icon, null, modifier = Modifier.size(16.dp)) }
                    )
                }
            }

            // Stats
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceAround) {
                    MemoryStat(label = "Total", value = memories.size.toString())
                    MemoryStat(label = "This Week", value = "3")
                    MemoryStat(label = "High Priority", value = memories.count { it.importance == 3 }.toString())
                }
            }

            // Memory list
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyStateCard(Icons.Outlined.Psychology, "No memories found", "Add memories to help Aladdin personalize responses")
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { memory ->
                        MemoryCard(
                            memory = memory,
                            onDelete = { memories = memories.filter { m -> m.id != memory.id } }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMemoryDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { title, content, category ->
                memories = memories + MemoryEntry(title = title, content = content, category = category)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun MemoryStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
    }
}

@Composable
private fun MemoryCard(memory: MemoryEntry, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(memory.category.icon, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(memory.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        repeat(memory.importance) {
                            Icon(Icons.Default.Star, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(memory.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(0.7f))
                    Spacer(Modifier.height(4.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(memory.category.label, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.error.copy(0.7f)) }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AddMemoryDialog(onDismiss: () -> Unit, onAdd: (String, String, MemoryCategory) -> Unit) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MemoryCategory.FACTS) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = content, onValueChange = { content = it }, label = { Text("Content") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
                ExposedDropdownMenuBox(expanded = false, onExpandedChange = {}) {
                    OutlinedTextField(value = category.label, onValueChange = {}, label = { Text("Category") }, readOnly = true, modifier = Modifier.fillMaxWidth().menuAnchor())
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(MemoryCategory.values().toList()) { cat ->
                        FilterChip(selected = category == cat, onClick = { category = cat }, label = { Text(cat.label) })
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (title.isNotBlank() && content.isNotBlank()) onAdd(title, content, category) }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
