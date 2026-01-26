# MumbleChat Relay Desktop App

📡 **Earn MCT tokens by relaying messages on the MumbleChat network**

## Features

- 🖥️ Beautiful native desktop application
- 📊 Live message statistics and uptime tracking
- 💎 Tier display (Bronze/Silver/Gold/Platinum)
- 💰 Rewards tracking and one-click claiming
- 🔔 System tray support - runs in background
- 🔄 Auto-reconnect on connection loss
- 🌐 Blockchain integration with Ramestta Network

## Quick Start

### Option 1: Download Pre-built (Coming Soon)
Visit https://nodesetup.mumblechat.com and download the installer for your platform.

### Option 2: Build from Source

1. **Requirements**
   - Node.js 18+ ([Download](https://nodejs.org))
   - npm (comes with Node.js)

2. **Clone/Download the source**
   ```bash
   # Or download from https://nodesetup.mumblechat.com/scripts/desktop-app-source.tar.gz
   tar -xzf desktop-app-source.tar.gz
   cd desktop-app
   ```

3. **Install dependencies**
   ```bash
   npm install
   ```

4. **Run in development mode**
   ```bash
   npm start
   ```

5. **Build for distribution**
   ```bash
   # macOS
   npm run build:mac
   
   # Windows
   npm run build:win
   
   # Linux
   npm run build:linux
   
   # All platforms
   npm run dist
   ```

## Configuration

The app connects to:
- **Hub WebSocket**: `wss://hub.mumblechat.com/node/connect`
- **Blockchain RPC**: `https://blockchain.ramestta.com`
- **Chain ID**: 1370 (Ramestta)

### Contract Addresses
- MCT Token: `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE`
- Registry: `0x4f8D4955F370881B05b68D2344345E749d8632e3`
- Relay Manager: `0xF78F840eF0e321512b09e98C76eA0229Affc4b73`

## Staking Tiers

| Tier | MCT Staked | Required Uptime | Multiplier |
|------|------------|-----------------|------------|
| 🥉 Bronze | 100 MCT | 4 hours/day | 1.0x |
| 🥈 Silver | 200 MCT | 8 hours/day | 1.5x |
| 🥇 Gold | 300 MCT | 12 hours/day | 2.0x |
| 💎 Platinum | 400 MCT | 16 hours/day | 3.0x |

## V3 Reward System

Relay node operators earn MCT tokens from three sources:

1. **Daily Pool** - Proportional share of daily rewards based on uptime
2. **Fee Pool** - Share of transaction fees from message relaying  
3. **Minting Rewards** - New tokens minted for network participation

**V3 Reward Cap**: Rewards are capped at `MIN(poolShare, (messages/1000) × 0.001 MCT)` to prevent over-payment.

## Running on macOS

### Quick Start (Development Mode)
```bash
# 1. Clone/download the repository
git clone https://github.com/mumblechat/Mumblechat.git
cd "Mumblechat Ramestta Protocol/desktop-app"

# 2. Install dependencies
npm install

# 3. Run the app
npm start
```

### Building a DMG for Distribution
```bash
# Build DMG for macOS (supports both Intel and Apple Silicon)
npm run build:mac

# Output will be in dist/ folder:
# - MumbleChat Relay-4.0.0-arm64.dmg (Apple Silicon M1/M2/M3)
# - MumbleChat Relay-4.0.0-x64.dmg (Intel Macs)
```

### Installing the DMG
1. Open the `.dmg` file
2. Drag "MumbleChat Relay" to Applications
3. On first launch: Right-click → Open (to bypass Gatekeeper)
4. Connect your wallet and start relaying!

### Requirements for Building
- **Node.js 18+**: `brew install node` or download from https://nodejs.org
- **Xcode Command Line Tools**: `xcode-select --install`

## Development

```bash
# Install dependencies
npm install

# Run in development mode
npm start

# Package for current platform
npm run pack

# Build distributable
npm run build
```

## File Structure

```
desktop-app/
├── main.js          # Electron main process
├── preload.js       # Context bridge for IPC
├── index.html       # UI (renderer)
├── package.json     # Dependencies & build config
├── build.sh         # Build helper script
└── assets/          # Icons and images
```

## Troubleshooting

### App won't connect
- Check your internet connection
- Verify wallet address is correct
- Ensure you have staked MCT at https://mumblechat.com/staking

### Rewards not showing
- Click "Refresh" to fetch latest from blockchain
- Ensure node has been running for sufficient uptime
- Check that wallet has sufficient staked MCT

### Build fails
- Ensure Node.js 18+ is installed
- Try `npm cache clean --force` then reinstall
- On macOS, you may need Xcode command line tools: `xcode-select --install`

## Support

- Website: https://mumblechat.com
- Setup: https://nodesetup.mumblechat.com
- Network Status: https://mumblechat.com/network-status.html

## License

MIT License - MumbleChat Protocol
