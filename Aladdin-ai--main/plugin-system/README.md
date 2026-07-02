# Aladdin Plugin System

A hot-reloadable, permission-gated Android plugin system for the Aladdin AI assistant.

## Architecture

```
plugin-system/
├── api/                  ← Public API: BasePlugin, PluginContext, CoreServiceProvider
├── config/               ← Per-plugin JSON/SharedPrefs configuration
├── discovery/            ← APK scanning, DexClassLoader loading
├── docs/                 ← Auto-generated Markdown documentation
├── hotreload/            ← Reload plugins without restarting the app
├── manager/              ← PluginManager orchestrator + restricted services
├── model/                ← PluginInfo, PluginState, PluginPermission
├── permissions/          ← User consent system, permission enforcement
├── registry/             ← Room-backed plugin registry + DB
└── di/                   ← Hilt dependency injection module
```

## How It Works

### 1. Plugin Discovery
Place any `.apk` containing a `plugin.json` asset in:
```
/sdcard/Android/data/<your-app>/files/plugins/
```
`PluginDiscovery` scans this directory (and watches for changes) and reads metadata from each APK.

### 2. Plugin Metadata (`plugin.json`)
Every plugin APK **must** contain `assets/plugin.json`:
```json
{
  "pluginId":    "com.example.weather",
  "name":        "Weather Plugin",
  "version":     "1.0.0",
  "description": "Fetches live weather data",
  "author":      "Jane Doe",
  "mainClass":   "com.example.weather.WeatherPlugin",
  "minAladdinVersion": "1.0.0",
  "requiredPermissions": ["INTERNET", "CORE_LLM"],
  "handledCommands":     ["weather.get", "weather.forecast"],
  "tags": ["weather", "internet"],
  "config": {
    "units": "metric",
    "cacheMinutes": 10
  }
}
```

### 3. Permission Consent
Before loading, the host app shows a consent dialog for each required permission.
Grants are stored in Room and enforced at every `CoreServiceProvider` call.

### 4. Loading via DexClassLoader
```kotlin
val plugin = PluginLoader(context).load(pluginInfo)
// Inject context and call lifecycle
plugin.pluginContext = ...
plugin.onLoad()
```

### 5. Hot Reload
```kotlin
pluginManager.hotReload("com.example.weather")
// onUnload() → dex cache cleared → fresh load → onLoad()
```

### 6. Command Dispatch
```kotlin
val result = pluginManager.dispatch(PluginCommand("weather.get", mapOf("city" to "London")))
```

## Writing a Plugin

See `sample-plugin/` for a complete example.

```kotlin
class WeatherPlugin : BasePlugin() {
    override val pluginId = "com.example.weather"
    override val name     = "Weather Plugin"
    override val version  = "1.0.0"
    override val description = "Live weather data"
    override val author   = "Jane Doe"
    override val requiredPermissions = listOf(PluginPermissionType.INTERNET)
    override val handledCommands     = listOf("weather.get")

    override fun onLoad(): Boolean {
        logger.i("WeatherPlugin", "Loaded!")
        return true
    }

    override fun onUnload() {
        logger.i("WeatherPlugin", "Unloaded.")
    }

    override fun onCommand(command: PluginCommand): PluginCommandResult {
        val city = command.argOrDefault("city", "London")
        return PluginCommandResult.Success("Weather in $city: Sunny 22°C")
    }
}
```

## Available Permissions

| Permission | Description |
|------------|-------------|
| `INTERNET` | Access the internet |
| `MICROPHONE` | Record audio |
| `CAMERA` | Access camera |
| `CONTACTS` | Read/write contacts |
| `CALENDAR` | Read/write calendar |
| `STORAGE` | External storage |
| `LOCATION` | Device location |
| `NOTIFICATIONS` | Send notifications |
| `CORE_LLM` | Query the AI engine |
| `CORE_MEMORY` | Read/write smart memory |
| `CORE_VOICE` | Text-to-speech |
| `CORE_TOOLS` | Register tools |
| `SYSTEM_SETTINGS` | Modify settings |
| `ACCESSIBILITY` | Accessibility APIs |
