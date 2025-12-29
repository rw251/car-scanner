package com.rw251.pleasecharge

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.rw251.pleasecharge.databinding.ActivityLogViewerBinding
import java.io.File

/**
 * Activity for viewing and managing application logs.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        setupUI()
        loadLogs()
    }

    private fun setupUI() {
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnClearLogs.setOnClickListener {
            showClearConfirmation()
        }
    }

    private fun loadLogs() {
        val logPath = AppLogger.getLogFilePath()
        binding.tvLogInfo.text = "Log file: ${logPath ?: "not initialized"}"

        val content = AppLogger.getLogFileContent()
        binding.tvLogs.text = content.ifEmpty {
            "No logs available"
        }

        // Scroll to bottom
        binding.logScroll.post {
            binding.logScroll.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun showClearConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear Logs")
            .setMessage("Are you sure you want to clear all logs? This cannot be undone.")
            .setPositiveButton("Clear") { _, _ ->
                AppLogger.clearLogs()
                loadLogs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareLogs() {
        try {
            val logPath = AppLogger.getLogFilePath() ?: run {
                AppLogger.w("Cannot share logs - log file path not available")
                return
            }

            val logFile = File(logPath)
            if (!logFile.exists() || logFile.length() == 0L) {
                AlertDialog.Builder(this)
                    .setTitle("No Logs")
                    .setMessage("Log file is empty or doesn't exist.")
                    .setPositiveButton("OK", null)
                    .show()
                return
            }

            val uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "PleaseCharge App Logs")
                putExtra(Intent.EXTRA_TEXT, "PleaseCharge application logs attached.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(shareIntent, "Share logs via"))
            AppLogger.i("Logs shared via system share sheet")
        } catch (e: Exception) {
            AppLogger.e("Failed to share logs", e)
            AlertDialog.Builder(this)
                .setTitle("Error")
                .setMessage("Failed to share logs: ${e.message}")
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
