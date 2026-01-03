# MumbleChat Protocol - Backup System

## Part 5 of 8

---

## 1. BACKUP SYSTEM OVERVIEW

### 1.1 Core Principles

```
┌─────────────────────────────────────────────────────────────┐
│                   BACKUP SYSTEM GOALS                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. WALLET = IDENTITY                                       │
│     └── Same wallet always restores same chat identity      │
│                                                              │
│  2. ENCRYPTED BACKUP                                        │
│     └── Only wallet owner can decrypt backup                │
│                                                              │
│  3. AUTO-DISCOVERY                                          │
│     └── System finds backup files automatically             │
│                                                              │
│  4. CROSS-DEVICE                                            │
│     └── Backup works across all devices                     │
│                                                              │
│  5. NO CENTRAL STORAGE                                      │
│     └── Backup stored locally or user's cloud               │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 What's Backed Up

| Data | Backed Up | Notes |
|------|-----------|-------|
| Chat messages | ✅ Yes | All conversations |
| Group memberships | ✅ Yes | Groups you're in |
| Group keys | ✅ Yes | To decrypt group messages |
| Contacts/nicknames | ✅ Yes | Custom names |
| Settings | ✅ Yes | Preferences |
| Chat keys | ❌ No | Re-derived from wallet |
| Relay data | ❌ No | Not user's messages |

---

## 2. BACKUP ENCRYPTION

### 2.1 Key Derivation for Backup

```
┌─────────────────────────────────────────────────────────────┐
│              BACKUP KEY DERIVATION                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Same wallet signature approach as chat keys:               │
│                                                              │
│  Wallet signs: "MUMBLECHAT_BACKUP_KEY_V1"                   │
│           │                                                  │
│           ▼                                                  │
│  signature = 0x1234...abcd                                  │
│           │                                                  │
│           ▼                                                  │
│  seed = keccak256(signature)                                │
│           │                                                  │
│           ▼                                                  │
│  backupKey = HKDF(seed, salt="mumblechat", info="backup")   │
│           │                                                  │
│           ▼                                                  │
│  AES-256-GCM encryption key (32 bytes)                      │
│                                                              │
│  RESULT:                                                    │
│  ───────                                                    │
│  - Same wallet → Same backup key → Can decrypt backup       │
│  - Different wallet → Different key → Cannot decrypt        │
│  - No password needed (wallet IS the password)              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Backup File Structure

```json
{
  "version": 1,
  "format": "mumblechat-backup",
  "createdAt": 1703836800000,
  "walletAddressHash": "0xabcd1234...",  // First 8 bytes of keccak256(address)
  
  "metadata": {
    "encryptedWith": "AES-256-GCM",
    "keyDerivation": "HKDF-SHA256",
    "nonce": "base64_encoded_12_bytes",
    "authTag": "base64_encoded_16_bytes"
  },
  
  "encryptedPayload": "base64_encoded_encrypted_data"
}
```

### 2.3 Encrypted Payload Contents

```json
// After decryption:
{
  "conversations": [
    {
      "id": "conversation_id",
      "type": "dm",
      "participants": ["0x...", "0x..."],
      "createdAt": 1703836800000,
      "messages": [
        {
          "id": "msg_id",
          "sender": "0x...",
          "content": "decrypted message text",
          "timestamp": 1703836800000,
          "type": "text"
        }
      ]
    }
  ],
  
  "groups": [
    {
      "id": "group_id",
      "name": "Group Name",
      "members": ["0x...", "0x..."],
      "myRole": "member",
      "encryptedGroupKey": "base64_encoded",
      "keyVersion": 3,
      "createdAt": 1703836800000,
      "messages": [...]
    }
  ],
  
  "contacts": [
    {
      "address": "0x...",
      "nickname": "Alice",
      "addedAt": 1703836800000
    }
  ],
  
  "settings": {
    "autoDeleteDays": null,
    "readReceipts": true,
    "typingIndicators": true
  }
}
```

---

## 3. BACKUP OPERATIONS

### 3.1 Create Backup

