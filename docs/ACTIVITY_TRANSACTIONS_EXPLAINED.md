# Activity Window - Transaction Display System

## Overview
The **Activity** tab in RamaPay shows all received and sent transactions for all supported networks. This document explains how it works and confirms that **Ramestta network is already fully configured** for transaction tracking.

---

## How Activity Window Works

### 1. **Architecture Components**

#### A. Frontend (UI Layer)
- **`ActivityFragment.java`** - Main UI component that displays the activity list
- **`ActivityAdapter.java`** - RecyclerView adapter that renders transaction items
- **Location**: `app/src/main/java/com/alphawallet/app/ui/ActivityFragment.java`

#### B. Data Fetching Layer
- **`TransactionsService.java`** - Orchestrates transaction fetching across all networks
- **`TransactionsNetworkClient.java`** - Handles API calls to blockchain explorers
- **`FetchTransactionsInteract.java`** - Interaction layer between UI and services

#### C. Data Storage
- **Realm Database** - Stores transactions locally
  - `RealmTransaction` - Stores transaction data
  - `RealmTransfer` - Stores token transfer events (ERC20, ERC721, ERC1155)

---

## 2. **Transaction Fetching Flow**

### Step-by-Step Process:

```
1. User Opens Activity Tab
   └─> ActivityFragment initializes
       └─> Calls viewModel.prepare()
           └─> TransactionsService starts fetching

2. TransactionsService Cycle
   ├─> Iterates through all enabled networks
   ├─> For each network (including Ramestta):
   │   ├─> Fetches native transactions (ETH/RAMA transfers)
   │   ├─> Fetches ERC20 token transfers
   │   ├─> Fetches ERC721 NFT transfers
   │   └─> Fetches ERC1155 multi-token transfers
   │
   └─> Stores all transactions in Realm database

3. Real-time Updates
   └─> Realm listener detects new transactions
       └─> Updates UI automatically
```

---

## 3. **Network Configuration for Transaction Fetching**

### Required Configuration for Each Network:

Each network needs the following in `EthereumNetworkBase.java`:

```java
new NetworkInfo(
    "Network Name",           // Display name
    "SYMBOL",                 // Token symbol
    RPC_URLS,                 // Array of RPC endpoints
    "https://explorer/tx/",   // Block explorer URL
    CHAIN_ID,                 // Network chain ID
    "https://api.explorer/"   // API endpoint for transactions
)
```

### Ramestta Configuration (Already Implemented ✅)

**Location**: `app/src/main/java/com/alphawallet/app/repository/EthereumNetworkBase.java` (Line 644-653)

```java
// Ramestta Mainnet
put(RAMESTTA_MAINNET_ID, new NetworkInfo("Ramestta Network", "RAMA",
        CHAIN_CONFIG_RPC.get(RAMESTTA_MAINNET_ID),
        "https://ramascan.com/tx/", 
        RAMESTTA_MAINNET_ID,
        "https://latest-backendapi.ramascan.com/api/v1/"));

// Ramestta Testnet
put(RAMESTTA_TESTNET_ID, new NetworkInfo("Ramestta Testnet", "RAMA",
        CHAIN_CONFIG_RPC.get(RAMESTTA_TESTNET_ID),
        "https://testnet.ramascan.com/tx/", 
        RAMESTTA_TESTNET_ID,
        "https://latest-backendapi.ramascan.com/api/v1/"));
```

---

## 4. **API Endpoints Used**

### Transaction Fetching APIs

The `TransactionsNetworkClient` uses these API routes:

#### A. **Native Transactions** (RAMA transfers)
```
GET https://latest-backendapi.ramascan.com/api/v1/?module=account&action=txlist
    &address={wallet_address}
    &startblock={last_block}
    &endblock=999999999
    &sort=asc
```

#### B. **ERC20 Token Transfers**
```
GET https://latest-backendapi.ramascan.com/api/v1/?module=account&action=tokentx
    &address={wallet_address}
    &startblock={last_block}
    &endblock=999999999
    &sort=asc
```

