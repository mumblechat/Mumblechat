package com.ramapay.app.chat.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.ramapay.app.R
import com.ramapay.app.databinding.ActivityPrivacySettingsBinding
import dagger.hilt.android.AndroidEntryPoint

/**
 * Privacy settings for MumbleChat
 */
@AndroidEntryPoint
class PrivacySettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPrivacySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacySettingsBinding.inflate(layoutInflater)
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
            title = "Privacy"
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSettings() {
        // Read receipts toggle
        binding.switchReadReceipts.setOnCheckedChangeListener { _, isChecked ->
            savePreference("read_receipts", isChecked)
        }

        // Online status toggle
        binding.switchOnlineStatus.setOnCheckedChangeListener { _, isChecked ->
            savePreference("online_status", isChecked)
        }

        // Typing indicator toggle
        binding.switchTypingIndicator.setOnCheckedChangeListener { _, isChecked ->
            savePreference("typing_indicator", isChecked)
        }

        // Last seen toggle
        binding.switchLastSeen.setOnCheckedChangeListener { _, isChecked ->
            savePreference("last_seen", isChecked)
        }

        // Screen security toggle
        binding.switchScreenSecurity.setOnCheckedChangeListener { _, isChecked ->
            savePreference("screen_security", isChecked)
        }

        // Incognito keyboard toggle
        binding.switchIncognitoKeyboard.setOnCheckedChangeListener { _, isChecked ->
            savePreference("incognito_keyboard", isChecked)
        }
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences("mumblechat_prefs", MODE_PRIVATE)
        
        binding.switchReadReceipts.isChecked = prefs.getBoolean("read_receipts", true)
        binding.switchOnlineStatus.isChecked = prefs.getBoolean("online_status", true)
        binding.switchTypingIndicator.isChecked = prefs.getBoolean("typing_indicator", true)
        binding.switchLastSeen.isChecked = prefs.getBoolean("last_seen", true)
        binding.switchScreenSecurity.isChecked = prefs.getBoolean("screen_security", false)
        binding.switchIncognitoKeyboard.isChecked = prefs.getBoolean("incognito_keyboard", false)
    }

    private fun savePreference(key: String, value: Boolean) {
        val prefs = getSharedPreferences("mumblechat_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean(key, value).apply()
    }
}
