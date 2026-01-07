#!/bin/bash
#
# MumbleChat Relay Node - Linux Installation Script
#
# Features:
# - Auto-detects CPU, RAM, Disk to calculate max nodes
# - Creates isolated storage directories per node
# - Locks/reserves storage space using fallocate
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
MUMBLECHAT_BASE="/var/lib/mumblechat"
MUMBLECHAT_CONFIG="/etc/mumblechat"
MUMBLECHAT_LOG="/var/log/mumblechat"
NODES_DIR="$MUMBLECHAT_BASE/nodes"
LOCK_FILE="$MUMBLECHAT_BASE/.storage.lock"
RESOURCE_FILE="$MUMBLECHAT_CONFIG/resources.json"

echo -e "${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         MumbleChat Desktop Relay Node - Linux Installer        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}Please run as root (sudo)${NC}"
    exit 1
fi

# Detect OS
if [ -f /etc/os-release ]; then
    . /etc/os-release
    OS=$ID
else
    echo -e "${RED}Cannot detect OS${NC}"
    exit 1
fi

echo -e "${GREEN}Detected OS: $OS${NC}"

# =============================================================================
# RESOURCE DETECTION FUNCTIONS
# =============================================================================

get_cpu_cores() {
    nproc 2>/dev/null || grep -c ^processor /proc/cpuinfo 2>/dev/null || echo "1"
}

get_total_ram_mb() {
    free -m | awk '/^Mem:/{print $2}'
}

get_available_ram_mb() {
    free -m | awk '/^Mem:/{print $7}'
}

get_total_disk_mb() {
    df -BM "$MUMBLECHAT_BASE" 2>/dev/null | awk 'NR==2{gsub(/M/,"",$2); print $2}' || echo "0"
}

get_free_disk_mb() {
    df -BM "$MUMBLECHAT_BASE" 2>/dev/null | awk 'NR==2{gsub(/M/,"",$4); print $4}' || echo "0"
}

get_machine_id_hash() {
    if [ -f /etc/machine-id ]; then
        MACHINE_ID=$(cat /etc/machine-id)
    elif [ -f /var/lib/dbus/machine-id ]; then
        MACHINE_ID=$(cat /var/lib/dbus/machine-id)
    else
        MACHINE_ID=$(hostname)-$(ip link show 2>/dev/null | grep ether | awk '{print $2}' | head -1)
    fi
    echo -n "$MACHINE_ID" | sha256sum | cut -d' ' -f1
}

# Calculate max nodes: 2 per core, 256MB RAM each, min 1GB disk each
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
        grep -oP '"total_locked_mb":\s*\K[0-9]+' "$LOCK_FILE" 2>/dev/null || echo "0"
    else
        echo "0"
    fi
}

