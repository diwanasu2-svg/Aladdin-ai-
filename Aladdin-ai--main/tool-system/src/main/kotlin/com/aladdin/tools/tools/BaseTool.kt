package com.aladdin.tools.tools

/** Shared interface every tool implements. */
interface BaseTool {
    val id: String
    val name: String
    val description: String
    suspend fun execute(params: Map<String, String>): ToolResult
}

/** Structured result from any tool execution. */
data class ToolResult(
    val toolId: String,
    val success: Boolean,
    val output: String = "",
    val error: String? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun success(toolId: String, output: String, durationMs: Long = 0) =
            ToolResult(toolId, true, output = output, durationMs = durationMs)

        fun error(toolId: String, error: String) =
            ToolResult(toolId, false, error = error)
    }

    override fun toString(): String = if (success) output else "Error: $error"
}
