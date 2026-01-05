# MumbleChat Protocol - Security Threat Model

**Version:** 1.0.0  
**Date:** January 2026  
**Status:** Active Development  

---

## 1. Executive Summary

This document identifies security threats, attack vectors, and mitigations for the MumbleChat Protocol. It addresses the concerns raised in the independent security review.

---

## 2. Trust Model

### 2.1 What We Trust

| Component | Trust Level | Reason |
|-----------|-------------|--------|
| Ramestta Blockchain | ✅ Full | Decentralized, consensus-based |
| User's Private Key | ✅ Full | User-controlled, never leaves device |
| AES-256-GCM | ✅ Full | Industry-standard, battle-tested |
| X25519 | ✅ Full | Modern, safe curve |
| User's Device | ⚠️ Partial | Trusted execution, but could be compromised |

### 2.2 What We Don't Trust

| Component | Reason |
|-----------|--------|
| Network | Assumed adversarial (MITM possible) |
| Relay Nodes | Only trusted for routing, not content |
| Other Peers | May be malicious |
| STUN Servers | Only used for IP discovery, no data |

---

## 3. Attack Vectors & Mitigations

### 3.1 Replay Attacks

**Threat:** Attacker captures encrypted message and resends it.

**Impact:** Duplicate messages, confusion, potential exploitation.

**Mitigation:** ✅ IMPLEMENTED
```kotlin
// AEAD Associated Data binds ciphertext to context
aad = senderNodeId || recipientNodeId || messageId
cipher.updateAAD(aad)
```

Each message has unique `messageId`, so replayed messages will fail decryption.

---

### 3.2 Man-in-the-Middle (MITM)

**Threat:** Attacker intercepts key exchange between Alice and Bob.

**Impact:** Attacker can decrypt all messages.

**Mitigation:** ✅ IMPLEMENTED
- Public keys registered on blockchain
- Challenge-response verification
- Out-of-band verification via QR codes

```
Alice verifies Bob's key:
1. Query blockchain: registry.identities(Bob.wallet)
2. Get registered public key
3. Challenge: Send random nonce
4. Bob signs nonce with private key
5. Verify signature matches registered key
```

---

### 3.3 Self-Relay Farming

**Threat:** Attacker creates fake sender/recipient to farm MCT rewards.

**Impact:** Draining reward pool, inflation of MCT.

**Mitigations:** ✅ TO IMPLEMENT
1. **Relay ≠ Sender/Recipient:** Smart contract rejects if relay wallet equals sender or recipient
2. **Daily Relay Cap:** Maximum rewards per wallet per day
3. **Minimum Stake:** Relays must stake MCT to participate
4. **Rate Limiting:** Max messages per sender-recipient pair per hour

```solidity
// Anti-abuse checks in smart contract
require(relay != sender, "Relay cannot be sender");
require(relay != recipient, "Relay cannot be recipient");
require(relayDailyCount[relay] < MAX_DAILY_RELAYS, "Daily cap exceeded");
require(pairCount[sender][recipient] < MAX_PAIR_HOURLY, "Rate limit");
```

---

### 3.4 Message Flooding / DoS

**Threat:** Attacker floods network with messages to overwhelm nodes.

**Impact:** Network congestion, battery drain, storage exhaustion.

**Mitigations:**
1. **TTL:** Messages have max hop count (default 16)
2. **Message ID Bloom Filter:** Nodes track seen messages, reject duplicates
3. **Rate Limiting:** Max messages per source per minute
4. **Connection Limits:** Max 20 active peer connections
5. **Proof-of-Work (optional):** Small PoW for store-forward requests

---

### 3.5 Storage Spam (Store-Forward)

**Threat:** Attacker floods relay storage with messages for fake recipients.

**Impact:** Storage exhaustion on relay nodes.

**Mitigations:**
1. **MCT Bond:** Sender must hold minimum MCT balance
2. **Per-Sender Quota:** Max stored messages per sender
3. **TTL:** Messages expire after 24 hours
4. **Size Limit:** Max 64KB per message
5. **Recipient Verification:** Recipient must be registered on blockchain

```kotlin
// Before storing message for offline delivery
require(senderMctBalance >= MIN_MCT_FOR_STORAGE)
require(storedMessagesFromSender < MAX_STORED_PER_SENDER)
require(recipientIsRegistered)
```

---

### 3.6 Sybil Attack

**Threat:** Attacker creates many fake nodes to control network routing.

**Impact:** Message interception, network partitioning.

**Mitigations:**
1. **Wallet-based Identity:** Each node tied to Ramestta wallet
2. **On-chain Registration:** Creating identities costs gas
3. **Reputation System:** Nodes track peer reliability
4. **Diverse Peer Selection:** Connect to peers from different IP ranges

---

### 3.7 Eclipse Attack

**Threat:** Attacker surrounds honest node with malicious peers.

