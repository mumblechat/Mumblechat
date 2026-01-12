# MumbleChat Security Documentation

**Version:** 1.2.0  
**Date:** January 12, 2026  
**Status:** ✅ Production Security Implemented  

---

## Overview

MumbleChat implements multiple layers of security to ensure private and secure messaging.

## Technology Security Summary

| Component | Security Technology | Standard |
|-----------|---------------------|----------|
| Key Exchange | ECDH | P-256 (NIST) |
| Encryption | AES-GCM | 256-bit (FIPS 197) |
| Transport | TLS 1.3 | RFC 8446 |
| Signatures | ECDSA | secp256k1 (Ethereum) |
| Hashing | SHA-256 | FIPS 180-4 |
| Random | CSPRNG | Web Crypto API |

## Encryption

### End-to-End Encryption (E2EE)

**Algorithm:** ECDH Key Exchange + AES-256-GCM

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        E2EE Implementation                                  │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────┐                                          ┌─────────────┐
│   User A    │                                          │   User B    │
│             │                                          │             │
│ Private Key ├──┐                              ┌────────┤ Private Key │
│ (ECDH P-256)│  │                              │        │ (ECDH P-256)│
│             │  │                              │        │             │
│ Public Key  ├──┼──────────► Exchange ◄───────┼────────┤ Public Key  │
│             │  │                              │        │             │
└─────────────┘  │                              │        └─────────────┘
                 │                              │
                 ▼                              ▼
         ┌──────────────┐              ┌──────────────┐
         │ Shared Secret│              │ Shared Secret│
         │  (256 bits)  │              │  (256 bits)  │
         └──────┬───────┘              └──────┬───────┘
                │                              │
                ▼                              ▼
         ┌──────────────┐              ┌──────────────┐
         │  AES-256-GCM │              │  AES-256-GCM │
         │  Encryption  │              │  Decryption  │
         └──────────────┘              └──────────────┘
```

### Encryption Parameters

| Parameter | Value | Purpose |
|-----------|-------|---------|
| ECDH Curve | P-256 (secp256r1) | Key exchange |
| Symmetric Algorithm | AES-GCM | Authenticated encryption |
| Key Length | 256 bits | Encryption key |
| IV Length | 12 bytes | Initialization vector |
| Salt Length | 16 bytes | Key derivation |

### Key Management

```javascript
// Key Pair Generation
crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,  // extractable
    ['deriveKey', 'deriveBits']
);

// Shared Secret Derivation
crypto.subtle.deriveBits(
    { name: 'ECDH', public: contactPublicKey },
    myPrivateKey,
    256  // bits
);

// AES Key from Shared Secret
crypto.subtle.importKey(
    'raw',
    sharedBits,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
);
```

### Message Encryption Flow

1. **Key Exchange:** Public keys exchanged when users first connect
2. **Derivation:** ECDH derives shared secret from public + private keys
3. **Encryption:** AES-256-GCM with random IV for each message
4. **Transmission:** IV prepended to ciphertext, sent as base64

```javascript
// Encryption
const iv = crypto.getRandomValues(new Uint8Array(12));
const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    sharedKey,
    plaintext
);
// Output: base64(iv + ciphertext)

// Decryption
const combined = base64ToArrayBuffer(encryptedData);
const iv = combined.slice(0, 12);
const ciphertext = combined.slice(12);
const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv },
    sharedKey,
    ciphertext
);
```

### Message Signing (Optional)

- **Algorithm:** SHA-256 hash + wallet signature
- **Purpose:** Prove message authenticity (sender verification)
- **Implementation:** Ethers.js `signer.signMessage()`

```javascript
// Sign message hash
const hash = await crypto.subtle.digest('SHA-256', messageData);
const signature = await wallet.signer.signMessage(hashHex);

// Verify signature
const recoveredAddress = ethers.verifyMessage(hashHex, signature);
```

## Key Storage

### Local Storage Security

Keys are stored in browser's localStorage (per-origin):

```javascript
// Storage Keys
CRYPTO_KEYS: 'mumblechat_crypto_keys'      // User's ECDH key pair
PUBLIC_KEYS: 'mumblechat_public_keys'      // Contacts' public keys
```

**Current Implementation:**
- Keys stored as JSON in localStorage
- Per-origin isolation (browser security)
- Keys persist across sessions

**Enhanced Security (Optional):**
- PBKDF2 key derivation from wallet address
- Encrypted key storage
- 100,000 iterations for key stretching

```javascript
// Key Derivation for Storage Encryption
crypto.subtle.deriveKey(
    {
        name: 'PBKDF2',
        salt: 'mumblechat_salt_v1',
        iterations: 100000,
        hash: 'SHA-256'
    },
    keyMaterial,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
);
```

## Transport Security

### WebSocket Security

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Transport Security                                   │
└─────────────────────────────────────────────────────────────────────────────┘

Client ←──── WSS (TLS 1.3) ────► Hub ←──── WSS (TLS 1.3) ────► Relay Node
         │                            │
         │ • Certificate validation   │ • Tunnel authentication
         │ • Encrypted transport      │ • Node ID verification
         │ • E2EE payload             │ • Heartbeat monitoring
         │                            │
```

