#!/bin/bash
#
# MumbleChat Relay Node - macOS Installation Script
#
# Features:
# - Auto-detects CPU, RAM, Disk to calculate max nodes
# - Creates isolated storage directories per node
# - Locks/reserves storage space
# - Enforces resource limits per machine
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# Configuration
MUMBLECHAT_BASE="/usr/local/var/mumblechat"
MUMBLECHAT_CONFIG="/usr/local/etc/mumblechat"
MUMBLECHAT_LOG="/usr/local/var/log"
NODES_DIR="$MUMBLECHAT_BASE/nodes"
LOCK_FILE="$MUMBLECHAT_BASE/.storage.lock"
RESOURCE_FILE="$MUMBLECHAT_CONFIG/resources.json"

echo -e "${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         MumbleChat Desktop Relay Node - macOS Installer        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# =============================================================================
# RESOURCE DETECTION FUNCTIONS (macOS)
# =============================================================================

get_cpu_cores() {
    sysctl -n hw.ncpu 2>/dev/null || echo "1"
}

get_total_ram_mb() {
    local RAM_BYTES=$(sysctl -n hw.memsize 2>/dev/null || echo "0")
    echo $((RAM_BYTES / 1024 / 1024))
}

get_available_ram_mb() {
    # macOS doesn't have 'free', use vm_stat
    local PAGE_SIZE=$(vm_stat | grep "page size" | awk '{print $8}')
    local FREE_PAGES=$(vm_stat | grep "Pages free" | awk '{print $3}' | tr -d '.')
    echo $(((FREE_PAGES * PAGE_SIZE) / 1024 / 1024))
}

get_total_disk_mb() {
    df -m "$MUMBLECHAT_BASE" 2>/dev/null | awk 'NR==2{print $2}' || echo "0"
}

get_free_disk_mb() {
    df -m "$MUMBLECHAT_BASE" 2>/dev/null | awk 'NR==2{print $4}' || echo "0"
}

get_machine_id_hash() {
    # macOS uses IOPlatformUUID
    local MACHINE_ID=$(ioreg -rd1 -c IOPlatformExpertDevice | awk '/IOPlatformUUID/ { split($0, line, "\""); print line[4]; }' 2>/dev/null)
    if [ -z "$MACHINE_ID" ]; then
        MACHINE_ID=$(hostname)-$(ifconfig en0 2>/dev/null | awk '/ether/{print $2}')
    fi
    echo -n "$MACHINE_ID" | shasum -a 256 | cut -d' ' -f1
}

calculate_max_nodes() {
    local CPU_CORES=$(get_cpu_cores)
    local RAM_MB=$(get_total_ram_mb)
    local DISK_MB=$(get_free_disk_mb)
    
    local MAX_BY_CPU=$((CPU_CORES * 2))
    local MAX_BY_RAM=$((RAM_MB / 256))
    local MAX_BY_DISK=$((DISK_MB / 1024))
    
    local MAX_NODES=$MAX_BY_CPU
    [ $MAX_BY_RAM -lt $MAX_NODES ] && MAX_NODES=$MAX_BY_RAM
    [ $MAX_BY_DISK -lt $MAX_NODES ] && MAX_NODES=$MAX_BY_DISK
    
    [ $MAX_NODES -gt 10 ] && MAX_NODES=10
    [ $MAX_NODES -lt 1 ] && MAX_NODES=1
    
    echo $MAX_NODES
}

# =============================================================================
# STORAGE LOCK FUNCTIONS
# =============================================================================

init_storage_tracking() {
    mkdir -p "$MUMBLECHAT_BASE"
    mkdir -p "$NODES_DIR"
    
    if [ ! -f "$LOCK_FILE" ]; then
        echo '{"locked_nodes":[],"total_locked_mb":0}' > "$LOCK_FILE"
        chmod 600 "$LOCK_FILE"
    fi
}

get_locked_storage_mb() {
    if [ -f "$LOCK_FILE" ]; then
        grep -o '"total_locked_mb":[0-9]*' "$LOCK_FILE" | cut -d: -f2 || echo "0"
    else
        echo "0"
    fi
}

