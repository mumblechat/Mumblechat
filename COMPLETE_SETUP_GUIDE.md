# MumbleChat Complete Setup Guide

## Overview
MumbleChat is a complete decentralized messaging ecosystem with three components:
1. **Android App** - Native Android mobile application
2. **Desktop Relay Node** - Cross-platform relay server (Mac, Windows, Linux)
3. **PWA Web App** - Progressive Web App for browsers (Chrome, Safari, Firefox)

## System Architecture

```
┌─────────────────┐
│   Android App   │
│   (Mobile)      │
└────────┬────────┘
         │
         │ WebSocket
         │
┌────────▼──────────────────────┐
│  Desktop Relay Node           │
│  (Mac/Windows/Linux)          │
│  - TCP Port: 19370            │
│  - WebSocket Port: 19371      │
│  - P2P Messaging              │
│  - Message Storage            │
└────────┬──────────────────────┘
         │
         │ WebSocket
         │
┌────────▼────────┐
│   PWA Web App   │
│   (Chrome)      │
└─────────────────┘

All components connect to:
┌──────────────────────────┐
│  Ramestta Blockchain     │
│  - Chain ID: 0x55A       │
│  - RPC: blockchain.      │
│    ramestta.com          │
│  - MumbleChatRegistry    │
│    Contract              │
└──────────────────────────┘
```

## Prerequisites

### For All Components
- MetaMask or compatible Web3 wallet
- RAMA tokens for gas fees
- Ramestta network configured in wallet

### For Desktop Relay Node
- Node.js 18+ 
- npm or yarn
- 8GB+ RAM recommended
- Stable internet connection

### For PWA Web App
- Modern browser (Chrome 90+, Safari 14+, Firefox 88+)
- HTTP server (Python, Node.js, or any static server)

## Part 1: Setup Desktop Relay Node

### Step 1: Install Dependencies
```bash
cd desktop-relay
npm install
```

### Step 2: Configure Relay Node
Create `config.json`:
```json
{
  "relay": {
    "host": "0.0.0.0",
    "port": 19370,
    "maxConnections": 200,
    "maxStorageGB": 8
  },
  "blockchain": {
    "rpcUrl": "https://blockchain.ramestta.com",
    "registryAddress": "0x4f8D4955F370881B05b68D2344345E749d8632e3",
    "mctTokenAddress": "0xYourMCTTokenAddress"
  },
  "logging": {
    "level": "info",
    "logDir": "./logs"
  }
}
```

### Step 3: Generate Relay Private Key
```bash
# Generate new wallet for relay
node -e "console.log(require('ethers').Wallet.createRandom().privateKey)"
```

Save the private key securely!

### Step 4: Start Relay Node
```bash
# Development mode
npm run dev

# Production mode
npm run build
npm start
```

The relay will start on:
- **TCP Port**: 19370 (for Android/desktop clients)
- **WebSocket Port**: 19371 (for web browsers)

## Part 2: Setup PWA Web App

### Step 1: Configure Web Server
```bash
cd website

# Using Python
python3 -m http.server 8080

# Or using Node.js
npx http-server -p 8080

# Or using PHP
php -S localhost:8080
```

### Step 2: Access the App
Open browser and navigate to:
```
http://localhost:8080/chat.html
```

### Step 3: Connect Wallet
1. Click **"Connect Wallet"** button
2. MetaMask will prompt for permission
3. App will check Ramestta network
4. If not on Ramestta, it will prompt to switch/add network

### Step 4: Register on Contract
**First-time users:**
1. After wallet connection, registration prompt appears
2. Enter your display name
3. Click "Register" 
4. Confirm MetaMask transaction
5. Wait for transaction confirmation

**Returning users:**
- App automatically detects registration
- Proceeds directly to chat interface

## Part 3: Using the Chat System

### Connecting to Relay
The app automatically connects to the relay node at `ws://localhost:19371`

**Connection Status:**
- ✅ Green dot = Connected
- 🔴 Red dot = Disconnected
- 🟡 Yellow dot = Connecting

### Adding Contacts
1. Click **"+"** button or "New" tab
2. Enter wallet address (0x...)
3. Enter contact name
4. Click Add

### Sending Messages
1. Select contact from list
2. Type message in input box
3. Press Enter or click Send button
4. Message is:
   - Saved locally
   - Sent to relay node
   - Relayed to recipient
   - Delivered when recipient online

### Message Protocol
```javascript
{
  "type": "relay",
  "messageId": "msg_1234567890_abc123",
  "senderAddress": "0x...",
  "recipientAddress": "0x...",
  "encryptedBlob": "base64encodedtext",
  "timestamp": 1704500000000,
  "ttlDays": 7
}
```

## Part 4: Mobile Features

### Bottom Navigation
- **Chats**: View all conversations
- **New**: Add new contact
- **Group**: Create group chat (coming soon)
- **Profile**: View/edit profile

### Responsive Design
The PWA automatically adapts to mobile screens:
- Full-screen chat interface
- Back button navigation
- Touch-friendly buttons
- Bottom tab navigation
- Pull-to-refresh

