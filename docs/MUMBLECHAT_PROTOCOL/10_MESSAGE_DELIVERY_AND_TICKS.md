# MumbleChat Message Delivery & Tick System

## Overview

MumbleChat implements a WhatsApp-style message delivery confirmation system with visual indicators (ticks) across both mobile and web platforms. This document describes the complete message lifecycle, delivery mechanisms, and tick status indicators.

---

## Message Lifecycle

### Phase 1: Creation & Local Storage
```
User composes message
↓
Message saved locally with PENDING status
↓
Content encrypted (if supported)
```

### Phase 2: Delivery Attempt
Messages are sent through a prioritized routing system:

1. **Hub Relay First** (Cross-platform compatible)
   - Plaintext via TLS (secure transport)
   - Supported by both mobile and web
   - Fastest for online recipients

2. **P2P Fallback** (Mobile-to-mobile only)
   - AEAD encrypted (incompatible with web)
   - Direct peer-to-peer connection
   - Fallback if hub relay fails

3. **Offline Storage** (Fallback)
   - Message queued on relay nodes
   - Delivered when recipient comes online
   - Expires after 5 days

### Phase 3: Delivery Confirmation
```
Message sent to hub
↓
Hub routes to recipient or stores offline
↓
If online: Recipient receives immediately
If offline: Queued for later delivery
↓
Delivery receipt sent back to sender
↓
Status updated: SENT → DELIVERED
```

---

## Tick Status System

### Status Indicators

| Status | Icon | Color | Meaning |
|--------|------|-------|---------|
| **SENDING** | 🕐 | Gray | Message being sent to relay |
| **SENT** | ✓ | Gray | Message arrived at hub/relay |
| **PENDING** | ⏳ | Amber | Recipient offline, queued for delivery |
| **DELIVERED** | ✓✓ | 🟢 GREEN | Message delivered to recipient's device |
| **READ** | ✓✓ | 🔵 BLUE | Recipient opened conversation |
| **FAILED** | ❌ | Red | Delivery failed, can retry |

### Visual Implementation

#### Web (JavaScript)
**File**: `website/js/chat/views/ConversationView.js`

```javascript
function getStatusIcon(status) {
    switch (status) {
        case 'sending': 
            return '<span class="status-icon sending">🕐</span>';
        case 'sent': 
            return '<span class="status-icon sent">✓</span>';
        case 'pending': 
            return '<span class="status-icon pending">⏳</span>';
        case 'delivered': 
            return '<span class="status-icon delivered" style="color: #10b981;">✓✓</span>';  // GREEN
        case 'read': 
            return '<span class="status-icon read" style="color: #1b8cff;">✓✓</span>';  // BLUE
        case 'failed': 
            return '<span class="status-icon failed">❌</span>';
        default: 
            return '';
    }
}
```

#### Mobile (Kotlin)
**File**: `app/src/main/java/com/ramapay/app/chat/ui/adapter/MessageListAdapter.kt`

```kotlin
when (message.status) {
    MessageStatus.PENDING, MessageStatus.SENDING -> {
        binding.iconStatus.text = "🕐"  // Clock
        binding.iconStatus.setTextColor(0xFFB0B0B0.toInt())  // Gray
    }
    MessageStatus.SENT_DIRECT, MessageStatus.SENT_TO_RELAY -> {
        binding.iconStatus.text = "✓"   // Single tick
        binding.iconStatus.setTextColor(0xFF808080.toInt())  // Gray
    }
    MessageStatus.DELIVERED -> {
        binding.iconStatus.text = "✓✓"  // Double tick
        binding.iconStatus.setTextColor(0xFF10B981.toInt())  // GREEN #10B981
    }
    MessageStatus.READ -> {
        binding.iconStatus.text = "✓✓"  // Double tick
        binding.iconStatus.setTextColor(0xFF1B8CFF.toInt())  // BLUE #1B8CFF
    }
    MessageStatus.FAILED -> {
        binding.iconStatus.text = "❌"
        binding.iconStatus.setTextColor(0xFFF43F5E.toInt())  // Red
    }
}
```