#### C. **ERC721 NFT Transfers**
```
GET https://latest-backendapi.ramascan.com/api/v1/?module=account&action=tokennfttx
    &address={wallet_address}
    &startblock={last_block}
    &endblock=999999999
    &sort=asc
```

#### D. **ERC1155 Multi-Token Transfers**
```
GET https://latest-backendapi.ramascan.com/api/v1/?module=account&action=token1155tx
    &address={wallet_address}
    &startblock={last_block}
    &endblock=999999999
    &sort=asc
```

---

## 5. **How Data is Processed**

### Transaction Processing Pipeline:

```java
// 1. Fetch from API
TransactionsNetworkClient.readTransfers()
    └─> Makes API call to Ramascan backend
    └─> Returns array of EtherscanEvent[]

// 2. Convert to Transaction Objects
└─> Converts API response to Transaction objects
    ├─> Parses: hash, from, to, value, timestamp, blockNumber
    └─> Identifies token transfers

// 3. Store in Database
└─> Writes to Realm database
    ├─> RealmTransaction (for main transactions)
    └─> RealmTransfer (for token transfers)

// 4. Display in UI
ActivityFragment listens to Realm changes
    └─> buildTransactionList()
        └─> Filters and formats transactions
        └─> Updates RecyclerView adapter
        └─> Shows in Activity tab
```

---

## 6. **Supported Transaction Types**

### What Shows in Activity Window:

| Type | Description | Example |
|------|-------------|---------|
| **Native Transfer** | RAMA sent/received | Sending 10 RAMA to another wallet |
| **ERC20 Transfer** | Token transfers | USDT, USDC, custom tokens |
| **ERC721 Transfer** | NFT transfers | Receiving/sending NFTs |
| **ERC1155 Transfer** | Multi-token transfers | Gaming items, multiple NFTs |
| **Contract Interaction** | Smart contract calls | DeFi swaps, staking |

---

## 7. **Real-time Updates**

### How Live Updates Work:

```java
// ActivityFragment.java (Line 100-130)
private void startTxListener() {
    // Listen to Realm database changes
    realmUpdates = realm.where(RealmTransaction.class)
        .greaterThan("timeStamp", lastUpdateTime)
        .findAllAsync();
    
    realmUpdates.addChangeListener(realmTransactions -> {
        // New transaction detected
        // Update UI automatically
        adapter.updateActivityItems(...);
    });
}
```

**Background Service**: `TransactionsService` runs in background checking for new transactions every **30 seconds**.

---

## 8. **Verification - Ramestta is Already Working** ✅

### Configuration Checklist:

- [x] **Chain ID Defined**: `RAMESTTA_MAINNET_ID = 1370`
- [x] **RPC URLs Configured**: Multiple RPC endpoints in `CHAIN_CONFIG_RPC`
- [x] **Explorer API Configured**: `https://latest-backendapi.ramascan.com/api/v1/`
- [x] **Block Explorer URL**: `https://ramascan.com/tx/`
- [x] **Network in Main List**: Added to `NETWORKS_TO_SHOW`
- [x] **Network Icons**: Logo and network icon configured
- [x] **CoinGecko Integration**: Added in `TickerService.java`

---

## 9. **How to Test Ramestta Transactions**

### Test Steps:

1. **Enable Ramestta Network**
   - Go to Settings → Networks
   - Enable "Ramestta Network"

2. **Get Test Wallet**
   - Create/Import wallet
   - Get Ramestta address

3. **Send Test Transaction**
   - Send some RAMA tokens
   - Or interact with a contract

4. **Check Activity Tab**
   - Open Activity tab
   - Pull to refresh
   - Transaction should appear within 30 seconds

---

## 10. **API Response Format**

### Expected JSON Response from Ramascan:

```json
{
  "status": "1",
  "message": "OK",
  "result": [
    {
      "blockNumber": "123456",
      "timeStamp": "1702123456",
      "hash": "0xabc123...",
      "from": "0x123...",
      "to": "0x456...",
      "value": "1000000000000000000",
      "gas": "21000",
      "gasPrice": "20000000000",
      "gasUsed": "21000",
      "input": "0x",
      "contractAddress": "",
      "isError": "0"
    }
  ]
}
```

