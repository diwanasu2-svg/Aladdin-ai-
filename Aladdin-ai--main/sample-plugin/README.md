# Sample Plugin

A complete reference plugin demonstrating every Aladdin plugin API feature.

## Commands

| Command | Arguments | Description |
|---------|-----------|-------------|
| `sample.hello` | `name` (optional) | Returns a greeting |
| `sample.time` | — | Returns current date/time |
| `sample.echo` | `text` | Echoes text back |
| `sample.ask` | `question` | Queries the AI engine |
| `sample.remember` | `key`, `value` | Stores a memory fact |
| `sample.recall` | `query` | Retrieves memory facts |
| `sample.notify` | `title`, `body` | Sends a notification |

## Config Keys

| Key | Default | Description |
|-----|---------|-------------|
| `greeting` | `"Hello"` | Prefix for sample.hello responses |
| `timeFormat` | `"yyyy-MM-dd HH:mm:ss"` | Java SimpleDateFormat pattern |

## Building as a standalone APK

```bash
# From the project root
./gradlew :sample-plugin:assembleRelease
# Output: sample-plugin/build/outputs/apk/release/sample-plugin-release.apk
```

Copy the output APK to the Aladdin plugins directory to install.

## Usage (host app)

```kotlin
val result = pluginManager.dispatch(
    PluginCommand("sample.hello", mapOf("name" to "Aladdin"))
)
// → PluginCommandResult.Success("Hello, Aladdin! I am the Sample Plugin.")
```
