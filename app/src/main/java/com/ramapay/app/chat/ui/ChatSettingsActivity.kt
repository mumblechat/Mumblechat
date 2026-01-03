package com.ramapay.app.chat.ui

import android.app.ProgressDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ramapay.app.R
import com.ramapay.app.chat.backup.ChatBackupManager
import com.ramapay.app.chat.backup.LocalBackupInfo
import com.ramapay.app.chat.backup.MergeMode
import com.ramapay.app.chat.backup.WalletMismatchException
import com.ramapay.app.chat.core.WalletBridge
import com.ramapay.app.chat.data.dao.ConversationDao
import com.ramapay.app.chat.data.dao.MessageDao
import com.ramapay.app.databinding.ActivityChatSettingsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Chat settings activity for MumbleChat.
 * 
 * Settings include:
 * - Notification preferences
 * - Privacy settings
 * - Relay node management
 * - Backup/restore chat history
 * - Clear chat data
 */
@AndroidEntryPoint
class ChatSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatSettingsBinding
    
    @Inject
    lateinit var chatBackupManager: ChatBackupManager
    
    @Inject
    lateinit var walletBridge: WalletBridge
    
    @Inject
    lateinit var conversationDao: ConversationDao
    
    @Inject
    lateinit var messageDao: MessageDao
    
    private var pendingBackupPassword: String? = null
    private var pendingImportPassword: String? = null
    
    // Activity result launchers
    private val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let { 
            pendingBackupPassword?.let { password ->
                performBackup(it, password)
            }
        }
        pendingBackupPassword = null
    }
    
    private val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            pendingImportPassword?.let { password ->
                performRestore(it, password)
            }
        }
        pendingImportPassword = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupSettings()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getString(R.string.chat_settings)
        }

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
    }

    private fun setupSettings() {
        // Profile
        binding.itemProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
        
        // Notifications
        binding.itemNotifications.setOnClickListener {
            Toast.makeText(this, "Notification settings coming soon", Toast.LENGTH_SHORT).show()
        }

        // Privacy
        binding.itemPrivacy.setOnClickListener {
            Toast.makeText(this, "Privacy settings coming soon", Toast.LENGTH_SHORT).show()
        }

        // Relay Node
        binding.itemRelayNode.setOnClickListener {
            startActivity(Intent(this, RelayNodeActivity::class.java))
        }

        // Backup
        binding.itemBackup.setOnClickListener {
            showBackupOptionsDialog()
        }

        // Clear Data
        binding.itemClearData.setOnClickListener {
            showClearDataConfirmation()
        }

        // About
        binding.itemAbout.setOnClickListener {
            showAboutDialog()
        }
    }
    
    // ============ Backup/Restore ============
    
    private fun showBackupOptionsDialog() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Check for available local backups
        lifecycleScope.launch {
            val localBackups = chatBackupManager.listBackupsForWallet(walletAddress)
            
            val options = mutableListOf(
                "💾 Quick Backup (Auto-save)",
                "📤 Export to File",
                "📥 Import from File"
            )
            
            if (localBackups.isNotEmpty()) {
                options.add("📋 Restore from Auto-Backups (${localBackups.size})")
            }
            
            AlertDialog.Builder(this@ChatSettingsActivity)
                .setTitle("Backup & Restore\n${walletAddress.take(10)}...")
                .setItems(options.toTypedArray()) { _, which ->
                    when (which) {
                        0 -> showQuickBackupDialog()
                        1 -> showExportBackupDialog()
                        2 -> showImportBackupDialog()
                        3 -> showLocalBackupsDialog(localBackups)
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }
    
    private fun showQuickBackupDialog() {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        
        val input = EditText(this).apply {
            hint = "Enter backup password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("Quick Backup")
            .setMessage("This will save an encrypted backup to app storage for wallet:\n\n${walletAddress.take(20)}...\n\nEnter a password to protect your backup:")
            .setView(input)
            .setPositiveButton("Backup") { _, _ ->
                val password = input.text.toString()
                if (password.length < 6) {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performQuickBackup(walletAddress, password)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun performQuickBackup(walletAddress: String, password: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Saving Backup")
            setMessage("Please wait...")
            setCancelable(false)
            isIndeterminate = true
            show()
        }
        
        lifecycleScope.launch {
            val result = chatBackupManager.autoSaveBackup(walletAddress, password)
            progressDialog.dismiss()
            
            result.fold(
                onSuccess = { stats ->
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("✅ Backup Saved")
                        .setMessage(
                            "Backup saved to app storage:\n\n" +
                            "• ${stats.conversationCount} conversations\n" +
                            "• ${stats.messageCount} messages\n\n" +
                            "You can restore this backup anytime from the 'Restore from Auto-Backups' option."
                        )
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = { error ->
                    Toast.makeText(this@ChatSettingsActivity, "Backup failed: ${error.message}", Toast.LENGTH_LONG).show()
                }
            )
        }
    }
    
    private fun showLocalBackupsDialog(backups: List<LocalBackupInfo>) {
        if (backups.isEmpty()) {
            Toast.makeText(this, "No local backups found for this wallet", Toast.LENGTH_SHORT).show()
            return
        }
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        val items = backups.map { backup ->
            val date = dateFormat.format(Date(backup.createdAt))
            "$date\n${formatSize(backup.fileSize)}"
        }.toTypedArray()
        
        var selectedIndex = 0
        
        AlertDialog.Builder(this)
            .setTitle("Select Backup to Restore")
            .setSingleChoiceItems(items, 0) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton("Restore") { _, _ ->
                showRestoreLocalBackupDialog(backups[selectedIndex])
            }
            .setNeutralButton("Delete") { _, _ ->
                confirmDeleteBackup(backups[selectedIndex])
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun showRestoreLocalBackupDialog(backup: LocalBackupInfo) {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        
        val input = EditText(this).apply {
            hint = "Enter backup password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        AlertDialog.Builder(this)
            .setTitle("Restore Backup")
            .setMessage("Backup from: ${dateFormat.format(Date(backup.createdAt))}\n\nEnter the password used when creating this backup:")
            .setView(input)
            .setPositiveButton("Restore") { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                performLocalRestore(backup, password, walletAddress)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun performLocalRestore(backup: LocalBackupInfo, password: String, walletAddress: String) {
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Restoring Backup")
            setMessage("Please wait...")
            setCancelable(false)
            isIndeterminate = true
            show()
        }
        
        lifecycleScope.launch {
            val result = chatBackupManager.importFromLocalBackup(
                backupInfo = backup,
                password = password,
                walletAddress = walletAddress,
                mergeMode = MergeMode.SKIP_EXISTING
            )
            
            progressDialog.dismiss()
            
            result.fold(
                onSuccess = { stats ->
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("✅ Restore Complete")
                        .setMessage(
                            "Imported:\n" +
                            "• ${stats.conversationsImported} conversations\n" +
                            "• ${stats.messagesImported} messages\n\n" +
                            "Skipped (already exists):\n" +
                            "• ${stats.conversationsSkipped} conversations\n" +
                            "• ${stats.messagesSkipped} messages"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = { error ->
                    val message = when (error) {
                        is WalletMismatchException -> "This backup was created for a different wallet."
                        else -> "Error: ${error.message}\n\nMake sure the password is correct."
                    }
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("Restore Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }
    
    private fun confirmDeleteBackup(backup: LocalBackupInfo) {
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        AlertDialog.Builder(this)
            .setTitle("Delete Backup?")
            .setMessage("Are you sure you want to delete the backup from ${dateFormat.format(Date(backup.createdAt))}?\n\nThis cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    if (chatBackupManager.deleteLocalBackup(backup)) {
                        Toast.makeText(this@ChatSettingsActivity, "Backup deleted", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@ChatSettingsActivity, "Failed to delete backup", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun showExportBackupDialog() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Password input dialog
        val input = EditText(this).apply {
            hint = "Enter backup password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("Export Backup")
            .setMessage("Enter a password to encrypt your backup. You'll need this password to restore your messages.")
            .setView(input)
            .setPositiveButton("Export") { _, _ ->
                val password = input.text.toString()
                if (password.length < 6) {
                    Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                pendingBackupPassword = password
                val filename = chatBackupManager.generateBackupFilename(walletAddress)
                createBackupLauncher.launch(filename)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun showImportBackupDialog() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Password input dialog
        val input = EditText(this).apply {
            hint = "Enter backup password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        
        AlertDialog.Builder(this)
            .setTitle("Import Backup")
            .setMessage("Enter the password used when creating the backup.")
            .setView(input)
            .setPositiveButton("Select File") { _, _ ->
                val password = input.text.toString()
                if (password.isEmpty()) {
                    Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                pendingImportPassword = password
                restoreBackupLauncher.launch(arrayOf("*/*"))
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun performBackup(uri: android.net.Uri, password: String) {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Exporting Backup")
            setMessage("Please wait...")
            setCancelable(false)
            isIndeterminate = true
            show()
        }
        
        lifecycleScope.launch {
            val result = chatBackupManager.exportBackup(walletAddress, uri, password)
            
            progressDialog.dismiss()
            
            result.fold(
                onSuccess = { stats ->
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("Backup Complete")
                        .setMessage(
                            "Successfully exported:\n\n" +
                            "• ${stats.conversationCount} conversations\n" +
                            "• ${stats.messageCount} messages\n\n" +
                            "Backup size: ${formatSize(stats.backupSize)}"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = { error ->
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("Backup Failed")
                        .setMessage("Error: ${error.message}")
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }
    
    private fun performRestore(uri: android.net.Uri, password: String) {
        val walletAddress = walletBridge.getCurrentWalletAddress() ?: return
        
        @Suppress("DEPRECATION")
        val progressDialog = ProgressDialog(this).apply {
            setTitle("Importing Backup")
            setMessage("Please wait...")
            setCancelable(false)
            isIndeterminate = true
            show()
        }
        
        lifecycleScope.launch {
            val result = chatBackupManager.importBackup(
                inputUri = uri,
                password = password,
                walletAddress = walletAddress,
                mergeMode = MergeMode.SKIP_EXISTING
            )
            
            progressDialog.dismiss()
            
            result.fold(
                onSuccess = { stats ->
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("Import Complete")
                        .setMessage(
                            "Successfully imported:\n\n" +
                            "• ${stats.conversationsImported} conversations\n" +
                            "• ${stats.messagesImported} messages\n\n" +
                            "Skipped (already exist):\n" +
                            "• ${stats.conversationsSkipped} conversations\n" +
                            "• ${stats.messagesSkipped} messages"
                        )
                        .setPositiveButton("OK", null)
                        .show()
                },
                onFailure = { error ->
                    val message = when (error) {
                        is WalletMismatchException -> "This backup was created for a different wallet. Please switch to the correct wallet and try again."
                        else -> "Error: ${error.message}\n\nMake sure the password is correct."
                    }
                    
                    AlertDialog.Builder(this@ChatSettingsActivity)
                        .setTitle("Import Failed")
                        .setMessage(message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            )
        }
    }
    
    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    private fun showClearDataConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Clear All Chat Data")
            .setMessage("This will permanently delete all your conversations and messages. This action cannot be undone.\n\nAre you sure you want to continue?")
            .setPositiveButton("Delete All") { _, _ ->
                clearAllChatData()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun clearAllChatData() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val conversations = conversationDao.getAllForWallet(walletAddress).first()
                
                // Delete all messages and conversations
                for (conv in conversations) {
                    messageDao.deleteAllForConversation(conv.id)
                    conversationDao.delete(conv.id)
                }
                
                Toast.makeText(this@ChatSettingsActivity, "All chat data cleared", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ChatSettingsActivity, "Error clearing data: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAboutDialog() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("About MumbleChat")
            .setMessage(
                "MumbleChat Protocol v1.0\n\n" +
                "Decentralized, end-to-end encrypted messaging on the Ramestta blockchain.\n\n" +
                "• Your messages are encrypted with your private keys\n" +
                "• No central servers store your messages\n" +
                "• Relay nodes earn MCT tokens for routing messages\n\n" +
                "Built with ❤️ by the RamaPay team"
            )
            .setPositiveButton("OK", null)
            .show()
    }
}
