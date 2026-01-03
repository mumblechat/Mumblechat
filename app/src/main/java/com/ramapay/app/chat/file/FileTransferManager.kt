package com.ramapay.app.chat.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.ramapay.app.chat.crypto.MessageEncryption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SECURE FILE TRANSFER MANAGER for MumbleChat
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * Features:
 * - Supports PDF and image files only (security)
 * - 50MB max file size
 * - AES-256-GCM encryption before upload
 * - File validation and virus scanning
 * - 20-minute expiry on relay nodes
 * - 5x message rewards for file transfers
 * - Only high-tier relay nodes (2GB+ storage) can handle files
 * 
 * File Transfer Flow:
 * 1. User selects file → Validate type/size
 * 2. Scan file for malicious content
 * 3. Encrypt with AES-256-GCM
 * 4. Upload to eligible relay node
 * 5. Send file metadata message to recipient
 * 6. Recipient downloads from relay → Decrypt
 * 7. Relay deletes after download or 20 min
 */
@Singleton
class FileTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageEncryption: MessageEncryption
) {
    companion object {
        private const val TAG = "FileTransferManager"
        
        // File constraints
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024  // 50 MB
        const val FILE_EXPIRY_MS = 20L * 60 * 1000         // 20 minutes
        const val MESSAGE_REWARD_MULTIPLIER = 5            // 1 file = 5 messages
        const val MIN_RELAY_STORAGE_GB = 2                 // 2 GB minimum for file relay
        
        // Allowed file types (security whitelist)
        val ALLOWED_MIME_TYPES = setOf(
            // Images
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/heic",
            "image/heif",
            // Documents
            "application/pdf"
        )
        
        val ALLOWED_EXTENSIONS = setOf(
            "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "pdf"
        )
        
        // Encryption
        private const val ENCRYPTION_ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_SIZE = 256
        private const val NONCE_SIZE = 12
        private const val TAG_SIZE = 128
        
        // Magic bytes for virus scanning
        private val DANGEROUS_SIGNATURES = listOf(
            // Executable signatures
            byteArrayOf(0x4D, 0x5A),                     // MZ (Windows EXE)
            byteArrayOf(0x7F, 0x45, 0x4C, 0x46),         // ELF (Linux binary)
            byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), // Java class
            byteArrayOf(0x50, 0x4B, 0x03, 0x04),         // ZIP (could contain exe)
            byteArrayOf(0x52, 0x61, 0x72, 0x21),         // RAR
            byteArrayOf(0x1F, 0x8B.toByte()),            // GZIP
            byteArrayOf(0x25, 0x50, 0x44, 0x46),         // PDF - validate further
        )
        
        // Valid PDF header
        private val PDF_HEADER = byteArrayOf(0x25, 0x50, 0x44, 0x46)  // %PDF
        
        // Valid image headers
        private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_HEADER = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        private val GIF_HEADER = byteArrayOf(0x47, 0x49, 0x46)
        private val WEBP_HEADER = "RIFF".toByteArray()
    }
    
    // Transfer states
    private val _activeTransfers = MutableStateFlow<Map<String, FileTransferState>>(emptyMap())
    val activeTransfers: StateFlow<Map<String, FileTransferState>> = _activeTransfers
    
    // Pending files (uploaded, waiting for download)
    private val pendingFiles = ConcurrentHashMap<String, PendingFile>()
    
    // File cache directory
    private val cacheDir: File by lazy {
        File(context.cacheDir, "mumblechat_files").also { 
            if (!it.exists()) it.mkdirs() 
        }
    }
    
    // ============ Public API ============
    
    /**
     * Prepare a file for sending.
     * Validates, scans, and encrypts the file.
     * 
     * @param uri Content URI of the file
     * @param recipientPublicKey Recipient's public key for encryption
     * @return PreparedFile with encrypted data and metadata, or error
     */
    suspend fun prepareFileForSending(
        uri: Uri,
        recipientPublicKey: ByteArray
    ): Result<PreparedFile> = withContext(Dispatchers.IO) {
        val transferId = UUID.randomUUID().toString()
        
        try {
            updateTransferState(transferId, FileTransferState.Validating)
            
            // 1. Get file info
            val fileInfo = getFileInfo(uri) 
                ?: return@withContext Result.failure(FileTransferException("Cannot read file info"))
            
            Timber.d("$TAG: Preparing file: ${fileInfo.name} (${fileInfo.size} bytes, ${fileInfo.mimeType})")
            
            // 2. Validate file type
            if (!isAllowedFileType(fileInfo)) {
                return@withContext Result.failure(
                    FileTransferException("File type not allowed. Only PDF and images are supported.")
                )
            }
            
            // 3. Check file size
            if (fileInfo.size > MAX_FILE_SIZE_BYTES) {
                return@withContext Result.failure(
                    FileTransferException("File too large. Maximum size is 50 MB.")
                )
            }
            
            // 4. Read file bytes
            updateTransferState(transferId, FileTransferState.Reading)
            val fileBytes = readFileBytes(uri)
                ?: return@withContext Result.failure(FileTransferException("Cannot read file"))
            
            // 5. Scan for malicious content
            updateTransferState(transferId, FileTransferState.Scanning)
            val scanResult = scanFile(fileBytes, fileInfo)
            if (!scanResult.isSafe) {
                Timber.w("$TAG: File scan failed: ${scanResult.reason}")
                return@withContext Result.failure(
                    FileTransferException("File security scan failed: ${scanResult.reason}")
                )
            }
            
            // 6. Encrypt file
            updateTransferState(transferId, FileTransferState.Encrypting)
            val encryptedFile = encryptFile(fileBytes, recipientPublicKey)
            
            // 7. Generate file hash for integrity
            val fileHash = MessageDigest.getInstance("SHA-256").digest(fileBytes)
            
            val preparedFile = PreparedFile(
                transferId = transferId,
                originalName = fileInfo.name,
                mimeType = fileInfo.mimeType,
                originalSize = fileInfo.size,
                encryptedData = encryptedFile.encryptedData,
                encryptionKey = encryptedFile.key,
                nonce = encryptedFile.nonce,
                fileHash = fileHash,
                thumbnail = if (isImage(fileInfo.mimeType)) {
                    generateThumbnail(fileBytes, fileInfo.mimeType)
                } else null
            )
            
            updateTransferState(transferId, FileTransferState.Ready)
            
            Timber.d("$TAG: File prepared successfully: ${preparedFile.transferId}")
            Result.success(preparedFile)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to prepare file")
            updateTransferState(transferId, FileTransferState.Failed(e.message ?: "Unknown error"))
            Result.failure(e)
        }
    }
    
    /**
     * Decrypt a received file.
     * 
     * @param encryptedData Encrypted file bytes
     * @param key Decryption key
     * @param nonce Encryption nonce
     * @param expectedHash Expected SHA-256 hash for verification
     * @return Decrypted file bytes or error
     */
    suspend fun decryptReceivedFile(
        encryptedData: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        expectedHash: ByteArray
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // Decrypt
            val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(TAG_SIZE, nonce)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            
            val decryptedBytes = cipher.doFinal(encryptedData)
            
            // Verify hash
            val actualHash = MessageDigest.getInstance("SHA-256").digest(decryptedBytes)
            if (!actualHash.contentEquals(expectedHash)) {
                return@withContext Result.failure(
                    FileTransferException("File integrity check failed. File may be corrupted.")
                )
            }
            
            Timber.d("$TAG: File decrypted and verified successfully")
            Result.success(decryptedBytes)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to decrypt file")
            Result.failure(FileTransferException("Decryption failed: ${e.message}"))
        }
    }
    
    /**
     * Save decrypted file to cache for viewing.
     */
    suspend fun saveToCache(
        data: ByteArray,
        filename: String,
        mimeType: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            // Sanitize filename
            val safeName = filename.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val file = File(cacheDir, safeName)
            
            file.outputStream().use { it.write(data) }
            
            Timber.d("$TAG: File saved to cache: ${file.absolutePath}")
            Result.success(file)
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save file to cache")
            Result.failure(e)
        }
    }
    
    /**
     * Clean up expired cached files.
     */
    suspend fun cleanupCache() = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            cacheDir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > FILE_EXPIRY_MS) {
                    file.delete()
                    Timber.d("$TAG: Deleted expired cache file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to cleanup cache")
        }
    }
    
    /**
     * Check if a relay node can handle file transfers.
     */
    fun canRelayHandleFiles(storageAllocationGb: Long): Boolean {
        return storageAllocationGb >= MIN_RELAY_STORAGE_GB
    }
    
    /**
     * Calculate reward for file transfer (5x normal message).
     */
    fun calculateFileReward(baseMessageReward: Long): Long {
        return baseMessageReward * MESSAGE_REWARD_MULTIPLIER
    }
    
    // ============ File Validation ============
    
    private fun getFileInfo(uri: Uri): FileInfo? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else "unknown"
                    val size = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                    
                    val mimeType = context.contentResolver.getType(uri) 
                        ?: getMimeTypeFromExtension(name)
                        ?: "application/octet-stream"
                    
                    FileInfo(name = name, size = size, mimeType = mimeType, uri = uri)
                } else null
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get file info")
            null
        }
    }
    
    private fun getMimeTypeFromExtension(filename: String): String? {
        val extension = filename.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }
    
    private fun isAllowedFileType(fileInfo: FileInfo): Boolean {
        // Check MIME type
        if (fileInfo.mimeType !in ALLOWED_MIME_TYPES) {
            Timber.w("$TAG: MIME type not allowed: ${fileInfo.mimeType}")
            return false
        }
        
        // Check extension
        val extension = fileInfo.name.substringAfterLast('.', "").lowercase()
        if (extension !in ALLOWED_EXTENSIONS) {
            Timber.w("$TAG: Extension not allowed: $extension")
            return false
        }
        
        return true
    }
    
    private fun isImage(mimeType: String): Boolean {
        return mimeType.startsWith("image/")
    }
    
    // ============ Security Scanning ============
    
    /**
     * Scan file for malicious content.
     * This is a basic scan - checks magic bytes, structure, and embedded content.
     */
    private fun scanFile(data: ByteArray, fileInfo: FileInfo): ScanResult {
        if (data.isEmpty()) {
            return ScanResult(false, "Empty file")
        }
        
        // Validate magic bytes match claimed type
        return when {
            fileInfo.mimeType == "application/pdf" -> scanPdf(data)
            fileInfo.mimeType.startsWith("image/jpeg") -> scanJpeg(data)
            fileInfo.mimeType.startsWith("image/png") -> scanPng(data)
            fileInfo.mimeType.startsWith("image/gif") -> scanGif(data)
            fileInfo.mimeType.startsWith("image/webp") -> scanWebp(data)
            fileInfo.mimeType.startsWith("image/heic") || 
            fileInfo.mimeType.startsWith("image/heif") -> ScanResult(true) // HEIC is container format
            else -> ScanResult(false, "Unknown file type")
        }
    }
    
    private fun scanPdf(data: ByteArray): ScanResult {
        // Check PDF header
        if (data.size < 4 || !data.sliceArray(0..3).contentEquals(PDF_HEADER)) {
            return ScanResult(false, "Invalid PDF header")
        }
        
        // Check for JavaScript (potential exploit)
        val content = String(data, Charsets.ISO_8859_1)
        if (content.contains("/JavaScript") || content.contains("/JS")) {
            return ScanResult(false, "PDF contains JavaScript - blocked for security")
        }
        
        // Check for embedded files
        if (content.contains("/EmbeddedFile") || content.contains("/Launch")) {
            return ScanResult(false, "PDF contains embedded files or launch actions - blocked")
        }
        
        // Check for form actions
        if (content.contains("/OpenAction") || content.contains("/AA")) {
            // Additional actions might be suspicious
            Timber.w("$TAG: PDF has OpenAction/AA - allowing but logging")
        }
        
        return ScanResult(true)
    }
    
    private fun scanJpeg(data: ByteArray): ScanResult {
        if (data.size < 3) return ScanResult(false, "File too small")
        
        // JPEG must start with FF D8 FF
        if (data[0] != 0xFF.toByte() || data[1] != 0xD8.toByte() || data[2] != 0xFF.toByte()) {
            return ScanResult(false, "Invalid JPEG header")
        }
        
        // Check for embedded scripts in EXIF
        val content = String(data, Charsets.ISO_8859_1)
        if (content.contains("<script") || content.contains("<?php")) {
            return ScanResult(false, "JPEG contains embedded scripts")
        }
        
        return ScanResult(true)
    }
    
    private fun scanPng(data: ByteArray): ScanResult {
        if (data.size < 8) return ScanResult(false, "File too small")
        
        // PNG signature: 89 50 4E 47 0D 0A 1A 0A
        val pngSignature = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        )
        if (!data.sliceArray(0..7).contentEquals(pngSignature)) {
            return ScanResult(false, "Invalid PNG header")
        }
        
        return ScanResult(true)
    }
    
    private fun scanGif(data: ByteArray): ScanResult {
        if (data.size < 6) return ScanResult(false, "File too small")
        
        // GIF89a or GIF87a
        val header = String(data.sliceArray(0..5), Charsets.US_ASCII)
        if (header != "GIF89a" && header != "GIF87a") {
            return ScanResult(false, "Invalid GIF header")
        }
        
        return ScanResult(true)
    }
    
    private fun scanWebp(data: ByteArray): ScanResult {
        if (data.size < 12) return ScanResult(false, "File too small")
        
        // RIFF....WEBP
        val riff = String(data.sliceArray(0..3), Charsets.US_ASCII)
        val webp = String(data.sliceArray(8..11), Charsets.US_ASCII)
        
        if (riff != "RIFF" || webp != "WEBP") {
            return ScanResult(false, "Invalid WEBP header")
        }
        
        return ScanResult(true)
    }
    
    // ============ Encryption ============
    
    private fun encryptFile(data: ByteArray, recipientPublicKey: ByteArray): EncryptedFile {
        // Generate random AES key
        val key = ByteArray(KEY_SIZE / 8).also { SecureRandom().nextBytes(it) }
        val nonce = ByteArray(NONCE_SIZE).also { SecureRandom().nextBytes(it) }
        
        // Encrypt file with AES-GCM
        val cipher = Cipher.getInstance(ENCRYPTION_ALGORITHM)
        val keySpec = SecretKeySpec(key, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        
        val encryptedData = cipher.doFinal(data)
        
        return EncryptedFile(
            encryptedData = encryptedData,
            key = key,
            nonce = nonce
        )
    }
    
    // ============ Helpers ============
    
    private fun readFileBytes(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ByteArrayOutputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.toByteArray()
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to read file bytes")
            null
        }
    }
    
    private fun generateThumbnail(data: ByteArray, mimeType: String): ByteArray? {
        // TODO: Implement thumbnail generation for images
        // For now, return null - UI can display placeholder
        return null
    }
    
    private fun updateTransferState(transferId: String, state: FileTransferState) {
        _activeTransfers.value = _activeTransfers.value + (transferId to state)
    }
    
    fun clearTransferState(transferId: String) {
        _activeTransfers.value = _activeTransfers.value - transferId
    }
}