get_deployed_node_count() {
    if [ -d "$NODES_DIR" ]; then
        ls -1 "$NODES_DIR" 2>/dev/null | wc -l | tr -d ' '
    else
        echo "0"
    fi
}

can_allocate_storage() {
    local REQUESTED_MB=$1
    local FREE_MB=$(get_free_disk_mb)
    local LOCKED_MB=$(get_locked_storage_mb)
    local AVAILABLE_MB=$((FREE_MB - LOCKED_MB - 1024))
    
    if [ $REQUESTED_MB -le $AVAILABLE_MB ]; then
        echo "true"
    else
        echo "false"
    fi
}

# Lock storage for a node using sparse file (macOS compatible)
lock_node_storage() {
    local NODE_ID=$1
    local STORAGE_MB=$2
    local NODE_DIR="$NODES_DIR/$NODE_ID"
    
    if [ -d "$NODE_DIR" ]; then
        echo -e "${RED}Node $NODE_ID already exists!${NC}"
        return 1
    fi
    
    local MAX_NODES=$(calculate_max_nodes)
    local DEPLOYED=$(get_deployed_node_count)
    if [ $DEPLOYED -ge $MAX_NODES ]; then
        echo -e "${RED}Max nodes ($MAX_NODES) reached!${NC}"
        return 1
    fi
    
    local CAN_ALLOC=$(can_allocate_storage $STORAGE_MB)
    if [ "$CAN_ALLOC" != "true" ]; then
        echo -e "${RED}Not enough storage available!${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Locking $STORAGE_MB MB storage for node $NODE_ID...${NC}"
    
    mkdir -p "$NODE_DIR/storage"
    mkdir -p "$NODE_DIR/logs"
    mkdir -p "$NODE_DIR/keys"
    mkdir -p "$NODE_DIR/cache"
    
    # Create reserved space file using mkfile (macOS) or dd
    local RESERVE_FILE="$NODE_DIR/storage/.reserved_space"
    echo -e "${YELLOW}Allocating ${STORAGE_MB}MB disk space...${NC}"
    
    if command -v mkfile &> /dev/null; then
        mkfile -n "${STORAGE_MB}m" "$RESERVE_FILE"
    else
        dd if=/dev/zero of="$RESERVE_FILE" bs=1m count=$STORAGE_MB 2>/dev/null
    fi
    
    # Set immutable flag (macOS uses chflags)
    chflags uchg "$RESERVE_FILE" 2>/dev/null || true
    
    # Create node info
    cat > "$NODE_DIR/node.json" << EOF
{
    "node_id": "$NODE_ID",
    "storage_mb": $STORAGE_MB,
    "locked_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "storage_path": "$NODE_DIR/storage",
    "reserve_file": "$RESERVE_FILE",
    "status": "locked"
}
EOF
    
    # Update lock file
    python3 << PYTHON_SCRIPT
import json

lock_file = "$LOCK_FILE"
try:
    with open(lock_file, 'r') as f:
        data = json.load(f)
except:
    data = {"locked_nodes": [], "total_locked_mb": 0}

data["locked_nodes"].append({
    "node_id": "$NODE_ID",
    "storage_mb": $STORAGE_MB,
    "path": "$NODE_DIR"
})
data["total_locked_mb"] = sum(n["storage_mb"] for n in data["locked_nodes"])

with open(lock_file, 'w') as f:
    json.dump(data, f, indent=2)
PYTHON_SCRIPT
    
    echo -e "${GREEN}✓ Storage locked: $STORAGE_MB MB for node $NODE_ID${NC}"
}