**Impact:** Isolated node receives manipulated view of network.

**Mitigations:**
1. **Diverse Bootstrapping:** Multiple methods (cache, LAN, blockchain, QR)
2. **Random Peer Selection:** Don't rely on closest nodes only
3. **Periodic Re-bootstrap:** Refresh peer list periodically
4. **Anchor Nodes:** Connect to known-stable nodes

---

### 3.8 Key Compromise

**Threat:** Attacker obtains user's private key.

**Impact:** Full impersonation, message decryption.

**Mitigations:**
1. **Key Rotation:** Support for `identityKeyVersion`
2. **Hardware-backed Storage:** Use Android Keystore
3. **Key Revocation:** On-chain mechanism to invalidate old keys
4. **Forward Secrecy:** (Future) Double Ratchet protocol

---

## 4. Platform-Specific Threats

### 4.1 Android

| Threat | Mitigation |
|--------|------------|
| Background kill | Foreground service for relay mode |
| Battery drain | Optimize DHT refresh interval |
| Storage access | Encrypt local database |

### 4.2 iOS

| Threat | Mitigation |
|--------|------------|
| Background restrictions | iOS = client only, not relay |
| Push notifications | Store-forward when app wakes |
| Network limits | Use opportunistic connections |

**IMPORTANT:** iOS devices CANNOT reliably serve as relay nodes due to OS restrictions. Document this clearly to users.

---

## 5. Incentive Abuse Prevention

### 5.1 Anti-Sybil for Relays

```
RELAY REGISTRATION REQUIREMENTS:
1. Minimum 1000 MCT staked
2. Unique device fingerprint (not enforced, but tracked)
3. Progressive reward unlocking (7 days)
4. Slashing for proven misbehavior
```

### 5.2 Reward Distribution Safety

```
ON-CHAIN CHECKS:
□ relay ≠ sender
□ relay ≠ recipient  
□ messageId not seen before
□ timestamp within acceptable range
□ sender signature valid
□ recipient signature valid
□ relay below daily cap
□ message pair below rate limit
```

---

## 6. Privacy Considerations

### 6.1 Metadata Leakage

| Data | Visibility | Mitigation |
|------|------------|------------|
| Message content | ❌ Encrypted | E2E encryption |
| Sender/Recipient | ⚠️ Visible to relays | Use multiple relays, onion routing (future) |
| Timing | ⚠️ Visible to relays | Random delays, cover traffic (future) |
| IP Address | ⚠️ Visible to peers | VPN support, Tor integration (future) |

### 6.2 Blockchain Privacy

- Identity registration is public (wallet → public key)
- Message content is NOT on-chain
- Relay receipts are on-chain (metadata visible)

**Future Enhancement:** Zero-knowledge proofs for relay receipts.

---

## 7. Security Checklist

### Before Mainnet Launch

- [x] AEAD binding for replay prevention
- [ ] Smart contract audit
- [ ] Penetration testing
- [ ] NAT traversal success rate testing (India)
- [ ] Incentive abuse simulation
- [ ] Key rotation implementation
- [ ] Rate limiting implementation
- [ ] iOS limitations documented
- [ ] Security advisory process

---

## 8. Incident Response

### 8.1 Key Compromise Response

1. User generates new keys via Settings
2. Old key marked revoked on-chain
3. Contacts notified of key change
4. Messages to old key rejected

### 8.2 Protocol Vulnerability Response

1. Identify affected versions
2. Prepare patched version
3. Force-update mechanism (blockchain flag)
4. Public disclosure after 30 days

---

## 9. Comparison with Established Protocols

| Security Property | MumbleChat | Signal | WhatsApp | Matrix |
|-------------------|------------|--------|----------|--------|
| E2E Encryption | ✅ | ✅ | ✅ | ✅ |
| Forward Secrecy | 🔄 Planned | ✅ | ✅ | ✅ |
| Open Source | ✅ | ✅ | ❌ | ✅ |
| Decentralized | ✅ | ❌ | ❌ | ⚠️ |
| Metadata Protection | ⚠️ | ⚠️ | ❌ | ⚠️ |
| No Phone Number | ✅ | ❌ | ❌ | ✅ |

---

## 10. Recommendations

### Immediate (Before Alpha)

1. Complete key rotation support
2. Add rate limiting to P2P transport
3. Document iOS limitations clearly
4. Test NAT success rate in India

### Short-term (Before Beta)

1. Smart contract security audit
2. Implement anti-abuse rules on-chain
3. Add message ID uniqueness enforcement
4. Penetration testing

### Long-term (V2)

1. Double Ratchet for forward secrecy
2. Onion routing for metadata protection
3. Zero-knowledge relay proofs
4. Multi-hop relay support

---

**Document Maintainer:** MumbleChat Protocol Team  
**Last Reviewed:** January 5, 2026  
**Next Review:** Before Beta Release