```kotlin
// BackupManager.kt
class BackupManager @Inject constructor(
    private val chatKeyManager: ChatKeyManager,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val settingsManager: SettingsManager,
    private val context: Context
) {
    companion object {
        const val BACKUP_FILE_NAME = "mumblechat_backup.mcb"
        const val BACKUP_VERSION = 1
    }
    
    data class BackupResult(
        val success: Boolean,
        val filePath: String? = null,
        val error: String? = null,
        val messageCount: Int = 0,
        val groupCount: Int = 0
    )
    
    suspend fun createBackup(wallet: Wallet): BackupResult {
        return try {
            // 1. Derive backup key from wallet
            val chatKeys = chatKeyManager.deriveChatKeys(wallet)
            val backupKey = chatKeys.backupKey
            
            // 2. Gather all data to backup
            val backupData = gatherBackupData(wallet.address)
            
            // 3. Serialize to JSON
            val jsonPayload = Gson().toJson(backupData)
            val payloadBytes = jsonPayload.toByteArray(Charsets.UTF_8)
            
            // 4. Encrypt with backup key
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(backupKey, "AES")
            val gcmSpec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val encryptedBytes = cipher.doFinal(payloadBytes)
            
            // Separate ciphertext and auth tag
            val ciphertext = encryptedBytes.copyOfRange(0, encryptedBytes.size - 16)
            val authTag = encryptedBytes.copyOfRange(encryptedBytes.size - 16, encryptedBytes.size)
            
            // 5. Create backup file structure
            val backupFile = BackupFile(
                version = BACKUP_VERSION,
                format = "mumblechat-backup",
                createdAt = System.currentTimeMillis(),
                walletAddressHash = Hash.keccak256(wallet.address.toByteArray()).take(8).toHex(),
                metadata = BackupMetadata(
                    encryptedWith = "AES-256-GCM",
                    keyDerivation = "HKDF-SHA256",
                    nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
                    authTag = Base64.encodeToString(authTag, Base64.NO_WRAP)
                ),
                encryptedPayload = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            )
            
            // 6. Save to file
            val filePath = saveBackupFile(backupFile)
            
            BackupResult(
                success = true,
                filePath = filePath,
                messageCount = backupData.totalMessages,
                groupCount = backupData.groups.size
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Backup creation failed")
            BackupResult(success = false, error = e.message)
        }
    }
    
    private suspend fun gatherBackupData(walletAddress: String): BackupPayload {
        val conversations = conversationDao.getAllForWallet(walletAddress)
        val groups = groupDao.getAllForWallet(walletAddress)
        val contacts = contactDao.getAllForWallet(walletAddress)
        val settings = settingsManager.getAllSettings()
        
        val conversationsWithMessages = conversations.map { conv ->
            val messages = messageDao.getMessagesForConversation(conv.id)
            ConversationBackup(
                id = conv.id,
                type = conv.type,
                participants = conv.participants,
                createdAt = conv.createdAt,
                messages = messages.map { it.toBackupMessage() }
            )
        }
        
        val groupsWithMessages = groups.map { group ->
            val messages = messageDao.getMessagesForGroup(group.id)
            GroupBackup(
                id = group.id,
                name = group.name,
                members = groupDao.getMembersForGroup(group.id),
                myRole = group.myRole,
                encryptedGroupKey = group.encryptedGroupKey,
                keyVersion = group.currentKeyVersion,
                createdAt = group.createdAt,
                messages = messages.map { it.toBackupMessage() }
            )
        }
        
        return BackupPayload(
            conversations = conversationsWithMessages,
            groups = groupsWithMessages,
            contacts = contacts.map { it.toBackupContact() },
            settings = settings
        )
    }
    
    private fun saveBackupFile(backupFile: BackupFile): String {
        val backupDir = File(context.getExternalFilesDir(null), "backups")
        backupDir.mkdirs()
        
        val file = File(backupDir, BACKUP_FILE_NAME)
        file.writeText(Gson().toJson(backupFile))
        
        return file.absolutePath
    }
}
```

### 3.2 Restore Backup

