package com.rw251.pleasecharge

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.rw251.pleasecharge.databinding.ActivityLogViewerBinding

/**
 * Activity for viewing and managing application logs.
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Without this you get a purple bar with the app name at the top
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
        binding.tvLogInfo.text = buildString {
            append("Log file: ")
            append((logPath ?: "not initialized"))
        }

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
}
