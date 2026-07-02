# Aladdin Plugin System — Integration Guide

## Quick Start

### 1. Add to your app's `build.gradle.kts`

```kotlin
dependencies {
    implementation(project(":plugin-system"))
}
```

### 2. Implement `CoreServiceProvider`

```kotlin
class AladdinCoreServices @Inject constructor(
    private val llmClient: LLMClient,
    private val memoryRepo: MemoryRepository,
    private val ttsEngine: TTSEngine,
    private val toolManager: ToolManager
) : CoreServiceProvider {

    override suspend fun queryLLM(prompt: String, systemPrompt: String?) =
        llmClient.complete(prompt, systemPrompt)

    override suspend fun storeMemory(key: String, value: String, importance: Float) =
        memoryRepo.store(key, value, importance)

    override suspend fun queryMemory(query: String, limit: Int) =
        memoryRepo.search(query, limit).map { MemoryFact(it.key, it.value, it.importance, it.timestamp) }

    override suspend fun speak(text: String) = ttsEngine.speak(text)

    override fun registerTool(toolName: String, description: String, handler: suspend (Map<String, Any?>) -> String) =
        toolManager.register(toolName, description, handler)

    override fun unregisterTool(toolName: String) = toolManager.unregister(toolName)

    override fun sendNotification(title: String, body: String, channelId: String) {
        // post Android notification
    }
}
```

### 3. Start PluginManager

```kotlin
@HiltViewModel
class MainViewModel @Inject constructor(
    private val pluginManager: PluginManager
) : ViewModel() {

    init {
        pluginManager.start()
    }

    override fun onCleared() {
        super.onCleared()
        pluginManager.stop()
    }
}
```

### 4. Show Permission Consent Dialog

Before loading a plugin, check for pending consents:

```kotlin
val result = pluginManager.loadPlugin("com.example.myplugin")
if (result is LoadResult.NeedsConsent) {
    showConsentDialog(result.permissions) { granted ->
        if (granted) {
            viewModelScope.launch {
                result.permissions.forEach { perm ->
                    pluginManager.getPermissionManager().grant("com.example.myplugin", perm)
                }
                pluginManager.loadPlugin("com.example.myplugin")
            }
        }
    }
}
```

### 5. Dispatch Commands

```kotlin
val result = pluginManager.dispatch(
    PluginCommand(
        name = "weather.get",
        args = mapOf("city" to "London", "units" to "metric"),
        rawInput = "What's the weather in London?"
    )
)
when (result) {
    is PluginCommandResult.Success  -> showToast(result.message)
    is PluginCommandResult.Failure  -> showError(result.reason)
    is PluginCommandResult.Deferred -> trackPending(result.trackingId)
    PluginCommandResult.NotHandled  -> fallbackToBuiltIn()
}
```

### 6. Hot Reload

```kotlin
pluginManager.hotReload("com.example.myplugin")
```

### 7. Generate Documentation

```kotlin
pluginManager.generateDocs(File(context.filesDir, "plugin-docs"))
```

## Plugin Directory

Plugins are loaded from:
```
/sdcard/Android/data/<your.app.package>/files/plugins/
```
Drop any `.apk` with a valid `assets/plugin.json` there. The directory is watched
automatically every 3 seconds.

## Plugin APK Structure

```
myplugin.apk
└── assets/
    └── plugin.json     ← required metadata manifest
```

## Modules Overview

| Module | Purpose |
|--------|---------|
| `plugin-system` | Core library (discovery, loading, registry, permissions, config, hot-reload, docs) |
| `sample-plugin` | Reference implementation — copy and adapt to build your own plugin |
