# MumbleChat WebApp Architecture

**Version:** 1.2.0  
**Date:** January 12, 2026  
**Status:** ✅ Production Ready  

---

## Table of Contents

1. [Overview](#overview)
2. [Technology Stack](#technology-stack)
3. [System Architecture](#system-architecture)
4. [Components](#components)
5. [Data Flow](#data-flow)
6. [Message Delivery Status System](#message-delivery-status-system)
7. [Contact List Sorting](#contact-list-sorting)
8. [Configuration](#configuration)
9. [PWA Features](#pwa-features)
10. [Mobile Wallet Support](#mobile-wallet-support)
11. [Deployment](#deployment)
12. [Dependencies](#dependencies)

---

## Overview

MumbleChat is a decentralized, end-to-end encrypted messaging application built on the Ramestta blockchain. It uses a hybrid architecture combining on-chain identity management with off-chain message relay for real-time communication.

## Technology Stack

### Core Technologies

| Layer | Technology | Version | Purpose |
|-------|------------|---------|----------|
| **Blockchain** | Ramestta | Chain ID 1370 | Identity & token economics |
| **Encryption** | Web Crypto API | Browser Native | E2EE (ECDH + AES-256-GCM) |
| **Transport** | WebSocket (WSS) | RFC 6455 | Real-time messaging |
| **Web3** | Ethers.js | v6.x | Blockchain interaction |
| **Frontend** | Vanilla JS | ES6+ Modules | No framework overhead |
| **Backend** | Node.js | 18+ LTS | Hub & Relay servers |
| **TypeScript** | TypeScript | 5.x | Hub server type safety |
| **Process Mgmt** | PM2 | Latest | Production deployment |

### Cryptographic Primitives

| Component | Algorithm | Parameters | Standard |
|-----------|-----------|------------|----------|
| Key Exchange | ECDH | P-256 (secp256r1) | NIST SP 800-56A |
| Symmetric Encryption | AES-GCM | 256-bit key, 12-byte IV | FIPS 197, NIST SP 800-38D |
| Hashing | SHA-256 | 256-bit output | FIPS 180-4 |
| Signatures | ECDSA | secp256k1 (Ethereum) | SEC 2 |
| Message Auth | GCM Tag | 128-bit authentication | NIST SP 800-38D |
| Random Generation | CSPRNG | Web Crypto API | NIST SP 800-90A |

### Network Protocols

| Protocol | Port | Usage | Security |
|----------|------|-------|----------|
| WSS | 443 | Client ↔ Hub/Relay | TLS 1.3 |
| HTTPS | 443 | REST API, Static files | TLS 1.3 |
| TCP | 8080 | Hub internal | Internal network |
| TCP | 19371-19373 | Relay nodes (local) | Internal network |

### Frontend Technologies

| Technology | Purpose | Details |
|------------|---------|---------|
| **ES6 Modules** | Code organization | Native browser modules, no bundler |
| **Web Crypto API** | Encryption | ECDH, AES-GCM, SHA-256 |
| **WebSocket API** | Real-time messaging | Native browser WebSocket |
| **Service Worker** | PWA offline support | Cache-first strategy |
| **localStorage** | Client persistence | Messages, contacts, keys |
| **EIP-6963** | Wallet discovery | Multi-wallet provider support |
| **CSS Custom Properties** | Theming | Dark theme variables |

### Backend Technologies

| Technology | Purpose | Details |
|------------|---------|---------|
| **Express.js 4.x** | REST API | Health checks, stats, user lookup |
| **ws 8.x** | WebSocket server | Node and user connections |
| **uuid** | ID generation | Session IDs, message IDs |
| **dotenv** | Configuration | Environment variables |
| **cors** | Security | Cross-origin handling |
| **TypeScript 5.x** | Type safety | Hub server strict mode |

## System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           MumbleChat Architecture                           │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│   Web Client    │     │   Web Client    │     │   Mobile App    │
│  (Browser/PWA)  │     │  (Wallet dApp)  │     │   (Android)     │
└────────┬────────┘     └────────┬────────┘     └────────┬────────┘
         │                       │                       │
         │ WSS (E2EE)            │ WSS (E2EE)            │ WSS (E2EE)
         │                       │                       │
         ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                              HUB SERVER                                     │
│                        (hub.mumblechat.com)                                 │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ • User Registry (address → sessionId, nodeId)                       │   │
│  │ • Cross-Node Message Routing                                        │   │
│  │ • Offline Message Queue (7-day expiry)                              │   │
│  │ • Load Balancing                                                    │   │
│  │ • Node Health Monitoring                                            │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└────────┬─────────────────────────┬─────────────────────────┬───────────────┘
         │                         │                         │
         ▼                         ▼                         ▼
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Relay Node 1   │     │  Relay Node 2   │     │  Relay Node 3   │
│  (node-76bc...)│     │  (node-1d32...) │     │  (node-d499...) │
│                 │     │                 │     │                 │
│  • Local Users  │     │  • Local Users  │     │  • Local Users  │
│  • Msg Storage  │     │  • Msg Storage  │     │  • Msg Storage  │
│  • Offline Q    │     │  • Offline Q    │     │  • Offline Q    │
└─────────────────┘     └─────────────────┘     └─────────────────┘
         │                         │                         │
         └─────────────────────────┼─────────────────────────┘
                                   │
                                   ▼
                    ┌─────────────────────────┐
                    │   Ramestta Blockchain   │
                    │   (Chain ID: 1370)      │
                    │                         │
                    │ • Identity Registry     │
                    │ • Relay Manager         │
                    │ • MCT Token Contract    │
                    └─────────────────────────┘
```

## Components

### 1. Web Client (Frontend)

**Location:** `/website/js/chat/`

**Technology Stack:**
- Vanilla JavaScript (ES6 Modules) - No React/Vue overhead
- Web Crypto API (SubtleCrypto) - Native browser encryption
- Ethers.js v6 - Blockchain interaction
- WebSocket API - Real-time bi-directional messaging
- Service Worker API - PWA offline support
- IndexedDB/localStorage - Client-side persistence
- EIP-6963 - Multi-wallet provider discovery

**Key Modules:**

| Module | Purpose | Key Functions |
|--------|---------|---------------|
| `app.js` | Main entry, routing | `initApp()`, `navigate()` |
| `config.js` | Network config | `getBestRelayEndpoint()` |
| `state.js` | State management | `saveMessages()`, `saveContacts()` |
| `wallet.js` | Web3 integration | `connectWallet()`, `signMessage()` |
| `walletDetection.js` | EIP-6963 wallets | `discoverWallets()` |
| `crypto.js` | E2EE implementation | `encryptMessage()`, `deriveSharedKey()` |
| `relay.js` | WebSocket client | `connectToRelay()`, `sendToRelay()` |
| `messages.js` | Message handling | `sendMessage()`, `updateMessageStatus()` |
| `contacts.js` | Contact management | `getSortedContacts()`, `storeContactPublicKey()` |
| `ui.js` | UI utilities | `showToast()`, `formatTime()` |

### 2. Hub Server

**Location:** `/relay-hub/src/index.ts`

**Technology Stack:**
- Node.js 18+ LTS
- TypeScript 5.x (strict mode)
- Express.js 4.x - REST API endpoints
- ws (WebSocket) 8.x - Real-time connections
- uuid - Session/message ID generation
- cors - Cross-origin handling
- dotenv - Environment configuration

**Data Structures:**
```typescript
// Global registries
connectedNodes: Map<tunnelId, ConnectedNode>
usersByAddress: Map<walletAddress, {sessionId, tunnelId}>
offlineMessages: Map<recipientAddress, OfflineMessage[]>
```

**Responsibilities:**
- Node registration and tunnel management
- Global user registry for cross-node routing
- Cross-node message relay with delivery confirmation
- Offline message queue (7-day TTL, 2-3 node redundancy)
- Load balancing - routes to least-loaded node
- Health monitoring via heartbeats (60s timeout)

### 3. Relay Nodes

**Location:** `/relay-nodes/node[1-3]/hub-relay-fixed.js`

**Technology Stack:**
- Node.js 18+ LTS
- ws (WebSocket) - Client and hub connections
- crypto (Node.js built-in) - ID generation
- PM2 - Process management, clustering

**Port Assignments:**
| Node | Port | Tunnel ID (dynamic) |
|------|------|---------------------|
| Node 1 | 19371 | Assigned by hub |
| Node 2 | 19372 | Assigned by hub |
| Node 3 | 19373 | Assigned by hub |

**Responsibilities:**
- Accept user WebSocket connections via hub tunnel
- Authenticate users via wallet address
- Forward user authentication to hub for global registry
- Local message store (Map-based, 7-day retention)
- Forward messages to hub for cross-node delivery
- Handle `MESSAGE_QUEUED` and `DELIVERY_RECEIPT` from hub
- Deliver offline messages when users reconnect

### 4. Smart Contracts (Ramestta Blockchain)

**Registry Contract:** `0x4f8D4955F370881B05b68D2344345E749d8632e3`
- User identity registration
- Display name management
- Public key storage

**Relay Manager Contract:** `0xF78F840eF0e321512b09e98C76eA0229Affc4b73`
- Relay node registration
- Staking mechanism
- Fee distribution

**MCT Token Contract:** `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE`
- Native token for staking and fees

## Data Flow

### Message Sending Flow

```
1. User A types message
2. Client retrieves User B's public key
3. ECDH key derivation → shared secret
4. AES-256-GCM encryption
5. Message signed with wallet (optional)
6. Send via WebSocket to relay node
7. Node forwards to hub
8. Hub routes to User B's node (cross-node if needed)
9. User B's client receives and decrypts
```

### Cross-Node Message Flow

```
User A (Node 1) → Node 1 → Hub → Node 2 → User B
                    │
                    ├── Hub checks usersByAddress registry
                    ├── Routes to target user's node
                    └── Queues offline if user not online
```

## Message Delivery Status System

### Status Flow (WhatsApp-style)

```
┌──────────┐    ┌──────────┐    ┌──────────┐    ┌───────────┐    ┌──────────┐
│ SENDING  │ → │   SENT   │ → │ PENDING  │ → │ DELIVERED │ → │   READ   │
│    🕐    │    │    ✓     │    │    ⏳    │    │    ✓✓     │    │  ✓✓ 🔵   │
└──────────┘    └──────────┘    └──────────┘    └───────────┘    └──────────┘
     │               │               │               │               │
     │               │               │               │               │
   Sending      Sent to        Recipient       Delivered        Recipient
   to relay      relay         offline          to user        opened chat
```

### Status Definitions

| Status | Icon | Color | Trigger |
|--------|------|-------|---------|
| `sending` | 🕐 | Gray | Message being sent to relay |
| `sent` | ✓ | Gray | Relay acknowledged receipt |
| `pending` | ⏳ | Yellow (animated) | Recipient offline, queued for delivery |
| `delivered` | ✓✓ | Gray | Message delivered to recipient's client |
| `read` | ✓✓ | Blue | Recipient opened the conversation |
| `failed` | ❌ | Red | Send failed, tap to retry |

### Status Update Flow

```javascript
// 1. Client sends message
message.status = 'sending';

// 2. Relay acknowledges (relay_ack)
message.status = 'sent';

// 3. If recipient offline (message_queued from hub)
message.status = 'pending';

// 4. When recipient receives (delivery_receipt from hub)
message.status = 'delivered';

// 5. When recipient opens chat (read_receipt)
message.status = 'read';
```

## Contact List Sorting

### Sort Priority (WhatsApp/Telegram style)

```
┌─────────────────────────────────────────────┐
│ 📌 Pinned contacts (always first)           │
├─────────────────────────────────────────────┤
│ 🔴 Unread messages (higher priority)        │
├─────────────────────────────────────────────┤
│ 📅 By timestamp (newest message first)      │
└─────────────────────────────────────────────┘
```

### Implementation

```javascript
// contacts.js - getSortedContacts()
contacts.sort((a, b) => {
    // 1. Pinned first
    if (a.isPinned && !b.isPinned) return -1;
    if (!a.isPinned && b.isPinned) return 1;
    
    // 2. Unread messages higher
    if (a.unread > 0 && b.unread === 0) return -1;
    if (a.unread === 0 && b.unread > 0) return 1;
    
    // 3. Newest message first
    return (b.lastMessageTimestamp || 0) - (a.lastMessageTimestamp || 0);
});
```

## Configuration

### Network Configuration

```javascript
RAMESTTA_CONFIG = {
    chainId: '0x55A',           // 1370 decimal
    chainIdDecimal: 1370,
    chainName: 'Ramestta Mainnet',
    nativeCurrency: { name: 'RAMA', symbol: 'RAMA', decimals: 18 },
    rpcUrls: ['https://blockchain.ramestta.com'],
    blockExplorerUrls: ['https://ramascan.com']
}
```

### Relay Endpoints

```javascript
RELAY_DEFAULTS = {
    primary: 'wss://hub.mumblechat.com/node/76bc8b54',
    fallback: [
        'wss://hub.mumblechat.com/node/1d32a3a0',
        'wss://hub.mumblechat.com/node/d4996d0d'
    ],
    default: 'wss://hub.mumblechat.com/node/76bc8b54'
}
```

## PWA Features

- **Service Worker:** Caches app shell for offline access
- **Install Prompt:** Native install on Android, instructions for iOS
- **Manifest:** Proper icons, theme colors, standalone display
- **Offline Support:** Cached resources, message queue

## Mobile Wallet Support

### Supported Connection Methods

1. **Desktop Browsers:** MetaMask extension, Coinbase Wallet extension
2. **Mobile In-App Browsers:** MetaMask, Trust Wallet, Coinbase Wallet browsers
3. **Deep Links:** Opens dApp inside wallet's browser

### Wallet Detection

Uses EIP-6963 (Multi Injected Provider Discovery) for modern wallets with fallback to legacy `window.ethereum` detection.

## Deployment

### Production URLs

- **Web App:** https://mumblechat.com/conversations.html
- **Hub API:** https://hub.mumblechat.com
- **Hub WebSocket:** wss://hub.mumblechat.com

### Server Requirements

- **Hub Server:** Node.js 18+, 2GB RAM, SSL/TLS
- **Relay Nodes:** Node.js 18+, 1GB RAM each
- **PM2:** Process management for all servers

## Dependencies

### Frontend (Browser)

```json
{
  "ethers": "^6.9.0",
  "Web Crypto API": "Browser Native",
  "WebSocket API": "Browser Native",
  "Service Worker API": "Browser Native"
}
```

### Hub Server

```json
{
  "express": "^4.18.2",
  "ws": "^8.14.2",
  "uuid": "^9.0.0",
  "cors": "^2.8.5",
  "dotenv": "^16.3.1",
  "typescript": "^5.3.2"
}
```

### Relay Nodes

```json
{
  "ws": "^8.14.2",
  "crypto": "Node.js built-in"
}
```

### DevOps

| Tool | Purpose |
|------|---------|
| PM2 | Process management, clustering |
| Nginx | Reverse proxy, SSL termination |
| Let's Encrypt | SSL certificates |
| Ubuntu 22.04 | Server OS |

---

*Last Updated: January 12, 2026*