### Connection Authentication

1. **User → Relay:**
```javascript
{
    type: 'authenticate',
    walletAddress: '0x...',    // Ethereum address
    displayName: 'Alice',
    publicKey: 'base64...',    // E2EE public key
    timestamp: Date.now()
}
```

2. **Node → Hub:**
```javascript
{
    type: 'NODE_AUTH',
    walletAddress: '0x...',    // Node operator wallet
    nodeId: 'node-abc123',
    signature: 'auto-node'
}
```

## Relay Node Security

### Message Handling

| Security Feature | Implementation |
|------------------|----------------|
| No message reading | Messages are E2E encrypted; nodes can't read content |
| Message expiry | 7-day automatic deletion |
| Redundant storage | Messages stored on 2-3 nodes |
| Delivery confirmation | ACK messages for delivery tracking |

### Delivery Confirmation Security

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    Delivery Confirmation Flow                               │
└─────────────────────────────────────────────────────────────────────────────┘

Sender                  Hub                    Recipient
  │                      │                         │
  │──── Send Message ───►│                         │
  │                      │                         │
  │◄── relay_ack (sent)──│                         │
  │                      │                         │
  │    [If Offline]      │                         │
  │◄─ message_queued ────│                         │
  │    (status: pending) │                         │
  │                      │                         │
  │    [When Delivered]  │                         │
  │                      │──── Deliver Message ───►│
  │◄─ DELIVERY_RECEIPT ──│◄───── ACK ─────────────│
  │    (status: delivered)                         │
  │                      │                         │
  │    [When Read]       │                         │
  │◄── read_receipt ─────│◄─── User opened chat ──│
  │    (status: read)    │                         │
```

### Status Message Types

| Message Type | Direction | Trigger |
|--------------|-----------|---------|
| `relay_ack` | Hub → Sender | Message received by relay |
| `message_queued` | Hub → Sender | Recipient offline |
| `DELIVERY_RECEIPT` | Hub → Sender | Message delivered to recipient |
| `read_receipt` | Recipient → Sender | Recipient opened conversation |

### Node Isolation

- Each relay node runs independently
- No shared state except via hub
- Node failure doesn't affect other nodes
- Automatic reconnection to hub

### Offline Message Queue

```javascript
// Offline message structure
{
    id: 'offline_...',
    from: '0x...',
    to: '0x...',
    payload: encrypted_content,
    timestamp: Date.now(),
    expiresAt: Date.now() + (7 * 24 * 60 * 60 * 1000),  // 7 days
    storedOnNodes: ['node1', 'node2', 'node3'],
    delivered: false
}
```

## Blockchain Security

### Smart Contract Security

**Registry Contract Features:**
- User identity tied to wallet address
- Public key updates require wallet signature
- On-chain verification of identity

**Functions:**
```solidity
function register(bytes32 publicKeyX, string displayName)
function updateDisplayName(string newDisplayName)
function updateIdentity(bytes32 newPubKeyX, bytes32 newPubKeyY, uint8 keyVersion)
```

### Network Security

- **Chain ID:** 1370 (Ramestta Mainnet)
- **Automatic Network Switch:** Prompts user to add/switch to Ramestta
- **Transaction Signing:** All on-chain actions require wallet signature

## Client-Side Security

### Input Validation

```javascript
// Message length limit (anti-spam)
const MAX_MESSAGE_LENGTH = 1024;

if (text.length > MAX_MESSAGE_LENGTH) {
    throw new Error(`Maximum ${MAX_MESSAGE_LENGTH} characters allowed.`);
}
```

### XSS Prevention

```javascript
// HTML escaping for display
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}
```

### Address Validation

```javascript
// Normalize addresses
recipientAddress = recipientAddress.toLowerCase();

// Validate Ethereum address format
if (!/^0x[a-fA-F0-9]{40}$/.test(address)) {
    throw new Error('Invalid address format');
}
```

## Security Best Practices

### For Users

1. ✅ Use in wallet's in-app browser on mobile
2. ✅ Verify you're on mumblechat.com (HTTPS)
3. ✅ Don't share encryption keys
4. ✅ Use strong wallet password/biometrics
5. ✅ Review transaction details before signing

### For Developers

1. ✅ Always use WSS (not WS) in production
2. ✅ Validate all input on both client and server
3. ✅ Use Web Crypto API (not custom crypto)
4. ✅ Implement rate limiting on relay nodes
5. ✅ Monitor for unusual activity patterns
6. ✅ Keep dependencies updated

## Known Limitations

| Limitation | Mitigation |
|------------|------------|
| localStorage accessible to page scripts | Per-origin isolation |
| No forward secrecy (same key per contact) | Key rotation (planned) |
| Metadata visible to relay (from/to addresses) | Addresses are pseudonymous |
| No server-side message verification | E2EE makes this unnecessary |

## Security Roadmap

- [ ] **Key Rotation:** Periodic ECDH key regeneration
- [ ] **Forward Secrecy:** Double Ratchet algorithm
- [ ] **Encrypted Storage:** Optional password-protected keys
- [ ] **Hardware Wallet Support:** Key derivation from hardware
- [ ] **Audit:** Third-party security audit

---

*Last Updated: January 2026*
