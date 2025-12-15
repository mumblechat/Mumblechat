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

## Credits

RamaPay is based on [RamaPay](https://github.com/RamaPay/alpha-wallet-android), an open source Ethereum wallet. We thank the RamaPay team for their excellent foundation.

## License

RamaPay Android is available under the [MIT license](LICENSE). Free for commercial and non-commercial use.

---

**Built with ❤️ for the Ramestta community**
