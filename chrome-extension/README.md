# RamaPay Chrome Extension

<p align="center">
  <img src="icons/icon.svg" alt="RamaPay Logo" width="128"/>
</p>

<p align="center">
  <strong>🔷 Secure Wallet Extension for Ramestta Blockchain</strong>
</p>

## Overview

RamaPay Chrome Extension brings the power of the RamaPay wallet to your browser. Connect to dApps, send and receive RAMA tokens, and manage your crypto assets seamlessly.

## Features

- 🔐 **HD Wallet** - BIP44 compliant hierarchical deterministic wallet
- 💰 **Send & Receive** - Easy RAMA token transfers
- 🌐 **dApp Browser** - Connect to decentralized applications
- 🔄 **Multi-Network** - Support for Ramestta Mainnet, Testnet, and custom networks
- 🔒 **Secure Storage** - AES-256 encrypted key storage
- 📱 **Multiple Accounts** - Derive unlimited accounts from seed phrase
- 💱 **Token Management** - Add and manage custom ERC-20 tokens
- 📊 **Price Tracking** - Real-time price data via CoinGecko
- 🔑 **Export Keys** - Export private key and recovery phrase securely
- 🌐 **Custom Networks** - Add any EVM-compatible network

## Supported Networks

### Built-in Networks

| Network | Chain ID | Symbol |
|---------|----------|--------|
| Ramestta Mainnet | 1370 | RAMA |
| Ramestta Testnet | 1377 | RAMA |

### Custom Networks

You can add any EVM-compatible network by providing:
- Network Name
- RPC URL
- Chain ID
- Currency Symbol
- Block Explorer URL (optional)

## Installation

### From Source (Developer Mode)

1. Clone or download this repository
2. Install dependencies:
   ```bash
   cd chrome-extension
   npm install
   ```

3. Open Chrome and navigate to `chrome://extensions/`
4. Enable "Developer mode" in the top right
5. Click "Load unpacked" and select the `chrome-extension` folder

### From Chrome Web Store

*Coming soon!*

## Development

### Project Structure

```
chrome-extension/
├── manifest.json          # Extension manifest (MV3)
├── package.json           # NPM dependencies
├── background/
│   └── service-worker.js  # Background service worker
├── content/
│   └── inject.js          # Content script for Web3 injection
├── inpage/
│   └── provider.js        # EIP-1193 provider for dApps
├── popup/
│   ├── popup.html         # Main popup UI
│   ├── popup.css          # Styles
│   └── popup.js           # Popup logic
├── lib/
│   └── wallet.js          # Core wallet functionality
└── icons/
    └── icon.svg           # Extension icon
```

### Building

```bash
# Install dependencies
npm install

# For development
npm run dev

# For production build
npm run build
```

### Key Technologies

- **ethers.js** - Ethereum library for wallet operations
- **Web Crypto API** - For secure encryption
- **Manifest V3** - Latest Chrome extension platform
- **EIP-1193** - Ethereum Provider JavaScript API

## Usage

### Creating a Wallet

1. Click the RamaPay extension icon
2. Click "Create New Wallet"
3. Set a strong password
4. **IMPORTANT**: Write down your 12-word recovery phrase
5. Confirm you've saved your phrase

### Importing a Wallet

1. Click "Import Existing Wallet"
2. Choose import method:
   - **Seed Phrase**: Enter your 12/24 word recovery phrase
   - **Private Key**: Enter your private key
3. Set a password for local encryption

### Connecting to dApps

1. Visit any Web3 dApp
2. Click "Connect Wallet"
3. RamaPay will prompt for connection approval
4. Approve the connection to interact with the dApp

### Sending Tokens

1. Click "Send" on the main screen
2. Enter recipient address
3. Enter amount
4. Review transaction details
5. Confirm and sign

### Adding Custom Tokens

1. Go to the main screen "Tokens" tab
2. Click "+" to add a token
3. Enter the token contract address
4. Token details will auto-populate
5. Click "Add Token"

### Managing Networks

1. Go to Settings → Manage Networks
2. View built-in and custom networks
3. Click on any network to switch to it
4. To add a custom network:
   - Click "Add Custom Network"
   - Enter network details (name, RPC, chain ID, symbol)
   - Click "Save Network"

### Security Features

#### Change Password
1. Go to Settings → Change Password
2. Enter current password
3. Enter and confirm new password
4. Click "Update Password"

#### Export Private Key
1. Go to Settings → Export Private Key
2. Enter your password
3. View and copy your private key
4. ⚠️ Never share your private key!

#### Export Recovery Phrase
1. Go to Settings → Export Recovery Phrase
2. Enter your password
3. View your 12-word recovery phrase
4. ⚠️ Store securely offline!

#### Connected Sites
1. Go to Settings
2. View all connected dApp sites
3. Click "Disconnect" to revoke access

## Security

- Private keys are encrypted with AES-256-GCM
- Password-derived keys using PBKDF2 (100,000 iterations)
- Keys never leave your device
- No server-side storage of sensitive data

## API Reference

### EIP-1193 Methods

```javascript
// Request accounts (connect wallet)
const accounts = await ethereum.request({ method: 'eth_requestAccounts' });

// Get connected accounts
const accounts = await ethereum.request({ method: 'eth_accounts' });

// Get chain ID
const chainId = await ethereum.request({ method: 'eth_chainId' });

// Sign message
const signature = await ethereum.request({
  method: 'personal_sign',
  params: [message, address]
});

// Send transaction
const txHash = await ethereum.request({
  method: 'eth_sendTransaction',
  params: [{
    to: '0x...',
    value: '0x...',
    from: address
  }]
});

// Switch network
await ethereum.request({
  method: 'wallet_switchEthereumChain',
  params: [{ chainId: '0x55a' }] // Ramestta Mainnet
});
```

### Events

```javascript
// Listen for account changes
ethereum.on('accountsChanged', (accounts) => {
  console.log('Accounts:', accounts);
});

// Listen for network changes
ethereum.on('chainChanged', (chainId) => {
  console.log('Chain ID:', chainId);
});

// Listen for connection
ethereum.on('connect', (info) => {
  console.log('Connected:', info.chainId);
});

// Listen for disconnection
ethereum.on('disconnect', (error) => {
  console.log('Disconnected:', error);
});
```

## Troubleshooting

### Extension not loading
- Make sure Developer mode is enabled
- Check for console errors in `chrome://extensions/`

### Cannot connect to dApp
- Refresh the dApp page
- Make sure wallet is unlocked
- Check if site is allowed in extension settings

### Transaction failing
- Check if you have enough balance for gas
- Verify recipient address is correct
- Try increasing gas limit

## Contributing

Contributions are welcome! Please read our contributing guidelines before submitting PRs.

1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

## License

MIT License - see [LICENSE](../LICENSE) for details

## Links

- [RamaPay Android App](https://github.com/obidua/RamaPay-android)
- [Ramestta Blockchain](https://ramestta.com)
- [RamaScan Explorer](https://ramascan.com)

---

<p align="center">
  Built with ❤️ for the Ramestta ecosystem
</p>
