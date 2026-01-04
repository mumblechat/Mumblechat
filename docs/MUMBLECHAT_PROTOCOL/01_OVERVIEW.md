# MumbleChat Protocol - Overview

## Version 3.2 | January 2026

---

## 1. PROJECT SUMMARY

MumbleChat is a **fully decentralized, wallet-native chat protocol** built for the Ramestta blockchain ecosystem. It replaces centralized messaging with a peer-to-peer system where:

- **Wallet Address = User Identity**
- **Every Device = Network Node**
- **No Central Servers**
- **End-to-End Encrypted**
- **Tier-Based Relay Rewards (MCT Token)**

---

## 2. CORE PRINCIPLES

| Principle | Description |
|-----------|-------------|
| **Decentralization** | No single point of failure, no central authority |
| **Privacy** | E2E encryption, no metadata leaks |
| **Self-Sovereignty** | Users own their identity and data |
| **Sustainable Incentives** | Relay nodes earn MCT with halving + fee pool |
| **GB-Scale Tiers** | 1GB/2GB/4GB/8GB+ storage with 10-40% daily pool share |
| **Compatibility** | Works across Android, Web, Browser Extension |

---

## 3. SYSTEM ARCHITECTURE

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        MUMBLECHAT PROTOCOL                               │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      APPLICATION LAYER                            │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │  RamaPay    │  │   Web App   │  │  Browser    │              │   │
│  │  │  Android    │  │    (PWA)    │  │  Extension  │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      PROTOCOL LAYER                               │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │   1:1 DM    │  │ Group Chat  │  │   Backup    │              │   │
│  │  │   System    │  │   System    │  │   System    │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      NETWORK LAYER                                │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │  P2P DHT    │  │   Relay     │  │   Message   │              │   │
│  │  │  (Kademlia) │  │   Nodes     │  │   Routing   │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                      CRYPTO LAYER                                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│  │  │   Key       │  │    E2E      │  │   Backup    │              │   │
│  │  │ Derivation  │  │ Encryption  │  │ Encryption  │              │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                    │                                     │
│  ┌──────────────────────────────────────────────────────────────────┐   │
│  │                    BLOCKCHAIN LAYER (Ramestta)                    │   │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │   │
│  │  │ MumbleChat      │  │    MCT Token    │  │  RelayStaking   │  │   │
│  │  │ Registry        │  │    (Rewards)    │  │  Contract       │  │   │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │   │
│  └──────────────────────────────────────────────────────────────────┘   │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 4. KEY FEATURES

### 4.1 Direct Messaging (1:1)
- Wallet-to-wallet encrypted chat
- Real-time P2P delivery when both online
- Relay delivery when recipient offline

### 4.2 Group Chat
- Create groups with multiple wallets
- Admin controls (add/remove members)
- Shared group encryption key
- Message sync across all members

### 4.3 Backup System
- Encrypted local backup
- Auto-discovery on reinstall
- Cloud export (Google Drive, etc.)
- Cross-device restore

### 4.4 Relay Nodes
- Any user can become a relay
- Earn MCT for message delivery
- Mobile-friendly (battery-aware)
- Staking requirements

---

## 5. INTEGRATION WITH RAMAPAY

**IMPORTANT:** MumbleChat is built as a **separate module** that integrates with RamaPay without modifying the core wallet functionality.

```
RamaPay Wallet (UNCHANGED)
├── Wallet Management      ← No changes
├── Transaction System     ← No changes
├── Token Management       ← No changes
├── DApp Browser          ← No changes
└── Chat Tab              ← NEW: MumbleChat Module
    ├── Reads wallet address (read-only)
    ├── Requests message signing (via KeyService)
    └── Independent storage (separate database)
```

### Integration Points
| RamaPay Component | MumbleChat Usage | Modification |
|-------------------|------------------|--------------|
| KeyService | Sign messages for key derivation | None (read-only) |
| Wallet Entity | Get current wallet address | None (read-only) |
| HomeActivity | Host ChatFragment | Minimal |
| Bottom Navigation | Already has Chat tab | None |

---

## 6. BLOCKCHAIN DETAILS

### Ramestta Mainnet
- **Chain ID:** 1370
- **RPC:** https://blockchain.ramestta.com
- **Explorer:** https://ramascan.com
- **Native Token:** RAMA

