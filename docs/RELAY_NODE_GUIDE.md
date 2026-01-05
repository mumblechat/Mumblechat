# Running a MumbleChat Relay Node

## Overview

MumbleChat relay nodes are essential infrastructure for the decentralized messaging network. They store and forward encrypted messages for offline users and earn MCT token rewards for their service.

## Node Types

### 1. Browser-Based Node (Easy)
- **Run in your browser** - No installation required
- **Instant setup** - Connect wallet and start earning
- **Lower rewards** - Limited storage and uptime
- **Best for**: Testing, casual users, beginners

### 2. Desktop Node (Recommended)
- **Higher rewards** - Up to 3x more earnings
- **Better uptime** - 24/7 operation
- **More storage** - Up to 8GB+ storage
- **Best for**: Serious node operators

## Browser Node Setup

### Requirements
- MetaMask or compatible Web3 wallet
- Some RAMA tokens for gas fees
- Stable internet connection
- Keep browser tab open while running

### Quick Start

1. **Visit the Node Dashboard**
   ```
   https://mumblechat.com/relay-node.html
   ```

2. **Connect Your Wallet**
   - Click "Connect Wallet"
   - Approve MetaMask connection
   - Wallet will be used for rewards

3. **Select Your Tier**
   - 🥉 **Bronze**: 100 MB storage - 1.0x rewards
   - 🥈 **Silver**: 500 MB storage - 1.5x rewards  
   - 🥇 **Gold**: 1 GB storage - 2.0x rewards
   - 💎 **Platinum**: 2 GB storage - 3.0x rewards

4. **Start Your Node**
   - Click "Start Relay Node"
   - Node begins accepting connections
   - Earnings start accumulating

5. **Keep Running**
   - Keep browser tab open
   - Node runs in background
   - Check stats periodically

### Browser Node Features

✅ **Instant Setup** - No downloads or installation
✅ **Web-Based Dashboard** - Monitor stats in real-time
✅ **Automatic Updates** - Always running latest version
✅ **Mobile Compatible** - Run on phone or tablet
✅ **Safe & Secure** - Keys never leave your browser

⚠️ **Limitations**
- Lower storage capacity (max 2GB)
- Requires tab to stay open
- Lower rewards compared to desktop
- Cannot run 24/7 easily

## Desktop Node Setup

### Requirements
- Node.js 18+ installed
- 100 MCT tokens for staking
- Some RAMA for gas fees
- Dedicated machine (recommended)

### Installation

1. **Download Node Software**
   ```bash
   git clone https://github.com/ramestta/mumblechat.git
   cd mumblechat/desktop-relay
   npm install
   ```

2. **Configure Node**
   ```bash
   npm run cli -- setup
   ```
   Follow the interactive wizard:
   - Set storage capacity (1-8GB+)
   - Configure network port
   - Import or create wallet
   - Set monitoring API

3. **Start Node**
   ```bash
   npm run cli -- start
   ```
   Or use PM2 for 24/7 operation:
   ```bash
   pm2 start npm --name "mumblechat-relay" -- run start
   pm2 save
   pm2 startup
   ```

### Desktop Node Features

✅ **Higher Rewards** - Up to 3x browser nodes
✅ **More Storage** - 1GB to 8GB+ capacity
✅ **24/7 Operation** - Run continuously
✅ **Better Uptime** - Dedicated hardware
✅ **Auto-Start** - System service support
✅ **Advanced Monitoring** - Web dashboard + API

### Configuration Options

**config.json**
```json
{
  "relay": {
    "port": 19371,           // P2P port
    "host": "0.0.0.0",      // Bind address
    "maxStorageGB": 8,      // Storage limit
    "maxConnections": 200   // Max peers
  },
  "blockchain": {
    "rpcUrl": "https://rpc.ramestta.com",
    "chainId": 1370
  },
  "api": {
    "enabled": true,
    "port": 19380           // Monitoring API
  }
}
```

**.env**
```bash
# Your relay wallet private key
RELAY_PRIVATE_KEY=0x...

# Optional: Custom RPC
RELAY_RPC_URL=https://rpc.ramestta.com

# Optional: Log level
LOG_LEVEL=info
```

## Reward System

### How Rewards Work

1. **Message Relaying**
   - Earn MCT for each message relayed
   - Base reward: 0.001 - 0.01 MCT per message
   - Multiplied by your tier multiplier

2. **Tier Multipliers**
   - Bronze (100MB): 1.0x
   - Silver (500MB): 1.5x
   - Gold (1GB): 2.0x
   - Platinum (2GB+): 3.0x

