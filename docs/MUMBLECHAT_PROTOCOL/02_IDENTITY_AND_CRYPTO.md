# MumbleChat Protocol - Identity & Cryptography

## Part 2 of 8

---

## 1. IDENTITY MODEL

### 1.1 Core Principle

```
ChatIdentity = Wallet Address (EVM)
```

| Rule | Description |
|------|-------------|
| One wallet = One identity | Each wallet address is a unique chat identity |
| Multiple wallets = Multiple identities | User can have separate identities |
| No email/phone/username | Pure wallet-based identity |
| Import wallet = Restore identity | Same wallet always has same chat identity |

### 1.2 Identity Registration (On-Chain)

Every wallet must register on-chain before chatting:

```
┌─────────────────────────────────────────────────────────────┐
│                 REGISTRATION FLOW                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. User opens chat for first time                          │
│           │                                                  │
│           ▼                                                  │
│  2. App derives chat public key from wallet                 │
│           │                                                  │
│           ▼                                                  │
│  3. App calls MumbleChatRegistry.register(publicKey)        │
│           │                                                  │
│           ▼                                                  │
│  4. User signs transaction (pays RAMA gas)                  │
│           │                                                  │
│           ▼                                                  │
│  5. Identity registered on Ramestta blockchain              │
│           │                                                  │
│           ▼                                                  │
│  6. User can now send/receive messages                      │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 1.3 What's Stored On-Chain

| Data | Stored | Not Stored |
|------|--------|------------|
| Wallet Address | ✅ | - |
| Chat Public Key Hash | ✅ | - |
| Registration Timestamp | ✅ | - |
| Messages | - | ❌ Never |
| Contacts | - | ❌ Never |
| Chat History | - | ❌ Never |
| Metadata | - | ❌ Never |

---

## 2. KEY DERIVATION

### 2.1 Security Rules

```
❌ NEVER expose wallet private key to chat system
❌ NEVER reuse wallet private key for encryption
❌ NEVER store wallet private key outside KeyService

✅ Use wallet signature to derive separate chat keys
✅ Chat keys are deterministic (reproducible)
✅ Same wallet always produces same chat keys
```

### 2.2 Derivation Process

```
┌─────────────────────────────────────────────────────────────┐
│                 KEY DERIVATION FLOW                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  WALLET PRIVATE KEY (Never leaves KeyService)               │
│           │                                                  │
│           ▼                                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  Sign Message: "MUMBLECHAT_KEY_DERIVATION_V1"       │    │
│  │  Using: EIP-191 Personal Sign                        │    │
│  └─────────────────────────────────────────────────────┘    │
│           │                                                  │
│           ▼                                                  │
│  signature = 0x1a2b3c...def (65 bytes)                      │
│           │                                                  │
│           ▼                                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │  seed = keccak256(signature)                         │    │
│  └─────────────────────────────────────────────────────┘    │
│           │                                                  │
│           ▼                                                  │
│  seed = 0xabcd...1234 (32 bytes)                            │
│           │                                                  │
│           ▼                                                  │
│  ┌─────────────────────────────────────────────────────┐    │
│  │                    HKDF-SHA256                       │    │
│  │  ┌─────────────────────────────────────────────┐    │    │
│  │  │ Input: seed                                  │    │    │
│  │  │ Salt: "mumblechat"                          │    │    │
│  │  │ Info: "identity" / "session" / "backup"     │    │    │
│  │  │ Length: 32 bytes each                       │    │    │
│  │  └─────────────────────────────────────────────┘    │    │
│  └─────────────────────────────────────────────────────┘    │
│           │                                                  │
│     ┌─────┴─────────────────┬─────────────────┐             │
│     ▼                       ▼                  ▼             │
│  IDENTITY KEY          SESSION KEY         BACKUP KEY       │
│  (Ed25519)             (X25519)            (AES-256)        │
│  - Sign messages       - Key exchange      - Encrypt backup │
│  - P2P identity        - E2E encryption    - Local storage  │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 2.3 Key Types

