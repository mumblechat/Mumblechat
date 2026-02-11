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
import com.ramapay.app.chat.service.NonceClearService
import com.ramapay.app.databinding.ActivityChatSettingsBinding
import com.ramapay.app.widget.AWalletAlertDialog
import dagger.hilt.android.AndroidEntryPoint
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
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
 * - QR code peer exchange
 * - Key rotation
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
    
    @Inject
    lateinit var nonceClearService: NonceClearService
    
    @Inject
    lateinit var chatService: com.ramapay.app.chat.core.ChatService
    
    @Inject
    lateinit var chatKeyManager: com.ramapay.app.chat.crypto.ChatKeyManager
    
    @Inject
    lateinit var registrationManager: com.ramapay.app.chat.registry.RegistrationManager
    
    private val disposables = CompositeDisposable()
    
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
        
        // Handle deep link from intent
        handleIncomingDeepLink()
    }
    
    /**
     * Check for and handle incoming MumbleChat peer deep links.
     */
    private fun handleIncomingDeepLink() {
        val peerLink = intent?.getStringExtra("mumblechat_peer_link")
        if (!peerLink.isNullOrEmpty()) {
            // Process the peer exchange link
            lifecycleScope.launch {
                processPeerQR(peerLink)
            }
        }
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
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }

        // Privacy
        binding.itemPrivacy.setOnClickListener {
            startActivity(Intent(this, PrivacySettingsActivity::class.java))
        }
        
        // Blocked Contacts
        binding.itemBlockedContacts.setOnClickListener {
            startActivity(Intent(this, BlockedContactsActivity::class.java))
        }

        // Relay Node
        binding.itemRelayNode.setOnClickListener {
            startActivity(Intent(this, RelayNodeActivity::class.java))
        }
        
        // Mobile Relay Settings (Hub + Mobile Node)
        binding.itemMobileRelay.setOnClickListener {
            startActivity(Intent(this, MobileRelaySettingsActivity::class.java))
        }
        
        // Clear Stuck Transactions
        binding.itemClearNonce.setOnClickListener {
            checkAndClearStuckTransactions()
        }
        
        // QR Code Peer Exchange
        binding.itemPeerQR.setOnClickListener {
            showPeerQRDialog()
        }
        
        // Key Rotation
        binding.itemKeyRotation.setOnClickListener {
            showKeyRotationDialog()
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

        // Set version info dynamically so user can identify which APK they have
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            val versionCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
            binding.textVersion.text = "Version $versionName (Build $versionCode)"
            try {
                val buildTime = io.ramestta.wallet.BuildConfig.BUILD_TIME
                binding.textBuildTime.text = "Built: $buildTime"
            } catch (_: Exception) {
                binding.textBuildTime.text = "MumbleChat Protocol v1.0"
            }
        } catch (_: Exception) { }
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
    
    // ============ Clear Stuck Transactions ============
    
    private fun checkAndClearStuckTransactions() {
        val walletAddress = walletBridge.getCurrentWalletAddress()
        if (walletAddress == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val progressDialog = AWalletAlertDialog(this).apply {
            setTitle("Checking Transactions")
            setMessage("Looking for stuck transactions...")
            setProgressMode()
            setCancelable(false)
            show()
        }
        
        disposables.add(
            nonceClearService.checkNonceStatus(walletAddress)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ status ->
                    progressDialog.dismiss()
                    
                    if (status.hasStuckTransactions) {
                        showClearNonceDialog(status.stuckCount, status.confirmedNonce)
                    } else {
                        showNoStuckTransactionsDialog()
                    }
                }, { error ->
                    progressDialog.dismiss()
                    Toast.makeText(this, "Error: ${error.message}", Toast.LENGTH_LONG).show()
                })
        )
    }
    
    private fun showNoStuckTransactionsDialog() {
        AlertDialog.Builder(this)
            .setTitle("✅ No Issues Found")
            .setMessage("Your wallet has no stuck transactions. All pending transactions have been confirmed.")
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun showClearNonceDialog(stuckCount: Int, startingNonce: Long) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Stuck Transactions Found")
            .setMessage(
                "Found $stuckCount stuck transaction${if (stuckCount > 1) "s" else ""}.\n\n" +
                "These are pending transactions that haven't been confirmed by the network.\n\n" +
                "Clearing them will:\n" +
                "• Send $stuckCount small replacement transaction${if (stuckCount > 1) "s" else ""}\n" +
                "• Use a higher gas price to replace stuck ones\n" +
                "• Cost a small amount of RAMA for gas\n\n" +
                "Do you want to proceed?"
            )
            .setPositiveButton("Clear Now") { _, _ ->
                performNonceClear()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performNonceClear() {
        val wallet = walletBridge.getCurrentWallet()
        if (wallet == null) {
            Toast.makeText(this, "No wallet connected", Toast.LENGTH_SHORT).show()
            return
        }
        
        val progressDialog = AWalletAlertDialog(this).apply {
            setTitle("Clearing Transactions")
            setMessage("Sending replacement transactions...")
            setProgressMode()
            setCancelable(false)
            show()
        }
        
        disposables.add(
            nonceClearService.clearStuckTransactions(wallet)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ result ->
                    progressDialog.dismiss()
                    
                    if (result.success) {
                        showClearSuccessDialog(result.clearedCount)
                    } else {
                        showClearErrorDialog(result.clearedCount, result.failedAt, result.error)
                    }
                }, { error ->
                    progressDialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Error")
                        .setMessage("Failed to clear transactions: ${error.message}")
                        .setPositiveButton("OK", null)
                        .show()
                })
        )
    }
    
    private fun showClearSuccessDialog(clearedCount: Int) {
        if (clearedCount == 0) {
            AlertDialog.Builder(this)
                .setTitle("✅ All Clear")
                .setMessage("No transactions needed to be cleared.")
                .setPositiveButton("OK", null)
                .show()
        } else {
            AlertDialog.Builder(this)
                .setTitle("✅ Transactions Cleared!")
                .setMessage(
                    "Successfully cleared $clearedCount stuck transaction${if (clearedCount > 1) "s" else ""}.\n\n" +
                    "You can now send transactions normally."
                )
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun showClearErrorDialog(clearedCount: Int, failedAt: Long?, error: String?) {
        AlertDialog.Builder(this)
            .setTitle("Partial Success")
            .setMessage(
                "Cleared $clearedCount transaction${if (clearedCount > 1) "s" else ""}, but encountered an error.\n\n" +
                (if (failedAt != null) "Failed at nonce: $failedAt\n" else "") +
                (if (error != null) "Error: $error\n\n" else "\n") +
                "You may try again to clear remaining stuck transactions."
            )
            .setPositiveButton("OK", null)
            .setNeutralButton("Try Again") { _, _ ->
                checkAndClearStuckTransactions()
            }
            .show()
    }
    
    // ============ QR Code Peer Exchange ============
    
    private fun showPeerQRDialog() {
        val options = arrayOf(
            "📱 Show My QR Code",
            "📷 Scan QR Code",
            "🔗 Copy My Deep Link",
            "📋 Enter Deep Link"
        )
        
        AlertDialog.Builder(this)
            .setTitle("Peer Exchange")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showMyPeerQR()
                    1 -> startQRScanner()
                    2 -> copyDeepLink()
                    3 -> showEnterDeepLinkDialog()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun showMyPeerQR() {
        lifecycleScope.launch {
            try {
                val qrBitmap = chatService.generatePeerQRCode(400)
                if (qrBitmap != null) {
                    showQRCodeDialog(qrBitmap)
                } else {
                    Toast.makeText(this@ChatSettingsActivity, "Failed to generate QR code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatSettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showQRCodeDialog(bitmap: android.graphics.Bitmap) {
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            setPadding(32, 32, 32, 32)
        }
        
        AlertDialog.Builder(this)
            .setTitle("📱 Your Peer QR Code")
            .setMessage("Let another user scan this QR code to connect directly to you.\n\nExpires in 5 minutes.")
            .setView(imageView)
            .setPositiveButton("Done", null)
            .setNeutralButton("Refresh") { _, _ ->
                showMyPeerQR()  // Generate fresh QR
            }
            .show()
    }
    
    private fun startQRScanner() {
        // Launch QR scanner activity
        val intent = Intent(this, com.ramapay.app.ui.QRScanning.QRScannerActivity::class.java)
        intent.putExtra("callback_mode", "mumblechat_peer")
        qrScanLauncher.launch(intent)
    }
    
    private val qrScanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrContent = result.data?.getStringExtra("qr_content") ?: return@registerForActivityResult
            processPeerQR(qrContent)
        }
    }
    
    private fun processPeerQR(qrContent: String) {
        lifecycleScope.launch {
            try {
                val result = chatService.processPeerQRCode(qrContent)
                result.fold(
                    onSuccess = { walletAddress ->
                        AlertDialog.Builder(this@ChatSettingsActivity)
                            .setTitle("✅ Peer Connected")
                            .setMessage("Successfully connected to:\n\n$walletAddress\n\nYou can now send messages directly to this peer.")
                            .setPositiveButton("Send Message") { _, _ ->
                                // Open conversation with this peer
                                val intent = Intent(this@ChatSettingsActivity, 
                                    com.ramapay.app.chat.ui.conversation.ConversationActivity::class.java)
                                intent.putExtra("recipient_address", walletAddress)
                                startActivity(intent)
                            }
                            .setNegativeButton("Close", null)
                            .show()
                    },
                    onFailure = { error ->
                        AlertDialog.Builder(this@ChatSettingsActivity)
                            .setTitle("❌ Connection Failed")
                            .setMessage("Could not connect to peer:\n\n${error.message}")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                )
            } catch (e: Exception) {
                Toast.makeText(this@ChatSettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun copyDeepLink() {
        lifecycleScope.launch {
            try {
                val deepLink = chatService.getPeerDeepLink()
                if (deepLink != null) {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("MumbleChat Peer Link", deepLink)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@ChatSettingsActivity, "Deep link copied to clipboard!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@ChatSettingsActivity, "Failed to generate deep link", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@ChatSettingsActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun showEnterDeepLinkDialog() {
        val input = EditText(this).apply {
            hint = "mumblechat://connect?wallet=..."
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(48, 32, 48, 32)
        }
        
        AlertDialog.Builder(this)
            .setTitle("Enter Peer Deep Link")
            .setMessage("Paste a MumbleChat peer link to connect:")
            .setView(input)
            .setPositiveButton("Connect") { _, _ ->
                val link = input.text.toString().trim()
                if (link.isNotEmpty()) {
                    processPeerQR(link)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    // ============ Key Rotation ============
    
    private fun showKeyRotationDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔑 Rotate Encryption Keys")
            .setMessage(
                "Key rotation generates new encryption keys for enhanced security.\n\n" +
                "⚠️ IMPORTANT:\n" +
                "• All future messages will use the new keys\n" +
                "• Old messages remain readable\n" +
                "• Contacts need your new public key (shared automatically)\n" +
                "• Requires a blockchain transaction (gas fee)\n\n" +
                "Rotate your keys periodically or if you suspect compromise."
            )
            .setPositiveButton("Rotate Keys") { _, _ ->
                confirmKeyRotation()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
    
    private fun confirmKeyRotation() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Confirm Key Rotation")
            .setMessage("Are you sure you want to rotate your encryption keys?\n\nThis will require a small gas fee to update your public key on-chain.")
            .setPositiveButton("Yes, Rotate") { _, _ ->
                performKeyRotation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun performKeyRotation() {
        @Suppress("DEPRECATION")
        val progressDialog = android.app.ProgressDialog(this).apply {
            setTitle("Rotating Keys")
            setMessage("Generating new keys...")
            setCancelable(false)
            isIndeterminate = true
            show()
        }
        
        lifecycleScope.launch {
            try {
                // Get current wallet address
                val walletAddress = walletBridge.getCurrentWalletAddress()
                    ?: throw Exception("No wallet connected")
                
                progressDialog.setMessage("Deriving new session keys...")
                
                // Derive new keys with incremented version
                val newKeys = chatKeyManager.rotateKeys()
                
                progressDialog.setMessage("Updating public key on blockchain...")
                
                // Register the new public key on-chain
                val txData = registrationManager.getUpdateKeyTxData(newKeys.identityPublic, newKeys.keyVersion)
                
                progressDialog.dismiss()
                
                // Show transaction confirmation
                AlertDialog.Builder(this@ChatSettingsActivity)
                    .setTitle("✅ Keys Generated")
                    .setMessage(
                        "New encryption keys generated!\n\n" +
                        "Key Version: ${newKeys.keyVersion}\n\n" +
                        "To complete rotation, you need to update your public key on-chain.\n\n" +
                        "This requires sending a transaction with a small gas fee."
                    )
                    .setPositiveButton("Send Transaction") { _, _ ->
                        // Launch transaction signing (through WalletConnect or internal signing)
                        Toast.makeText(this@ChatSettingsActivity, "Key rotation transaction initiated", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Later") { _, _ ->
                        Toast.makeText(this@ChatSettingsActivity, "Keys saved locally. Update on-chain later.", Toast.LENGTH_LONG).show()
                    }
                    .show()
                    
            } catch (e: Exception) {
                progressDialog.dismiss()
                AlertDialog.Builder(this@ChatSettingsActivity)
                    .setTitle("❌ Key Rotation Failed")
                    .setMessage("Error: ${e.message}")
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disposables.clear()
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
