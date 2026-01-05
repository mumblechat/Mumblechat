# MumbleChat Desktop Relay Node

[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-blue)](https://github.com/ramestta/mumblechat)
[![Node](https://img.shields.io/badge/node-%3E%3D18-green)](https://nodejs.org/)
[![License](https://img.shields.io/badge/license-MIT-orange)](LICENSE)

> **Earn MCT tokens by relaying encrypted messages for the MumbleChat P2P messaging network**

The MumbleChat Desktop Relay Node is a cross-platform server application that participates in the MumbleChat decentralized messaging network. Relay nodes store and forward encrypted messages for offline users and earn MCT token rewards for their service.

## 🌟 Features

- **Cross-Platform**: Runs on macOS, Linux, and Windows
- **Earn Rewards**: Receive MCT tokens for relaying messages
- **Tier System**: Higher storage & uptime = higher rewards (up to 3x)
- **Easy Setup**: Interactive CLI wizard for configuration
- **Auto-Start**: Systemd (Linux) and launchd (macOS) service support
- **Secure**: All messages remain end-to-end encrypted
- **Low Overhead**: Efficient SQLite storage and minimal CPU usage

## 📊 Reward Tiers

| Tier | Storage | Uptime | Pool Share | Multiplier |
|------|---------|--------|------------|------------|
| 🥉 Bronze | 1 GB | 4+ hours/day | 10% | 1.0x |
| 🥈 Silver | 2 GB | 8+ hours/day | 20% | 1.5x |
| 🥇 Gold | 4 GB | 12+ hours/day | 30% | 2.0x |
| 💎 Platinum | 8+ GB | 16+ hours/day | 40% | 3.0x |

**Desktop relay nodes earn significantly more than mobile nodes** because they can maintain higher uptime and storage capacity.

## 🚀 Quick Start

### Prerequisites

- Node.js 18+ ([Download](https://nodejs.org/))
- 100 MCT tokens for staking (required to register as relay)
- Some RAMA for gas fees

### Installation

```bash
# Clone or download the repository
git clone https://github.com/ramestta/mumblechat.git
cd mumblechat/desktop-relay

# Install dependencies
npm install

# Build
npm run build

# Install globally (optional)
npm link
```

### Setup

```bash
# Run the interactive setup wizard
mumblechat-relay setup

# Or manually configure
cp config.example.json config.json
# Edit config.json with your settings
```

### Register on Blockchain

Before running the relay, you need to register on the MumbleChat Registry smart contract:

```bash
# Register as a relay node (requires 100 MCT stake)
mumblechat-relay register --endpoint "tcp://YOUR_PUBLIC_IP:19370" --storage 8192
```

### Start Relaying

```bash
# Start the relay node
mumblechat-relay start

# Or run in background (Linux)
sudo systemctl start mumblechat-relay

# Or run in background (macOS)
launchctl load ~/Library/LaunchAgents/com.mumblechat.relay.plist
```

## 📋 Commands

| Command | Description |
|---------|-------------|
| `mumblechat-relay setup` | Interactive configuration wizard |
| `mumblechat-relay start` | Start the relay node |
| `mumblechat-relay status` | Check relay status and rewards |
| `mumblechat-relay register` | Register as relay on blockchain |
| `mumblechat-relay claim` | Claim pending MCT rewards |
| `mumblechat-relay qr` | Display connection QR code |
| `mumblechat-relay config` | Show current configuration |

## ⚙️ Configuration

Configuration is stored in `config.json`:

```json
{
  "relay": {
    "port": 19370,
    "host": "0.0.0.0",
    "maxConnections": 200,
    "maxStorageGB": 8,
    "messageTTLDays": 7,
    "heartbeatIntervalMs": 300000
  },
  "blockchain": {
    "rpcUrl": "https://rpc.ramestta.com",
    "chainId": 1370,
    "registryAddress": "0x4f8D4955F370881B05b68D2344345E749d8632e3",
    "mctTokenAddress": "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE"
  },
  "wallet": {
    "privateKeyEnvVar": "RELAY_PRIVATE_KEY",
    "keyStorePath": "./keystore"
  },
  "storage": {
    "dbPath": "./data/messages.db",
    "backupPath": "./data/backup"
  },
  "logging": {
    "level": "info",
    "file": "./logs/relay.log",
    "maxSize": "100m",
    "maxFiles": 5
  },
  "api": {
    "enabled": true,
    "port": 19380
  }
}
```

## 🔐 Security

- **Private Key**: Store your private key securely. Use environment variables or encrypted keystore:
  ```bash
  # Environment variable
  export RELAY_PRIVATE_KEY=0x...
  
  # Or keystore (setup wizard creates this)
  mumblechat-relay setup
  ```

- **Firewall**: Open ports 19370 (TCP) and 19371 (WebSocket) for P2P connections

- **Messages**: All relayed messages remain end-to-end encrypted. The relay node cannot read message contents.

## 🖥️ Platform-Specific Installation

### Linux (Ubuntu/Debian)

```bash
# Run the installation script
sudo ./scripts/install-linux.sh

# Configure
sudo nano /etc/mumblechat/relay.env
# Add: RELAY_PRIVATE_KEY=0x...

# Start service
sudo systemctl start mumblechat-relay
sudo systemctl status mumblechat-relay

# View logs
journalctl -u mumblechat-relay -f
```

### macOS

```bash
# Run the installation script
./scripts/install-macos.sh

# Run setup wizard
mumblechat-relay setup

# Start as background service
launchctl load ~/Library/LaunchAgents/com.mumblechat.relay.plist

# View logs
tail -f /usr/local/var/log/mumblechat-relay.log
```

### Windows

1. Run `scripts/install-windows.bat` as Administrator
2. Run `mumblechat-relay setup` to configure
3. Start the relay:
   ```cmd
   mumblechat-relay start
   ```
4. Or use the auto-start script created during installation

## 📦 Building Standalone Binaries

Create standalone executables that don't require Node.js:

```bash
# Build for all platforms
npm run pkg:all

# Or build for specific platform
npm run pkg:mac      # macOS Intel + Apple Silicon
npm run pkg:linux    # Linux x64 + ARM64
npm run pkg:win      # Windows x64
```

Binaries will be in `dist/bin/`.

## 🔧 Docker

```dockerfile
# Dockerfile
FROM node:20-alpine

WORKDIR /app
COPY package*.json ./
RUN npm ci --production
COPY dist ./dist

EXPOSE 19370 19371 19380

CMD ["node", "dist/index.js"]
```

```bash
# Build and run
docker build -t mumblechat-relay .
docker run -d \
  -p 19370:19370 \
  -p 19371:19371 \
  -e RELAY_PRIVATE_KEY=0x... \
  -v mumblechat-data:/app/data \
  mumblechat-relay
```

## 📈 Monitoring

The relay node exposes a REST API for monitoring (when `api.enabled` is true):

```bash
# Get status
curl http://localhost:19380/status

# Get stats
curl http://localhost:19380/stats

# Health check
curl http://localhost:19380/health
```

## 🐛 Troubleshooting

### "Insufficient MCT balance"
You need 100 MCT tokens to stake as a relay node. Get MCT from:
- Ramestta DEX
- Bridge from other chains

### "Connection refused"
- Ensure ports 19370 and 19371 are open in your firewall
- Check that your public IP is correctly set in the endpoint

### "Heartbeat failed"
- Ensure you have RAMA for gas fees
- Check your RPC connection

### "Storage limit reached"
- Increase `maxStorageGB` in config
- Or upgrade your tier for better rewards

## 🤝 Contributing

Contributions are welcome! Please read our contributing guidelines and submit pull requests.

## 📄 License

MIT License - see [LICENSE](LICENSE) file.

## 🔗 Links

- [MumbleChat Protocol Documentation](../docs/MUMBLECHAT_PROTOCOL/)
- [Smart Contracts](../contracts/)
- [Ramestta Blockchain](https://ramestta.com)
- [RamaScan Explorer](https://ramascan.com)

---

**Made with ❤️ for the decentralized future of messaging**
