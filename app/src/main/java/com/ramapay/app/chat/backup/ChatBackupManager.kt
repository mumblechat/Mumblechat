package com.ramapay.app.chat.backup

import android.content.Context
import android.net.Uri
import com.ramapay.app.chat.data.dao.ConversationDao
import com.ramapay.app.chat.data.dao.MessageDao
import com.ramapay.app.chat.data.entity.ConversationEntity
import com.ramapay.app.chat.data.entity.MessageEntity
import com.ramapay.app.chat.data.entity.MessageStatus
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Backup data structure for JSON export.
 */
data class ChatBackup(
    val version: Int = 2,
    val createdAt: Long = System.currentTimeMillis(),
    val walletAddress: String,
    val conversations: List<ConversationBackup>,
    val messages: List<MessageBackup>
)

data class ConversationBackup(
    val id: String,
    val peerAddress: String,
    val customName: String?,
    val lastMessagePreview: String?,
    val lastMessageTime: Long?,
    val isPinned: Boolean,
    val isMuted: Boolean,
    val createdAt: Long
)

data class MessageBackup(
    val id: String,
    val conversationId: String,
    val groupId: String?,
    val senderAddress: String,
    val recipientAddress: String?,
    val contentType: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val replyToId: String?,
    val isDeleted: Boolean
)

/**
 * Manages chat backup and restore operations.
 * 
 * Backup format: Encrypted JSON file
 * Encryption: AES-256-GCM with PBKDF2 key derivation
 */
@Singleton
class ChatBackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    companion object {
        private const val TAG = "ChatBackupManager"
        private const val BACKUP_VERSION = 2
        private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_LENGTH = 256
        private const val ITERATION_COUNT = 100_000
        private const val GCM_NONCE_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val SALT_LENGTH = 16
        
        // File header to identify encrypted backups
        private const val FILE_HEADER = "MUMBLECHAT_BACKUP_V2"
        
