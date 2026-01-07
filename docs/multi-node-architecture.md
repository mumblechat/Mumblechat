# MumbleChat Multi-Node Architecture

## Overview

MumbleChat allows running **multiple relay nodes on a single machine** with different wallet addresses. Each node:
- Has its own wallet and staked MCT tokens
- Gets isolated storage allocation
- Earns separate rewards
- Can be in different tiers

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           SINGLE MACHINE                                     │
│                     (e.g., 160.187.80.116)                                  │
│                                                                             │
│  ┌─────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │     NODE 1          │  │     NODE 2          │  │     NODE 3          │  │
│  │  ┌──────────────┐   │  │  ┌──────────────┐   │  │  ┌──────────────┐   │  │
│  │  │ Wallet: 0xA1 │   │  │  │ Wallet: 0xB2 │   │  │  │ Wallet: 0xC3 │   │  │
│  │  │ Tier: BRONZE │   │  │  │ Tier: SILVER │   │  │  │ Tier: GOLD   │   │  │
│  │  │ Stake: 100   │   │  │  │ Stake: 500   │   │  │  │ Stake: 1000  │   │  │
│  │  └──────────────┘   │  │  └──────────────┘   │  │  └──────────────┘   │  │
│  │                     │  │                     │  │                     │  │
│  │  Storage: 1 GB      │  │  Storage: 4 GB      │  │  Storage: 10 GB     │  │
│  │  ┌───────────────┐  │  │  ┌───────────────┐  │  │  ┌───────────────┐  │  │
│  │  │ /data/node1/  │  │  │  │ /data/node2/  │  │  │  │ /data/node3/  │  │  │
│  │  │ └─ storage/   │  │  │  │ └─ storage/   │  │  │  │ └─ storage/   │  │  │
│  │  │ └─ keys/      │  │  │  │ └─ keys/      │  │  │  │ └─ keys/      │  │  │
│  │  │ └─ logs/      │  │  │  │ └─ logs/      │  │  │  │ └─ logs/      │  │  │
│  │  └───────────────┘  │  │  └───────────────┘  │  │  └───────────────┘  │  │
│  └──────────┬──────────┘  └──────────┬──────────┘  └──────────┬──────────┘  │
│             │                        │                        │             │
│             └────────────┬───────────┴────────────────────────┘             │
│                          │                                                  │
│                          ▼                                                  │
│             ┌────────────────────────┐                                      │
│             │   HUB CONNECTION       │                                      │
│             │   MANAGER              │                                      │
│             └───────────┬────────────┘                                      │
│                         │                                                   │
└─────────────────────────│───────────────────────────────────────────────────┘
                          │
                          │ WebSocket (outbound)
                          ▼
            ┌─────────────────────────────────┐
            │     hub.mumblechat.com          │
            │  ┌───────────────────────────┐  │
            │  │  Node 1: tunnel-a1b2c3    │  │
            │  │  Node 2: tunnel-d4e5f6    │  │
            │  │  Node 3: tunnel-g7h8i9    │  │
            │  └───────────────────────────┘  │
            └─────────────────────────────────┘
                          │
                          │ Registered Endpoints
                          ▼
            ┌─────────────────────────────────┐
            │   Ramestta Blockchain           │
            │   RelayManager Contract         │
            │  ┌───────────────────────────┐  │
            │  │ machineIdHash: 0xABC...   │  │
            │  │ ├─ nodeId1 (0xA1)         │  │
            │  │ ├─ nodeId2 (0xB2)         │  │
            │  │ └─ nodeId3 (0xC3)         │  │
            │  └───────────────────────────┘  │
            └─────────────────────────────────┘
```

## Storage Management

### How Storage is Divided

Each node gets **isolated storage** within the machine:

```
Machine Disk (100 GB total, 80 GB free)
├── System Reserved: 10 GB (minimum 10% kept free)
├── Node 1 Quota: 1 GB (BRONZE)
│   └── Can only use files in /data/node1/
├── Node 2 Quota: 4 GB (SILVER)
│   └── Can only use files in /data/node2/
├── Node 3 Quota: 10 GB (GOLD)
│   └── Can only use files in /data/node3/
└── Available for new nodes: 55 GB
```

### Storage Rules

1. **Pre-allocation**: When adding a node, storage is reserved from available space
2. **Isolation**: Each node has its own directory - cannot access other nodes' data
3. **Quota Enforcement**: StorageManager enforces per-node limits
4. **Machine Limit**: Total allocated cannot exceed 80% of free disk space

### Real vs Claimed Storage

**The Problem**: A malicious operator could:
- Claim 10 GB storage on blockchain
- Actually only allocate 1 GB
- Fail to deliver stored files

**Our Solution**:

```typescript
// StorageManager verifies REAL disk usage
class StorageManager {
  // Get actual disk info (not trust-based)
  getDiskInfo(): { totalMB, freeMB, usedMB }
  