| Key | Algorithm | Purpose | Derivation Info |
|-----|-----------|---------|-----------------|
| Identity Private | Ed25519 | Sign messages, P2P identity | "identity" |
| Identity Public | Ed25519 | Registered on-chain | From identity private |
| Session Private | X25519 | Decrypt incoming | "session" |
| Session Public | X25519 | Share with contacts | From session private |
| Backup Key | AES-256 | Encrypt backup files | "backup" |

### 2.4 Code Implementation

```kotlin
// ChatKeyManager.kt
class ChatKeyManager @Inject constructor(
    private val keyService: KeyService
) {
    companion object {
        const val DERIVATION_MESSAGE = "MUMBLECHAT_KEY_DERIVATION_V1"
        const val HKDF_SALT = "mumblechat"
    }
    
    data class ChatKeyPair(
        val identityPrivate: ByteArray,  // Ed25519 private (32 bytes)
        val identityPublic: ByteArray,   // Ed25519 public (32 bytes)
        val sessionPrivate: ByteArray,   // X25519 private (32 bytes)
        val sessionPublic: ByteArray,    // X25519 public (32 bytes)
        val backupKey: ByteArray         // AES-256 key (32 bytes)
    )
    
    suspend fun deriveChatKeys(wallet: Wallet): ChatKeyPair {
        // Step 1: Sign the derivation message
        val message = DERIVATION_MESSAGE.toByteArray(Charsets.UTF_8)
        val signatureResult = keyService.signPersonalMessage(wallet, message)
        
        // Step 2: Hash signature to get seed
        val seed = Hash.keccak256(signatureResult.signature)
        
        // Step 3: Derive keys using HKDF
        val identityPrivate = hkdfExpand(seed, "identity", 32)
        val sessionPrivate = hkdfExpand(seed, "session", 32)
        val backupKey = hkdfExpand(seed, "backup", 32)
        
        // Step 4: Generate public keys
        val identityPublic = Ed25519.publicKeyFromPrivate(identityPrivate)
        val sessionPublic = X25519.publicKeyFromPrivate(sessionPrivate)
        
        return ChatKeyPair(
            identityPrivate = identityPrivate,
            identityPublic = identityPublic,
            sessionPrivate = sessionPrivate,
            sessionPublic = sessionPublic,
            backupKey = backupKey
        )
    }
    
    private fun hkdfExpand(seed: ByteArray, info: String, length: Int): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(seed, HKDF_SALT.toByteArray(), info.toByteArray()))
        val output = ByteArray(length)
        hkdf.generateBytes(output, 0, length)
        return output
    }
}
```

---

## 3. MESSAGE ENCRYPTION

### 3.1 Encryption Algorithm

| Component | Algorithm |
|-----------|-----------|
| Key Exchange | X25519 ECDH |
| Symmetric Cipher | AES-256-GCM |
| Message Auth | Built into GCM |
| Nonce | 12 bytes random |

### 3.2 Encryption Flow (1:1 DM)

```
┌─────────────────────────────────────────────────────────────┐
│              SENDER → RECIPIENT ENCRYPTION                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  SENDER                              RECIPIENT               │
│  ───────                             ─────────               │
│  Session Private Key ─────┐    ┌───── Session Public Key    │
│                           │    │                             │
│                           ▼    ▼                             │
│                    ┌──────────────────┐                      │
│                    │  X25519 ECDH     │                      │
│                    │  Key Exchange    │                      │
│                    └────────┬─────────┘                      │
│                             │                                │
│                             ▼                                │
│                    Shared Secret (32 bytes)                  │
│                             │                                │
│                             ▼                                │
│                    ┌──────────────────┐                      │
│                    │   HKDF-SHA256    │                      │
│                    └────────┬─────────┘                      │
│                             │                                │
│                             ▼                                │
│                    Message Key (32 bytes)                    │
│                             │                                │
│  Plaintext Message ─────────┤                                │
│  Random Nonce (12b) ────────┤                                │
│                             ▼                                │
│                    ┌──────────────────┐                      │
│                    │   AES-256-GCM    │                      │
│                    │   Encrypt        │                      │
│                    └────────┬─────────┘                      │
│                             │                                │
│                             ▼                                │
│                    Ciphertext + Auth Tag                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 3.3 Message Envelope Structure

```json
{
  "version": 1,
  "type": "dm",
  "sender": "0x1234...abcd",
  "recipient": "0x5678...efgh",
  "timestamp": 1703836800000,
  "nonce": "base64_encoded_12_bytes",
  "ciphertext": "base64_encoded_encrypted_message",
  "signature": "base64_encoded_ed25519_signature"
}
```

### 3.4 Code Implementation

```kotlin
// MessageEncryption.kt
class MessageEncryption @Inject constructor() {
    