3. **Uptime Bonus**
   - 24-hour operation: +10% bonus
   - 7-day streak: +25% bonus
   - 30-day streak: +50% bonus

4. **Daily Pool Distribution**
   - 100 MCT distributed daily
   - Split proportionally among active nodes
   - Based on: storage × uptime × messages

### Estimated Earnings

**Browser Node (Silver Tier)**
- Storage: 500 MB
- Uptime: 8 hours/day
- Messages: ~100/day
- **Earnings: ~0.5 MCT/day** (~$0.50/day)

**Desktop Node (Platinum Tier)**
- Storage: 8 GB  
- Uptime: 24 hours/day
- Messages: ~500/day
- **Earnings: ~5-10 MCT/day** (~$5-10/day)

*Earnings estimates based on current network activity*

## Best Practices

### For Maximum Rewards

1. **Choose Higher Tier** - More storage = more rewards
2. **Maximize Uptime** - Run 24/7 for bonus multipliers
3. **Stable Connection** - Ensure reliable internet
4. **Monitor Stats** - Check dashboard regularly
5. **Update Software** - Keep node up to date

### Security Tips

1. **Protect Private Key** - Never share with anyone
2. **Use Dedicated Wallet** - Separate from main wallet
3. **Backup Configuration** - Save config files
4. **Monitor Resources** - Watch CPU/memory usage
5. **Firewall Rules** - Open only required ports

### Troubleshooting

**Node won't start**
- Check if port 19371 is available
- Verify wallet has RAMA for gas
- Ensure Node.js version 18+

**No connections**
- Check firewall settings
- Verify port forwarding
- Ensure public IP is reachable

**Low earnings**
- Increase storage tier
- Improve uptime
- Check network connectivity

## Monitoring

### Browser Node Dashboard
- Real-time stats
- Peer connections
- Messages relayed
- Storage usage
- Current earnings

### Desktop Node API

Access monitoring API at `http://localhost:19380/api/stats`

```json
{
  "status": "running",
  "uptime": 86400,
  "peers": 47,
  "messagesRelayed": 1234,
  "storageUsed": "2.4 GB",
  "tier": "platinum",
  "earnings": "8.42 MCT"
}
```

### CLI Commands

```bash
# Check node status
npm run cli -- status

# View earnings
npm run cli -- earnings

# List connections
npm run cli -- peers

# Export data
npm run cli -- export

# Stop node
npm run cli -- stop
```

## Node Registration

### On-Chain Registration (Required)

Before earning rewards, nodes must register on the MumbleChat Registry contract:

1. **Stake MCT Tokens**
   - Bronze: 100 MCT
   - Silver: 500 MCT
   - Gold: 1000 MCT
   - Platinum: 2500 MCT

2. **Register Node**
   - Submit wallet address
   - Set storage capacity
   - Specify tier level

3. **Activate**
   - Node becomes discoverable
   - Starts accepting connections
   - Begins earning rewards

### Registration via Browser
1. Visit relay-node.html
2. Connect wallet
3. Click "Register Node"
4. Approve MCT stake transaction
5. Confirm registration
6. Start earning!

### Registration via CLI
```bash
npm run cli -- register --tier platinum --stake 2500
```

## FAQ

**Q: How much can I earn?**
A: Depends on tier, uptime, and network activity. Browser nodes: $0.50-2/day. Desktop nodes: $5-20/day.

**Q: Do I need to stake MCT?**
A: Yes, registration requires staking based on tier (100-2500 MCT).

**Q: Can I run multiple nodes?**
A: Yes! Use different wallets for each node.

**Q: What happens if I go offline?**
A: Rewards pause. Uptime bonuses reset. Messages are redistributed to other nodes.

**Q: Are messages private?**
A: Yes! All messages are end-to-end encrypted. Relay nodes cannot read content.

**Q: How often are rewards paid?**
A: Rewards accumulate continuously and can be claimed anytime.

**Q: Can I upgrade my tier?**
A: Yes! Increase storage and stake more MCT to upgrade.

## Support

- **Documentation**: https://mumblechat.com/docs
- **Discord**: https://discord.gg/mumblechat
- **Telegram**: https://t.me/mumblechat
- **GitHub**: https://github.com/ramestta/mumblechat

## Contract Addresses

- **Ramestta Mainnet**
  - Registry: `0x4f8D4955F370881B05b68D2344345E749d8632e3`
  - MCT Token: `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE`
  - Chain ID: `1370`

---

**Ready to start earning? [Launch Browser Node →](https://mumblechat.com/relay-node.html)**
