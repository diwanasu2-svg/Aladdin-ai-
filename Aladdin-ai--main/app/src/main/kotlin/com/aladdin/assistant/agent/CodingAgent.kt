package com.aladdin.assistant.agent

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Phase 5 – Coding Agent (Medium Priority)
 *
 * Responsibilities:
 *  - Generate code from descriptions
 *  - Analyse existing code
 *  - Identify bugs and suggest fixes
 *  - Optimise code for performance/readability
 *  - Suggest and generate unit tests
 *  - Understand and use documentation
 *  - Execute programming tasks end-to-end
 */
class CodingAgent(
    private val safetyAgent: SafetyAgent,
    private val memoryAgent: MemoryAgent
) {
    companion object {
        private const val TAG = "CodingAgent"
    }

    // ── Models ───────────────────────────────────────────────────────────────

    enum class Language {
        KOTLIN, JAVA, PYTHON, JAVASCRIPT, TYPESCRIPT, SWIFT, CPP, RUST, GO, UNKNOWN
    }

    data class CodeAnalysis(
        val language: Language,
        val linesOfCode: Int,
        val bugs: List<Bug>,
        val suggestions: List<String>,
        val complexity: ComplexityLevel,
        val testCoverage: Float          // estimated 0–1
    )

    data class Bug(
        val line: Int?,
        val severity: Severity,
        val description: String,
        val suggestion: String
    )

    enum class Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
    enum class ComplexityLevel { LOW, MEDIUM, HIGH, VERY_HIGH }

    data class GeneratedCode(
        val code: String,
        val language: Language,
        val explanation: String,
        val tests: String = "",
        val dependencies: List<String> = emptyList()
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Lifecycle ────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.CODING ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Coding Agent started")
    }

    // ── Code generation ──────────────────────────────────────────────────────

    fun generateCode(description: String, language: Language = Language.KOTLIN): GeneratedCode {
        // Safety check
        val safety = safetyAgent.validate(description)
        if (!safety.isSafe) {
            return GeneratedCode(
                code = "// Blocked by Safety Agent: ${safety.reason}",
                language = language,
                explanation = "Request blocked: ${safety.reason}"
            )
        }

        Log.d(TAG, "Generating $language code for: ${description.take(80)}")

        // Build code from description using heuristics + templates
        val code = buildCodeFromDescription(description, language)
        val tests = generateTests(code, language)

        val result = GeneratedCode(
            code = code,
            language = language,
            explanation = "Generated $language code for: $description",
            tests = tests,
            dependencies = inferDependencies(code, language)
        )

        // Save to memory
        memoryAgent.save(
            content = "Generated code for: $description\n```${language.name.lowercase()}\n$code\n```",
            type = MemoryAgent.MemoryType.EPISODIC,
            tags = listOf("code", "generated", language.name.lowercase()),
            importance = 0.6f
        )

        return result
    }

    // ── Code analysis ─────────────────────────────────────────────────────────

    fun analyseCode(code: String): CodeAnalysis {
        val language = detectLanguage(code)
        val lines = code.lines().filter { it.trim().isNotEmpty() }
        val bugs = detectBugs(code, language)
        val suggestions = generateSuggestions(code, language)
        val complexity = estimateComplexity(code)

        Log.d(TAG, "Analysed ${lines.size} LOC in $language — found ${bugs.size} bugs")

        return CodeAnalysis(
            language = language,
            linesOfCode = lines.size,
            bugs = bugs,
            suggestions = suggestions,
            complexity = complexity,
            testCoverage = estimateTestCoverage(code)
        )
    }

    // ── Bug detection ─────────────────────────────────────────────────────────

    private fun detectBugs(code: String, language: Language): List<Bug> {
        val bugs = mutableListOf<Bug>()
        val lines = code.lines()

        lines.forEachIndexed { idx, line ->
            val lineNum = idx + 1
            val trimmed = line.trim()

            // Null pointer risks
            if (trimmed.contains("!!") && language == Language.KOTLIN) {
                bugs.add(Bug(lineNum, Severity.HIGH,
                    "Force-unwrap (!!) can cause NullPointerException",
                    "Use safe call (?.) or elvis operator (?:) instead"))
            }

            // Empty catch blocks
            if (trimmed.startsWith("catch") && idx + 1 < lines.size &&
                lines[idx + 1].trim() in listOf("{}", "{ }")) {
                bugs.add(Bug(lineNum, Severity.MEDIUM,
                    "Empty catch block silently swallows exceptions",
                    "Log the exception or rethrow it"))
            }

            // Hardcoded credentials
            if (Regex("(password|passwd|pwd|secret|api_key)\\s*=\\s*\"[^\"]+\"", RegexOption.IGNORE_CASE)
                    .containsMatchIn(trimmed)) {
                bugs.add(Bug(lineNum, Severity.CRITICAL,
                    "Hardcoded credential detected",
                    "Move to BuildConfig, environment variables, or encrypted storage"))
            }

            // Thread-unsafe singletons
            if (trimmed.contains("companion object") &&
                trimmed.contains("var ")) {
                bugs.add(Bug(lineNum, Severity.MEDIUM,
                    "Mutable state in companion object may be thread-unsafe",
                    "Use @Volatile or a thread-safe data structure"))
            }

            // Memory leaks: Context stored as field
            if (Regex("val context\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(trimmed) &&
                !trimmed.contains("ApplicationContext", ignoreCase = true)) {
                bugs.add(Bug(lineNum, Severity.HIGH,
                    "Storing Activity Context as a field may cause memory leaks",
                    "Use applicationContext or WeakReference<Context>"))
            }

            // Magic numbers
            if (Regex("\\b[2-9][0-9]{2,}\\b").containsMatchIn(trimmed) &&
                !trimmed.startsWith("//") && !trimmed.startsWith("*")) {
                bugs.add(Bug(lineNum, Severity.LOW,
                    "Magic number detected",
                    "Extract to a named constant for readability"))
            }
        }

        return bugs
    }

    // ── Optimisation suggestions ─────────────────────────────────────────────

    private fun generateSuggestions(code: String, language: Language): List<String> {
        val suggestions = mutableListOf<String>()

        if (code.contains("Thread.sleep", ignoreCase = true))
            suggestions.add("Replace Thread.sleep with coroutine delay() for non-blocking behaviour")

        if (code.contains("for (") && code.contains(".add("))
            suggestions.add("Consider replacing manual loop with functional operations (map, filter, fold)")

        if (code.contains("Log.d") || code.contains("println"))
            suggestions.add("Remove or gate debug logging behind BuildConfig.DEBUG in production code")

        if (code.split("fun ").size > 15)
            suggestions.add("File has many functions — consider splitting into smaller, focused classes")

        if (!code.contains("@Test") && !code.contains("test"))
            suggestions.add("No tests detected — add unit tests to cover critical paths")

        if (code.contains("var ") && language == Language.KOTLIN)
            suggestions.add("Prefer val over var where mutation is not necessary (immutability)")

        if (code.lines().any { it.length > 120 })
            suggestions.add("Some lines exceed 120 characters — break for readability")

        return suggestions
    }

    // ── Test generation ──────────────────────────────────────────────────────

    fun generateTests(code: String, language: Language = Language.KOTLIN): String {
        val functions = extractFunctionNames(code)
        if (functions.isEmpty()) return "// No testable functions found"

        return buildString {
            appendLine("// Auto-generated unit tests by CodingAgent")
            if (language == Language.KOTLIN) {
                appendLine("import org.junit.Test")
                appendLine("import org.junit.Assert.*")
                appendLine("import io.mockk.*")
                appendLine()
                appendLine("class GeneratedTest {")
                functions.take(5).forEach { fn ->
                    appendLine()
                    appendLine("    @Test")
                    appendLine("    fun `test $fn returns expected result`() {")
                    appendLine("        // Arrange")
                    appendLine("        // Act")
                    appendLine("        // Assert")
                    appendLine("        // TODO(user): implement test for $fn — generated by CodingAgent")
                    appendLine("    }")
                    appendLine()
                    appendLine("    @Test")
                    appendLine("    fun `test $fn handles edge case`() {")
                    appendLine("        // [Generated stub — user fills this in]")
                    appendLine("        // TODO(user): implement edge case test for $fn — generated by CodingAgent")
                    appendLine("    }")
                }
                appendLine("}")
            } else {
                functions.take(5).forEach { fn ->
                    appendLine("def test_${fn}():")
                    appendLine("    # [Generated stub — user fills this in]")
                    appendLine("    # TODO(user): implement test for $fn — generated by CodingAgent")
                    appendLine("    assert True")
                    appendLine()
                }
            }
        }
    }

    // ── Documentation understanding ──────────────────────────────────────────

    fun explainCode(code: String): String {
        val language = detectLanguage(code)
        val functions = extractFunctionNames(code)
        val analysis = analyseCode(code)
        return buildString {
            appendLine("## Code Explanation")
            appendLine("**Language:** ${language.name}")
            appendLine("**Lines of Code:** ${analysis.linesOfCode}")
            appendLine("**Complexity:** ${analysis.complexity}")
            if (functions.isNotEmpty()) {
                appendLine("**Functions/Methods:** ${functions.joinToString(", ")}")
            }
            if (analysis.bugs.isNotEmpty()) {
                appendLine("**Issues Found:** ${analysis.bugs.size}")
                analysis.bugs.take(3).forEach { bug ->
                    appendLine("- [${bug.severity}] ${bug.description}")
                }
            }
            if (analysis.suggestions.isNotEmpty()) {
                appendLine("**Suggestions:**")
                analysis.suggestions.take(3).forEach { appendLine("- $it") }
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun detectLanguage(code: String): Language {
        return when {
            code.contains("fun ") && code.contains("val ") -> Language.KOTLIN
            code.contains("public class") || code.contains("void ") -> Language.JAVA
            code.contains("def ") && code.contains("import ") -> Language.PYTHON
            code.contains("const ") && code.contains("=>") -> Language.JAVASCRIPT
            code.contains(": string") || code.contains(": number") -> Language.TYPESCRIPT
            code.contains("func ") && code.contains("->") -> Language.SWIFT
            code.contains("#include") -> Language.CPP
            code.contains("fn ") && code.contains("let mut") -> Language.RUST
            code.contains("func ") && code.contains("package ") -> Language.GO
            else -> Language.UNKNOWN
        }
    }

    private fun estimateComplexity(code: String): ComplexityLevel {
        val keywords = listOf("if ", "else ", "when ", "for ", "while ", "catch ", "&&", "||")
        val count = keywords.sumOf { kw -> code.split(kw).size - 1 }
        return when {
            count < 5  -> ComplexityLevel.LOW
            count < 15 -> ComplexityLevel.MEDIUM
            count < 30 -> ComplexityLevel.HIGH
            else       -> ComplexityLevel.VERY_HIGH
        }
    }

    private fun estimateTestCoverage(code: String): Float {
        val testAnnotations = code.split("@Test").size - 1
        val functions = extractFunctionNames(code).size
        return if (functions == 0) 0f else minOf(1f, testAnnotations.toFloat() / functions)
    }

    private fun extractFunctionNames(code: String): List<String> {
        val pattern = Regex("fun\\s+(\\w+)\\s*\\(")
        return pattern.findAll(code).map { it.groupValues[1] }.toList()
    }

    private fun inferDependencies(code: String, language: Language): List<String> {
        val deps = mutableListOf<String>()
        if (language == Language.KOTLIN) {
            if (code.contains("Flow")) deps.add("org.jetbrains.kotlinx:kotlinx-coroutines-core")
            if (code.contains("Retrofit")) deps.add("com.squareup.retrofit2:retrofit")
            if (code.contains("Room")) deps.add("androidx.room:room-runtime")
            if (code.contains("Hilt")) deps.add("com.google.dagger:hilt-android")
        }
        return deps
    }

    private fun buildCodeFromDescription(description: String, language: Language): String {
        val descLower = description.lowercase()
        return when (language) {
            Language.KOTLIN -> buildKotlinCode(description, descLower)
            Language.PYTHON -> buildPythonCode(description, descLower)
            else            -> buildGenericCode(description, language)
        }
    }

    private fun buildKotlinCode(description: String, descLower: String): String = buildString {
        appendLine("// Generated by CodingAgent for: $description")
        when {
            descLower.contains("repository") || descLower.contains("data") -> {
                appendLine("class DataRepository(private val db: AppDatabase) {")
                appendLine()
                appendLine("    suspend fun getAll(): List<Item> = withContext(Dispatchers.IO) {")
                appendLine("        db.itemDao().getAll()")
                appendLine("    }")
                appendLine()
                appendLine("    suspend fun save(item: Item) = withContext(Dispatchers.IO) {")
                appendLine("        db.itemDao().insert(item)")
                appendLine("    }")
                appendLine()
                appendLine("    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {")
                appendLine("        db.itemDao().deleteById(id)")
                appendLine("    }")
                appendLine("}")
            }
            descLower.contains("viewmodel") -> {
                appendLine("@HiltViewModel")
                appendLine("class MainViewModel @Inject constructor(")
                appendLine("    private val repository: DataRepository")
                appendLine(") : ViewModel() {")
                appendLine()
                appendLine("    private val _state = MutableStateFlow<UiState>(UiState.Loading)")
                appendLine("    val state: StateFlow<UiState> = _state.asStateFlow()")
                appendLine()
                appendLine("    fun load() {")
                appendLine("        viewModelScope.launch {")
                appendLine("            _state.value = UiState.Loading")
                appendLine("            try {")
                appendLine("                val data = repository.getAll()")
                appendLine("                _state.value = UiState.Success(data)")
                appendLine("            } catch (e: Exception) {")
                appendLine("                _state.value = UiState.Error(e.message ?: \"Unknown error\")")
                appendLine("            }")
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
            }
            descLower.contains("api") || descLower.contains("network") -> {
                appendLine("class ApiService(private val client: OkHttpClient) {")
                appendLine()
                appendLine("    suspend fun get(url: String): String = withContext(Dispatchers.IO) {")
                appendLine("        val request = Request.Builder().url(url).build()")
                appendLine("        client.newCall(request).execute().use { response ->")
                appendLine("            if (!response.isSuccessful) throw IOException(\"HTTP \${response.code}\")")
                appendLine("            response.body?.string() ?: \"\"")
                appendLine("        }")
                appendLine("    }")
                appendLine("}")
            }
            else -> {
                appendLine("fun execute(input: String): Result<String> {")
                appendLine("    return try {")
                appendLine("        // $description")
                appendLine("        val result = processInput(input)")
                appendLine("        Result.success(result)")
                appendLine("    } catch (e: Exception) {")
                appendLine("        Result.failure(e)")
                appendLine("    }")
                appendLine("}")
                appendLine()
                appendLine("private fun processInput(input: String): String {")
                appendLine("    // [Generated stub — user fills this in]")
                appendLine("    // TODO(user): implement $description — generated by CodingAgent")
                appendLine("    return input")
                appendLine("}")
            }
        }
    }

    private fun buildPythonCode(description: String, descLower: String): String = buildString {
        appendLine("# Generated by CodingAgent for: $description")
        appendLine()
        appendLine("def execute(input_data):")
        appendLine("    \"\"\"$description\"\"\"")
        appendLine("    try:")
        appendLine("        result = process(input_data)")
        appendLine("        return {\"success\": True, \"result\": result}")
        appendLine("    except Exception as e:")
        appendLine("        return {\"success\": False, \"error\": str(e)}")
        appendLine()
        appendLine("def process(data):")
        appendLine("    # [Generated stub — user fills this in]")
        appendLine("    # TODO(user): implement $description — generated by CodingAgent")
        appendLine("    return data")
    }

    private fun buildGenericCode(description: String, language: Language): String =
        "// Generated by CodingAgent ($language)\n// Task: $description\n// [Generated stub — user fills this in]\n// TODO(user): implement — generated by CodingAgent"

    // ── Message handler ──────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type != AgentCommunication.MessageType.TASK_REQUEST) return
        val task = msg.payload["task"]?.toString() ?: return
        val code = msg.payload["code"]?.toString()
        val languageStr = msg.payload["language"]?.toString()
        val language = languageStr?.let { runCatching { Language.valueOf(it.uppercase()) }.getOrNull() }
            ?: Language.KOTLIN

        val result = if (code != null) {
            val analysis = analyseCode(code)
            mapOf(
                "type" to "analysis",
                "bugs" to analysis.bugs.size,
                "suggestions" to analysis.suggestions,
                "complexity" to analysis.complexity.name
            )
        } else {
            val generated = generateCode(task, language)
            mapOf(
                "type" to "generated",
                "code" to generated.code,
                "tests" to generated.tests,
                "explanation" to generated.explanation
            )
        }

        AgentCommunication.reportResult(
            sender = AgentCommunication.AgentType.CODING,
            receiver = msg.sender,
            taskId = msg.taskId,
            result = result
        )
    }
}