  // Verify node is using claimed storage
  verifyNodeStorage(nodeId): { claimed, actual, isValid }
  
  // Prevent over-allocation
  canAllocateStorage(requestedMB): { canAllocate, reason }
}
```

**On-Chain Verification** (Future Enhancement):
```solidity
// Contract could require proof of storage
function challengeNode(bytes32 nodeId, bytes32 fileHash) external {
    // Node must prove they have the file
    // Fail = slashing
}
```

## Running Multiple Nodes

### Step 1: Check Machine Capacity

```bash
node multi-node-cli.js info

# Output:
# Machine ID Hash: 0x1234...
# Disk Total:      100 GB
# Disk Free:       80 GB
# Available:       70 GB (for nodes)
# 
# Recommended: GOLD tier with 10 GB
```

### Step 2: Add Nodes

```bash
# Add first node (BRONZE, 1 GB)
node multi-node-cli.js add
# Enter wallet key or generate new
# Select tier: 1 (BRONZE)
# Storage: 1024 MB

# Add second node (SILVER, 4 GB)
node multi-node-cli.js add
# Enter different wallet key
# Select tier: 2 (SILVER)
# Storage: 4096 MB
```

### Step 3: Fund Wallets

Each node needs MCT tokens for staking:

| Tier | Stake Required |
|------|---------------|
| BRONZE | 100 MCT |
| SILVER | 500 MCT |
| GOLD | 1,000 MCT |
| PLATINUM | 5,000 MCT |

### Step 4: Register on Blockchain

```bash
node multi-node-cli.js register
# Approves MCT spend
# Calls registerNodeWithId()
# Node is now on-chain
```

### Step 5: Start Nodes

```bash
node multi-node-cli.js start-all

# Each node:
# 1. Connects to hub.mumblechat.com
# 2. Gets assigned a public endpoint
# 3. Updates endpoint on blockchain
# 4. Ready to serve users
```

## Machine ID Tracking

The contract tracks which machine each node runs on:

```solidity
mapping(bytes32 => bytes32) public machineIdHash;
mapping(bytes32 => bytes32[]) public machineNodeIds;

// Example:
// machineIdHash = 0xABC123...
// machineNodeIds[0xABC123] = [nodeId1, nodeId2, nodeId3]
```

**Why Track Machines?**
1. **Decentralization Score**: Know if many nodes are on one machine
2. **Slashing**: If machine goes offline, all its nodes affected
3. **Rewards**: Could reduce rewards for concentrated nodes
4. **Limits**: Could enforce max nodes per machine

## Security Considerations

### 1. Wallet Isolation
- Each node has separate wallet
- Compromise of one doesn't affect others
- Separate staking, separate rewards

### 2. Storage Isolation
- Nodes can't read each other's data
- Directory permissions enforced
- Cross-contamination prevented

### 3. Network Isolation
- Each node gets separate tunnel ID
- Separate WebSocket connections
- Hub tracks them independently

### 4. Reward Protection
- Rewards go to individual wallets
- Can't be redirected
- Withdrawal requires wallet signature

## Best Practices

### For Node Operators

1. **Don't Over-commit**: Don't claim more storage than you have
2. **Separate Wallets**: Use different wallets for each node
3. **Monitor Resources**: Watch CPU, RAM, bandwidth
4. **Backup Keys**: Store wallet private keys securely

### For Multiple Nodes

```
Recommended Configuration (100 GB disk):
├── Node 1: BRONZE (1 GB) - Low stake, low reward
├── Node 2: SILVER (4 GB) - Medium stake, medium reward
└── Node 3: GOLD (10 GB) - Higher stake, higher reward

Total: 15 GB allocated, 600 + 500 + 1000 = 2100 MCT staked
```

### Resource Limits Per Machine

| Machine Type | RAM | Recommended Max Nodes |
|--------------|-----|----------------------|
| Small (2 GB) | 2 GB | 2 nodes |
| Medium (4 GB) | 4 GB | 4 nodes |
| Large (8 GB) | 8 GB | 8 nodes |
| Server (16+ GB) | 16+ GB | 10 nodes |

## FAQ

**Q: Can I run 10 PLATINUM nodes on a small VPS?**
A: No. Each PLATINUM needs 50 GB storage. The StorageManager will reject allocations that exceed available space.

**Q: What if my machine ID changes?**
A: Node registration is tied to machineIdHash. If it changes (e.g., after OS reinstall), you'd need to re-register nodes.

**Q: Can two machines share the same wallet?**
A: No. The contract maps wallet → nodeIds. One wallet = one node registration.

**Q: How does the hub know which node is which?**
A: Each node connects with its unique nodeId and signs a challenge to prove wallet ownership.

**Q: What happens if I claim 10 GB but only have 5 GB?**
A: Currently: You'd fail to store files and lose reputation.
Future: Proof of storage challenges could slash your stake.