```kotlin
// RestoreManager.kt
class RestoreManager @Inject constructor(
    private val chatKeyManager: ChatKeyManager,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val groupDao: GroupDao,
    private val contactDao: ContactDao,
    private val settingsManager: SettingsManager,
    private val p2pManager: P2PManager,
    private val context: Context
) {
    data class RestoreResult(
        val success: Boolean,
        val error: String? = null,
        val messagesRestored: Int = 0,
        val groupsRestored: Int = 0,
        val contactsRestored: Int = 0
    )
    
    suspend fun restoreFromBackup(
        wallet: Wallet,
        backupFile: File
    ): RestoreResult {
        return try {
            // 1. Read backup file
            val backupJson = backupFile.readText()
            val backup = Gson().fromJson(backupJson, BackupFile::class.java)
            
            // 2. Verify this backup belongs to this wallet
            val expectedHash = Hash.keccak256(wallet.address.toByteArray()).take(8).toHex()
            if (backup.walletAddressHash != expectedHash) {
                return RestoreResult(
                    success = false, 
                    error = "Backup was created for a different wallet"
                )
            }
            
            // 3. Derive backup key
            val chatKeys = chatKeyManager.deriveChatKeys(wallet)
            val backupKey = chatKeys.backupKey
            
            // 4. Decrypt payload
            val nonce = Base64.decode(backup.metadata.nonce, Base64.NO_WRAP)
            val authTag = Base64.decode(backup.metadata.authTag, Base64.NO_WRAP)
            val ciphertext = Base64.decode(backup.encryptedPayload, Base64.NO_WRAP)
            
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(backupKey, "AES")
            val gcmSpec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            
            val encryptedWithTag = ciphertext + authTag
            val decryptedBytes = cipher.doFinal(encryptedWithTag)
            
            val payloadJson = String(decryptedBytes, Charsets.UTF_8)
            val payload = Gson().fromJson(payloadJson, BackupPayload::class.java)
            
            // 5. Restore data to database
            val stats = restoreData(wallet.address, payload)
            
            // 6. Sync with relay nodes for messages since backup
            syncNewMessages(wallet)
            
            RestoreResult(
                success = true,
                messagesRestored = stats.messages,
                groupsRestored = stats.groups,
                contactsRestored = stats.contacts
            )
            
        } catch (e: Exception) {
            Timber.e(e, "Restore failed")
            RestoreResult(success = false, error = e.message)
        }
    }
    
    private suspend fun restoreData(walletAddress: String, payload: BackupPayload): RestoreStats {
        var messagesRestored = 0
        var groupsRestored = 0
        var contactsRestored = 0
        
        // Restore conversations and messages
        for (conv in payload.conversations) {
            conversationDao.insert(conv.toConversation(walletAddress))
            for (msg in conv.messages) {
                messageDao.insert(msg.toMessage(conv.id))
                messagesRestored++
            }
        }
        
        // Restore groups
        for (group in payload.groups) {
            groupDao.insert(group.toGroup(walletAddress))
            for (member in group.members) {
                groupDao.insertMember(member.toGroupMember(group.id))
            }
            for (msg in group.messages) {
                messageDao.insert(msg.toMessage(group.id))
                messagesRestored++
            }
            groupsRestored++
        }
        
        // Restore contacts
        for (contact in payload.contacts) {
            contactDao.insert(contact.toContact(walletAddress))
            contactsRestored++
        }
        
        // Restore settings
        settingsManager.restoreSettings(payload.settings)
        
        return RestoreStats(messagesRestored, groupsRestored, contactsRestored)
    }
    
    private suspend fun syncNewMessages(wallet: Wallet) {
        // Connect to P2P network
        p2pManager.connect()
        
        // Find relay nodes
        val relays = p2pManager.findRelays()
        
        // Query for pending messages
        for (relay in relays) {
            val pendingMessages = p2pManager.queryPendingMessages(relay, wallet.address)
            for (message in pendingMessages) {
                // Decrypt and store
                val decrypted = decryptMessage(message, wallet)
                if (decrypted != null) {
                    messageDao.insertIfNotExists(decrypted)
                    
                    // Acknowledge delivery
                    p2pManager.acknowledgeDelivery(relay, message.id)
                }
            }
        }
    }
}
```

---

## 4. AUTO-DISCOVERY

### 4.1 Backup Discovery Locations

