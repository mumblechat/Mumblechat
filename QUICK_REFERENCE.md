# Quick Reference Card - MCT Validation & OS Detection

## 🚀 Feature Overview

```
┌────────────────────────────────────────────────────────────────┐
│              RELAY NODE MCT VALIDATION SYSTEM                  │
├────────────────────────────────────────────────────────────────┤
│                                                                 │
│  AUTOMATIC OS DETECTION                                        │
│  ├─ 🪟 Windows    → Shows Windows .exe download              │
│  ├─ 🍎 macOS      → Shows macOS .dmg download                │
│  └─ 🐧 Linux      → Shows Linux .tar.gz download             │
│                                                                 │
│  MCT BALANCE CHECKING                                          │
│  ├─ Queries blockchain directly                               │
│  ├─ Shows exact MCT balance with 2 decimals                   │
│  └─ Updates when tier changes                                 │
│                                                                 │
│  TIER VALIDATION                                               │
│  ├─ 🥉 Bronze    = 100 MCT needed                             │
│  ├─ 🥈 Silver    = 500 MCT needed                             │
│  ├─ 🥇 Gold      = 1000 MCT needed                            │
│  └─ 💎 Platinum  = 2500 MCT needed                            │
│                                                                 │
│  STATUS FEEDBACK                                               │
│  ├─ ✅ "Ready to register" → Button ENABLED (green)          │
│  └─ ❌ "Need X more MCT" → Button DISABLED (gray)            │
│                                                                 │
└────────────────────────────────────────────────────────────────┘
```

---

## 📋 User Journey

```
START HERE
    │
    └─→ Visit relay-node.html
            │
            └─→ [OS Auto-Detected: 🪟/🍎/🐧]
                    │
                    └─→ Click "Connect Wallet"
                            │
                            └─→ MetaMask Approval
                                    │
                                    └─→ [Wallet Connected]
                                            │
                                            ├─→ Show wallet address
                                            │
                                            ├─→ Query MCT balance
                                            │
                                            ├─→ Validate tier requirement
                                            │
                                            └─→ Display OS-specific downloads
                                                    │
                                                    ├─ IF Balance ≥ Required:
                                                    │   ✅ "Ready to register"
                                                    │   ✅ Button ENABLED
                                                    │
                                                    └─ IF Balance < Required:
                                                        ❌ "Need X more MCT"
                                                        ❌ Button DISABLED
                                                            │
                                                            └─→ User acquires MCT
                                                                    │
                                                                    └─→ Tier now available
                                                                            │
                                                                            └─→ Start relay node
```

---

## 🔧 Technical Reference

### Files Modified
| File | Changes | Lines Added |
|------|---------|------------|
| `relay-node.js` | MCT checks, OS detection, tier validation | +52 |
| `relay-node.html` | Wallet info display, download section | +41 |

### New Methods (relay-node.js)
| Method | Purpose | Returns |
|--------|---------|---------|
| `detectOS()` | Identifies OS from user agent | `{name, icon, supported}` |
| `checkMCTBalance()` | Queries blockchain for MCT | `boolean` |
| `validateTierRequirements()` | Checks balance vs tier | Updates DOM |
| `showOSDownloadOptions()` | Renders download cards | Updates DOM |

### Constants (relay-node.js)
```javascript
MCT_ABI = ['balanceOf', 'decimals']
MCT_ADDRESS = '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE'
MCT_REQUIREMENTS = {
  bronze: 100,
  silver: 500,
  gold: 1000,
  platinum: 2500
}
```

---

## 🎯 Key Features

### Smart Validation
```
On Wallet Connect:
1. Query blockchain → Get MCT balance
2. Detect OS → Get user's system
3. Validate tier → Check if eligible
4. Update UI → Show status & downloads
5. Enable/Disable → "Start Node" button
```

### Automatic Detection
```
No user setup needed:
✅ OS detected automatically
✅ MCT balance fetched live
✅ Tier validated instantly
✅ Downloads highlighted for user's OS
✅ All without manual configuration
```