get_deployed_node_count() {
    if [ -d "$NODES_DIR" ]; then
        ls -1 "$NODES_DIR" 2>/dev/null | wc -l
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

# Lock storage for a node - creates reserved space file
lock_node_storage() {
    local NODE_ID=$1
    local STORAGE_MB=$2
    local NODE_DIR="$NODES_DIR/$NODE_ID"
    
    # Check if already exists
    if [ -d "$NODE_DIR" ]; then
        echo -e "${RED}Node $NODE_ID already exists!${NC}"
        return 1
    fi
    
    # Check max nodes
    local MAX_NODES=$(calculate_max_nodes)
    local DEPLOYED=$(get_deployed_node_count)
    if [ $DEPLOYED -ge $MAX_NODES ]; then
        echo -e "${RED}Max nodes ($MAX_NODES) reached! Cannot deploy more nodes.${NC}"
        return 1
    fi
    
    # Check available storage
    local CAN_ALLOC=$(can_allocate_storage $STORAGE_MB)
    if [ "$CAN_ALLOC" != "true" ]; then
        echo -e "${RED}Not enough storage available!${NC}"
        local FREE_MB=$(get_free_disk_mb)
        local LOCKED_MB=$(get_locked_storage_mb)
        echo "Free: ${FREE_MB}MB, Locked: ${LOCKED_MB}MB, Requested: ${STORAGE_MB}MB"
        return 1
    fi
    
    echo -e "${YELLOW}Locking $STORAGE_MB MB storage for node $NODE_ID...${NC}"
    
    # Create node directory structure
    mkdir -p "$NODE_DIR/storage"
    mkdir -p "$NODE_DIR/logs"
    mkdir -p "$NODE_DIR/keys"
    mkdir -p "$NODE_DIR/cache"
    
    # Create reserved space file using fallocate (instant allocation)
    local RESERVE_FILE="$NODE_DIR/storage/.reserved_space"
    echo -e "${YELLOW}Allocating ${STORAGE_MB}MB disk space...${NC}"
    
    if command -v fallocate &> /dev/null; then
        fallocate -l "${STORAGE_MB}M" "$RESERVE_FILE" 2>/dev/null || {
            dd if=/dev/zero of="$RESERVE_FILE" bs=1M count=$STORAGE_MB status=progress 2>/dev/null
        }
    else
        dd if=/dev/zero of="$RESERVE_FILE" bs=1M count=$STORAGE_MB status=progress 2>/dev/null
    fi
    
    # Make it immutable to prevent accidental deletion
    chattr +i "$RESERVE_FILE" 2>/dev/null || true
    
    # Create node info file
    cat > "$NODE_DIR/node.json" << EOF
{
    "node_id": "$NODE_ID",
    "storage_mb": $STORAGE_MB,
    "locked_at": "$(date -Iseconds)",
    "storage_path": "$NODE_DIR/storage",
    "reserve_file": "$RESERVE_FILE",
    "status": "locked"
}
EOF
    
    # Update global lock file
    local CURRENT_LOCKED=$(get_locked_storage_mb)
    local NEW_LOCKED=$((CURRENT_LOCKED + STORAGE_MB))
    
    # Read existing nodes and add new one
    if command -v python3 &> /dev/null; then
        python3 << PYTHON_SCRIPT
import json
import os

lock_file = "$LOCK_FILE"
try:
    with open(lock_file, 'r') as f:
        data = json.load(f)
except:
    data = {"locked_nodes": [], "total_locked_mb": 0}

data["locked_nodes"].append({
    "node_id": "$NODE_ID",
    "storage_mb": $STORAGE_MB,
    "path": "$NODE_DIR",
    "locked_at": "$(date -Iseconds)"
})
data["total_locked_mb"] = sum(n["storage_mb"] for n in data["locked_nodes"])

with open(lock_file, 'w') as f:
    json.dump(data, f, indent=2)
PYTHON_SCRIPT
    else
        # Simple fallback without python
        echo "{\"locked_nodes\":[],\"total_locked_mb\":$NEW_LOCKED}" > "$LOCK_FILE"
    fi
    
    echo -e "${GREEN}✓ Storage locked successfully: $STORAGE_MB MB for node $NODE_ID${NC}"
    echo -e "${GREEN}✓ Node directory: $NODE_DIR${NC}"
}

# Unlock storage for a node
unlock_node_storage() {
    local NODE_ID=$1
    local NODE_DIR="$NODES_DIR/$NODE_ID"
    
    if [ ! -d "$NODE_DIR" ]; then
        echo -e "${RED}Node $NODE_ID not found!${NC}"
        return 1
    fi
    
    echo -e "${YELLOW}Unlocking storage for node $NODE_ID...${NC}"
    
    # Get storage size before removing
    local STORAGE_MB=0
    if [ -f "$NODE_DIR/node.json" ]; then
        STORAGE_MB=$(grep -oP '"storage_mb":\s*\K[0-9]+' "$NODE_DIR/node.json" 2>/dev/null || echo "0")
    fi
    
    # Remove immutable flag from reserve file
    local RESERVE_FILE="$NODE_DIR/storage/.reserved_space"
    chattr -i "$RESERVE_FILE" 2>/dev/null || true
    
    # Remove node directory
    rm -rf "$NODE_DIR"
    
    # Update lock file
    if command -v python3 &> /dev/null; then
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
    fi
    
    echo -e "${GREEN}✓ Storage unlocked: ${STORAGE_MB}MB freed from node $NODE_ID${NC}"
}

# Display resource summary
display_resource_summary() {
    local CPU_CORES=$(get_cpu_cores)
    local RAM_MB=$(get_total_ram_mb)
    local RAM_AVAIL=$(get_available_ram_mb)
    local DISK_TOTAL=$(get_total_disk_mb)
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
    
    # Save to resource file
    mkdir -p "$MUMBLECHAT_CONFIG"
    cat > "$RESOURCE_FILE" << EOF
{
    "machine_id_hash": "0x$MACHINE_HASH",
    "cpu_cores": $CPU_CORES,
    "ram_total_mb": $RAM_MB,
    "disk_total_mb": $DISK_TOTAL,
    "disk_free_mb": $DISK_FREE,
    "disk_locked_mb": $DISK_LOCKED,
    "disk_available_mb": $AVAILABLE_DISK,
    "max_nodes": $MAX_NODES,
    "deployed_nodes": $DEPLOYED,
    "available_slots": $((MAX_NODES - DEPLOYED)),
    "updated_at": "$(date -Iseconds)"
}
EOF
}

# List deployed nodes
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
                local STORAGE=$(grep -oP '"storage_mb":\s*\K[0-9]+' "${node_dir}node.json" 2>/dev/null || echo "?")
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
# NODEJS INSTALLATION
# =============================================================================

install_nodejs() {
    echo -e "${YELLOW}Installing Node.js...${NC}"
    
    case $OS in
        ubuntu|debian)
            curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
            apt-get install -y nodejs
            ;;
        centos|rhel|fedora)
            curl -fsSL https://rpm.nodesource.com/setup_20.x | bash -
            yum install -y nodejs
            ;;
        arch)
            pacman -S --noconfirm nodejs npm
            ;;
        *)
            echo -e "${RED}Unsupported OS. Please install Node.js 18+ manually${NC}"
            exit 1
            ;;
    esac
}

