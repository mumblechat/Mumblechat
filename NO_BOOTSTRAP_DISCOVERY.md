# Node Discovery Without Bootstrap Nodes

## Problem
How do relay nodes find each other if there's no central bootstrap node?

## Solutions (No Bootstrap Required)

### Option 1: On-Chain Endpoint Registry (Fully Decentralized)

**How it works:**
- Nodes store their endpoints in a separate smart contract
- Other nodes query the contract to get active endpoints
- Update endpoint when IP changes (costs gas)

**Smart Contract:**
```solidity
contract RelayEndpointRegistry {
    struct Endpoint {
        string tcpUrl;      // "tcp://123.45.67.89:19371"
        string wsUrl;       // "ws://123.45.67.89:19372"
        uint256 lastUpdate;
        bool isActive;
    }
    
    mapping(bytes32 => Endpoint) public nodeEndpoints; // nodeId => endpoint
    
    function updateEndpoint(
        bytes32 nodeId,
        string memory tcpUrl,
        string memory wsUrl
    ) external {
        require(relayManager.getNodeWallet(nodeId) == msg.sender, "Not owner");
        nodeEndpoints[nodeId] = Endpoint(tcpUrl, wsUrl, block.timestamp, true);
    }
    
    function getAllActiveEndpoints() external view returns (Endpoint[] memory) {
        // Return list of all active node endpoints
    }
}
```

**Pros:**
- ✅ Fully decentralized
- ✅ No central point of failure
- ✅ Trustless

**Cons:**
- ❌ Gas cost for every IP change (bad for mobile)
- ❌ Slower (blockchain query latency)
- ❌ Exposes IP addresses publicly on-chain

---

### Option 2: Centralized API Registry (Simple)

**How it works:**
- Run a simple API at `https://api.mumblechat.com/v1/nodes`
- Nodes POST their endpoints when they start
- Other nodes GET the list to discover peers

**API Implementation:**
```javascript
// POST /v1/nodes/register
{
  "nodeId": "0xabc123...",
  "endpoints": {
    "tcp": "tcp://123.45.67.89:19371",
    "ws": "ws://123.45.67.89:19372"
  },
  "signature": "0x..." // Signed with wallet to prove ownership
}

// GET /v1/nodes
{
  "nodes": [
    {
      "nodeId": "0xabc123...",
      "endpoints": {...},
      "lastSeen": 1704654123,
      "isOnline": true
    }
  ]
}
```

**Implementation (Express.js):**
```javascript
const express = require('express');
const { ethers } = require('ethers');

const app = express();
const nodes = new Map(); // nodeId => {endpoints, lastSeen, wallet}

app.post('/v1/nodes/register', async (req, res) => {
  const { nodeId, endpoints, signature, timestamp } = req.body;
  
  // Verify signature
  const message = `Register node ${nodeId} at ${timestamp}`;
  const wallet = ethers.verifyMessage(message, signature);
  
  // Check on-chain if wallet owns this nodeId
  const owner = await relayManager.getNodeWallet(nodeId);
  if (owner !== wallet) {
    return res.status(403).json({ error: 'Not node owner' });
  }
  
  // Store endpoint
  nodes.set(nodeId, {
    endpoints,
    lastSeen: Date.now(),
    wallet
  });
  
  res.json({ success: true });
});

app.get('/v1/nodes', (req, res) => {
  const activeNodes = Array.from(nodes.entries())
    .filter(([_, node]) => Date.now() - node.lastSeen < 5 * 60 * 1000) // 5 min timeout
    .map(([nodeId, node]) => ({ nodeId, ...node }));
  
  res.json({ nodes: activeNodes });
});

app.listen(3000);
```

**Pros:**
- ✅ Simple to implement
- ✅ Fast queries
- ✅ No gas costs
- ✅ Can handle dynamic IPs (mobile)

**Cons:**
- ❌ Centralized (single point of failure)
- ❌ You must run/maintain the API server

---

### Option 3: Contract Events for Discovery

**How it works:**
- When a node registers, emit an event with contact info
- Nodes listen to contract events to discover peers
- Use WebRTC signaling for actual connection

**Smart Contract:**
```solidity
event NodeOnline(
    bytes32 indexed nodeId,
    address indexed wallet,
    string signalData  // WebRTC signaling data or encrypted endpoint
);

function announceOnline(bytes32 nodeId, string memory signalData) external {
    require(getNodeWallet(nodeId) == msg.sender, "Not owner");
    emit NodeOnline(nodeId, msg.sender, signalData);
}
```

**Node Implementation:**
```javascript
// Listen for new nodes coming online
relayManager.on('NodeOnline', async (nodeId, wallet, signalData) => {
  console.log(`Node ${nodeId} came online`);
  
  // Decrypt signal data or parse endpoint
  const endpoint = decryptSignalData(signalData);
  
  // Connect to peer
  await connectToPeer(endpoint);
});

// Announce when starting
await relayManager.announceOnline(myNodeId, encryptedEndpoint);
```

**Pros:**
- ✅ Decentralized
- ✅ Real-time discovery
- ✅ Can encrypt endpoints

**Cons:**
- ❌ Requires nodes to keep event listener running
- ❌ Gas cost for announcement
- ❌ May miss events if node was offline

---

### Option 4: Distributed with App (Static Seed List)

**How it works:**
- Include a list of known public relay nodes in the app
- Nodes connect to any seed from the list
- Once connected, exchange peer lists

**In app configuration:**
```json
// config/seeds.json
{
  "seedNodes": [
    "tcp://relay1.someuser.com:19371",
    "tcp://relay2.anotheruser.com:19371",
    "tcp://relay3.thirduser.com:19371"
  ]
}
```