### Smart Contracts (DEPLOYED ✅)
| Contract | Purpose | Proxy Address |
|----------|---------|---------------|
| MumbleChatRegistry V3.2 | Identity + Relay + GB-Scale Tier System + Daily Pool | `0x4f8D4955F370881B05b68D2344345E749d8632e3` |
| MCTToken V3 | Reward token + Fee Pool + Governance | `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE` |

> **Note:** Contracts are UUPS upgradeable proxies. RelayStaking functionality is built into MCTToken V3.

---

## 7. TOKEN: MCT (MumbleChat Token) V3

| Property | Value |
|----------|-------|
| Name | MumbleChat Token |
| Symbol | MCT |
| Decimals | 18 |
| Initial Supply | 1,000 MCT |
| Max Supply | 1,000,000 MCT (governance changeable) |
| Chain | Ramestta (1370) |

### Sustainable Tokenomics

```
┌─────────────────────────────────────────────────────────────────┐
│                    MCT TOKEN ECONOMICS V3                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  INITIAL SUPPLY:     1,000 MCT (minted at deploy)               │
│  MAX SUPPLY:         1,000,000 MCT (can increase via 90% vote)  │
│                                                                  │
│  ═══════════════════════════════════════════════════════════    │
│                                                                  │
│  RELAY REWARDS (Minting Phase):                                 │
│  ─────────────────────────────                                  │
│  • Base Reward: 0.001 MCT per 1,000 messages relayed            │
│  • Daily Cap: 100 MCT max minted per day                        │
│  • Halving: Every 100,000 MCT minted, reward halves             │
│                                                                  │
│  ═══════════════════════════════════════════════════════════    │
│                                                                  │
│  TRANSFER FEE (Post-Minting Era):                               │
│  ────────────────────────────────                               │
│  • 0.1% fee on all transfers                                    │
│  • Fees accumulate in fee pool                                  │
│  • Relay nodes claim from fee pool based on TIER                │
│                                                                  │
│  ═══════════════════════════════════════════════════════════    │
│                                                                  │
│  GOVERNANCE:                                                    │
│  ───────────                                                    │
│  • 90% relay node vote can change max supply                    │
│  • 7-day voting period                                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Tier System (Fee Pool Distribution Only)

| Tier | Daily Uptime | Storage | Pool Share | Fee Pool Multiplier |
|------|--------------|---------|------------|---------------------|
| 🥉 Bronze | 4+ hours | 1 GB | 10% | 1.0x |
| 🥈 Silver | 8+ hours | 2 GB | 20% | 1.5x |
| 🥇 Gold | 12+ hours | 4 GB | 30% | 2.0x |
| 💎 Platinum | 16+ hours | 8+ GB | 40% | 3.0x |

> **Note:** Minting rewards are always 1x (no tier bonus). Tier bonuses ONLY apply to fee pool distribution to keep max supply controlled.

---

## 8. DOCUMENT STRUCTURE

This documentation is split into multiple parts:

| Document | Content |
|----------|---------|
| **01_OVERVIEW.md** | This file - project summary |
| **02_IDENTITY_AND_CRYPTO.md** | Key derivation, encryption |
| **03_MESSAGING_PROTOCOL.md** | P2P, DM, Group chat |
| **04_RELAY_AND_REWARDS.md** | Relay nodes, MCT rewards |
| **05_BACKUP_SYSTEM.md** | Backup and restore |
| **06_ANDROID_IMPLEMENTATION.md** | Code structure, classes |
| **07_SMART_CONTRACTS.md** | Solidity contracts |
| **08_API_REFERENCE.md** | Interface definitions |

---

## 9. IMPLEMENTATION TIMELINE

| Phase | Duration | Deliverables |
|-------|----------|--------------|
| Phase 1 | 2 weeks | Smart contracts, Key derivation |
| Phase 2 | 3 weeks | P2P network, 1:1 messaging |
| Phase 3 | 2 weeks | Group chat, Native UI |
| Phase 4 | 2 weeks | Relay nodes, MCT rewards |
| Phase 5 | 1 week | Backup system, Testing |

**Total: 10 weeks**

---

## 10. SUCCESS CRITERIA

- [ ] Wallet import restores chat identity
- [ ] New wallet creates new identity
- [ ] Messages encrypted end-to-end
- [ ] Offline messages delivered via relay
- [ ] Group chat works with 10+ members
- [ ] Backup restores all messages
- [ ] Relay nodes earn MCT
- [ ] No impact on wallet functionality
- [ ] Battery usage < 5% for light nodes
- [ ] Works without internet (local P2P)