    data class EncryptedMessage(
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val authTag: ByteArray
    )
    
    fun encryptMessage(
        plaintext: String,
        senderSessionPrivate: ByteArray,
        recipientSessionPublic: ByteArray
    ): EncryptedMessage {
        // Step 1: X25519 key exchange
        val sharedSecret = X25519.computeSharedSecret(
            senderSessionPrivate, 
            recipientSessionPublic
        )
        
        // Step 2: Derive message key
        val messageKey = hkdfExpand(sharedSecret, "message", 32)
        
        // Step 3: Generate random nonce
        val nonce = ByteArray(12)
        SecureRandom().nextBytes(nonce)
        
        // Step 4: AES-256-GCM encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(128, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        
        val plaintextBytes = plaintext.toByteArray(Charsets.UTF_8)
        val ciphertextWithTag = cipher.doFinal(plaintextBytes)
        
        // GCM appends 16-byte auth tag to ciphertext
        val ciphertext = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - 16)
        val authTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - 16, ciphertextWithTag.size)
        
        return EncryptedMessage(nonce, ciphertext, authTag)
    }
    
    fun decryptMessage(
        encrypted: EncryptedMessage,
        recipientSessionPrivate: ByteArray,
        senderSessionPublic: ByteArray
    ): String {
        // Step 1: X25519 key exchange (same shared secret)
        val sharedSecret = X25519.computeSharedSecret(
            recipientSessionPrivate, 
            senderSessionPublic
        )
        
        // Step 2: Derive message key
        val messageKey = hkdfExpand(sharedSecret, "message", 32)
        
        // Step 3: AES-256-GCM decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(messageKey, "AES")
        val gcmSpec = GCMParameterSpec(128, encrypted.nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        
        val ciphertextWithTag = encrypted.ciphertext + encrypted.authTag
        val plaintextBytes = cipher.doFinal(ciphertextWithTag)
        
        return String(plaintextBytes, Charsets.UTF_8)
    }
}
```

---

## 4. GROUP CHAT ENCRYPTION

### 4.1 Group Key Management

```
┌─────────────────────────────────────────────────────────────┐
│                 GROUP KEY STRUCTURE                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  GROUP                                                       │
│  ├── Group ID: keccak256(creator + timestamp + random)      │
│  ├── Group Key: Random 32 bytes (rotated on member change)  │
│  └── Members: [wallet1, wallet2, wallet3, ...]              │
│                                                              │
│  KEY DISTRIBUTION                                            │
│  ─────────────────                                           │
│  Each member receives Group Key encrypted with their         │
│  session public key:                                         │
│                                                              │
│  EncryptedGroupKey[member1] = Encrypt(GroupKey, member1_pub)│
│  EncryptedGroupKey[member2] = Encrypt(GroupKey, member2_pub)│
│  EncryptedGroupKey[member3] = Encrypt(GroupKey, member3_pub)│
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 Group Message Encryption