**Auto-update seed list:**
```javascript
// Fetch latest seed list from IPFS or GitHub
const response = await fetch('https://ipfs.io/ipfs/QmSeedList...');
const { seedNodes } = await response.json();

// Try connecting to seeds
for (const seed of seedNodes) {
  try {
    await connectToPeer(seed);
    break; // Connected to at least one
  } catch (e) {
    continue;
  }
}

// Once connected, get more peers from connected node
const peers = await getPeerList(connectedNode);
```

**Pros:**
- ✅ No infrastructure required
- ✅ Works offline initially
- ✅ Decentralized

**Cons:**
- ❌ Need to manually maintain seed list
- ❌ If all seeds offline, network breaks

---

### Option 5: Local Network Discovery (mDNS)

**How it works:**
- Nodes broadcast on local network (UDP multicast)
- Other nodes on same WiFi/LAN discover automatically
- Good for home/office deployments

**Implementation:**
```javascript
const dgram = require('dgram');
const socket = dgram.createSocket('udp4');

// Broadcast presence
setInterval(() => {
  const announcement = JSON.stringify({
    nodeId: myNodeId,
    port: 19371,
    wallet: myWallet
  });
  
  socket.send(announcement, 5353, '224.0.0.251'); // mDNS multicast
}, 10000);

// Listen for peers
socket.on('message', (msg, rinfo) => {
  const peer = JSON.parse(msg.toString());
  console.log(`Found peer: ${peer.nodeId} at ${rinfo.address}`);
  connectToPeer(`tcp://${rinfo.address}:${peer.port}`);
});

socket.bind(5353);
```

**Pros:**
- ✅ Zero configuration
- ✅ Works on local network without internet
- ✅ No infrastructure

**Cons:**
- ❌ Only works on same LAN
- ❌ Can't discover nodes on internet

---

### Option 6: QR Code / Invite System

**How it works:**
- One node generates QR code with its endpoint
- Another node scans QR to connect directly
- Share peer lists after connection

**Generate invite:**
```javascript
const invite = {
  nodeId: myNodeId,
  endpoint: 'tcp://192.168.1.100:19371',
  timestamp: Date.now(),
  signature: signedInvite
};

const qrCode = generateQR(JSON.stringify(invite));
// Display QR code in UI
```

**Scan and connect:**
```javascript
const invite = JSON.parse(scanQR());

// Verify signature
const wallet = ethers.verifyMessage(invite.endpoint, invite.signature);

// Connect
await connectToPeer(invite.endpoint);

// Exchange peer lists
const morePeers = await getPeerList(invite.nodeId);
```

**Pros:**
- ✅ Manual but works anywhere
- ✅ Good for trusted friends
- ✅ No infrastructure

**Cons:**
- ❌ Manual process
- ❌ Not scalable

---

## Recommended Hybrid Approach (No Bootstrap)

### Phase 1: App Ships with Static Seed List
```json
{
  "seedNodes": [
    "tcp://relay1.example.com:19371",
    "tcp://relay2.example.com:19371"
  ],
  "updateUrl": "https://api.mumblechat.com/v1/seeds"
}
```

### Phase 2: Auto-Update Seed List
- App fetches latest seed list from API on startup
- Stores in local cache
- Uses cached if API unavailable

### Phase 3: Peer Exchange Protocol
Once connected to one peer, nodes exchange their peer lists:

```javascript
// Connected to one seed node
const myPeers = await seed.getPeerList(); // Returns 50 other active nodes

// Now connect to more peers from the list
for (const peer of myPeers) {
  connectToPeer(peer.endpoint);
}

// After a while, no longer need seeds
// Node is part of the mesh network
```

### Phase 4: Optional Centralized Registry (For Fast Discovery)
- Run simple API for newly joining nodes
- Established nodes don't need it (already have peers)
- If API goes down, network keeps working (P2P mesh)

---

## Configuration for Your Setup

**Option A: No Infrastructure at All**
```json
{
  "discovery": {
    "method": "static-seeds",
    "seeds": [
      "tcp://VOLUNTEER1-IP:19371",
      "tcp://VOLUNTEER2-IP:19371",
      "tcp://VOLUNTEER3-IP:19371"
    ]
  }
}
```

**Option B: Minimal API (Recommended)**
```json
{
  "discovery": {
    "method": "api-registry",
    "registryUrl": "https://api.mumblechat.com/v1/nodes",
    "fallbackSeeds": [
      "tcp://160.187.80.116:19371"
    ]
  }
}
```

**Option C: On-Chain Only**
```json
{
  "discovery": {
    "method": "on-chain",
    "endpointRegistry": "0xEndpointRegistryAddress"
  }
}
```

---

## Which One to Choose?

| Method | Cost | Decentralized | Reliability | Speed |
|--------|------|---------------|-------------|-------|
| Bootstrap Node | Low | ❌ | ⭐⭐ | ⭐⭐⭐ |
| On-Chain Registry | High (gas) | ✅ | ⭐⭐⭐ | ⭐ |
| API Registry | Medium | ❌ | ⭐⭐ | ⭐⭐⭐ |
| Contract Events | Medium | ✅ | ⭐⭐ | ⭐⭐ |
| Static Seeds | Free | ✅ | ⭐ | ⭐⭐ |
| mDNS (Local) | Free | ✅ | ⭐⭐⭐ | ⭐⭐⭐ |
| QR Invite | Free | ✅ | ⭐⭐ | ⭐ |

**My Recommendation:**
Use **API Registry + Static Seeds** hybrid:
- API for fast discovery when available
- Seeds as fallback if API is down
- Peer exchange to build mesh network
- After initial connection, nodes don't need centralized components

Want me to implement one of these?
