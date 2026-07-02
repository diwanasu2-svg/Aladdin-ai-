package com.aladdin.plugin

import com.aladdin.plugin.model.PluginInfo
import com.aladdin.plugin.model.PluginState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PluginInfoTest {

    private fun makePlugin(
        id: String = "com.test.plugin",
        state: PluginState = PluginState.DISCOVERED
    ) = PluginInfo(
        pluginId = id,
        name = "Test Plugin",
        version = "1.0.0",
        description = "A test plugin",
        author = "Test Author",
        apkPath = "/data/plugins/test.apk",
        mainClass = "com.test.plugin.TestPlugin",
        state = state
    )

    @Test fun `plugin info default state is DISCOVERED`() {
        val plugin = makePlugin()
        assertThat(plugin.state).isEqualTo(PluginState.DISCOVERED)
    }

    @Test fun `plugin info fields are correct`() {
        val plugin = makePlugin(id = "com.example.plugin")
        assertThat(plugin.pluginId).isEqualTo("com.example.plugin")
        assertThat(plugin.name).isEqualTo("Test Plugin")
        assertThat(plugin.version).isEqualTo("1.0.0")
        assertThat(plugin.author).isEqualTo("Test Author")
        assertThat(plugin.mainClass).isEqualTo("com.test.plugin.TestPlugin")
    }

    @Test fun `default values are sane`() {
        val plugin = makePlugin()
        assertThat(plugin.requiredPermissions).isEmpty()
        assertThat(plugin.handledCommands).isEmpty()
        assertThat(plugin.minAladdinVersion).isEqualTo("1.0.0")
        assertThat(plugin.tags).isEmpty()
        assertThat(plugin.iconResName).isNull()
        assertThat(plugin.loadError).isNull()
        assertThat(plugin.lastLoadedAtMs).isEqualTo(0L)
    }

    @Test fun `plugin state transitions are distinct`() {
        val states = PluginState.values()
        assertThat(states).hasLength(6)
        assertThat(states.map { it.name }.distinct()).hasSize(6)
    }

    @Test fun `active state plugin`() {
        val plugin = makePlugin(state = PluginState.ACTIVE)
        assertThat(plugin.state).isEqualTo(PluginState.ACTIVE)
    }

    @Test fun `error state with load error`() {
        val plugin = makePlugin(state = PluginState.ERROR).copy(loadError = "ClassNotFoundException")
        assertThat(plugin.state).isEqualTo(PluginState.ERROR)
        assertThat(plugin.loadError).isEqualTo("ClassNotFoundException")
    }

    @Test fun `plugin discovery test - multiple plugins`() {
        val plugins = (1..20).map { i ->
            makePlugin(id = "com.plugin.$i", state = PluginState.DISCOVERED)
        }
        assertThat(plugins).hasSize(20)
        assertThat(plugins.all { it.state == PluginState.DISCOVERED }).isTrue()
        assertThat(plugins.map { it.pluginId }.distinct()).hasSize(20)
    }

    @Test fun `plugin loading state transition`() {
        var plugin = makePlugin(state = PluginState.DISCOVERED)
        plugin = plugin.copy(state = PluginState.LOADING)
        assertThat(plugin.state).isEqualTo(PluginState.LOADING)
        plugin = plugin.copy(state = PluginState.ACTIVE, lastLoadedAtMs = System.currentTimeMillis())
        assertThat(plugin.state).isEqualTo(PluginState.ACTIVE)
        assertThat(plugin.lastLoadedAtMs).isGreaterThan(0L)
    }

    @Test fun `disabled state`() {
        val plugin = makePlugin(state = PluginState.DISABLED)
        assertThat(plugin.state).isEqualTo(PluginState.DISABLED)
    }

    @Test fun `unloading state`() {
        val plugin = makePlugin(state = PluginState.UNLOADING)
        assertThat(plugin.state).isEqualTo(PluginState.UNLOADING)
    }

    @Test fun `plugin with commands and permissions`() {
        val plugin = makePlugin().copy(
            handledCommands = listOf("search", "weather", "news"),
            tags = listOf("utility", "search")
        )
        assertThat(plugin.handledCommands).containsExactly("search", "weather", "news")
        assertThat(plugin.tags).containsExactly("utility", "search")
    }

    @Test fun `installedAt is set automatically`() {
        val plugin = makePlugin()
        assertThat(plugin.installedAtMs).isGreaterThan(0L)
    }
}