```
┌─────────────────────────────────────────────────────────────┐
│              AUTO-DISCOVERY LOCATIONS                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  PRIORITY ORDER:                                            │
│                                                              │
│  1. APP INTERNAL STORAGE                                    │
│     /data/data/com.ramapay.app/files/backups/              │
│     └── mumblechat_backup.mcb                              │
│                                                              │
│  2. APP EXTERNAL STORAGE                                    │
│     /Android/data/com.ramapay.app/files/backups/           │
│     └── mumblechat_backup.mcb                              │
│                                                              │
│  3. DOCUMENTS FOLDER                                        │
│     /Documents/MumbleChat/                                  │
│     └── mumblechat_backup.mcb                              │
│                                                              │
│  4. DOWNLOADS FOLDER                                        │
│     /Download/                                              │
│     └── mumblechat_backup.mcb                              │
│                                                              │
│  5. SD CARD (if available)                                  │
│     /sdcard/MumbleChat/                                     │
│     └── mumblechat_backup.mcb                              │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Auto-Discovery Implementation

```kotlin
// BackupDiscovery.kt
class BackupDiscovery @Inject constructor(
    private val context: Context
) {
    data class DiscoveredBackup(
        val file: File,
        val createdAt: Long,
        val walletAddressHash: String,
        val location: String
    )
    
    fun discoverBackups(): List<DiscoveredBackup> {
        val discovered = mutableListOf<DiscoveredBackup>()
        
        // Check all possible locations
        val locations = getSearchLocations()
        
        for (location in locations) {
            val backupFile = File(location, BackupManager.BACKUP_FILE_NAME)
            if (backupFile.exists() && backupFile.canRead()) {
                try {
                    val backup = parseBackupHeader(backupFile)
                    discovered.add(
                        DiscoveredBackup(
                            file = backupFile,
                            createdAt = backup.createdAt,
                            walletAddressHash = backup.walletAddressHash,
                            location = location.absolutePath
                        )
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Invalid backup file at ${backupFile.absolutePath}")
                }
            }
        }
        
        // Sort by creation date (newest first)
        return discovered.sortedByDescending { it.createdAt }
    }
    
    fun findBackupForWallet(walletAddress: String): DiscoveredBackup? {
        val expectedHash = Hash.keccak256(walletAddress.toByteArray()).take(8).toHex()
        return discoverBackups().find { it.walletAddressHash == expectedHash }
    }
    
    private fun getSearchLocations(): List<File> {
        val locations = mutableListOf<File>()
        
        // 1. Internal app storage
        context.filesDir?.let { 
            locations.add(File(it, "backups")) 
        }
        
        // 2. External app storage
        context.getExternalFilesDir(null)?.let { 
            locations.add(File(it, "backups")) 
        }
        
        // 3. Documents folder
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)?.let {
            locations.add(File(it, "MumbleChat"))
        }
        
        // 4. Downloads folder
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.let {
            locations.add(it)
        }
        
        // 5. SD Card
        val externalDirs = ContextCompat.getExternalFilesDirs(context, null)
        for (dir in externalDirs) {
            if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                locations.add(File(dir.parentFile?.parentFile?.parentFile, "MumbleChat"))
            }
        }
        
        return locations
    }
    
    private fun parseBackupHeader(file: File): BackupFile {
        val json = file.readText()
        return Gson().fromJson(json, BackupFile::class.java)
    }
}
```

### 4.3 First Launch Flow

```
┌─────────────────────────────────────────────────────────────┐
│              FIRST LAUNCH BACKUP DETECTION                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  User opens app with new wallet (import or create)          │
│           │                                                  │
│           ▼                                                  │
│  ┌─────────────────────────────────────┐                    │
│  │  Is this wallet registered in       │                    │
│  │  MumbleChatRegistry?                │                    │
│  └─────────────────┬───────────────────┘                    │
│            YES     │     NO                                 │
│           ┌────────┴────────┐                               │
│           ▼                 ▼                               │
│  Returning user        New user                            │
│           │                 │                               │
│           ▼                 │                               │
│  ┌─────────────────┐       │                               │
│  │ Search for      │       │                               │
│  │ backup files    │       │                               │
│  └────────┬────────┘       │                               │
│    FOUND  │  NOT FOUND     │                               │
│   ┌───────┴───────┐        │                               │
│   ▼               ▼        ▼                               │
│ ┌─────────┐  ┌─────────┐ ┌─────────┐                       │
│ │ Prompt  │  │ Manual  │ │Register │                       │
│ │ Restore │  │ Upload? │ │ & Start │                       │
│ └────┬────┘  └────┬────┘ └────┬────┘                       │
│      │            │           │                             │
│      ▼            ▼           ▼                             │
│  Restore     Sync from    Fresh start                      │
│  backup      relays                                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.4 UI Implementation

