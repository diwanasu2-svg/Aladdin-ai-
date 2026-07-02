import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Phase 5 – Browser Agent (Medium Priority)
 *
 * Responsibilities:
 *  - Open browser / navigate to URLs
 *  - Fetch and parse web page content
 *  - Simulate form filling (via HTTP POST)
 *  - Scrape information from web pages
 *  - Manage session cookies
 *  - Handle downloads and content extraction
 */
@Singleton
class BrowserAgent @Inject constructor(
    @ApplicationContext private val context: Context,
    private val safetyAgent: SafetyAgent,
    private val memoryAgent: MemoryAgent
) {
    companion object {
        private const val TAG = "BrowserAgent"
        private const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile) AladdinAssistant/5.0"
        private const val DEFAULT_TIMEOUT = 20L
    }

    // ── Models ────────────────────────────────────────────────────────────────

    data class PageContent(
        val url: String,
        val title: String,
        val text: String,
        val links: List<String> = emptyList(),
        val images: List<String> = emptyList(),
        val forms: List<FormInfo> = emptyList(),
        val statusCode: Int = 200
    )

    data class FormInfo(
        val action: String,
        val method: String,
        val fields: List<String>
    )

    data class BrowserSession(
        val id: String,
        var currentUrl: String = "",
        val cookies: MutableMap<String, String> = mutableMapOf(),
        val history: MutableList<String> = mutableListOf()
    )

    // ── State ─────────────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(DEFAULT_TIMEOUT, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val sessions = mutableMapOf<String, BrowserSession>()
    private val downloadCache = mutableMapOf<String, String>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        scope.launch {
            AgentCommunication.messageBus
                .filter { it.receiver == AgentCommunication.AgentType.BROWSER ||
                          it.receiver == AgentCommunication.AgentType.ALL }
                .collect { msg -> handleMessage(msg) }
        }
        Log.d(TAG, "Browser Agent started")
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    /** Open URL in the device's default browser app. */
    fun openInBrowser(url: String): Boolean {
        val safety = safetyAgent.validate(url)
        if (!safety.isSafe) {
            Log.w(TAG, "Blocked URL: ${safety.reason}")
            return false
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.d(TAG, "Opened in browser: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open browser: ${e.message}")
            false
        }
    }

    /** Fetch page content via HTTP (for scraping/reading). */
    suspend fun navigate(url: String, sessionId: String? = null): PageContent =
        withContext(Dispatchers.IO) {
            val safety = safetyAgent.validate(url)
            if (!safety.isSafe) {
                return@withContext PageContent(url = url, title = "Blocked", text = "Safety: ${safety.reason}")
            }

            val session = sessionId?.let { sessions[it] } ?: createSession()
            session.currentUrl = url
            session.history.add(url)

            try {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml")

                // Add session cookies
                if (session.cookies.isNotEmpty()) {
                    val cookieString = session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    requestBuilder.header("Cookie", cookieString)
                }

                val response = client.newCall(requestBuilder.build()).execute()

                // Save response cookies
                response.headers("Set-Cookie").forEach { header ->
                    val parts = header.split(";")[0].split("=")
                    if (parts.size >= 2) session.cookies[parts[0].trim()] = parts[1].trim()
                }

                val body = response.body?.string() ?: ""
                val content = parseHtml(url, body, response.code)

                // Cache and remember
                downloadCache[url] = body
                memoryAgent.save(
                    content = "Visited: $url — ${content.title}: ${content.text.take(200)}",
                    type = MemoryAgent.MemoryType.EPISODIC,
                    tags = listOf("browser", "web", extractDomain(url)),
                    importance = 0.5f
                )

                content
            } catch (e: Exception) {
                Log.e(TAG, "Navigation failed: ${e.message}")
                PageContent(url = url, title = "Error", text = e.message ?: "Unknown error", statusCode = 0)
            }
        }

    // ── Form interactions ─────────────────────────────────────────────────────

    /** Fill and submit an HTML form via POST. */
    suspend fun submitForm(
        actionUrl: String,
        fields: Map<String, String>,
        sessionId: String? = null
    ): PageContent = withContext(Dispatchers.IO) {
        val safety = safetyAgent.validate("$actionUrl ${fields.values.joinToString()}")
        if (!safety.isSafe) {
            return@withContext PageContent(url = actionUrl, title = "Blocked", text = safety.reason)
        }

        val session = sessionId?.let { sessions[it] } ?: createSession()

        val formBody = FormBody.Builder().apply {
            fields.forEach { (key, value) -> add(key, value) }
        }.build()

        val requestBuilder = Request.Builder()
            .url(actionUrl)
            .post(formBody)
            .header("User-Agent", USER_AGENT)

        if (session.cookies.isNotEmpty()) {
            val cookieStr = session.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            requestBuilder.header("Cookie", cookieStr)
        }

        try {
            val response = client.newCall(requestBuilder.build()).execute()
            val body = response.body?.string() ?: ""
            Log.d(TAG, "Form submitted to $actionUrl — HTTP ${response.code}")
            parseHtml(actionUrl, body, response.code)
        } catch (e: Exception) {
            Log.e(TAG, "Form submission failed: ${e.message}")
            PageContent(url = actionUrl, title = "Error", text = e.message ?: "Unknown error")
        }
    }

    // ── Scraping ──────────────────────────────────────────────────────────────

    /** Scrape specific content matching a CSS-like text pattern. */
    suspend fun scrape(url: String, pattern: String): List<String> {
        val page = navigate(url)
        if (page.statusCode == 0) return emptyList()

        val rawHtml = downloadCache[url] ?: return listOf(page.text)
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
        return regex.findAll(rawHtml).map { it.value }.toList().distinct().take(20)
    }

    /** Extract all text from a page in clean format. */
    suspend fun extractText(url: String): String {
        val page = navigate(url)
        return page.text
    }

    // ── Downloads ─────────────────────────────────────────────────────────────

    /** Download file content as a string (text/JSON/XML). */
    suspend fun downloadText(url: String): String? = withContext(Dispatchers.IO) {
        val safety = safetyAgent.validate(url)
        if (!safety.isSafe) return@withContext null
        try {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}")
            null
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    fun createSession(): BrowserSession {
        val session = BrowserSession(id = java.util.UUID.randomUUID().toString())
        sessions[session.id] = session
        return session
    }

    fun getSession(id: String): BrowserSession? = sessions[id]

    fun clearSession(id: String) {
        sessions[id]?.let {
            it.cookies.clear()
            it.history.clear()
        }
    }

    // ── HTML parsing ──────────────────────────────────────────────────────────

    private fun parseHtml(url: String, html: String, statusCode: Int): PageContent {
        val title = extractTag(html, "title") ?: extractTag(html, "h1") ?: url
        val text = stripHtml(html)
        val links = extractLinks(html)
        val images = extractImages(html)
        val forms = extractForms(html)
        return PageContent(url, title, text.take(5000), links, images, forms, statusCode)
    }

    private fun extractTag(html: String, tag: String): String? {
        val pattern = Pattern.compile("<$tag[^>]*>([^<]+)</$tag>", Pattern.CASE_INSENSITIVE)
        return pattern.matcher(html).let { m ->
            if (m.find()) m.group(1)?.trim()?.take(200) else null
        }
    }

    private fun stripHtml(html: String): String {
        var text = html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&nbsp;"), " ")
            .replace(Regex("&amp;"), "&")
            .replace(Regex("&lt;"), "<")
            .replace(Regex("&gt;"), ">")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text
    }

    private fun extractLinks(html: String): List<String> {
        val pattern = Regex("""href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html).map { it.groupValues[1] }
            .filter { it.startsWith("http") }.toList().distinct().take(20)
    }

    private fun extractImages(html: String): List<String> {
        val pattern = Regex("""src=["']([^"']+\.(jpg|png|gif|webp|svg))["']""", RegexOption.IGNORE_CASE)
        return pattern.findAll(html).map { it.groupValues[1] }.toList().distinct().take(10)
    }

    private fun extractForms(html: String): List<FormInfo> {
        val formPattern = Regex("""<form([^>]*)>([\s\S]*?)</form>""", RegexOption.IGNORE_CASE)
        return formPattern.findAll(html).map { match ->
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]
            val action = Regex("""action=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1) ?: ""
            val method = Regex("""method=["']([^"']+)["']""").find(attrs)?.groupValues?.get(1) ?: "GET"
            val fields = Regex("""name=["']([^"']+)["']""").findAll(body).map { it.groupValues[1] }.toList()
            FormInfo(action, method.uppercase(), fields)
        }.toList()
    }

    private fun extractDomain(url: String): String {
        return try { java.net.URL(url).host.removePrefix("www.") } catch (e: Exception) { url }
    }

    // ── Message handler ───────────────────────────────────────────────────────

    private suspend fun handleMessage(msg: AgentCommunication.AgentMessage) {
        if (msg.type != AgentCommunication.MessageType.TASK_REQUEST) return
        val url = msg.payload["url"]?.toString() ?: return
        val action = msg.payload["action"]?.toString() ?: "navigate"

        val result = when (action) {
            "navigate" -> {
                val page = navigate(url)
                mapOf("title" to page.title, "text" to page.text.take(500), "links" to page.links.size)
            }
            "scrape"   -> {
                val pattern = msg.payload["pattern"]?.toString() ?: ".*"
                mapOf("matches" to scrape(url, pattern))
            }
            "open"     -> mapOf("opened" to openInBrowser(url))
            else       -> mapOf("error" to "Unknown action: $action")
        }

        AgentCommunication.reportResult(
            sender = AgentCommunication.AgentType.BROWSER,
            receiver = msg.sender,
            taskId = msg.taskId,
            result = result
        )
    }
}