### For Token Transfers (ERC20):

```json
{
  "status": "1",
  "message": "OK",
  "result": [
    {
      "blockNumber": "123456",
      "timeStamp": "1702123456",
      "hash": "0xabc123...",
      "from": "0x123...",
      "to": "0x456...",
      "value": "100000000",
      "tokenName": "USD Coin",
      "tokenSymbol": "USDC",
      "tokenDecimal": "6",
      "contractAddress": "0x789..."
    }
  ]
}
```

---

## 11. **Troubleshooting**

### If Transactions Don't Show:

1. **Check Network is Enabled**
   ```
   Settings → Networks → Ramestta Network (ON)
   ```

2. **Verify API Endpoint**
   - Test: `https://latest-backendapi.ramascan.com/api/v1/?module=account&action=txlist&address=0xYourAddress`
   - Should return JSON with transactions

3. **Check Logs**
   ```bash
   adb logcat | grep -i "transaction\|ramestta"
   ```

4. **Force Refresh**
   - Pull down to refresh in Activity tab
   - Or restart app

### Common Issues:

| Issue | Solution |
|-------|----------|
| No transactions showing | Check if Ramascan API is online |
| Old transactions missing | API may have pagination limits |
| Slow loading | Network connectivity issue |
| Duplicate entries | Realm database cache - clear app data |

---

## 12. **Code Flow Diagram**

```
User Opens Activity Tab
         ↓
   ActivityFragment
         ↓
   ActivityViewModel.prepare()
         ↓
   TransactionsService.fetchTransactions()
         ↓
   ┌─────────────────────────────────┐
   │  For Each Enabled Network       │
   │  (Including Ramestta 1370)      │
   └─────────────────────────────────┘
         ↓
   TransactionsNetworkClient.readTransfers()
         ↓
   ┌──────────────────────────────────┐
   │  API Call to Ramascan Backend    │
   │  https://latest-backendapi...    │
   └──────────────────────────────────┘
         ↓
   Parse JSON Response
         ↓
   Convert to Transaction Objects
         ↓
   ┌──────────────────────────────────┐
   │  Store in Realm Database         │
   │  - RealmTransaction              │
   │  - RealmTransfer                 │
   └──────────────────────────────────┘
         ↓
   Realm Change Listener Triggered
         ↓
   ActivityFragment.onItemsLoaded()
         ↓
   buildTransactionList()
         ↓
   ActivityAdapter.updateActivityItems()
         ↓
   Display in RecyclerView (Activity Tab)
```

---

## 13. **Summary**

### ✅ **Ramestta is Fully Configured for Activity Tracking**

Everything is already in place:

1. **Network Configuration**: Complete with API endpoints
2. **Transaction Fetching**: Automatic background service
3. **Data Storage**: Realm database integration
4. **UI Display**: Activity fragment and adapter
5. **Real-time Updates**: Automatic refresh system
6. **Token Support**: ERC20, ERC721, ERC1155
7. **Price Integration**: CoinGecko API configured

### **No Additional Implementation Needed!** 🎉

The Activity window will automatically show all transactions (sent/received) for Ramestta network once:
- User enables the network
- Has a wallet with transactions on Ramestta
- Ramascan API is accessible and returning data

---

## 14. **Related Files**

### Core Transaction Files:
```
app/src/main/java/com/alphawallet/app/
├── ui/
│   ├── ActivityFragment.java          # Main Activity UI
│   └── widget/adapter/
│       └── ActivityAdapter.java        # Transaction list adapter
├── service/
│   ├── TransactionsService.java        # Transaction orchestration
│   ├── TransactionsNetworkClient.java  # API calls
│   └── TickerService.java              # Price tracking
├── repository/
│   ├── EthereumNetworkBase.java        # Network configurations
│   └── entity/
│       ├── RealmTransaction.java       # Transaction storage
│       └── RealmTransfer.java          # Transfer storage
└── viewmodel/
    └── ActivityViewModel.java          # ViewModel for Activity
```

---

**Document Version**: 1.0  
**Last Updated**: December 9, 2025  
**Status**: Ramestta Network Fully Configured ✅