        // Local backup storage directory
        private const val BACKUP_DIR = "mumblechat_backups"
        private const val MAX_LOCAL_BACKUPS_PER_WALLET = 5
    }
    
    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    
    private val backupAdapter: JsonAdapter<ChatBackup> = moshi.adapter(ChatBackup::class.java)
    
    // ============ Local Backup Storage ============
    
    /**
     * Get backup directory, creating if needed.
     */
    private fun getBackupDir(): File {
        val dir = File(context.filesDir, BACKUP_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * List all available local backups for a specific wallet.
     */
    suspend fun listBackupsForWallet(walletAddress: String): List<LocalBackupInfo> = withContext(Dispatchers.IO) {
        val prefix = "backup_${walletAddress.lowercase().take(10)}_"
        val backupDir = getBackupDir()
        
        if (!backupDir.exists()) return@withContext emptyList()
        
        backupDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".mcb") }
            ?.mapNotNull { file ->
                try {
                    val metadata = parseBackupMetadata(file)
                    LocalBackupInfo(
                        file = file,
                        filename = file.name,
                        walletAddress = metadata?.walletAddress ?: walletAddress,
                        createdAt = metadata?.createdAt ?: file.lastModified(),
                        conversationCount = metadata?.conversationCount ?: 0,
                        messageCount = metadata?.messageCount ?: 0,
                        fileSize = file.length()
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse backup: ${file.name}")
                    null
                }
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }
    
    /**
     * Auto-save backup to local storage (encrypted).
     */
    suspend fun autoSaveBackup(
        walletAddress: String,
        password: String
    ): Result<BackupStats> = withContext(Dispatchers.IO) {
        try {
            val conversations = conversationDao.getAllForWallet(walletAddress).first()
            if (conversations.isEmpty()) {
                return@withContext Result.failure(Exception("No conversations to backup"))
            }
            
            val allMessages = mutableListOf<MessageEntity>()
            for (conv in conversations) {
                val messages = messageDao.getMessagesForConversation(conv.id).first()
                allMessages.addAll(messages)
            }
            
            val backup = ChatBackup(
                version = BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                walletAddress = walletAddress,
                conversations = conversations.map { it.toBackup() },
                messages = allMessages.map { it.toBackup() }
            )
            
            val json = backupAdapter.toJson(backup)
            val encryptedData = encrypt(json, password)
            
            // Generate filename with wallet prefix
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val timestamp = dateFormat.format(Date())
            val shortAddr = walletAddress.lowercase().take(10)
            val filename = "backup_${shortAddr}_$timestamp.mcb"
            
            val file = File(getBackupDir(), filename)
            file.outputStream().use { fos ->
                OutputStreamWriter(fos).use { writer ->
                    writer.write(FILE_HEADER)
                    writer.write("\n")
                    writer.write(android.util.Base64.encodeToString(encryptedData, android.util.Base64.NO_WRAP))
                }
            }
            
            // Cleanup old backups (keep only MAX_LOCAL_BACKUPS_PER_WALLET)
            cleanupOldBackups(walletAddress)
            
            val stats = BackupStats(
                conversationCount = conversations.size,
                messageCount = allMessages.size,
                backupSize = file.length(),
                timestamp = System.currentTimeMillis()
            )
            
            Timber.d("$TAG: Auto-saved backup: ${file.name}")
            Result.success(stats)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Auto-save failed")
            Result.failure(e)
        }
    }
    
    /**
     * Import from a local backup file.
     */
    suspend fun importFromLocalBackup(
        backupInfo: LocalBackupInfo,
        password: String,
        walletAddress: String,
        mergeMode: MergeMode = MergeMode.SKIP_EXISTING
    ): Result<ImportStats> = withContext(Dispatchers.IO) {
        importBackup(Uri.fromFile(backupInfo.file), password, walletAddress, mergeMode)
    }
    
    /**
     * Delete a local backup.
     */
    suspend fun deleteLocalBackup(backupInfo: LocalBackupInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            backupInfo.file.delete()
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete backup")
            false
        }
    }
    
    /**
     * Cleanup old backups, keeping only the most recent ones.
     */
    private fun cleanupOldBackups(walletAddress: String) {
        val prefix = "backup_${walletAddress.lowercase().take(10)}_"
        val backups = getBackupDir().listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name.endsWith(".mcb") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        if (backups.size > MAX_LOCAL_BACKUPS_PER_WALLET) {
            backups.drop(MAX_LOCAL_BACKUPS_PER_WALLET).forEach { old ->
                Timber.d("$TAG: Deleting old backup: ${old.name}")
                old.delete()
            }
        }
    }
    
    /**
     * Parse backup metadata without fully decrypting (for listing).
     */
    private fun parseBackupMetadata(file: File): BackupMetadata? {
        // We can't read metadata without password, so just return null
        // UI will show file info instead
        return null
    }
    
    /**
     * Export all chat data for a wallet to an encrypted backup file.
     * 
     * @param walletAddress The wallet address to export data for
     * @param outputUri URI to write the backup file to
     * @param password Password for encryption (user-provided)
     * @return Result with backup stats or error
     */
    suspend fun exportBackup(
        walletAddress: String,
        outputUri: Uri,
        password: String
    ): Result<BackupStats> = withContext(Dispatchers.IO) {
        try {
            Timber.d("$TAG: Starting backup export for $walletAddress")
            
            // Fetch all conversations for this wallet
            val conversations = conversationDao.getAllForWallet(walletAddress).first()
            Timber.d("$TAG: Found ${conversations.size} conversations")
            
            // Fetch all messages for these conversations
            val allMessages = mutableListOf<MessageEntity>()
            for (conv in conversations) {
                val messages = messageDao.getMessagesForConversation(conv.id).first()
                allMessages.addAll(messages)
            }
            Timber.d("$TAG: Found ${allMessages.size} messages")
            
            // Convert to backup format
            val backup = ChatBackup(
                version = BACKUP_VERSION,
                createdAt = System.currentTimeMillis(),
                walletAddress = walletAddress,
                conversations = conversations.map { it.toBackup() },
                messages = allMessages.map { it.toBackup() }
            )
            
            // Serialize to JSON
            val json = backupAdapter.toJson(backup)
            
            // Encrypt the JSON
            val encryptedData = encrypt(json, password)
            
            // Write to file
            context.contentResolver.openOutputStream(outputUri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(FILE_HEADER)
                    writer.write("\n")
                    writer.write(android.util.Base64.encodeToString(encryptedData, android.util.Base64.NO_WRAP))
                }
            } ?: throw Exception("Could not open output stream")
            
            val stats = BackupStats(
                conversationCount = conversations.size,
                messageCount = allMessages.size,
                backupSize = encryptedData.size.toLong(),
                timestamp = System.currentTimeMillis()
            )
            
            Timber.d("$TAG: Backup export completed: $stats")
            Result.success(stats)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Backup export failed")
            Result.failure(e)
        }
    }
    
    /**
     * Import chat data from an encrypted backup file.
     * 
     * @param inputUri URI to read the backup file from
     * @param password Password for decryption
     * @param walletAddress Current wallet address (must match backup)
     * @param mergeMode How to handle existing data
     * @return Result with import stats or error
     */
    suspend fun importBackup(
        inputUri: Uri,
        password: String,
        walletAddress: String,
        mergeMode: MergeMode = MergeMode.SKIP_EXISTING
    ): Result<ImportStats> = withContext(Dispatchers.IO) {
        try {
            Timber.d("$TAG: Starting backup import")
            
            // Read file content
            val content = context.contentResolver.openInputStream(inputUri)?.use { inputStream ->
                BufferedReader(inputStream.reader()).use { reader ->
                    reader.readText()
                }
            } ?: throw Exception("Could not open input stream")
            
            // Parse header and encrypted data
            val lines = content.split("\n", limit = 2)
            if (lines.size != 2 || lines[0] != FILE_HEADER) {
                throw Exception("Invalid backup file format")
            }
            
            val encryptedData = android.util.Base64.decode(lines[1], android.util.Base64.NO_WRAP)
            
            // Decrypt
            val json = decrypt(encryptedData, password)
            
            // Parse JSON
            val backup = backupAdapter.fromJson(json)
                ?: throw Exception("Failed to parse backup data")
            
            // Verify wallet address matches
            if (backup.walletAddress.lowercase() != walletAddress.lowercase()) {
                throw WalletMismatchException(
                    "Backup is for wallet ${backup.walletAddress}, " +
                    "but current wallet is $walletAddress"
                )
            }
            
            Timber.d("$TAG: Parsed backup v${backup.version} with ${backup.conversations.size} conversations, ${backup.messages.size} messages")
            
            // Import conversations
            var conversationsImported = 0
            var conversationsSkipped = 0
            
            for (convBackup in backup.conversations) {
                val existing = conversationDao.getById(convBackup.id)
                when {
                    existing == null -> {
                        conversationDao.insert(convBackup.toEntity(walletAddress))
                        conversationsImported++
                    }
                    mergeMode == MergeMode.REPLACE_ALL -> {
                        conversationDao.insert(convBackup.toEntity(walletAddress))
                        conversationsImported++
                    }
                    else -> {
                        conversationsSkipped++
                    }
                }
            }
            
            // Import messages
            var messagesImported = 0
            var messagesSkipped = 0
            
            for (msgBackup in backup.messages) {
                val existing = messageDao.getMessageById(msgBackup.id)
                when {
                    existing == null -> {
                        messageDao.insert(msgBackup.toEntity())
                        messagesImported++
                    }
                    mergeMode == MergeMode.REPLACE_ALL -> {
                        messageDao.insert(msgBackup.toEntity())
                        messagesImported++
                    }
                    else -> {
                        messagesSkipped++
                    }
                }
            }
            
            val stats = ImportStats(
                conversationsImported = conversationsImported,
                conversationsSkipped = conversationsSkipped,
                messagesImported = messagesImported,
                messagesSkipped = messagesSkipped,
                backupVersion = backup.version,
                backupDate = backup.createdAt
            )
            
            Timber.d("$TAG: Import completed: $stats")
            Result.success(stats)
            
        } catch (e: WalletMismatchException) {
            Timber.e(e, "$TAG: Wallet mismatch")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Import failed")
            Result.failure(e)
        }
    }
    
    /**
     * Generate a default backup filename.
     */
    fun generateBackupFilename(walletAddress: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val timestamp = dateFormat.format(Date())
        val shortAddress = walletAddress.take(10)
        return "mumblechat_backup_${shortAddress}_$timestamp.mcb"
    }
    
    // ============ Encryption Helpers ============
    
    private fun encrypt(plaintext: String, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(GCM_NONCE_LENGTH).also { SecureRandom().nextBytes(it) }
        
        val key = deriveKey(password, salt)
        
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        
        // Format: salt + nonce + ciphertext
        return salt + nonce + ciphertext
    }
    
    private fun decrypt(encryptedData: ByteArray, password: String): String {
        if (encryptedData.size < SALT_LENGTH + GCM_NONCE_LENGTH) {
            throw Exception("Invalid encrypted data")
        }
        
        val salt = encryptedData.sliceArray(0 until SALT_LENGTH)
        val nonce = encryptedData.sliceArray(SALT_LENGTH until SALT_LENGTH + GCM_NONCE_LENGTH)
        val ciphertext = encryptedData.sliceArray(SALT_LENGTH + GCM_NONCE_LENGTH until encryptedData.size)
        
        val key = deriveKey(password, salt)
        
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
    
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH)
        val secretKey = factory.generateSecret(spec)
        return SecretKeySpec(secretKey.encoded, "AES")
    }
    
    // ============ Extension Functions ============
    
    private fun ConversationEntity.toBackup() = ConversationBackup(
        id = id,
        peerAddress = peerAddress,
        customName = customName,
        lastMessagePreview = lastMessagePreview,
        lastMessageTime = lastMessageTime,
        isPinned = isPinned,
        isMuted = isMuted,
        createdAt = createdAt
    )
    
    private fun ConversationBackup.toEntity(walletAddress: String) = ConversationEntity(
        id = id,
        walletAddress = walletAddress,
        peerAddress = peerAddress,
        peerPublicKey = null, // Will be fetched from blockchain
        customName = customName,
        lastMessagePreview = lastMessagePreview,
        lastMessageTime = lastMessageTime,
        isPinned = isPinned,
        isMuted = isMuted,
        createdAt = createdAt
    )
    
    private fun MessageEntity.toBackup() = MessageBackup(
        id = id,
        conversationId = conversationId,
        groupId = groupId,
        senderAddress = senderAddress,
        recipientAddress = recipientAddress,
        contentType = contentType,
        content = content,
        timestamp = timestamp,
        status = status.name,
        replyToId = replyToId,
        isDeleted = isDeleted
    )
    
    private fun MessageBackup.toEntity() = MessageEntity(
        id = id,
        conversationId = conversationId,
        groupId = groupId,
        senderAddress = senderAddress,
        recipientAddress = recipientAddress,
        contentType = contentType,
        content = content,
        encryptedContent = null,
        timestamp = timestamp,
        status = try { MessageStatus.valueOf(status) } catch (e: Exception) { MessageStatus.DELIVERED },
        replyToId = replyToId,
        isDeleted = isDeleted,
        signature = null
    )
}

/**
 * Backup statistics.
 */
data class BackupStats(
    val conversationCount: Int,
    val messageCount: Int,
    val backupSize: Long,
    val timestamp: Long
)

/**
 * Import statistics.
 */
data class ImportStats(
    val conversationsImported: Int,
    val conversationsSkipped: Int,
    val messagesImported: Int,
    val messagesSkipped: Int,
    val backupVersion: Int,
    val backupDate: Long
)

/**
 * How to handle existing data during import.
 */
enum class MergeMode {
    SKIP_EXISTING,  // Keep existing, only add new
    REPLACE_ALL     // Replace all with backup data
}

/**
 * Exception for wallet address mismatch.
 */
class WalletMismatchException(message: String) : Exception(message)

/**
 * Information about a local backup file.
 */
data class LocalBackupInfo(
    val file: File,
    val filename: String,
    val walletAddress: String,
    val createdAt: Long,
    val conversationCount: Int,
    val messageCount: Int,
    val fileSize: Long
)

/**
 * Backup metadata (for display without decryption).
 */
data class BackupMetadata(
    val walletAddress: String,
    val createdAt: Long,
    val conversationCount: Int,
    val messageCount: Int
)