```kotlin
// BackupDiscoveryDialog.kt
class BackupDiscoveryDialog : DialogFragment() {
    
    private lateinit var binding: DialogBackupDiscoveryBinding
    private val viewModel: ChatViewModel by activityViewModels()
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogBackupDiscoveryBinding.inflate(layoutInflater)
        
        val backup = arguments?.getParcelable<DiscoveredBackup>("backup")
        
        binding.textBackupInfo.text = buildString {
            append("Backup found!\n\n")
            append("Created: ${formatDate(backup?.createdAt ?: 0)}\n")
            append("Location: ${backup?.location}\n")
        }
        
        binding.buttonRestore.setOnClickListener {
            backup?.let { viewModel.restoreBackup(it.file) }
            dismiss()
        }
        
        binding.buttonSkip.setOnClickListener {
            viewModel.skipBackupRestore()
            dismiss()
        }
        
        binding.buttonUploadOther.setOnClickListener {
            openFilePicker()
        }
        
        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .create()
    }
    
    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_FILE_PICK)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_FILE_PICK && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                viewModel.restoreBackupFromUri(uri)
                dismiss()
            }
        }
    }
}
```

---

## 5. EXPORT & CLOUD BACKUP

### 5.1 Export to Cloud

```kotlin
// BackupExporter.kt
class BackupExporter @Inject constructor(
    private val context: Context,
    private val backupManager: BackupManager
) {
    sealed class ExportDestination {
        object GoogleDrive : ExportDestination()
        object LocalFile : ExportDestination()
        data class CustomPath(val path: String) : ExportDestination()
    }
    
    suspend fun exportBackup(
        wallet: Wallet,
        destination: ExportDestination
    ): Result<String> {
        // First create the backup
        val backupResult = backupManager.createBackup(wallet)
        
        if (!backupResult.success) {
            return Result.failure(Exception(backupResult.error))
        }
        
        val backupFile = File(backupResult.filePath!!)
        
        return when (destination) {
            is ExportDestination.GoogleDrive -> {
                exportToGoogleDrive(backupFile)
            }
            is ExportDestination.LocalFile -> {
                exportToDownloads(backupFile)
            }
            is ExportDestination.CustomPath -> {
                exportToPath(backupFile, destination.path)
            }
        }
    }
    
    private suspend fun exportToGoogleDrive(file: File): Result<String> {
        // Use Google Drive API
        return try {
            val driveService = getDriveService()
            
            val fileMetadata = com.google.api.services.drive.model.File().apply {
                name = file.name
                mimeType = "application/octet-stream"
                parents = listOf("appDataFolder") // App-specific folder
            }
            
            val mediaContent = FileContent("application/octet-stream", file)
            val uploadedFile = driveService.files()
                .create(fileMetadata, mediaContent)
                .setFields("id, name")
                .execute()
            
            Result.success("Uploaded to Google Drive: ${uploadedFile.id}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    private suspend fun exportToDownloads(file: File): Result<String> {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        )
        val destFile = File(downloadsDir, file.name)
        
        return try {
            file.copyTo(destFile, overwrite = true)
            Result.success("Saved to ${destFile.absolutePath}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
```

### 5.2 Auto-Backup Schedule

```kotlin
// AutoBackupWorker.kt
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    @Inject lateinit var backupManager: BackupManager
    @Inject lateinit var walletRepository: WalletRepository
    @Inject lateinit var settingsManager: SettingsManager
    
    override suspend fun doWork(): Result {
        // Get current wallet
        val wallet = walletRepository.getCurrentWallet() ?: return Result.failure()
        
        // Create backup
        val result = backupManager.createBackup(wallet)
        
        return if (result.success) {
            Timber.i("Auto-backup completed: ${result.messageCount} messages backed up")
            Result.success()
        } else {
            Timber.e("Auto-backup failed: ${result.error}")
            Result.retry()
        }
    }
    
    companion object {
        fun schedule(context: Context, intervalHours: Int = 24) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .build()
            
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                intervalHours.toLong(), TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    "auto_backup",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
        }
    }
}
```