### Installing as PWA
**On Android Chrome:**
1. Open chat.html in Chrome
2. Click menu (⋮)
3. Select "Add to Home screen"
4. Name the app
5. App icon appears on home screen

**On iOS Safari:**
1. Open chat.html in Safari
2. Click Share button
3. Select "Add to Home Screen"
4. Name the app
5. App icon appears on home screen

## Part 5: Relay Node Configuration

### Network Configuration
The relay node listens on two ports:
```
TCP: 19370     → For native apps (Android, iOS)
WebSocket: 19371 → For web browsers (PWA)
```

### Firewall Settings
**For public relay:**
```bash
# Ubuntu/Debian
sudo ufw allow 19370/tcp
sudo ufw allow 19371/tcp

# macOS
sudo pfctl -a "com.apple/mumblechat" -f - <<EOF
pass in proto tcp to port 19370
pass in proto tcp to port 19371
EOF

# Windows
netsh advfirewall firewall add rule name="MumbleChat TCP" dir=in action=allow protocol=TCP localport=19370
netsh advfirewall firewall add rule name="MumbleChat WS" dir=in action=allow protocol=TCP localport=19371
```

### Running as Service

**Linux (systemd):**
```bash
sudo nano /etc/systemd/system/mumblechat-relay.service
```

```ini
[Unit]
Description=MumbleChat Relay Node
After=network.target

[Service]
Type=simple
User=mumblechat
WorkingDirectory=/opt/mumblechat/desktop-relay
Environment=NODE_ENV=production
ExecStart=/usr/bin/node dist/server.js
Restart=always

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable mumblechat-relay
sudo systemctl start mumblechat-relay
```

**macOS (launchd):**
```bash
nano ~/Library/LaunchAgents/com.mumblechat.relay.plist
```

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.mumblechat.relay</string>
    <key>ProgramArguments</key>
    <array>
        <string>/usr/local/bin/node</string>
        <string>/path/to/desktop-relay/dist/server.js</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/com.mumblechat.relay.plist
```

## Part 6: Troubleshooting

### Issue: Wallet won't connect
**Solution:**
- Ensure MetaMask is installed
- Check browser console for errors
- Try refreshing the page
- Clear browser cache

### Issue: Wrong network
**Solution:**
- App will prompt to switch to Ramestta
- Click "Switch Network" in MetaMask
- If network not found, app will add it automatically

### Issue: Can't connect to relay
**Solution:**
```bash
# Check if relay is running
netstat -an | grep 19371

# Check relay logs
tail -f desktop-relay/logs/relay.log

# Restart relay
cd desktop-relay && npm restart
```

### Issue: Messages not sending
**Solution:**
1. Check relay connection status (green dot)
2. Verify wallet is connected
3. Check browser console for errors
4. Ensure recipient address is valid
5. Try reconnecting to relay

### Issue: Port already in use
**Solution:**
```bash
# Find process using port
lsof -ti:19371 | xargs kill -9

# Or change port in config.json
```

## Part 7: Development Tips

### Hot Reload PWA
```bash
cd website
python3 -m http.server 8080 &
open http://localhost:8080/chat.html
```

### Debug Relay Messages
Add to relay config:
```json
{
  "logging": {
    "level": "debug"
  }
}
```

### Test Messaging
```javascript
// In browser console
state.relaySocket.send(JSON.stringify({
  type: 'relay',
  messageId: 'test_' + Date.now(),
  senderAddress: state.address,
  recipientAddress: '0x...',
  encryptedBlob: btoa('Hello World'),
  timestamp: Date.now(),
  ttlDays: 1
}));
```

### Monitor Relay Health
```bash
# Check connections
curl http://localhost:19370/health

# View logs
tail -f desktop-relay/logs/relay.log
```

## Part 8: Production Deployment

### Relay Node (VPS)
1. Get a VPS (DigitalOcean, AWS, etc.)
2. Install Node.js 18+
3. Clone repository
4. Install dependencies
5. Configure firewall
6. Setup systemd service
7. Point DNS to VPS IP
8. Update PWA relay URL

### PWA Hosting
1. **Option A: Static hosting**
   - Deploy to Vercel, Netlify, or GitHub Pages
   - Update manifest.json with domain
   - Configure HTTPS

2. **Option B: Self-hosted**
   - Use Nginx or Apache
   - Setup SSL certificate (Let's Encrypt)
   - Configure proper CORS headers

### Update PWA Relay URL
Edit `chat.html`:
```javascript
settings: {
    relayUrl: 'wss://relay.yourdomain.com:19371'
}
```

## Summary

✅ **Android App** - Native mobile messaging
✅ **Desktop Relay** - Cross-platform message routing
✅ **PWA Web App** - Browser-based messaging
✅ **Blockchain Integration** - Decentralized identity
✅ **End-to-End Ready** - Full message encryption framework

All three components work together to provide a complete decentralized messaging experience matching WhatsApp/Telegram functionality with blockchain security!
