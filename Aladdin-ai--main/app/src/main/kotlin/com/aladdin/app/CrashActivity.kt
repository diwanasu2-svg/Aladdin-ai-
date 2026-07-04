package com.aladdin.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * Diagnostic-only crash screen.
 *
 * Shown immediately whenever the app has an uncaught exception, instead of
 * silently dying back to the home screen. Displays the full stack trace on
 * screen and offers a one-tap Share button so it can be sent to us directly
 * (via WhatsApp / email / etc.) without needing a file manager or adb.
 *
 * This activity intentionally avoids Hilt / DI / any app subsystem so it can
 * never itself fail to start, even if the rest of the app is fundamentally
 * broken.
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val report = intent.getStringExtra(EXTRA_REPORT) ?: "No crash details available."

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Aladdin crashed"
            textSize = 20f
            setPadding(0, 0, 0, 24)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Copy or share the details below so it can be fixed."
            textSize = 14f
            setPadding(0, 0, 0, 24)
        }
        root.addView(subtitle)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }

        val shareButton = Button(this).apply {
            text = "Share report"
            setOnClickListener { shareReport(report) }
        }
        buttonRow.addView(shareButton)

        val closeButton = Button(this).apply {
            text = "Close"
            setOnClickListener { finishAffinity() }
        }
        buttonRow.addView(closeButton)

        root.addView(buttonRow)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        val reportView = TextView(this).apply {
            text = report
            textSize = 12f
            setTextIsSelectable(true)
        }
        scroll.addView(reportView)
        root.addView(scroll)

        setContentView(root)
    }

    private fun shareReport(report: String) {
        try {
            // Write to a cache file and share via FileProvider so it can be
            // attached as a file (in addition to the plain text) in apps
            // that support it, e.g. email.
            val file = File(cacheDir, "aladdin_crash_report.txt")
            file.writeText(report)
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Aladdin crash report")
                putExtra(Intent.EXTRA_TEXT, report)
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share crash report"))
        } catch (e: Exception) {
            // Fall back to plain text share without attachment.
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Aladdin crash report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(intent, "Share crash report"))
        }
    }

    companion object {
        const val EXTRA_REPORT = "extra_report"
    }
}
