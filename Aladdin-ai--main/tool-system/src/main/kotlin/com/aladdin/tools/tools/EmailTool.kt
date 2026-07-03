package com.aladdin.tools.tools
import javax.inject.Singleton
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.aladdin.tools.tools.BaseTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Properties
import javax.mail.*
import javax.mail.internet.*

/**
 * Phase 9 — Email Tool
 * Provides email operations: send, read, reply, forward, draft, search, label.
 * Uses JavaMail for SMTP/IMAP. Configure via environment variables or app settings.
 */
@Singleton
class EmailTool @Inject constructor(@ApplicationContext private val context: Context) : BaseTool {

    override val id = "email"

    override val name = "email"
    override val description = "Send, read, search, and manage emails via SMTP/IMAP"

    private val smtpHost get() = System.getenv("SMTP_HOST") ?: "smtp.gmail.com"
    private val smtpPort get() = (System.getenv("SMTP_PORT") ?: "587").toInt()
    private val smtpUser get() = System.getenv("SMTP_USER") ?: ""
    private val smtpPass get() = System.getenv("SMTP_PASS") ?: ""
    private val imapHost get() = System.getenv("IMAP_HOST") ?: "imap.gmail.com"

    // ── Send email ──────────────────────────────────────────────────────────
    suspend fun sendEmail(
        to: String, subject: String, body: String,
        cc: String = "", bcc: String = "", isHtml: Boolean = false
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            if (smtpUser.isEmpty()) return@withContext ToolResult.error(id, "SMTP_USER not configured")
            val props = Properties().apply {
                put("mail.smtp.auth", "true")
                put("mail.smtp.starttls.enable", "true")
                put("mail.smtp.host", smtpHost)
                put("mail.smtp.port", smtpPort.toString())
            }
            val session = Session.getInstance(props, object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(smtpUser, smtpPass)
            })
            val msg = MimeMessage(session).apply {
                setFrom(InternetAddress(smtpUser))
                setRecipients(Message.RecipientType.TO, InternetAddress.parse(to))
                if (cc.isNotBlank()) setRecipients(Message.RecipientType.CC, InternetAddress.parse(cc))
                if (bcc.isNotBlank()) setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bcc))
                this.subject = subject
                val contentType = if (isHtml) "text/html; charset=utf-8" else "text/plain; charset=utf-8"
                setContent(body, contentType)
            }
            Transport.send(msg)
            ToolResult.success(id, JSONObject().apply {
                put("sent", true); put("to", to); put("subject", subject)
            }.toString())
        } catch (e: Exception) {
            ToolResult.error(id, "Failed to send email: ${e.message}")
        }
    }

    // ── Read emails ─────────────────────────────────────────────────────────
    suspend fun readEmails(folder: String = "INBOX", count: Int = 10, unreadOnly: Boolean = false): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                if (smtpUser.isEmpty()) return@withContext ToolResult.error(id, "IMAP not configured")
                val props = Properties().apply { put("mail.store.protocol", "imaps") }
                val session = Session.getDefaultInstance(props)
                val store: Store = session.getStore("imaps")
                store.connect(imapHost, smtpUser, smtpPass)
                val inbox = store.getFolder(folder)
                inbox.open(javax.mail.Folder.READ_ONLY)
                val messages = inbox.messages.takeLast(count)
                val emails = messages.mapNotNull { msg ->
                    if (unreadOnly && msg.flags.contains(Flags.Flag.SEEN)) return@mapNotNull null
                    JSONObject().apply {
                        put("subject", msg.subject ?: "")
                        put("from", msg.from?.firstOrNull()?.toString() ?: "")
                        put("date", msg.sentDate?.toString() ?: "")
                        put("seen", msg.flags.contains(Flags.Flag.SEEN))
                        put("size", msg.size)
                    }
                }
                inbox.close(false); store.close()
                ToolResult.success(id, JSONObject().apply {
                    put("emails", emails.map { it.toString() })
                    put("count", emails.size)
                    put("folder", folder)
                }.toString())
            } catch (e: Exception) {
                ToolResult.error(id, "Failed to read emails: ${e.message}")
            }
        }

    // ── Search emails ────────────────────────────────────────────────────────
    suspend fun searchEmails(query: String, folder: String = "INBOX"): ToolResult =
        withContext(Dispatchers.IO) {
            try {
                val props = Properties().apply { put("mail.store.protocol", "imaps") }
                val session = Session.getDefaultInstance(props)
                val store = session.getStore("imaps")
                store.connect(imapHost, smtpUser, smtpPass)
                val f = store.getFolder(folder)
                f.open(javax.mail.Folder.READ_ONLY)
                val term = javax.mail.search.SubjectTerm(query)
                val results = f.search(term)
                val found = results.take(20).map { msg ->
                    JSONObject().apply {
                        put("subject", msg.subject ?: "")
                        put("from", msg.from?.firstOrNull()?.toString() ?: "")
                    }.toString()
                }
                f.close(false); store.close()
                ToolResult.success(id, JSONObject().apply {
                    put("results", found); put("count", found.size); put("query", query)
                }.toString())
            } catch (e: Exception) {
                ToolResult.error(id, "Search failed: ${e.message}")
            }
        }

    // ── Open compose intent (Android native fallback) ────────────────────────
    fun openComposeIntent(to: String, subject: String = "", body: String = "") {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Send email").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        return when (val action = (params["action"] ?: "read")) {
            "send" -> sendEmail(
                to = (params["to"] ?: return ToolResult.error(id, "Missing required parameter: " + "to")),
                subject = (params["subject"] ?: ""),
                body = (params["body"] ?: ""),
                cc = (params["cc"] ?: ""),
                isHtml = (params["html"]?.toBoolean() ?: false)
            )
            "read" -> readEmails(
                folder = (params["folder"] ?: "INBOX"),
                count = (params["count"]?.toIntOrNull() ?: 10),
                unreadOnly = (params["unread_only"]?.toBoolean() ?: false)
            )
            "search" -> searchEmails(
                query = (params["query"] ?: return ToolResult.error(id, "Missing required parameter: " + "query")),
                folder = (params["folder"] ?: "INBOX")
            )
            "compose" -> {
                openComposeIntent(
                    (params["to"] ?: return ToolResult.error(id, "Missing required parameter: " + "to")),
                    (params["subject"] ?: ""),
                    (params["body"] ?: "")
                )
                ToolResult.success(id, JSONObject().put("opened_compose", true).toString())
            }
            else -> ToolResult.error(id, "Unknown email action: $action")
        }
    }
}