---

## Message Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ SENDER (Mobile/Web)                                              │
│ ┌──────────────────────────────────────────────────────────────┐ │
│ │ Compose Message                                               │ │
│ │ Status: PENDING                                               │ │
│ │ Icon: (nothing yet)                                           │ │
│ └──────────────┬───────────────────────────────────────────────┘ │
│                │ Press Send                                       │
│ ┌──────────────▼───────────────────────────────────────────────┐ │
│ │ Send via Hub Relay (Plaintext via TLS)                       │ │
│ │ Status: SENDING                                               │ │
│ │ Icon: 🕐 (gray)                                              │ │
│ └──────────────┬───────────────────────────────────────────────┘ │
│                │                                                  │
│ ┌──────────────▼───────────────────────────────────────────────┐ │
│ │ If Hub fails: Try P2P (Encrypted, mobile-only)               │ │
│ │ If P2P fails: Queue offline                                   │ │
│ └──────────────┬───────────────────────────────────────────────┘ │
└─────────────────┬──────────────────────────────────────────────────┘
                  │
        ┌─────────▼──────────┐
        │  RELAY HUB/NODE    │
        │                    │
        │ Routes message to: │
        │ - Online user      │
        │ - Offline storage  │
        └─────────┬──────────┘
                  │
        ┌─────────▼───────────────────────────────────────┐
        │ RECIPIENT (Mobile/Web)                          │
        │ ┌───────────────────────────────────────────┐   │
        │ │ Receives message                          │   │
        │ │ Sent ACK back to sender                  │   │
        │ │ DELIVERED notification sent               │   │
        │ └───────────────────────────────────────────┘   │
        └─────────┬──────────────────────────────────────┘
                  │
        ┌─────────▼───────────────────────────────────────┐
        │ SENDER receives DELIVERED notification          │
        │ Status: DELIVERED                               │
        │ Icon: ✓✓ (GREEN #10B981)                        │
        └───────────────────────────────────────────────┘
```

---

## Offline Message Handling

### Queue Storage
Messages are stored on relay nodes when recipient is offline:

**Location**: `/root/relay-nodes/hub-relay-fixed.js` (lines 117-150)

```javascript
// Store offline message
function storeOfflineMessage(from, to, payload) {
    const msgId = `msg_${generateId()}`;
    
    offlineMessages[to] = offlineMessages[to] || [];
    offlineMessages[to].push({
        from: from,
        to: to,
        messageId: msgId,
        timestamp: Date.now(),
        ...payload
    });
    
    // Expire after 5 days
    setTimeout(() => {
        offlineMessages[to] = offlineMessages[to]?.filter(m => m.messageId !== msgId);
    }, 5 * 24 * 60 * 60 * 1000);
    
    return msgId;
}
```

### Delivery on Reconnect
When a user comes online:

1. **Web**: Sends `sync` request to retrieve stored messages
2. **Mobile**: Requests offline messages from ChatService hub fallback
3. **Hub**: Delivers all queued messages from `offlineMessages` storage
4. **Recipient**: Updates status to DELIVERED

**Handler**: `hub-relay-fixed.js` (lines 153-195)

```javascript
case 'DELIVER_OFFLINE_MESSAGE':
    // Send stored offline messages to user
    const userOfflineMessages = offlineMessages[message.recipient] || [];
    userOfflineMessages.forEach(offMsg => {
        sendToHubUser(sessionId, {
            type: 'message',
            ...offMsg,
            isOfflineMessage: true
        });
    });
    // Clear after delivery
    delete offlineMessages[message.recipient];
    break;
```

---

## Cross-Platform Compatibility

### Mobile-to-Web
- ✅ **Hub Relay**: Plaintext via TLS (both support)
- ✅ **Delivered**: Mobile → Web works perfectly
- ❌ **P2P**: Incompatible (different encryption schemes)

### Web-to-Mobile
- ✅ **Hub Relay**: Plaintext via TLS (both support)
- ✅ **Delivered**: Web → Mobile works perfectly
- ⚠️ **Encryption**: Web can't decrypt mobile's AEAD keys (falls back to plaintext)

### Mobile-to-Mobile
- ✅ **Hub Relay**: Plaintext via TLS (always works)
- ✅ **P2P**: Direct encrypted connection (faster if available)
- ✅ **Fallback**: Automatic switch between both

### Web-to-Web
- ✅ **Hub Relay**: Plaintext via TLS
- ✅ **E2EE**: ECDH-P256 + AES-GCM encryption
- ✅ **Delivered**: Full end-to-end encryption support

---

## Error Handling & Retry

### Failed Delivery
If a message fails to send:
- Status: FAILED
- Icon: ❌ (red)
- Action: User can tap to retry

### Retry Logic
```kotlin
// In MessageListAdapter.kt
binding.buttonRetry.setOnClickListener {
    viewModel.retrySendMessage(message)
}
```

### Automatic Fallback
```kotlin
// In ChatService.kt - tries Hub relay first, then P2P
val hubSent = hubConnection.sendMessage(...)
if (!hubSent) {
    // Fall back to P2P
    p2pManager.sendMessage(...)
}
```

---

## Implementation Files

| File | Purpose | Status |
|------|---------|--------|
| `website/js/chat/views/ConversationView.js` | Web tick UI | ✅ v4.4.6+ |
| `app/src/main/java/com/ramapay/app/chat/ui/adapter/MessageListAdapter.kt` | Mobile tick UI | ✅ v4.4.7+ |
| `relay-nodes/hub-relay-fixed.js` | Offline storage + delivery | ✅ v4.4.3+ |
| `app/src/main/java/com/ramapay/app/chat/core/ChatService.kt` | Message routing logic | ✅ v4.4.5+ |
| `website/js/chat/relay.js` | Web relay connection | ✅ v4.4.1+ |

---

## Testing Checklist

- [ ] **Mobile→Web**: Send from mobile, verify message appears as plaintext on web with ✓✓ GREEN tick
- [ ] **Web→Mobile**: Send from web, verify message appears on mobile with ✓✓ GREEN tick
- [ ] **Mobile→Mobile**: Send between mobiles, verify P2P + plaintext options work
- [ ] **Web→Web**: Send between web clients, verify E2EE ticks blue ✓✓ when read
- [ ] **Offline Delivery**: Disable mobile, send messages, reconnect, verify offline messages delivered
- [ ] **Delivery ACK**: Verify sender sees GREEN ✓✓ when recipient receives
- [ ] **Read Status**: Verify BLUE ✓✓ when recipient opens conversation
- [ ] **Failed Retry**: Send without connection, verify ❌, then enable and retry

---

## Version History

| Version | Changes |
|---------|---------|
| **v4.4.7** | ✅ Mobile tick system with green/blue colors |
| **v4.4.6** | ✅ Web tick system with green/blue colors, suppress crypto errors |
| **v4.4.5** | ✅ Hub relay first for cross-platform, P2P fallback |
| **v4.4.4** | ✅ Button visibility fixes, version display |
| **v4.4.3** | ✅ Offline message delivery, console spam reduction |
| **v4.4.1-2** | ✅ Base chat functionality |

---

## Future Enhancements

- [ ] **Typing Indicators**: Show "typing..." when user is composing
- [ ] **Message Reactions**: Emoji reactions on messages
- [ ] **Message Editing**: Edit sent messages
- [ ] **Message Forwarding**: Forward messages to other chats
- [ ] **Voice Messages**: Record and send audio clips
- [ ] **Message Search**: Full-text search in conversations

---

## References

- **Delivery Protocol**: `PROTOCOL_SPECIFICATION.md`
- **Threat Model**: `THREAT_MODEL.md`
- **Implementation Status**: `09_IMPLEMENTATION_STATUS.md`
- **Mobile Architecture**: `../../ANDROID_CHAT_SYSTEM.md`
