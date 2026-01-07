# Endpoint Management for Multi-Device Registration

## Problem Statement
When users register relay nodes from different devices (desktop app, mobile app, or web), we need to manage the endpoint URLs properly so the network knows how to reach each node.

## Current V4 Architecture

### Node Registration Flow:
```
User Wallet → Multiple Nodes → Each has unique Node ID (SHA256 hash)
   └─ 0xAC59...9DC7
      ├─ Node 1: 0xabc123... (Desktop PC - IP: 192.168.1.100:19371)
      ├─ Node 2: 0xdef456... (Server - IP: 160.187.80.116:19371)  
      └─ Node 3: 0x789abc... (Mobile - dynamic IP)
```

### Smart Contract Storage:
```solidity
struct RelayNode {
    address walletAddress;        // User's wallet
    bytes32 machineIdHash;        // Unique machine fingerprint
    uint256 instanceNumber;       // 1, 2, 3... for same machine
    uint256 stakedAmount;         // MCT staked
    bytes32 nodeId;              // SHA256(walletAddress + machineId + instance)
    // NO endpoint stored on-chain for privacy/security
}
```

## Solution: Endpoint Management Strategies

### Option 1: DHT-Based Discovery (Recommended)
**How it works:**
- Each node registers its Node ID on-chain
- Nodes announce their endpoints to a Distributed Hash Table (DHT)
- Other nodes discover endpoints by querying DHT with Node ID
- Endpoints can change (mobile switching networks) without on-chain updates

**Pros:**
- ✅ Decentralized
- ✅ Handles dynamic IPs (mobile/residential)
- ✅ No on-chain endpoint storage (privacy + cheaper)
- ✅ Works offline then syncs when online

**Implementation:**
```javascript
// Node announces to DHT every 5 minutes
dht.announce(nodeId, {
  endpoints: [
    'tcp://160.187.80.116:19371',
    'ws://160.187.80.116:19372'
  ],
  lastSeen: Date.now(),
  capabilities: ['relay', 'store']
});

// Other nodes discover
const endpoints = await dht.lookup(nodeId);
```

### Option 2: Relay Manager Endpoint Registry
**How it works:**
- Add off-chain endpoint registry (separate contract or centralized service)
- Nodes POST their endpoints when they come online
- Query service to get current endpoints for a Node ID

**Pros:**
- ✅ Simple to implement
- ✅ Fast lookups
- ✅ Can track node health/uptime

**Cons:**
- ❌ Centralized point of failure
- ❌ Requires server infrastructure

**Implementation:**
```javascript
// POST endpoint when node starts
await fetch('https://relay-registry.mumblechat.com/api/register', {
  method: 'POST',
  body: JSON.stringify({
    nodeId: '0xabc123...',
    endpoints: ['tcp://160.187.80.116:19371'],
    signature: signedMessage // Proof of wallet ownership
  })
});

// GET endpoints for a node
const response = await fetch(`https://relay-registry.mumblechat.com/api/nodes/${nodeId}`);
```

### Option 3: Hybrid (Best of Both)
**How it works:**
- Use DHT for primary discovery (decentralized)
- Fallback to centralized registry if DHT lookup fails
- Both systems updated when node comes online

**Benefits:**
- ✅ Decentralized by default
- ✅ Fallback for reliability
- ✅ Handles all device types

## Registration Flow by Device Type

### Desktop App Registration:
```
1. User opens desktop app
2. App detects: OS, Machine ID, Network interfaces
3. User clicks "Register" → Connects wallet (MetaMask/RamaPay)
4. App calls: registerRelay(endpoint="tcp://192.168.1.100:19371", tier=BRONZE)
5. Contract generates: nodeId = SHA256(wallet + machineId + instanceNum)
6. Node starts → Announces to DHT: nodeId → endpoints
```

### Mobile App Registration:
```
1. User opens mobile app (iOS/Android)
2. App uses device UUID as machineId
3. App opens background service on port (iOS: 19371, Android: 19371)
4. User connects wallet (WalletConnect → RamaPay/MetaMask)
5. App calls: registerRelay(endpoint="mobile://dynamic", tier=BRONZE)
6. Node announces to DHT when online (WiFi/cellular changes tracked)
```

### Web Registration (From Website):
```
1. User visits: https://mumblechat.com/relay-node.html
2. Clicks "Connect Wallet" → MetaMask/RamaPay connects
3. User selects tier, confirms transaction
4. Web shows: "Node registered! Download desktop/mobile app to run it"
5. When desktop app starts with same wallet:
   - Detects Node ID already registered
   - Starts relay operations
   - Announces endpoint to DHT
```

## Current Implementation Status

### ✅ Implemented:
- V4 Node ID system (wallet + machineId + instance)
- Multi-node support (one wallet, multiple nodes)
- Tier-based staking (100/200/300/400 MCT)
- On-chain registration validation

### ❌ TODO:
- DHT endpoint announcement
- Endpoint discovery system
- Mobile app implementation
- Cross-device sync (register on web, run on desktop)

## Security Considerations

### Why NOT store endpoints on-chain:
1. **Privacy**: Reveals user's IP address publicly
2. **Cost**: Updating endpoint costs gas (mobile networks change IPs frequently)
3. **Flexibility**: Can't handle NAT traversal, VPNs, or dynamic IPs
4. **Attack Surface**: Enables targeted DDoS attacks

### How to prove node ownership:
```javascript
// Sign a message with the wallet that registered the node
const message = `I control node ${nodeId} at ${Date.now()}`;
const signature = await wallet.signMessage(message);

// Verify on DHT/registry
const recovered = ethers.verifyMessage(message, signature);
assert(recovered === walletAddress);
```

## Next Steps

1. **Fix registration requirement** (✅ Done - node won't start without registration)
2. **Add DHT system** to desktop relay
3. **Implement endpoint announcement** when node starts
4. **Add discovery API** to find nodes by Node ID
5. **Mobile app** with background relay service
6. **Web registration wizard** that generates "waiting for node" state

## Quick Fix for Current Setup

For now (before DHT implemented), we use **static configuration**:

```json
// config.json
{
  "relay": {
    "port": 19371,
    "publicEndpoint": "tcp://160.187.80.116:19371",  // ← Add this
    "advertiseEndpoint": true
  }
}
```

Then manually share endpoint with other node operators for now.