---

## 6. BACKUP SETTINGS UI

```kotlin
// BackupSettingsFragment.kt
@AndroidEntryPoint
class BackupSettingsFragment : Fragment() {
    
    @Inject lateinit var backupManager: BackupManager
    @Inject lateinit var backupDiscovery: BackupDiscovery
    @Inject lateinit var restoreManager: RestoreManager
    
    private var _binding: FragmentBackupSettingsBinding? = null
    private val binding get() = _binding!!
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Last backup info
        updateLastBackupInfo()
        
        // Create backup button
        binding.buttonCreateBackup.setOnClickListener {
            createBackup()
        }
        
        // Export backup button
        binding.buttonExportBackup.setOnClickListener {
            showExportOptions()
        }
        
        // Restore backup button
        binding.buttonRestoreBackup.setOnClickListener {
            showRestoreOptions()
        }
        
        // Auto-backup toggle
        binding.switchAutoBackup.isChecked = settingsManager.isAutoBackupEnabled
        binding.switchAutoBackup.setOnCheckedChangeListener { _, isChecked ->
            settingsManager.isAutoBackupEnabled = isChecked
            if (isChecked) {
                AutoBackupWorker.schedule(requireContext())
            } else {
                WorkManager.getInstance(requireContext())
                    .cancelUniqueWork("auto_backup")
            }
        }
        
        // Auto-backup frequency
        binding.spinnerBackupFrequency.setSelection(
            when (settingsManager.autoBackupIntervalHours) {
                6 -> 0
                12 -> 1
                24 -> 2
                72 -> 3
                else -> 2
            }
        )
    }
    
    private fun createBackup() {
        lifecycleScope.launch {
            binding.progressBar.visibility = View.VISIBLE
            
            val wallet = walletRepository.getCurrentWallet()!!
            val result = backupManager.createBackup(wallet)
            
            binding.progressBar.visibility = View.GONE
            
            if (result.success) {
                Toast.makeText(
                    requireContext(),
                    "Backup created: ${result.messageCount} messages",
                    Toast.LENGTH_SHORT
                ).show()
                updateLastBackupInfo()
            } else {
                Toast.makeText(
                    requireContext(),
                    "Backup failed: ${result.error}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    private fun showExportOptions() {
        val options = arrayOf(
            "Save to Downloads",
            "Google Drive",
            "Share..."
        )
        
        AlertDialog.Builder(requireContext())
            .setTitle("Export Backup")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportToDownloads()
                    1 -> exportToGoogleDrive()
                    2 -> shareBackup()
                }
            }
            .show()
    }
    
    private fun showRestoreOptions() {
        // First check for auto-discovered backups
        val discovered = backupDiscovery.discoverBackups()
        
        if (discovered.isNotEmpty()) {
            showDiscoveredBackupsDialog(discovered)
        } else {
            // No backups found, offer manual upload
            openFilePicker()
        }
    }
}
```

---

## 7. SECURITY WARNINGS

### 7.1 User Warnings

```
┌─────────────────────────────────────────────────────────────┐
│                    SECURITY WARNINGS                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  WARN USER ON FIRST USE:                                    │
│  ────────────────────────                                   │
│  "Your messages are encrypted with your wallet.             │
│   If you lose access to your wallet AND your backup,        │
│   your messages CANNOT be recovered.                        │
│                                                              │
│   We recommend:                                             │
│   • Backing up your wallet seed phrase                     │
│   • Enabling auto-backup for chat                          │
│   • Exporting backup to cloud storage"                     │
│                                                              │
│  WARN ON WALLET SWITCH:                                     │
│  ───────────────────────                                    │
│  "You are switching to a different wallet.                  │
│   Your current chat history will not be visible.            │
│                                                              │
│   To access chats for wallet 0xABC..., switch back         │
│   to that wallet or restore from backup."                  │
│                                                              │
│  WARN ON DELETE WALLET:                                     │
│  ─────────────────────                                      │
│  "⚠️ Deleting this wallet will make your chat history      │
│   inaccessible unless you have a backup or seed phrase.    │
│                                                              │
│   Would you like to create a backup first?"                │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```