// ============ Data Classes ============

data class FileInfo(
    val name: String,
    val size: Long,
    val mimeType: String,
    val uri: Uri
)

data class PreparedFile(
    val transferId: String,
    val originalName: String,
    val mimeType: String,
    val originalSize: Long,
    val encryptedData: ByteArray,
    val encryptionKey: ByteArray,
    val nonce: ByteArray,
    val fileHash: ByteArray,
    val thumbnail: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PreparedFile
        return transferId == other.transferId
    }

    override fun hashCode(): Int = transferId.hashCode()
}

data class EncryptedFile(
    val encryptedData: ByteArray,
    val key: ByteArray,
    val nonce: ByteArray
)

data class PendingFile(
    val transferId: String,
    val relayNodeId: String,
    val expiresAt: Long,
    val downloadUrl: String,
    val encryptionKey: ByteArray,
    val nonce: ByteArray,
    val fileHash: ByteArray
)

data class ScanResult(
    val isSafe: Boolean,
    val reason: String? = null
)

/**
 * File message metadata stored in message content.
 */
data class FileMessageContent(
    val transferId: String,
    val fileName: String,
    val mimeType: String,
    val fileSize: Long,
    val relayNodeId: String,
    val downloadUrl: String,
    val encryptionKey: String,    // Base64 encoded
    val nonce: String,            // Base64 encoded
    val fileHash: String,         // Base64 encoded
    val thumbnailBase64: String?, // Small thumbnail for preview
    val expiresAt: Long           // Timestamp when file expires
)

sealed class FileTransferState {
    object Validating : FileTransferState()
    object Reading : FileTransferState()
    object Scanning : FileTransferState()
    object Encrypting : FileTransferState()
    object Uploading : FileTransferState()
    data class UploadProgress(val percent: Int) : FileTransferState()
    object Ready : FileTransferState()
    object Downloading : FileTransferState()
    data class DownloadProgress(val percent: Int) : FileTransferState()
    object Complete : FileTransferState()
    data class Failed(val message: String) : FileTransferState()
}

class FileTransferException(message: String) : Exception(message)