unlock_node_storage() {
    local NODE_ID=$1
    local NODE_DIR="$NODES_DIR/$NODE_ID"
    
    if [ ! -d "$NODE_DIR" ]; then
        echo -e "${RED}Node $NODE_ID not found!${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Unlocking storage for node $NODE_ID...${NC}"
    
    local STORAGE_MB=$(grep -o '"storage_mb":[0-9]*' "$NODE_DIR/node.json" 2>/dev/null | cut -d: -f2 || echo "0")
    
    # Remove immutable flag
    chflags nouchg "$NODE_DIR/storage/.reserved_space" 2>/dev/null || true
    
    rm -rf "$NODE_DIR"
    
    # Update lock file
    python3 << PYTHON_SCRIPT
import json

lock_file = "$LOCK_FILE"
try:
    with open(lock_file, 'r') as f:
        data = json.load(f)
except:
    data = {"locked_nodes": [], "total_locked_mb": 0}

data["locked_nodes"] = [n for n in data["locked_nodes"] if n["node_id"] != "$NODE_ID"]
data["total_locked_mb"] = sum(n["storage_mb"] for n in data["locked_nodes"])

with open(lock_file, 'w') as f:
    json.dump(data, f, indent=2)
PYTHON_SCRIPT
    
    echo -e "${GREEN}✓ Storage unlocked: ${STORAGE_MB}MB freed${NC}"
}

