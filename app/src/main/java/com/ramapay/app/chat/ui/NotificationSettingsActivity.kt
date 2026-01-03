package com.ramapay.app.chat.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ramapay.app.R
import com.ramapay.app.databinding.ActivityMumblechatNotificationSettingsBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Notification settings for MumbleChat
 */
@AndroidEntryPoint
class NotificationSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMumblechatNotificationSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMumblechatNotificationSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSettings()
        loadSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = "Notifications"
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSettings() {
        // Message notifications toggle
        binding.switchMessageNotifications.setOnCheckedChangeListener { _, isChecked ->
            savePreference("message_notifications", isChecked)
        }

        // Group notifications toggle
        binding.switchGroupNotifications.setOnCheckedChangeListener { _, isChecked ->
            savePreference("group_notifications", isChecked)
        }

        // Sound toggle
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            savePreference("notification_sound", isChecked)
        }

        // Vibration toggle
        binding.switchVibration.setOnCheckedChangeListener { _, isChecked ->
            savePreference("notification_vibration", isChecked)
        }

        // Show preview toggle
        binding.switchShowPreview.setOnCheckedChangeListener { _, isChecked ->
            savePreference("show_preview", isChecked)
        }

        // Relay node notifications toggle
        binding.switchRelayStatus.setOnCheckedChangeListener { _, isChecked ->
            savePreference("relay_notifications", isChecked)
        }

        // Earnings alerts toggle
        binding.switchEarningsAlerts.setOnCheckedChangeListener { _, isChecked ->
            savePreference("earnings_alerts", isChecked)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("mumblechat_prefs", MODE_PRIVATE)
        
        binding.switchMessageNotifications.isChecked = prefs.getBoolean("message_notifications", true)
        binding.switchGroupNotifications.isChecked = prefs.getBoolean("group_notifications", true)
        binding.switchSound.isChecked = prefs.getBoolean("notification_sound", true)
        binding.switchVibration.isChecked = prefs.getBoolean("notification_vibration", true)
        binding.switchShowPreview.isChecked = prefs.getBoolean("show_preview", true)
        binding.switchRelayStatus.isChecked = prefs.getBoolean("relay_notifications", true)
        binding.switchEarningsAlerts.isChecked = prefs.getBoolean("earnings_alerts", true)
    }

    private fun savePreference(key: String, value: Boolean) {
        val prefs = getSharedPreferences("mumblechat_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }
}
