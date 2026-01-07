#!/bin/bash
#
# MumbleChat Multi-Node Test Setup
# 
# This script sets up 2 test relay nodes on this server
# using different wallet addresses but the same machine.
#

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       MUMBLECHAT - MULTI-NODE TEST SETUP                     ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Configuration
DATA_DIR="/root/MumbleChat/relay-nodes"
HUB_URL="https://hub.mumblechat.com"
RPC_URL="https://blockchain.ramestta.com"

# Contract addresses
MCT_TOKEN="0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE"
RELAY_MANAGER="0xF78F840eF0e321567362bDBF901DB3bCCa7B26524AF99"

# Deployer wallet (has MCT tokens)
DEPLOYER_KEY="deec7d287996f966385cb5977200083464c4282410a82d7ae57f880e860665e0"
DEPLOYER_ADDRESS="0xDF5522431567362bDBF901DB3bCCa7B26524AF99"

# Get machine ID hash
MACHINE_ID=$(cat /etc/machine-id 2>/dev/null || hostname)
MACHINE_ID_HASH=$(echo -n "$MACHINE_ID" | sha256sum | cut -d' ' -f1)

echo "Machine ID Hash: 0x${MACHINE_ID_HASH}"
echo ""

# Check disk space
DISK_FREE=$(df -m / | tail -1 | awk '{print $4}')
echo "Free disk space: ${DISK_FREE} MB"
echo ""

# Create data directories
mkdir -p "$DATA_DIR/node1/storage"
mkdir -p "$DATA_DIR/node2/storage"

# Generate test wallets for nodes
echo "Generating test wallets..."
echo ""

# Node 1 wallet
NODE1_KEY=$(openssl rand -hex 32)
NODE1_ADDR=$(cast wallet address $NODE1_KEY 2>/dev/null || echo "0x$(echo -n "$NODE1_KEY" | sha256sum | cut -c1-40)")

# Node 2 wallet
NODE2_KEY=$(openssl rand -hex 32)
NODE2_ADDR=$(cast wallet address $NODE2_KEY 2>/dev/null || echo "0x$(echo -n "$NODE2_KEY" | sha256sum | cut -c1-40)")

# Generate node IDs
NODE1_ID="0x$(echo -n "${NODE1_ADDR}$(date +%s)1" | sha256sum | cut -d' ' -f1)"
NODE2_ID="0x$(echo -n "${NODE2_ADDR}$(date +%s)2" | sha256sum | cut -d' ' -f1)"

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                      NODE 1 (BRONZE)                         ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Node ID:     ${NODE1_ID:0:42}  ║"
echo "║  Wallet:      (generated)                                    ║"
echo "║  Private Key: (save this!)                                   ║"
echo "║  Tier:        BRONZE                                         ║"
echo "║  Storage:     1024 MB (1 GB)                                 ║"
echo "║  Mode:        MANAGED (via hub.mumblechat.com)               ║"
echo "║  Data Dir:    $DATA_DIR/node1                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                      NODE 2 (SILVER)                         ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Node ID:     ${NODE2_ID:0:42}  ║"
echo "║  Wallet:      (generated)                                    ║"
echo "║  Private Key: (save this!)                                   ║"
echo "║  Tier:        SILVER                                         ║"
echo "║  Storage:     4096 MB (4 GB)                                 ║"
echo "║  Mode:        MANAGED (via hub.mumblechat.com)               ║"
echo "║  Data Dir:    $DATA_DIR/node2                                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Save configuration
cat > "$DATA_DIR/nodes-config.json" << EOF
{
  "machineId": "$MACHINE_ID",
  "machineIdHash": "0x$MACHINE_ID_HASH",
  "createdAt": "$(date -Iseconds)",
  "hub": "$HUB_URL",
  "nodes": [
    {
      "id": 1,
      "nodeId": "$NODE1_ID",
      "walletAddress": "$NODE1_ADDR",
      "privateKey": "$NODE1_KEY",
      "tier": "BRONZE",
      "storageMB": 1024,
      "dataPath": "$DATA_DIR/node1",
      "mode": "MANAGED",
      "registered": false
    },
    {
      "id": 2,
      "nodeId": "$NODE2_ID",
      "walletAddress": "$NODE2_ADDR",
      "privateKey": "$NODE2_KEY",
      "tier": "SILVER",
      "storageMB": 4096,
      "dataPath": "$DATA_DIR/node2",
      "mode": "MANAGED",
      "registered": false
    }
  ]
}
EOF

echo "Configuration saved to: $DATA_DIR/nodes-config.json"
echo ""

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                     STORAGE ALLOCATION                       ║"
echo "╠══════════════════════════════════════════════════════════════╣"
echo "║  Machine Total Free:  ${DISK_FREE} MB                               ║"
echo "║  Node 1 Allocated:    1024 MB                                ║"
echo "║  Node 2 Allocated:    4096 MB                                ║"
echo "║  Total Allocated:     5120 MB                                ║"
echo "║  Remaining Free:      $((DISK_FREE - 5120)) MB                              ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

echo "═══════════════════════════════════════════════════════════════"
echo " NEXT STEPS:"
echo "═══════════════════════════════════════════════════════════════"
echo ""
echo " 1. Send MCT tokens to node wallets:"
echo "    Node 1 needs: 100 MCT (BRONZE tier)"
echo "    Node 2 needs: 500 MCT (SILVER tier)"
echo ""
echo " 2. Register nodes on blockchain (requires MCT in wallets)"
echo ""
echo " 3. Start nodes (they will connect to hub.mumblechat.com)"
echo ""
echo "═══════════════════════════════════════════════════════════════"