display_resource_summary() {
    local CPU_CORES=$(get_cpu_cores)
    local RAM_MB=$(get_total_ram_mb)
    local DISK_FREE=$(get_free_disk_mb)
    local DISK_LOCKED=$(get_locked_storage_mb)
    local MAX_NODES=$(calculate_max_nodes)
    local DEPLOYED=$(get_deployed_node_count)
    local MACHINE_HASH=$(get_machine_id_hash)
    local AVAILABLE_DISK=$((DISK_FREE - DISK_LOCKED - 1024))
    [ $AVAILABLE_DISK -lt 0 ] && AVAILABLE_DISK=0
    
    echo -e "${CYAN}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    SYSTEM RESOURCES                            ║"
    echo "╠════════════════════════════════════════════════════════════════╣"
    printf "║  Machine ID: 0x%.48s...║\n" "$MACHINE_HASH"
    echo "╠════════════════════════════════════════════════════════════════╣"
    printf "║  CPU:    %-3s cores  (Max %-2s nodes by CPU)                     ║\n" "$CPU_CORES" "$((CPU_CORES * 2))"
    printf "║  RAM:    %-5s MB   (Max %-2s nodes by RAM)                     ║\n" "$RAM_MB" "$((RAM_MB / 256))"
    printf "║  Disk:   %-5s MB   (Max %-2s nodes by Disk)                    ║\n" "$DISK_FREE" "$((DISK_FREE / 1024))"
    echo "╠════════════════════════════════════════════════════════════════╣"
    printf "║  Storage Locked:    %-6s MB (by %-2s nodes)                    ║\n" "$DISK_LOCKED" "$DEPLOYED"
    printf "║  Storage Available: %-6s MB                                  ║\n" "$AVAILABLE_DISK"
    echo "╠════════════════════════════════════════════════════════════════╣"
    printf "║  MAX NODES ALLOWED: %-2s                                        ║\n" "$MAX_NODES"
    printf "║  NODES DEPLOYED:    %-2s                                        ║\n" "$DEPLOYED"
    printf "║  SLOTS AVAILABLE:   %-2s                                        ║\n" "$((MAX_NODES - DEPLOYED))"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    
    mkdir -p "$MUMBLECHAT_CONFIG"
    cat > "$RESOURCE_FILE" << EOF
{
    "machine_id_hash": "0x$MACHINE_HASH",
    "cpu_cores": $CPU_CORES,
    "ram_total_mb": $RAM_MB,
    "disk_free_mb": $DISK_FREE,
    "disk_locked_mb": $DISK_LOCKED,
    "disk_available_mb": $AVAILABLE_DISK,
    "max_nodes": $MAX_NODES,
    "deployed_nodes": $DEPLOYED,
    "available_slots": $((MAX_NODES - DEPLOYED)),
    "updated_at": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF
}

list_deployed_nodes() {
    echo -e "${CYAN}"
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    DEPLOYED NODES                              ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
    
    if [ -d "$NODES_DIR" ] && [ "$(ls -A $NODES_DIR 2>/dev/null)" ]; then
        printf "%-3s %-20s %-10s %-30s\n" "#" "NODE ID" "STORAGE" "PATH"
        echo "────────────────────────────────────────────────────────────────────"
        
        local i=1
        for node_dir in "$NODES_DIR"/*/; do
            if [ -f "${node_dir}node.json" ]; then
                local NODE_ID=$(basename "$node_dir")
                local STORAGE=$(grep -o '"storage_mb":[0-9]*' "${node_dir}node.json" | cut -d: -f2 || echo "?")
                printf "%-3s %-20s %-10s %-30s\n" "$i" "${NODE_ID:0:18}..." "${STORAGE}MB" "$node_dir"
                i=$((i + 1))
            fi
        done
    else
        echo "No nodes deployed yet."
    fi
    echo ""
}

# =============================================================================
# MAIN
# =============================================================================

# Check for Homebrew
if ! command -v brew &> /dev/null; then
    echo -e "${YELLOW}Installing Homebrew...${NC}"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

# Initialize
init_storage_tracking

# Handle arguments
case "$1" in
    --info|info)
        display_resource_summary
        exit 0
        ;;
    --list|list)
        list_deployed_nodes
        exit 0
        ;;
    --lock|lock)
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo "Usage: $0 --lock <node_id> <storage_mb>"
            exit 1
        fi
        lock_node_storage "$2" "$3"
        exit 0
        ;;
    --unlock|unlock)
        if [ -z "$2" ]; then
            echo "Usage: $0 --unlock <node_id>"
            exit 1
        fi
        unlock_node_storage "$2"
        exit 0
        ;;
    --help|help|-h)
        echo "MumbleChat Relay Node - macOS Installer"
        echo ""
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  (none)              Full installation"
        echo "  --info              Show system resources"
        echo "  --list              List deployed nodes"
        echo "  --lock <id> <mb>    Lock storage for node"
        echo "  --unlock <id>       Unlock storage"
        exit 0
        ;;
esac

# Full installation
display_resource_summary

# Install Node.js
if ! command -v node &> /dev/null; then
    echo -e "${YELLOW}Installing Node.js via Homebrew...${NC}"
    brew install node@20
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
echo -e "${GREEN}Node.js version: $(node -v)${NC}"

if [ "$NODE_VERSION" -lt 18 ]; then
    echo -e "${RED}Node.js 18+ required${NC}"
    exit 1
fi

# Create directories
echo -e "${YELLOW}Creating directories...${NC}"
mkdir -p "$MUMBLECHAT_BASE/data"
mkdir -p "$MUMBLECHAT_LOG"
mkdir -p "$MUMBLECHAT_CONFIG"
mkdir -p "$NODES_DIR"

# Build
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

echo -e "${YELLOW}Building relay node...${NC}"
npm install
npm run build

echo -e "${YELLOW}Installing globally...${NC}"
npm link

# Config
if [ ! -f "$MUMBLECHAT_CONFIG/config.json" ]; then
    cp config.example.json "$MUMBLECHAT_CONFIG/config.json"
    sed -i '' 's|./data/messages.db|/usr/local/var/mumblechat/data/messages.db|g' "$MUMBLECHAT_CONFIG/config.json"
    sed -i '' 's|./logs/relay.log|/usr/local/var/log/mumblechat-relay.log|g' "$MUMBLECHAT_CONFIG/config.json"
fi

# Env file
cat > "$MUMBLECHAT_CONFIG/relay.env" << EOF
# MumbleChat Relay Node
# RELAY_PRIVATE_KEY=0x...
MAX_NODES=$(calculate_max_nodes)
MACHINE_ID_HASH=0x$(get_machine_id_hash)
EOF

# LaunchAgent
cp scripts/com.mumblechat.relay.plist ~/Library/LaunchAgents/

echo -e "${GREEN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Installation Complete!                       ║"
echo "╠════════════════════════════════════════════════════════════════╣"
printf "║  Max Nodes: %-2s   Available Storage: %-6s MB                  ║\n" "$(calculate_max_nodes)" "$(($(get_free_disk_mb) - $(get_locked_storage_mb) - 1024))"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  Storage: $0 --info / --list / --lock / --unlock  ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  1. mumblechat-relay setup                                      ║"
echo "║  2. mumblechat-relay register                                   ║"
echo "║  3. launchctl load ~/Library/LaunchAgents/com.mumblechat.relay.plist"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"
