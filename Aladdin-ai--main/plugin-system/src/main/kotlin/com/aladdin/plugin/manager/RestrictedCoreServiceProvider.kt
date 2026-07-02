package com.aladdin.plugin.manager

import com.aladdin.plugin.api.CoreServiceProvider
import com.aladdin.plugin.api.MemoryFact
import com.aladdin.plugin.api.PluginPermissionType
import com.aladdin.plugin.permissions.PluginPermissionManager

/**
 * Wraps [CoreServiceProvider] with permission enforcement.
 * Every call checks that the plugin has the required permission before delegating.
 */
class RestrictedCoreServiceProvider(
    private val delegate: CoreServiceProvider,
    private val permissions: PluginPermissionManager,
    private val pluginId: String
) : CoreServiceProvider {

    private fun require(perm: PluginPermissionType) {
        check(permissions.hasPermission(pluginId, perm)) {
            "Plugin '$pluginId' missing permission: ${perm.name}"
        }
    }

    override suspend fun queryLLM(prompt: String, systemPrompt: String?): String {
        require(PluginPermissionType.CORE_LLM)
        return delegate.queryLLM(prompt, systemPrompt)
    }

    override suspend fun storeMemory(key: String, value: String, importance: Float) {
        require(PluginPermissionType.CORE_MEMORY)
        delegate.storeMemory(key, value, importance)
    }

    override suspend fun queryMemory(query: String, limit: Int): List<MemoryFact> {
        require(PluginPermissionType.CORE_MEMORY)
        return delegate.queryMemory(query, limit)
    }

    override suspend fun speak(text: String) {
        require(PluginPermissionType.CORE_VOICE)
        delegate.speak(text)
    }

    override fun registerTool(
        toolName: String,
        description: String,
        handler: suspend (Map<String, Any?>) -> String
    ) {
        require(PluginPermissionType.CORE_TOOLS)
        delegate.registerTool(toolName, description, handler)
    }

    override fun unregisterTool(toolName: String) {
        require(PluginPermissionType.CORE_TOOLS)
        delegate.unregisterTool(toolName)
    }

    override fun sendNotification(title: String, body: String, channelId: String) {
        require(PluginPermissionType.NOTIFICATIONS)
        delegate.sendNotification(title, body, channelId)
    }
}
