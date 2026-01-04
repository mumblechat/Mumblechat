# RamaPay - Ramestta Blockchain Wallet for Android

[![MIT license](https://img.shields.io/badge/License-MIT-blue.svg)](https://github.com/obidua/RamaPay-android/blob/master/LICENSE)

RamaPay is an open source mobile wallet for the Ramestta blockchain ecosystem. Built for users who want a secure, easy-to-use wallet for managing RAMA tokens and interacting with the Ramestta network.

## About RamaPay - Features

Easy to use and secure wallet for Android with native token support for the Ramestta ecosystem.

**Supported Networks:**
- Ramestta Mainnet (Chain ID: 1370)
- Ramestta Testnet (Pingaksha - Chain ID: 1377)
- Ethereum and other EVM-compatible networks

**Key Features:**
- 🔐 Secure HD Wallet with BIP44 derivation
- 💰 Send and receive RAMA tokens
- 🌐 Built-in Web3 dApp Browser
- 📱 Beginner Friendly interface
- 🔒 Biometric authentication support
- 📋 Multiple account management
- 🔄 Bulk wallet creation (1-50 accounts)

## Getting Started

### Prerequisites
1. [Download](https://developer.android.com/studio/) Android Studio
2. JDK 17 (recommended: JetBrains JDK)

### Build Instructions

1. Clone this repository:
```bash
git clone https://github.com/obidua/RamaPay-android.git
cd RamaPay-android
```

2. Generate a GitHub [Personal Access Token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token) with `read:packages, read:user` permission

3. Edit `~/.gradle/gradle.properties` and add:
```properties
gpr.user=Your GitHub Email
gpr.key=Your GitHub Personal Access Token
```

4. Build the project:
```bash
./gradlew assembleNoAnalyticsDebug
```

Or open the project in Android Studio and build from there.

### Installing on Device

```bash
adb install -r app/build/outputs/apk/noAnalytics/debug/RamaPay.apk
```

## Project Structure

```
RamaPay/
├── app/                    # Main Android application
│   └── src/main/java/com/ramapay/app/
├── lib/                    # Token and Ethereum utilities
│   └── src/main/java/com/ramapay/
├── dmz/                    # TokenScript web handling
├── hardware_stub/          # Hardware wallet stub
└── util/                   # Utility modules
```

## How to Contribute

You can submit feedback and report bugs as GitHub issues. Please include:
- Device model and Android version
- Steps to reproduce the issue
- Screenshots if applicable

### Pull Requests

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## Ramestta Network Information

| Network | Chain ID | RPC URL | Explorer |
|---------|----------|---------|----------|
| Mainnet | 1370 | https://blockchain.rfrm.io | https://ramascan.com |
| Testnet | 1377 | https://testnet.rfrm.io | https://pingaksha.ramascan.com |

## MumbleChat Protocol Integration

RamaPay includes **MumbleChat** - a fully decentralized, wallet-native messaging protocol.

### Smart Contracts (Deployed ✅)

| Contract | Proxy Address | Version |
|----------|---------------|---------|
| MumbleChatRegistry | `0x4f8D4955F370881B05b68D2344345E749d8632e3` | V3.2 |
| MCTToken | `0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE` | V3.0 |

### Relay Node Tier System

| Tier | Storage | Uptime | Pool Share | Multiplier |
|------|---------|--------|------------|------------|
| 🥉 Bronze | 1 GB | 4+ hours | 10% | 1.0x |
| 🥈 Silver | 2 GB | 8+ hours | 20% | 1.5x |
| 🥇 Gold | 4 GB | 12+ hours | 30% | 2.0x |
| 💎 Platinum | 8+ GB | 16+ hours | 40% | 3.0x |

### Features
- **Wallet-to-Wallet Chat**: E2E encrypted messaging
- **Relay Node Rewards**: Earn MCT for relaying messages
- **GB-Scale Tiers**: Higher storage = bigger daily pool share
- **Chrome Extension**: MumbleChat browser integration

📖 Full documentation: [docs/MUMBLECHAT_PROTOCOL/](docs/MUMBLECHAT_PROTOCOL/)

## Fork & Customize Your Own Wallet

RamaPay is designed to be easily forked and customized. Create your own branded blockchain wallet in minutes!

### Quick Start for Forking

1. **Fork this repository** on GitHub

2. **Clone your fork:**
```bash
git clone https://github.com/YOUR_USERNAME/RamaPay-android.git
cd RamaPay-android
```

3. **Customize branding** - Edit these key files:

| File | What to Change |
|------|----------------|
| `app/build.gradle` | `applicationId` (e.g., `com.yourapp.wallet`) |
| `app/src/main/res/values/strings.xml` | App name and strings |
| `app/src/main/res/mipmap-*` | App icons (all sizes) |
| `app/src/main/java/com/ramapay/app/entity/MediaLinks.java` | Social media links |
| `app/src/main/java/com/ramapay/app/C.java` | URLs and constants |
| `app/src/main/res/raw/` | Splash animation (Lottie JSON) |

4. **Add your own blockchain network** in:
```
lib/src/main/java/com/ramapay/ethereum/EthereumNetworkBase.java
```

5. **Build and publish:**
```bash
./gradlew assembleRelease
```

### Keeping Your Fork Updated

Stay up-to-date with RamaPay improvements:

```bash
# Add RamaPay as upstream (one time only)
git remote add upstream https://github.com/obidua/RamaPay-android.git

# Fetch and merge updates
git fetch upstream
git merge upstream/master

# Resolve any conflicts and push
git push origin master
```

### Full Rebrand (Optional)

If you want to completely rebrand the package names:

1. Rename directories: `com/ramapay` → `com/yourpackage`
2. Update all `package` declarations and `import` statements
3. Update `namespace` in all `build.gradle` files
4. Rebuild `hardware_stub` AAR: `./gradlew :hardware_stub:assembleDebug`
5. Copy new AAR to `app/libs/`

> 💡 **Tip:** Use find-and-replace across the entire project for package renaming.

## Credits

RamaPay is based on [AlphaWallet](https://github.com/AlphaWallet/alpha-wallet-android), an open source Ethereum wallet. We thank the AlphaWallet team for their excellent foundation.

## License

RamaPay Android is available under the [MIT license](LICENSE). Free for commercial and non-commercial use.

---

**Built with ❤️ for the Ramestta community**