### Visual Feedback
```
Success State:
├─ ✅ Green checkmarks
├─ "Ready to register" message
├─ Button: ENABLED (bright green)
└─ Can start relay node immediately

Error State:
├─ ❌ Red warnings
├─ "Need X more MCT" message
├─ Button: DISABLED (gray)
└─ Shows exact amount needed
```

---

## 📊 Tier Comparison Table

| Tier | Icon | Storage | Uptime | Multiplier | MCT Required | 
|------|------|---------|--------|------------|--------------|
| Bronze | 🥉 | 1 GB | 4+ h/day | 1.0x | 100 |
| Silver | 🥈 | 2 GB | 8+ h/day | 1.5x | 500 |
| Gold | 🥇 | 4 GB | 12+ h/day | 2.0x | 1000 |
| Platinum | 💎 | 8 GB | 16+ h/day | 3.0x | 2500 |

---

## 🌍 OS Detection

### Windows Detection
```
User Agent contains: 'Win'
Display: 🪟 Windows
Download: mumblechat-relay-node-windows.exe
Instructions: "Run installer, sets up as background service"
```

### macOS Detection
```
User Agent contains: 'Mac'
Display: 🍎 macOS
Download: mumblechat-relay-node-macos.dmg
Instructions: "Open DMG, drag to Applications"
```

### Linux Detection
```
User Agent contains: 'Linux'
Display: 🐧 Linux
Download: mumblechat-relay-node-linux.tar.gz
Instructions: "Extract, run ./start.sh, supports systemd"
```

---

## 💾 Data Flow

```
relay-node.html
    ↓
relayNode.connectWallet()
    ├─→ Get wallet (MetaMask)
    ├─→ Display address
    ├─→ Call checkMCTBalance()
    ├─→ Call validateTierRequirements()
    └─→ Call showOSDownloadOptions()
        ├─→ Query blockchain
        ├─→ Compare: balance vs required
        ├─→ Update button state
        └─→ Generate download HTML
```

---

## 🔐 Security Notes

### What It Does (Safe ✅)
- Reads MCT balance (no write)
- Detects OS from browser (local only)
- Validates tier locally
- Shows download links

### What It Doesn't Do (No Risk)
- Never transfers MCT
- Never changes smart contracts
- Never exposes private keys
- Never steals user data

---

## 🎓 Learning Resources

**Documentation Files:**
1. `MCT_VALIDATION_QUICK_GUIDE.md` - Step-by-step guide
2. `RELAY_NODE_ARCHITECTURE.md` - System design & diagrams
3. `RELAY_NODE_MCT_FEATURES.md` - Detailed features
4. `RELAY_NODE_GUIDE.md` - Complete setup guide

**Code Reference:**
- `/website/relay-node.html` - UI layout
- `/website/js/relay-node.js` - Core logic

---

## ✅ Implementation Checklist

- [x] MCT contract ABI defined
- [x] OS detection implemented
- [x] Wallet connection enhanced
- [x] MCT balance query implemented
- [x] Tier validation logic
- [x] UI elements for balance display
- [x] UI elements for OS display
- [x] Download section with OS highlighting
- [x] Button state management
- [x] Error handling
- [x] Documentation created
- [x] Testing completed

---

## 🚀 Ready to Use

The relay node system is complete and ready for:
✅ Production deployment
✅ User testing
✅ Browser relay nodes
✅ Desktop relay node downloads
✅ Cross-platform support

---

## 📞 Support

**If users report issues:**
- ❌ "MetaMask not found" → Install MetaMask extension
- ❌ "Can't connect wallet" → Check network in MetaMask
- ❌ "MCT balance showing 0" → Check if on Ramestta network
- ❌ "Button still disabled" → Need more MCT tokens
- ❌ "Wrong OS showing" → Check browser user agent

---

**Version: 1.0 - MCT Validation & OS Detection**
**Status: ✅ Complete and Production-Ready**
**Last Updated: 2024**
