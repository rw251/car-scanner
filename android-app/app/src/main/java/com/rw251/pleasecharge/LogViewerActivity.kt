package com.rw251.pleasecharge

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.rw251.pleasecharge.databinding.ActivityLogViewerBinding

/**
 * Activity for viewing and managing application logs.
 * 
 * Performance optimizations:
 * - Shows last 100 lines by default (fast loading even for 5MB+ logs)
 * - "Load More" button incrementally loads 100 more lines
 * - Efficiently reads from end of file without loading entire content
 * 
 * UI improvements:
 * - Edge-to-edge display with proper WindowInsets handling
 * - Respects safe areas (status bar, notches, rounded corners)
 * - Shows total line count and currently displayed lines
 * 
 * IMPORTANT: Always test XML layout changes!
 * Check for:
 * - Matching opening/closing tags
 * - Proper constraint relationships
 * - View ID references exist
 */
class LogViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogViewerBinding
    private var linesShown = 100
    private var totalLines = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityLogViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Without this you get a purple bar with the app name at the top
        supportActionBar?.hide()

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Apply window insets to header for safe area (status bar, notches, etc.)
        ViewCompat.setOnApplyWindowInsetsListener(binding.headerContainer) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                systemBars.top,
                view.paddingRight,
                view.paddingBottom
            )
            insets
        }

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
        
        binding.btnLoadMore.setOnClickListener {
            linesShown += 100
            loadLogs()
        }
    }

    private fun loadLogs() {
        val logPath = AppLogger.getLogFilePath()
        totalLines = AppLogger.getLogLineCount()
        
        binding.tvLogInfo.text = buildString {
            append("Log file: ")
            append((logPath ?: "not initialized"))
            append(" • Total lines: $totalLines")
            if (linesShown < totalLines) {
                append(" • Showing last $linesShown lines")
            }
        }

        val content = if (linesShown >= totalLines) {
            AppLogger.getLogFileContent()
        } else {
            AppLogger.getLastLogLines(linesShown)
        }
        
        binding.tvLogs.text = content.ifEmpty {
            "No logs available"
        }

        // Show/hide Load More button
        binding.btnLoadMore.visibility = if (linesShown < totalLines) View.VISIBLE else View.GONE

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
                linesShown = 100
                loadLogs()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