```
┌─────────────────────────────────────────────────────────────┐
│              GROUP MESSAGE ENCRYPTION                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Sender writes message                                       │
│           │                                                  │
│           ▼                                                  │
│  Encrypt with Group Key (AES-256-GCM)                       │
│           │                                                  │
│           ▼                                                  │
│  Sign with Sender's Identity Key (Ed25519)                  │
│           │                                                  │
│           ▼                                                  │
│  Broadcast to all members via P2P / Relay                   │
│           │                                                  │
│           ▼                                                  │
│  Each member decrypts with their copy of Group Key          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 Key Rotation

Group key is rotated when:
- Member is removed
- Member leaves
- Admin requests rotation
- Every 30 days (optional)

```kotlin
// GroupKeyManager.kt
class GroupKeyManager @Inject constructor(
    private val messageEncryption: MessageEncryption
) {
    fun createGroup(
        creator: ChatKeyPair,
        memberPublicKeys: List<ByteArray>
    ): GroupKeyBundle {
        // Generate random group key
        val groupKey = ByteArray(32)
        SecureRandom().nextBytes(groupKey)
        
        // Generate group ID
        val groupIdInput = creator.identityPublic + 
            System.currentTimeMillis().toString().toByteArray() +
            groupKey
        val groupId = Hash.keccak256(groupIdInput)
        
        // Encrypt group key for each member
        val encryptedKeys = memberPublicKeys.map { memberPub ->
            messageEncryption.encryptForRecipient(
                groupKey,
                creator.sessionPrivate,
                memberPub
            )
        }
        
        return GroupKeyBundle(
            groupId = groupId,
            groupKey = groupKey,
            encryptedKeysForMembers = encryptedKeys
        )
    }
    
    fun rotateGroupKey(
        group: Group,
        adminKeys: ChatKeyPair,
        currentMemberPublicKeys: List<ByteArray>
    ): GroupKeyBundle {
        // Generate new group key
        val newGroupKey = ByteArray(32)
        SecureRandom().nextBytes(newGroupKey)
        
        // Re-encrypt for all current members
        val encryptedKeys = currentMemberPublicKeys.map { memberPub ->
            messageEncryption.encryptForRecipient(
                newGroupKey,
                adminKeys.sessionPrivate,
                memberPub
            )
        }
        
        return GroupKeyBundle(
            groupId = group.id,
            groupKey = newGroupKey,
            encryptedKeysForMembers = encryptedKeys,
            version = group.keyVersion + 1
        )
    }
}
```

---

## 5. FORWARD SECRECY (FUTURE)

For enhanced security, implement Double Ratchet protocol:

```
┌─────────────────────────────────────────────────────────────┐
│              DOUBLE RATCHET (Future Enhancement)             │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Current: Static Session Keys                               │
│  ─────────────────────────────                              │
│  - Same key for all messages in conversation                │
│  - If key compromised, all messages exposed                 │
│                                                              │
│  Future: Double Ratchet                                     │
│  ─────────────────────────                                  │
│  - New key for every message                                │
│  - Forward secrecy: past messages safe                      │
│  - Post-compromise security: future messages safe           │
│                                                              │
│  Implementation: Signal Protocol adaptation                 │
│  - Diffie-Hellman ratchet                                   │
│  - Symmetric-key ratchet                                    │
│  - Header encryption                                        │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 6. SECURITY CONSIDERATIONS

### 6.1 Threat Model

| Threat | Mitigation |
|--------|------------|
| Key extraction from device | Keys stored in Android Keystore |
| Man-in-the-middle | Public keys verified on-chain |
| Replay attacks | Timestamp + nonce in messages |
| Metadata leaks | Minimal envelope, no content hints |
| Relay snooping | Relays only see encrypted blobs |
| Backup theft | Backup encrypted with wallet-derived key |

### 6.2 Key Storage

```kotlin
// Keys are stored encrypted in Android Keystore
// ChatKeyStore.kt
class ChatKeyStore @Inject constructor(
    private val context: Context
) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "mumblechat_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun storeChatKeys(walletAddress: String, keys: ChatKeyPair) {
        encryptedPrefs.edit()
            .putString("${walletAddress}_identity_private", keys.identityPrivate.toHex())
            .putString("${walletAddress}_session_private", keys.sessionPrivate.toHex())
            .putString("${walletAddress}_backup_key", keys.backupKey.toHex())
            .apply()
    }
    
    fun getChatKeys(walletAddress: String): ChatKeyPair? {
        // Retrieve and reconstruct keys
    }
}
```
