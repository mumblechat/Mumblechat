# V4.5.0 Changelog - Display Names, Stay Unlocked, Contact Details

**Date:** February 12, 2026  
**Build:** V4.5.0 (288)  
**Status:** ✅ Deployed to mumblechat.com/downloads

---

## 🎯 Release Objective

Implement user-friendly display name system with both on-chain (owner-registered) and off-chain (local nickname) support, add "Stay Unlocked in Chat" feature, and complete Contact Details functionality.

---

## 📋 Major Features

### 1. Display Name System
Two-tier display name support for better contact identification:

| Type | Description | Storage | Editable By |
|------|-------------|---------|-------------|
| **On-Chain Display Name** | Name registered by address owner on blockchain | Smart Contract | Address Owner |
| **Local Nickname** | Custom name you set for any contact | Local SQLite | You |

**Priority:** Local nickname > On-chain display name > Truncated address

#### Implementation Files
- `ContactRepository.kt` - New repository for contact CRUD operations
- `NewChatViewModel.kt` - Added `blockchainService.getOnChainDisplayName()` lookup
- `NewChatActivity.kt` - Auto-fills display name from on-chain data
- `MumbleChatBlockchainService.kt` - Added `getOnChainDisplayName()` method
- `activity_new_chat.xml` - Added display name input field

#### New Chat Screen Behavior
1. User enters wallet address
2. System verifies registration status
3. If registered, fetches on-chain display name from Registry contract
4. Shows status: "CryptoMaster ✓ (registered user)" or "User is registered ✓"
5. Auto-fills display name field with on-chain name (if available)
6. User can override with custom nickname

---

### 2. Stay Unlocked in Chat
Prevents auto-lock while actively chatting.

**Problem:** App would lock after timeout even during active conversation
**Solution:** Session refresh mechanism while in ConversationActivity

#### Implementation
- Added `KEY_BYPASS_LOCK_IN_CHAT` setting to `AppSecurityManager.java`
- Added Handler-based session refresh (every 30 seconds) in `ConversationActivity.kt`
- New toggle in Security Settings: "Stay Unlocked in Chat"

```kotlin
// Session refresh mechanism
private val sessionRefreshHandler = Handler(Looper.getMainLooper())
private val sessionRefreshRunnable = object : Runnable {
    override fun run() {
        if (appSecurityManager.isBypassLockInChatEnabled()) {
            appSecurityManager.refreshSession()
        }
        sessionRefreshHandler.postDelayed(this, 30_000L) // 30 seconds
    }
}
```

#### Lifecycle Management
- `onResume()` - Starts session refresh loop
- `onPause()` - Stops refresh loop
- `onDestroy()` - Cleanup handler

---

### 3. Contact Details Screen (NEW)
Full contact information and management screen.

**Previously:** "View Contact" showed "Coming soon" toast
**Now:** Complete ContactDetailsActivity with:

| Feature | Description |
|---------|-------------|
| Local Nickname | View and edit custom nickname |
| On-Chain Name | View owner's registered display name |
| "Use This Name" | One-tap to use on-chain name as nickname |
| Wallet Address | Full address with tap-to-copy |
| Block/Unblock | Toggle block status |
| Add/Remove Favorite | Toggle favorite status |

#### Files Added
- `ContactDetailsActivity.kt` - Full contact management UI
- `activity_contact_details.xml` - Material Design layout

---

### 4. Export Chat
Now functional export feature.

**Previously:** "Export coming soon" toast
**Now:** Exports chat history as formatted text via share intent

```kotlin
private fun exportChat() {
    val chatExport = StringBuilder()
    chatExport.appendLine("MumbleChat Export")
    chatExport.appendLine("Chat with: $peerAddress")
    chatExport.appendLine("Exported: ${timestamp}")
    messages.forEach { msg ->
        chatExport.appendLine("[$time] $sender: ${msg.content}")
    }
    // Share via intent
}
```

---

### 5. Dark Mode Input Fix
Fixed text visibility in "Add New Chat" screen dark mode.

**Problem:** Input text invisible in dark mode
**Solution:** Added explicit color attributes

```xml
<EditText
    android:textColor="?android:textColorPrimary"
    app:boxBackgroundColor="?android:colorBackground" />
```

---

## 📁 Files Modified

### New Files
| File | Purpose |
|------|---------|
| `ContactDetailsActivity.kt` | Contact details and management |
| `activity_contact_details.xml` | Contact details layout |
| `ContactRepository.kt` | Contact data operations |

### Modified Files
| File | Changes |
|------|---------|
| `AppSecurityManager.java` | Added bypass lock in chat setting |
| `ConversationActivity.kt` | Session refresh, contact details launch, export chat |
| `NewChatViewModel.kt` | Added on-chain display name fetching |
| `NewChatActivity.kt` | Display on-chain name, auto-fill display name |
| `MumbleChatBlockchainService.kt` | Added `getOnChainDisplayName()` |
| `SecuritySettingsActivity.java` | Added bypass lock toggle |
| `activity_security_settings.xml` | Added bypass lock card |
| `activity_new_chat.xml` | Fixed dark mode, added display name input |
| `strings_mumblechat.xml` | Added contact details strings |
| `AndroidManifest.xml` | Registered ContactDetailsActivity |
| `build.gradle` | Version 4.5.0, code 288 |

---

## 🧪 Testing Checklist

### Display Names
- [ ] New Chat: Enter address → Shows on-chain name if registered
- [ ] New Chat: Display name auto-fills from on-chain
- [ ] New Chat: Can override with custom nickname
- [ ] Conversation: Shows nickname in toolbar
- [ ] Contact Details: Shows both local and on-chain names
- [ ] Contact Details: "Use This Name" button works

### Stay Unlocked in Chat
- [ ] Security Settings: Toggle visible and functional
- [ ] Chat Screen: App doesn't lock during conversation
- [ ] Background: Still locks when leaving chat

### Contact Details
- [ ] Menu → View Contact opens ContactDetailsActivity
- [ ] Edit Nickname dialog works
- [ ] Block/Unblock toggles correctly
- [ ] Favorite toggle works
- [ ] Address copy to clipboard works

### Export Chat
- [ ] Menu → Export Chat opens share sheet
- [ ] Exported text contains all messages
- [ ] Format is readable

---

## 🚀 Deployment

### Build
```bash
gh workflow run 227068002 --ref master -R mumblechat/Mumblechat
```

### Download Links
- **Latest:** https://mumblechat.com/downloads/mumblechat-latest.apk
- **Versioned:** https://mumblechat.com/downloads/mumblechat-v4.5.0-build288.apk

---

## 📊 Version History

| Version | Build | Date | Key Features |
|---------|-------|------|--------------|
| V4.4.9 | 286 | Feb 12, 2026 | Notifications, private key display fix |
| **V4.5.0** | **288** | **Feb 12, 2026** | **Display names, stay unlocked, contact details** |
