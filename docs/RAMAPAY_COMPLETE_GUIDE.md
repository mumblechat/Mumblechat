# RamaPay - Complete Documentation Guide

<p align="center">
  <img src="../RamaLogo/RamaPay.png" alt="RamaPay Logo" width="200"/>
</p>

<p align="center">
  <strong>🔐 The Official Wallet for Ramestta Blockchain</strong>
</p>

<p align="center">
  <a href="https://github.com/obidua/RamaPay-android/blob/master/LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="MIT license"/>
  </a>
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Platform"/>
  <img src="https://img.shields.io/badge/Version-3.89-orange.svg" alt="Version"/>
</p>

---

## 📋 Table of Contents

1. [Introduction](#-introduction)
2. [Key Features](#-key-features)
3. [Supported Networks](#-supported-networks)
4. [HD Wallet System](#-hd-wallet-system)
   - [What is an HD Wallet?](#what-is-an-hd-wallet)
   - [BIP44 Derivation Path](#bip44-derivation-path)
   - [Creating a New HD Wallet](#creating-a-new-hd-wallet)
   - [Backing Up Your Seed Phrase](#backing-up-your-seed-phrase)
5. [Wallet Management](#-wallet-management)
   - [Import Wallet Options](#import-wallet-options)
   - [Import HD Wallet (Seed Phrase)](#import-hd-wallet-seed-phrase)
   - [Import Private Key (Legacy Wallet)](#import-private-key-legacy-wallet)
   - [Import Keystore JSON](#import-keystore-json)
   - [Watch-Only Wallet](#watch-only-wallet)
6. [Master Wallet & Sub-Accounts](#-master-wallet--sub-accounts)
   - [Understanding Master Wallet](#understanding-master-wallet)
   - [Adding Sub-Accounts](#adding-sub-accounts-to-master-wallet)
   - [Bulk Account Creation](#bulk-account-creation-1-50-accounts)
   - [Multiple Master Wallets](#importing-multiple-master-hd-wallets)
7. [Unlimited Private Key Wallets](#-unlimited-private-key-legacy-wallets)
8. [Hardware Wallet Support](#-hardware-wallet-support)
9. [Security Features](#-security-features)
10. [dApp Browser & Web3](#-dapp-browser--web3)
11. [Ramestta Proof of Stake (PoS)](#-ramestta-proof-of-stake-pos-system)
    - [What is PoS?](#what-is-proof-of-stake)
    - [How Ramestta PoS Works](#how-ramestta-pos-works)
    - [PoS vs PoW Comparison](#pos-vs-pow-comparison)
    - [How PoS Will Change the World](#how-pos-will-change-the-world)
12. [TokenScript Integration](#-tokenscript-integration)
13. [Universal Links](#-universal-links)
14. [Technical Specifications](#-technical-specifications)
15. [Network Configuration](#-network-configuration)
16. [Social Links & Support](#-social-links--support)
17. [For Developers](#-for-developers)
18. [License](#-license)

---

## 🌟 Introduction

**RamaPay** is the official open-source mobile wallet designed specifically for the **Ramestta blockchain ecosystem**. Built with security and usability at its core, RamaPay provides a comprehensive solution for managing RAMA tokens, interacting with decentralized applications (dApps), and participating in the Ramestta network.

### Why RamaPay?

- **Native Ramestta Support**: First-class integration with Ramestta Mainnet and Testnet
- **Self-Custody**: You own your keys, you own your crypto
- **Open Source**: Fully transparent, MIT licensed, community-driven
- **Multi-Network**: Support for 50+ EVM-compatible blockchains
- **Enterprise Ready**: Bulk wallet creation for businesses and power users

---

## 🚀 Key Features

### 🔐 Security First
| Feature | Description |
|---------|-------------|
| **HD Wallet** | Hierarchical Deterministic wallet with BIP44 standard |
| **Biometric Auth** | Fingerprint and Face unlock support |
| **TEE/StrongBox** | Hardware-backed key storage on supported devices |
| **Encrypted Storage** | AES-256 encryption for all sensitive data |
| **No Server Keys** | All private keys stored locally on device |

### 💰 Token Management
| Feature | Description |
|---------|-------------|
| **Native RAMA** | Send, receive, and manage RAMA tokens |
| **ERC-20** | Full support for fungible tokens |
| **ERC-721** | NFT viewing and transfers |
| **ERC-1155** | Multi-token standard support |
| **ERC-875** | Batch transfer support |

### 🌐 Web3 & DeFi
| Feature | Description |
|---------|-------------|
| **dApp Browser** | Built-in Web3 browser |
| **WalletConnect** | Connect to desktop dApps |
| **TokenScript** | Enhanced token interactions |
| **Swap Integration** | DEX integration support |

### 👥 Account Management
| Feature | Description |
|---------|-------------|
| **Multiple Masters** | Import unlimited HD wallets |
| **Sub-Accounts** | Derive accounts from master |
| **Bulk Creation** | Create 1-50 accounts at once |
| **Legacy Import** | Import unlimited private keys |
| **Watch Only** | Monitor any address |

---

## 🌐 Supported Networks

### Primary Network: Ramestta

| Network | Chain ID | Symbol | RPC URL | Explorer |
|---------|----------|--------|---------|----------|
| **Ramestta Mainnet** | 1370 | RAMA | https://blockchain.ramestta.com | https://ramascan.com |
| **Ramestta Testnet** | 1369 | RAMA | https://testnet.ramestta.com | https://testnet.ramascan.com |

**Backup RPC Endpoints:**
- Primary: `https://blockchain.ramestta.com`
- Secondary: `https://blockchain2.ramestta.com`
- Testnet: `https://testnet.ramestta.com`

### Additional Supported Networks

<details>
<summary>Click to expand full network list (50+ networks)</summary>

| Network | Chain ID | Symbol |
|---------|----------|--------|
| Ethereum Mainnet | 1 | ETH |
| Ethereum Classic | 61 | ETC |
| Binance Smart Chain | 56 | BNB |
| Polygon | 137 | MATIC |
| Avalanche C-Chain | 43114 | AVAX |
| Fantom Opera | 250 | FTM |
| Arbitrum One | 42161 | AETH |
| Optimism | 10 | ETH |
| Gnosis Chain | 100 | xDAI |
| Cronos | 25 | CRO |
| Aurora | 1313161554 | ETH |
| Kaia (Klaytn) | 8217 | KAIA |
| IoTeX | 4689 | IOTX |
| Base | 8453 | ETH |
| Linea | 59144 | ETH |
| Mantle | 5000 | MNT |
| Mint | 185 | ETH |
| Rootstock | 30 | RBTC |
| Palm | 11297108109 | PALM |
| Milkomeda | 2001 | milkADA |
| OKX Chain | 66 | OKT |
| *...and 20+ testnets* | | |

</details>

---

## 🔑 HD Wallet System

### What is an HD Wallet?

**Hierarchical Deterministic (HD) Wallet** is a system that allows you to generate an unlimited number of cryptocurrency addresses from a single master seed phrase. This is the most secure and convenient way to manage your crypto assets.

```
                    Master Seed (12/24 words)
                              │
                              ▼
                    ┌─────────────────┐
                    │   Master Key    │
                    │  (Never stored) │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
        ┌──────────┐   ┌──────────┐   ┌──────────┐
        │ Account 0│   │ Account 1│   │ Account 2│
        │  (Main)  │   │ (Savings)│   │(Business)│
        └──────────┘   └──────────┘   └──────────┘
```

### Benefits of HD Wallet

| Benefit | Description |
|---------|-------------|
| **Single Backup** | One seed phrase backs up ALL accounts forever |
| **Unlimited Addresses** | Generate billions of addresses from one seed |
| **Enhanced Privacy** | Use different addresses for different purposes |
| **Easy Recovery** | Restore entire wallet with just 12/24 words |
| **Organized** | Keep personal, business, and savings separate |

### BIP44 Derivation Path

RamaPay uses the **BIP44** standard for deriving Ethereum-compatible addresses:

```
m / 44' / 60' / 0' / 0 / address_index
│    │     │     │    │        │
│    │     │     │    │        └── Account Index (0, 1, 2...)
│    │     │     │    └─────────── Change (always 0 for ETH)
│    │     │     └──────────────── Account (always 0')
│    │     └────────────────────── Coin Type (60 = Ethereum)
│    └──────────────────────────── Purpose (44 = BIP44)
└───────────────────────────────── Master Key
```

**Example Derivation Paths:**
| Account | Path | Use Case |
|---------|------|----------|
| Account 0 | `m/44'/60'/0'/0/0` | Main wallet (default) |
| Account 1 | `m/44'/60'/0'/0/1` | Secondary/Savings |
| Account 2 | `m/44'/60'/0'/0/2` | Business transactions |
| Account N | `m/44'/60'/0'/0/N` | Any purpose |

### Creating a New HD Wallet

Follow these steps to create a new secure HD wallet:

#### Step 1: Open RamaPay
Launch the app and tap **"Create New Wallet"** on the welcome screen or go to:
`Settings → Wallets → Add Wallet → Create New Wallet`

#### Step 2: Security Authentication
- If device has biometrics enabled, authenticate with fingerprint/face
- Otherwise, enter device PIN/pattern
- This creates a secure enclave for your keys

#### Step 3: View Your Seed Phrase
⚠️ **CRITICAL SECURITY STEP**

```
┌─────────────────────────────────────────────────────┐
│                  YOUR SEED PHRASE                    │
│                                                      │
│  1. abandon   2. ability   3. able    4. about      │
│  5. above     6. absent    7. absorb  8. abstract   │
│  9. absurd   10. abuse    11. access  12. accident  │
│                                                      │
│  ⚠️ NEVER share these words with anyone!            │
│  ⚠️ Write them down on PAPER, not digitally!        │
│  ⚠️ Store in a secure, fireproof location!          │
└─────────────────────────────────────────────────────┘
```

#### Step 4: Verify Your Seed Phrase
- The app will ask you to confirm some words
- This ensures you've properly recorded them
- Example: "Enter word #3" → "able"

#### Step 5: Wallet Created!
Your HD wallet is now ready with:
- ✅ First account (Account 0) automatically created
- ✅ Seed phrase encrypted and stored securely
- ✅ Ready to receive RAMA and other tokens

### Backing Up Your Seed Phrase

**DO:**
- ✅ Write on paper with pen (not pencil)
- ✅ Make 2-3 copies
- ✅ Store in separate secure locations
- ✅ Consider fireproof/waterproof storage
- ✅ Consider metal backup plates for fire resistance

**DON'T:**
- ❌ Take screenshots
- ❌ Store in cloud (iCloud, Google Drive)
- ❌ Store in notes/password apps
- ❌ Email or text to yourself
- ❌ Store all copies in one location

---

## 📱 Wallet Management

### Import Wallet Options

RamaPay supports multiple ways to import existing wallets:

| Import Method | Type | Use Case |
|--------------|------|----------|
| **Seed Phrase** | HD Wallet | Import from other wallets (MetaMask, Trust) |
| **Private Key** | Legacy | Import single addresses |
| **Keystore JSON** | Legacy | Import from Geth/MyEtherWallet |
| **Watch Only** | View Only | Monitor any address |
| **Hardware** | Hardware | Ledger/Trezor support |

### Import HD Wallet (Seed Phrase)

Restore your entire wallet from a seed phrase:

#### Step 1: Navigate to Import
`Settings → Wallets → Add Wallet → Import Wallet`

#### Step 2: Select "Seed Phrase"

#### Step 3: Enter Your 12 or 24 Word Phrase

```
┌─────────────────────────────────────────────────────┐
│              ENTER YOUR SEED PHRASE                  │
│                                                      │
│  ┌─────────────────────────────────────────────┐    │
│  │ abandon ability able about above absent     │    │
│  │ absorb abstract absurd abuse access         │    │
│  │ accident...                                 │    │
│  └─────────────────────────────────────────────┘    │
│                                                      │
│  Words: 12/12  ✅ Valid seed phrase                  │
│                                                      │
│  [        IMPORT WALLET        ]                     │
└─────────────────────────────────────────────────────┘
```

#### Step 4: Account Discovery
After importing, RamaPay will:
1. Scan all derived addresses for transaction history
2. Automatically add accounts that have been used
3. Show discovered accounts for selection

#### Step 5: Confirm Import
Your HD wallet is restored with all discovered accounts!

### Import Private Key (Legacy Wallet)

Import a single wallet address using its private key:

#### Step 1: Navigate to Import
`Settings → Wallets → Add Wallet → Import Wallet`

#### Step 2: Select "Private Key"

#### Step 3: Enter Your Private Key
```
┌─────────────────────────────────────────────────────┐
│              ENTER PRIVATE KEY                       │
│                                                      │
│  ┌─────────────────────────────────────────────┐    │
│  │ 0x7f5c8a9b2e4d6f8a1c3e5b7d9f0a2c4e6...      │    │
│  └─────────────────────────────────────────────┘    │
│                                                      │
│  ⚠️ Keep your private key secure!                   │
│                                                      │
│  [        IMPORT WALLET        ]                     │
└─────────────────────────────────────────────────────┘
```

**Private Key Formats Supported:**
- With 0x prefix: `0x7f5c8a9b...`
- Without prefix: `7f5c8a9b...`
- 64 hexadecimal characters (32 bytes)

#### Step 4: Set Authentication
- Configure biometric/PIN lock for the wallet

#### Step 5: Legacy Wallet Ready!
- Wallet type: `KEYSTORE_LEGACY`
- Can export private key if needed
- Works on all supported networks

### Import Keystore JSON

Import a wallet from a JSON keystore file (from Geth, MyEtherWallet, etc.):

#### Step 1: Select "Keystore JSON"

#### Step 2: Paste or Load JSON Content
```json
{
  "version": 3,
  "id": "your-wallet-id",
  "address": "your-address",
  "crypto": {
    "ciphertext": "...",
    "cipherparams": { "iv": "..." },
    "cipher": "aes-128-ctr",
    "kdf": "scrypt",
    "kdfparams": { ... },
    "mac": "..."
  }
}
```

#### Step 3: Enter Keystore Password
The password used when creating the keystore file

#### Step 4: Import Complete!

### Watch-Only Wallet

Monitor any blockchain address without access to its private keys:

#### Step 1: Select "Watch Only"

#### Step 2: Enter Address
```
0xde0B295669a9FD93d5F28D9Ec85E40f4cb697BAe
```

Or scan a QR code containing the address.

#### Features of Watch Wallets:
- ✅ View balance and transactions
- ✅ Monitor multiple addresses
- ✅ No private key required
- ❌ Cannot sign transactions
- ❌ Cannot send tokens

---

## 👥 Master Wallet & Sub-Accounts

### Understanding Master Wallet

The **Master Wallet** is your primary HD wallet from which all sub-accounts are derived. Think of it as the "parent" wallet.

```
┌───────────────────────────────────────────────────────┐
│                    MASTER WALLET                       │
│               (Your 12/24 word seed)                   │
│                                                        │
│  ┌─────────────────────────────────────────────────┐  │
│  │  Address: 0xMaster...ABC                        │  │
│  │  Type: HDKEY                                    │  │
│  │  Index: 0 (Default)                             │  │
│  └─────────────────────────────────────────────────┘  │
│                          │                             │
│           ┌──────────────┼──────────────┐              │
│           ▼              ▼              ▼              │
│    ┌───────────┐  ┌───────────┐  ┌───────────┐        │
│    │ Sub-Acc 1 │  │ Sub-Acc 2 │  │ Sub-Acc 3 │        │
│    │ Index: 1  │  │ Index: 2  │  │ Index: 3  │        │
│    └───────────┘  └───────────┘  └───────────┘        │
│                                                        │
└───────────────────────────────────────────────────────┘
```

### Adding Sub-Accounts to Master Wallet

#### Method 1: Quick Add Single Account

1. Go to `Settings → Wallets`
2. Tap the **"+"** button
3. Select **"Add Account"**
4. If multiple master wallets exist, select which one
5. New account is created at the next available index

#### Method 2: From Wallet Selection Dialog

1. In Wallet Management, tap **"Add Account"**
2. Select the master wallet to derive from
3. Tap **"Derive New Account"**
4. Account is created and added to your list

### Bulk Account Creation (1-50 Accounts)

RamaPay's unique feature allows creating multiple accounts at once:

#### Step-by-Step Bulk Creation:

1. **Open Wallet Manager**
   `Settings → Wallets → "+" → Add Account`

2. **Select Master Wallet**
   Choose which HD wallet to derive from

3. **Tap "Add Bulk Wallets"**

4. **Enter Number of Accounts**
   ```
   ┌─────────────────────────────────────────┐
   │        ADD BULK WALLETS                  │
   │                                          │
   │   Master: Main Wallet                    │
   │   Next Index: 5                          │
   │                                          │
   │   How many wallets to add?               │
   │   ┌─────────────────────┐                │
   │   │        10           │                │
   │   └─────────────────────┘                │
   │                                          │
   │   (Maximum 50 per batch)                 │
   │                                          │
   │   [ Cancel ]    [ Add 10 Wallets ]       │
   └─────────────────────────────────────────┘
   ```

5. **Progress Display**
   ```
   Adding wallet index 5... (1/10)
   Adding wallet index 6... (2/10)
   ...
   Adding wallet index 14... (10/10)
   ✓ Successfully added 10 wallets
   ```

#### Bulk Creation Use Cases:

| Use Case | Accounts | Description |
|----------|----------|-------------|
| Personal | 2-5 | Main, savings, DeFi, NFTs |
| Business | 10-20 | Department wallets |
| Exchange | 50+ | Customer deposit addresses |
| Gaming | 5-10 | Different game accounts |
| Privacy | 10+ | One-time receive addresses |

### Importing Multiple Master HD Wallets

RamaPay supports importing **unlimited** independent HD wallets:

#### Why Multiple Master Wallets?

- 🏢 **Business Separation**: Keep company and personal funds separate
- 👥 **Family Wallets**: Manage family members' wallets
- 🔒 **Security Layers**: Different security levels for different amounts
- 🧪 **Testing**: Mainnet vs testnet wallets

#### How to Import Additional HD Wallets:

1. **Already Have One Master Wallet**
2. Go to `Settings → Wallets → "+" → Import Wallet`
3. Select **"Seed Phrase"**
4. Enter the 12/24 word seed for the NEW wallet
5. This creates a SECOND master wallet

```
┌─────────────────────────────────────────────────────────┐
│                  YOUR WALLETS                            │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ 🔐 Master Wallet 1 (Personal)                   │    │
│  │    ├── Account 0: 0xABC...123                   │    │
│  │    ├── Account 1: 0xDEF...456                   │    │
│  │    └── Account 2: 0xGHI...789                   │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ 🔐 Master Wallet 2 (Business)                   │    │
│  │    ├── Account 0: 0xJKL...012                   │    │
│  │    └── Account 1: 0xMNO...345                   │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
│  ┌─────────────────────────────────────────────────┐    │
│  │ 🔐 Master Wallet 3 (DeFi)                       │    │
│  │    └── Account 0: 0xPQR...678                   │    │
│  └─────────────────────────────────────────────────┘    │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

---

## 🔓 Unlimited Private Key (Legacy) Wallets

RamaPay allows importing **unlimited private key wallets** alongside your HD wallets:

### What are Legacy Wallets?

Legacy wallets (also called "imported wallets") are standalone addresses imported via their private keys. Unlike HD wallets, each legacy wallet:

- Has its own independent private key
- Must be backed up separately
- Cannot derive sub-accounts
- Is stored with type `KEYSTORE_LEGACY`

### Import Unlimited Private Keys

```
┌─────────────────────────────────────────────────────────┐
│                  YOUR WALLETS                            │
│                                                          │
│  ── HD WALLETS ──────────────────────────────────────   │
│  🔐 Master Wallet                                        │
│     └── 3 derived accounts                               │
│                                                          │
│  ── LEGACY WALLETS (Imported Private Keys) ──────────   │
│  🔑 Trading Wallet    0xAAA...111                        │
│  🔑 Cold Storage      0xBBB...222                        │
│  🔑 Mining Rewards    0xCCC...333                        │
│  🔑 Airdrop Wallet    0xDDD...444                        │
│  🔑 Old Wallet        0xEEE...555                        │
│  ... (unlimited)                                         │
│                                                          │
│  ── WATCH ONLY ──────────────────────────────────────   │
│  👁️ Exchange Hot      0xFFF...666                        │
│  👁️ Whale Tracker     0xGGG...777                        │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Exporting Private Keys

For `KEYSTORE_LEGACY` type wallets, you can export the private key:

1. Go to `Settings → Wallets`
2. Select the legacy wallet
3. Tap **"Export Private Key"**
4. Authenticate with biometrics/PIN
5. View and copy your private key

⚠️ **Warning**: Never share your private key with anyone!

---

## 🔧 Hardware Wallet Support

RamaPay supports hardware wallet integration for maximum security:

### Supported Hardware

| Device | Status | Features |
|--------|--------|----------|
| **Generic NFC Cards** | ✅ Supported | Sign transactions via NFC |
| **Ledger Nano** | 🔄 Planned | USB-C/Bluetooth |
| **Trezor** | 🔄 Planned | USB-C |

### Using Hardware Wallets

1. **Enable Hardware Card**
   - Go to `Settings → Wallets → Add → Hardware Card`
   
2. **Tap Card to Device**
   - Hold NFC card to phone's NFC reader
   
3. **Sign Transactions**
   - Hardware device signs, never exposes keys

---

## 🔒 Security Features

### Multi-Layer Security Architecture

```
┌─────────────────────────────────────────────────────────┐
│                  SECURITY LAYERS                         │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Layer 1: DEVICE SECURITY                                │
│  ├── Biometric Authentication (Fingerprint/Face)         │
│  ├── Device PIN/Pattern Lock                             │
│  └── Android Keystore Integration                        │
│                                                          │
│  Layer 2: KEY STORAGE                                    │
│  ├── TEE (Trusted Execution Environment)                 │
│  ├── StrongBox (Hardware Security Module)                │
│  └── AES-256-GCM Encryption                              │
│                                                          │
│  Layer 3: KEY DERIVATION                                 │
│  ├── BIP44 HD Wallet Standard                            │
│  ├── PBKDF2/Scrypt Key Derivation                        │
│  └── Secure Random Number Generation                     │
│                                                          │
│  Layer 4: TRANSACTION SECURITY                           │
│  ├── On-Device Signing (Keys never leave device)         │
│  ├── Transaction Preview & Verification                  │
│  └── dApp Permission Management                          │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Authentication Levels

| Level | Security | Use Case |
|-------|----------|----------|
| `STRONGBOX_AUTHENTICATION` | Highest | Hardware-backed, biometric required |
| `STRONGBOX_NO_AUTHENTICATION` | High | Hardware-backed, no biometric |
| `TEE_AUTHENTICATION` | Medium-High | Software secure enclave, biometric |
| `TEE_NO_AUTHENTICATION` | Medium | Software secure enclave |
| `NOT_SET` | Basic | Fallback mode |

### Key Storage Security

```java
// Keys are stored using Android Keystore with:
- AES-256-GCM encryption
- Hardware-backed storage (when available)
- Non-exportable key material
- User authentication binding
```

---

## 🌐 dApp Browser & Web3

### Built-in Web3 Browser

RamaPay includes a full-featured dApp browser:

```
┌─────────────────────────────────────────────────────────┐
│  ← → 🔄                   🔒 app.uniswap.org          ⋮ │
├─────────────────────────────────────────────────────────┤
│                                                          │
│                    ┌─────────────┐                       │
│                    │   Uniswap   │                       │
│                    └─────────────┘                       │
│                                                          │
│       [ Connect Wallet ]                                 │
│                                                          │
│  ┌──────────────────────────────────────────────────┐   │
│  │  Swap                                             │   │
│  │  ┌──────────────────────────────────────────┐    │   │
│  │  │  From: ETH                        1.0    │    │   │
│  │  └──────────────────────────────────────────┘    │   │
│  │                    ↓                              │   │
│  │  ┌──────────────────────────────────────────┐    │   │
│  │  │  To: RAMA                      ~1,250    │    │   │
│  │  └──────────────────────────────────────────┘    │   │
│  └──────────────────────────────────────────────────┘   │
│                                                          │
│               [ Swap Tokens ]                            │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### WalletConnect Integration

Connect to desktop dApps using WalletConnect:

1. **Desktop App**: Click "WalletConnect" on any dApp
2. **Scan QR Code**: Use RamaPay's QR scanner
3. **Approve Connection**: Review and approve
4. **Sign Transactions**: Approve on mobile, executes on desktop

### Supported Web3 Methods

| Method | Description |
|--------|-------------|
| `eth_requestAccounts` | Connect wallet |
| `eth_sendTransaction` | Send transactions |
| `personal_sign` | Sign messages |
| `eth_signTypedData_v4` | Sign typed data (EIP-712) |
| `wallet_switchEthereumChain` | Switch networks |
| `wallet_addEthereumChain` | Add custom networks |

---

## ⚡ Ramestta Proof of Stake (PoS) System

### What is Proof of Stake?

**Proof of Stake (PoS)** is a consensus mechanism that allows blockchain networks to achieve distributed consensus without the massive energy consumption of Proof of Work (PoW) mining.

```
┌─────────────────────────────────────────────────────────┐
│           PROOF OF STAKE vs PROOF OF WORK               │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  PROOF OF WORK (PoW)           PROOF OF STAKE (PoS)     │
│  ─────────────────────         ─────────────────────    │
│                                                          │
│  ⛏️ Miners compete with        🔐 Validators stake       │
│     computing power               their coins            │
│                                                          │
│  💡 Massive energy use         🌱 Minimal energy use     │
│     (like a small country)        (like a laptop)        │
│                                                          │
│  🖥️ Expensive hardware         💰 Stake your tokens      │
│     required (ASICs/GPUs)         to participate         │
│                                                          │
│  🐌 Slower transactions        ⚡ Faster finality        │
│     (10+ min Bitcoin)             (seconds)              │
│                                                          │
│  🎲 Random lottery by          🎯 Selection by stake     │
│     hash power                    and randomness         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### How Ramestta PoS Works

Ramestta implements a **Delegated Proof of Stake (DPoS)** system:

#### 1. Validators

Validators are nodes that:
- Stake RAMA tokens as collateral
- Validate and propose new blocks
- Earn rewards for honest behavior
- Risk losing stake for malicious actions

```
┌─────────────────────────────────────────────────────────┐
│                    VALIDATOR NODE                        │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  📊 Stake: 100,000 RAMA                                  │
│  📈 APY: ~8-12%                                          │
│  🏆 Uptime: 99.9%                                        │
│  🎁 Delegators: 150                                      │
│  💰 Total Delegated: 500,000 RAMA                        │
│                                                          │
│  Rewards Distribution:                                   │
│  ├── Validator Commission: 10%                           │
│  └── Delegators Share: 90%                               │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### 2. Delegators

Token holders can delegate their RAMA to validators:
- No minimum stake required
- Earn proportional rewards
- Can switch validators anytime
- Liquid staking (coming soon)

#### 3. Block Production

```
Block Production Cycle:
──────────────────────────────────────────────────────────

  Epoch 1         Epoch 2         Epoch 3
  ─────────       ─────────       ─────────
  
  Validator A  →  Validator C  →  Validator B
  [Block 1]       [Block 2]       [Block 3]
     ↓               ↓               ↓
  Verified ✓      Verified ✓      Verified ✓
     ↓               ↓               ↓
  Reward: 2 RAMA  Reward: 2 RAMA  Reward: 2 RAMA

──────────────────────────────────────────────────────────
```

### PoS vs PoW Comparison

| Aspect | PoW (Bitcoin) | PoS (Ramestta) |
|--------|---------------|----------------|
| **Energy Use** | ~150 TWh/year | ~0.0001 TWh/year |
| **Hardware** | ASICs ($10,000+) | Regular server |
| **Entry Barrier** | Very High | Low (stake tokens) |
| **Finality** | ~60 minutes | ~2-3 seconds |
| **TPS** | 7 transactions | 10,000+ transactions |
| **Decentralization** | Mining pools control | Distributed validators |
| **Security** | 51% hash attack | 51% stake attack |

### How PoS Will Change the World

#### 🌍 1. Environmental Revolution

```
Energy Comparison:
──────────────────────────────────────────────────────────

Bitcoin (PoW):     ████████████████████████████████ 150 TWh
Ethereum (PoW):    █████████████████████ 80 TWh
Ramestta (PoS):    ▏ 0.00015 TWh

Reduction: 99.99% less energy
Equivalent to: Powering 15 million homes saved per year
──────────────────────────────────────────────────────────
```

#### 💰 2. Financial Democratization

| Old System | PoS Revolution |
|------------|----------------|
| Banks control your money | You control your money |
| 2-5% inflation savings | 8-12% staking rewards |
| International fees 3-5% | Near-zero transfer fees |
| Days for settlements | Seconds for finality |
| Business hours only | 24/7/365 availability |

#### ⚡ 3. Scalability for Real-World Use

```
Transaction Capacity:
──────────────────────────────────────────────────────────

Visa:          ████████████████████████ 24,000 TPS
Ramestta PoS:  ████████████████ 10,000+ TPS
Ethereum PoW:  ██ 15 TPS
Bitcoin:       ▌ 7 TPS

Use Cases Enabled:
✓ Global payments
✓ Micropayments
✓ Gaming transactions
✓ IoT device payments
✓ Real-time settlement
──────────────────────────────────────────────────────────
```

#### 🏛️ 4. Governance & Participation

PoS enables true stakeholder governance:

```
┌─────────────────────────────────────────────────────────┐
│                 COMMUNITY GOVERNANCE                     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  📋 Proposal: Increase block size to 50MB                │
│                                                          │
│  Voting Power by Stake:                                  │
│  ────────────────────────────────────────────────────    │
│  For:     ████████████████████████ 72%                   │
│  Against: ████████ 28%                                   │
│                                                          │
│  Status: PASSED ✓                                        │
│  Implementation: Epoch 15,000                            │
│                                                          │
│  Your Vote: 1,000 RAMA = 1,000 voting power              │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### 🔒 5. Security Without Waste

PoS achieves security through economic incentives:

```
Attack Cost Comparison:
──────────────────────────────────────────────────────────

Bitcoin 51% Attack:
- Requires: 51% of global hash power
- Cost: ~$5-10 billion in hardware
- Ongoing: Massive electricity costs
- Result: Can double-spend (temporary)

Ramestta 51% Attack:
- Requires: 51% of staked RAMA
- Cost: Billions in RAMA purchases
- Risk: Attacker loses entire stake
- Result: Attack economically irrational

Why Attacking PoS is Pointless:
1. You need to BUY the tokens to attack
2. Your attack devalues your holdings
3. You lose your staked tokens (slashing)
4. Net result: Massive financial loss
──────────────────────────────────────────────────────────
```

#### 🌐 6. Building Web3 Infrastructure

PoS enables the infrastructure for:

| Application | Enabled By PoS |
|-------------|----------------|
| **DeFi** | Fast, cheap transactions |
| **NFTs** | Affordable minting |
| **Gaming** | Real-time in-game economy |
| **Supply Chain** | IoT device tracking |
| **Identity** | Self-sovereign ID |
| **Voting** | Transparent elections |
| **Healthcare** | Secure medical records |

---

## 📜 TokenScript Integration

RamaPay includes advanced TokenScript support for enhanced token interactions:

### What is TokenScript?

TokenScript is an open framework that brings:
- Rich token metadata
- Interactive token actions
- Programmable token behaviors
- Cross-platform compatibility

### Features

- View enhanced token information
- Execute token-specific actions
- Interact with smart contract functions
- Display custom token UIs

---

## 🔗 Universal Links

### What are Universal Links?

Universal Links allow sharing tokens and transactions via simple URLs:

```
https://ramapay.app/AAGGoFq1tAC8mhAmpLxvC6i75IbR...
```

### Use Cases

| Type | Description |
|------|-------------|
| **Token Transfer** | Send tokens via link |
| **NFT Sharing** | Share NFT with anyone |
| **Payment Request** | Request specific amount |
| **dApp Deep Link** | Open specific dApp page |

### How It Works

1. **Create Link**: Generate in app for any token/amount
2. **Share**: Send via any messenger
3. **Receive**: Recipient opens in RamaPay
4. **Claim**: One-tap claim with optional fee sponsorship

---

## ⚙️ Technical Specifications

### Application Details

| Property | Value |
|----------|-------|
| **Package ID** | `io.ramestta.wallet` |
| **Version** | 3.89 (Build 274) |
| **Min Android** | API 24 (Android 7.0 Nougat) |
| **Target SDK** | 35 (Android 15) |
| **Architecture** | armeabi-v7a, arm64-v8a, x86, x86_64 |
| **License** | MIT |

### Technology Stack

| Component | Technology |
|-----------|------------|
| **Language** | Java, Kotlin |
| **Build System** | Gradle 8.x |
| **Database** | Realm |
| **DI Framework** | Dagger Hilt |
| **Web3 Library** | web3j |
| **Crypto Core** | TrustWallet Core |
| **Native Code** | C++ (via NDK/CMake) |

### Key Dependencies

```groovy
// Core
implementation 'org.web3j:core:4.x.x'
implementation 'wallet.core:wallet-core:x.x.x'

// Database
implementation 'io.realm:realm-android-library:x.x.x'

// Security
implementation 'androidx.biometric:biometric:x.x.x'

// UI
implementation 'com.google.android.material:material:x.x.x'
```

---

## 🔧 Network Configuration

### Adding Custom Networks

RamaPay supports adding any EVM-compatible network:

#### Via dApp Browser
1. Visit a dApp that requests network addition
2. Approve the `wallet_addEthereumChain` request

#### Via Settings
1. `Settings → Networks → Add Custom Network`
2. Enter network details:
   - Network Name
   - RPC URL
   - Chain ID
   - Currency Symbol
   - Block Explorer URL

### Network Configuration Code

Located in `EthereumNetworkBase.java`:

```java
// Ramestta Mainnet Configuration
public static final long RAMESTTA_MAINNET_ID = 1370;
public static final String RAMESTTA_MAINNET_RPC = "https://blockchain.ramestta.com";

put(RAMESTTA_MAINNET_ID, new NetworkInfo(
    "Ramestta",                           // Name
    "RAMA",                               // Symbol
    RAMESTTA_MAINNET_RPC,                // RPC URL
    "https://ramascan.com/tx/",          // Explorer
    RAMESTTA_MAINNET_ID,                  // Chain ID
    false                                 // Is testnet
));
```

---

## 📞 Social Links & Support

### Official Channels

| Platform | Link |
|----------|------|
| 🌐 **Website** | https://ramestta.com |
| 📧 **Email** | support@ramestta.com |
| 🐦 **Twitter/X** | https://x.com/ramestta |
| 📘 **Facebook** | https://facebook.com/Ramestta |
| 📸 **Instagram** | https://instagram.com/ramestta |
| 💻 **GitHub** | https://github.com/Ramestta-Blockchain |
| 📝 **Blog** | https://ramestta.com/blog |
| ❓ **FAQ** | https://ramestta.com/faq |

### Getting Help

1. **Check FAQ**: Most common questions answered
2. **GitHub Issues**: Report bugs or request features
3. **Email Support**: For account-specific issues

---

## 👨‍💻 For Developers

### Fork & Customize

RamaPay is designed for easy customization:

```bash
# Clone
git clone https://github.com/obidua/RamaPay-android.git
cd RamaPay-android

# Build
./gradlew assembleNoAnalyticsDebug

# Install
adb install -r app/build/outputs/apk/noAnalytics/debug/RamaPay.apk
```

### Key Customization Files

| File | Purpose |
|------|---------|
| `app/build.gradle` | Package ID, version |
| `values/strings.xml` | App name, strings |
| `mipmap-*/` | App icons |
| `MediaLinks.java` | Social links |
| `C.java` | URLs, constants |
| `EthereumNetworkBase.java` | Network configs |

### Build Requirements

- JDK 17
- Android Studio Hedgehog+
- Android SDK 35
- NDK 27.1.12297006

---

## 📄 License

RamaPay is released under the **MIT License**.

```
MIT License

Copyright (c) 2024 Ramestta

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### Credits

RamaPay is based on [AlphaWallet](https://github.com/AlphaWallet/alpha-wallet-android), an open-source Ethereum wallet. We extend our gratitude to the AlphaWallet team for their excellent foundation.

---

<p align="center">
  <strong>Built with ❤️ for the Ramestta Community</strong>
</p>

<p align="center">
  <a href="https://ramestta.com">Website</a> •
  <a href="https://ramascan.com">Explorer</a> •
  <a href="https://github.com/obidua/RamaPay-android">GitHub</a> •
  <a href="https://x.com/ramestta">Twitter</a>
</p>

---

*Last Updated: December 2024 | Version 3.89*