check_nodejs() {
    if command -v node &> /dev/null; then
        NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
        if [ "$NODE_VERSION" -ge 18 ]; then
            echo -e "${GREEN}Node.js version: $(node -v)${NC}"
            return 0
        fi
    fi
    return 1
}

# =============================================================================
# MAIN - Handle arguments or run installation
# =============================================================================

# Initialize storage tracking first
init_storage_tracking

# Handle command line arguments
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
        echo "MumbleChat Relay Node - Linux Installer"
        echo ""
        echo "Usage: $0 [command]"
        echo ""
        echo "Commands:"
        echo "  (none)              Full installation"
        echo "  --info              Show system resources and limits"
        echo "  --list              List deployed nodes"
        echo "  --lock <id> <mb>    Lock storage for new node"
        echo "  --unlock <id>       Unlock storage and remove node"
        echo ""
        exit 0
        ;;
esac

# Full installation
display_resource_summary

if ! check_nodejs; then
    install_nodejs
fi

# Install Python3 if needed
if ! command -v python3 &> /dev/null; then
    echo -e "${YELLOW}Installing Python3...${NC}"
    case $OS in
        ubuntu|debian) apt-get install -y python3 ;;
        centos|rhel|fedora) yum install -y python3 ;;
        arch) pacman -S --noconfirm python ;;
    esac
fi

# Create user
echo -e "${YELLOW}Creating mumblechat user...${NC}"
if ! id "mumblechat" &>/dev/null; then
    useradd -r -s /bin/false mumblechat
fi

# Create directories
echo -e "${YELLOW}Creating directories...${NC}"
mkdir -p /opt/mumblechat-relay
mkdir -p "$MUMBLECHAT_CONFIG"
mkdir -p "$MUMBLECHAT_BASE/data"
mkdir -p "$MUMBLECHAT_LOG"
mkdir -p "$NODES_DIR"

# Build and install
echo -e "${YELLOW}Installing relay node...${NC}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

npm install
npm run build

cp -r dist/* /opt/mumblechat-relay/
cp package.json /opt/mumblechat-relay/
cd /opt/mumblechat-relay && npm install --production

ln -sf /opt/mumblechat-relay/cli.js /usr/local/bin/mumblechat-relay
chmod +x /usr/local/bin/mumblechat-relay

# Create config
if [ ! -f /etc/mumblechat/config.json ]; then
    cp "$SCRIPT_DIR/../config.example.json" /etc/mumblechat/config.json
    sed -i 's|./data/messages.db|/var/lib/mumblechat/data/messages.db|g' /etc/mumblechat/config.json
    sed -i 's|./logs/relay.log|/var/log/mumblechat/relay.log|g' /etc/mumblechat/config.json
fi

# Create env file with resource limits
cat > /etc/mumblechat/relay.env << EOF
# MumbleChat Relay Node Environment
# RELAY_PRIVATE_KEY=0x...

# Resource limits (auto-detected)
MAX_NODES=$(calculate_max_nodes)
MACHINE_ID_HASH=0x$(get_machine_id_hash)
EOF

# Set permissions
chown -R mumblechat:mumblechat /opt/mumblechat-relay
chown -R mumblechat:mumblechat /var/lib/mumblechat
chown -R mumblechat:mumblechat /var/log/mumblechat
chmod 700 /etc/mumblechat
chmod 600 /etc/mumblechat/relay.env

# Install systemd service
echo -e "${YELLOW}Installing systemd service...${NC}"
cp "$SCRIPT_DIR/mumblechat-relay.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable mumblechat-relay

echo -e "${GREEN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Installation Complete!                       ║"
echo "╠════════════════════════════════════════════════════════════════╣"
printf "║  Max Nodes: %-2s   Available Storage: %-6s MB                  ║\n" "$(calculate_max_nodes)" "$(($(get_free_disk_mb) - $(get_locked_storage_mb) - 1024))"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  Storage Commands:                                              ║"
echo "║    $0 --info              ║"
echo "║    $0 --list              ║"
echo "║    $0 --lock <id> <mb>    ║"
echo "║    $0 --unlock <id>       ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║  Next: Edit /etc/mumblechat/relay.env with your private key    ║"
echo "║        Then: systemctl start mumblechat-relay                  ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"
