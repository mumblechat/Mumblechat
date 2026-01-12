#!/bin/bash

# MumbleChat Relay Node Installer
# One-click installation script for macOS/Linux

set -e

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║        🌐 MumbleChat Relay Node Installer                    ║"
echo "║                                                              ║"
echo "║   Earn MCT tokens by relaying encrypted messages             ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Detect OS
OS="$(uname -s)"
ARCH="$(uname -m)"

echo -e "${BLUE}[INFO]${NC} Detected OS: $OS ($ARCH)"

# Check if Node.js is installed
if ! command -v node &> /dev/null; then
    echo -e "${YELLOW}[WARN]${NC} Node.js is not installed."
    echo ""
    
    if [[ "$OS" == "Darwin" ]]; then
        echo -e "${BLUE}[INFO]${NC} Installing Node.js via Homebrew..."
        if ! command -v brew &> /dev/null; then
            echo -e "${YELLOW}[INFO]${NC} Installing Homebrew first..."
            /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
        fi
        brew install node
    elif [[ "$OS" == "Linux" ]]; then
        echo -e "${BLUE}[INFO]${NC} Installing Node.js..."
        if command -v apt &> /dev/null; then
            curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
            sudo apt-get install -y nodejs
        elif command -v dnf &> /dev/null; then
            curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
            sudo dnf install -y nodejs
        elif command -v yum &> /dev/null; then
            curl -fsSL https://rpm.nodesource.com/setup_20.x | sudo bash -
            sudo yum install -y nodejs
        else
            echo -e "${RED}[ERROR]${NC} Could not detect package manager. Please install Node.js manually:"
            echo "  https://nodejs.org/en/download/"
            exit 1
        fi
    fi
fi

NODE_VERSION=$(node --version)
echo -e "${GREEN}[OK]${NC} Node.js version: $NODE_VERSION"

# Configuration
INSTALL_DIR="$HOME/.mumblechat-relay"
CONFIG_FILE="$INSTALL_DIR/config.json"
HUB_URL="wss://hub.mumblechat.com"

# Create install directory
echo -e "${BLUE}[INFO]${NC} Creating installation directory..."
mkdir -p "$INSTALL_DIR"

# Check if wallet argument provided
WALLET_ADDRESS=""
if [ ! -z "$1" ]; then
    WALLET_ADDRESS="$1"
    echo -e "${GREEN}[OK]${NC} Wallet address: $WALLET_ADDRESS"
fi

# Create config file
echo -e "${BLUE}[INFO]${NC} Creating configuration..."
cat > "$CONFIG_FILE" << EOF
{
    "version": "2.0.0",
    "hubUrl": "$HUB_URL",
    "wallet": "$WALLET_ADDRESS",
    "tier": "bronze",
    "storageMB": 1024,
    "maxConnections": 50,
    "port": 9876,
    "logLevel": "info",
    "autoStart": true
}
EOF

# Create the relay node script
echo -e "${BLUE}[INFO]${NC} Installing relay node..."
cat > "$INSTALL_DIR/relay-node.js" << 'NODEEOF'
#!/usr/bin/env node

/**
 * MumbleChat Desktop Relay Node
 * Connects to hub and earns MCT by relaying messages
 */

const WebSocket = require('ws');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const CONFIG_FILE = path.join(__dirname, 'config.json');
const LOG_FILE = path.join(__dirname, 'relay.log');

// Load config
let config = {
    hubUrl: 'wss://hub.mumblechat.com',
    wallet: '',
    tier: 'bronze',
    storageMB: 1024,
    port: 9876
};

if (fs.existsSync(CONFIG_FILE)) {
    try {
        config = { ...config, ...JSON.parse(fs.readFileSync(CONFIG_FILE, 'utf8')) };
    } catch (e) {
        console.error('Failed to load config:', e.message);
    }
}

// Generate node ID from wallet or random
const nodeId = config.wallet 
    ? `relay_${config.wallet.slice(2, 14)}` 
    : `relay_${crypto.randomBytes(6).toString('hex')}`;

let ws = null;
let reconnectAttempts = 0;
let messagesRelayed = 0;
let startTime = Date.now();
let isConnected = false;

function log(message, level = 'INFO') {
    const timestamp = new Date().toISOString();
    const logLine = `[${timestamp}] [${level}] ${message}`;
    console.log(logLine);
    
    // Also write to log file
    fs.appendFileSync(LOG_FILE, logLine + '\n');
}

function connect() {
    log(`Connecting to hub: ${config.hubUrl}`);
    
    ws = new WebSocket(config.hubUrl);
    
    ws.on('open', () => {
        isConnected = true;
        reconnectAttempts = 0;
        log('✅ Connected to MumbleChat Hub!', 'SUCCESS');
        
        // Authenticate as relay node
        ws.send(JSON.stringify({
            type: 'NODE_AUTH',
            nodeId: nodeId,
            wallet: config.wallet,
            tier: config.tier,
            storageMB: config.storageMB,
            version: '2.0.0'
        }));
    });
    
    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data.toString());
            handleMessage(msg);
        } catch (e) {
            log(`Failed to parse message: ${e.message}`, 'ERROR');
        }
    });
    
    ws.on('close', (code, reason) => {
        isConnected = false;
        log(`Disconnected from hub (code: ${code})`, 'WARN');
        scheduleReconnect();
    });
    
    ws.on('error', (err) => {
        log(`WebSocket error: ${err.message}`, 'ERROR');
    });
}

function handleMessage(msg) {
    switch (msg.type) {
        case 'AUTH_SUCCESS':
            log(`✅ Authenticated! Node ID: ${msg.nodeId}`, 'SUCCESS');
            log(`📊 Tier: ${msg.tier || 'Bronze'} | Staked: ${msg.stakedAmount || 0} MCT`);
            if (msg.endpoint) {
                log(`🌐 Endpoint: ${msg.endpoint}`);
            }
            break;
            
        case 'AUTH_FAILED':
            log(`❌ Authentication failed: ${msg.reason}`, 'ERROR');
            if (msg.reason.includes('staked')) {
                log('💡 Please stake MCT at https://mumblechat.com/relay-node.html');
            }
            break;
            
        case 'RELAY_MESSAGE':
            messagesRelayed++;
            // Forward message (in production, this would go to connected clients)
            if (msg.ack) {
                ws.send(JSON.stringify({
                    type: 'RELAY_ACK',
                    messageId: msg.messageId,
                    nodeId: nodeId
                }));
            }
            break;
            
        case 'PING':
            ws.send(JSON.stringify({ type: 'PONG', nodeId: nodeId }));
            break;
            
        case 'STATS_REQUEST':
            ws.send(JSON.stringify({
                type: 'STATS_RESPONSE',
                nodeId: nodeId,
                uptime: Math.floor((Date.now() - startTime) / 1000),
                messagesRelayed: messagesRelayed,
                storageMB: config.storageMB
            }));
            break;
            
        default:
            // Unknown message type
            break;
    }
}

function scheduleReconnect() {
    reconnectAttempts++;
    const delay = Math.min(30000, 1000 * Math.pow(2, reconnectAttempts));
    log(`Reconnecting in ${delay/1000}s (attempt ${reconnectAttempts})...`);
    setTimeout(connect, delay);
}

function printStatus() {
    const uptime = Math.floor((Date.now() - startTime) / 1000);
    const hours = Math.floor(uptime / 3600);
    const mins = Math.floor((uptime % 3600) / 60);
    const secs = uptime % 60;
    
    console.log('\n╔══════════════════════════════════════════════════════════╗');
    console.log('║           🌐 MumbleChat Relay Node Status                ║');
    console.log('╠══════════════════════════════════════════════════════════╣');
    console.log(`║  Node ID:    ${nodeId.padEnd(42)}║`);
    console.log(`║  Wallet:     ${(config.wallet || 'Not configured').slice(0, 42).padEnd(42)}║`);
    console.log(`║  Status:     ${(isConnected ? '🟢 Connected' : '🔴 Disconnected').padEnd(42)}║`);
    console.log(`║  Uptime:     ${`${hours}h ${mins}m ${secs}s`.padEnd(42)}║`);
    console.log(`║  Messages:   ${String(messagesRelayed).padEnd(42)}║`);
    console.log(`║  Tier:       ${config.tier.charAt(0).toUpperCase() + config.tier.slice(1).padEnd(41)}║`);
    console.log('╚══════════════════════════════════════════════════════════╝\n');
}

// Handle graceful shutdown
process.on('SIGINT', () => {
    log('Shutting down relay node...');
    if (ws) ws.close();
    process.exit(0);
});

// Main
console.log('\n╔══════════════════════════════════════════════════════════╗');
console.log('║        🌐 MumbleChat Desktop Relay Node v2.0.0           ║');
console.log('║                                                          ║');
console.log('║   Earn MCT tokens by relaying encrypted messages!        ║');
console.log('╚══════════════════════════════════════════════════════════╝\n');

log(`Node ID: ${nodeId}`);
log(`Wallet: ${config.wallet || 'Not configured'}`);
log(`Hub: ${config.hubUrl}`);
log(`Tier: ${config.tier}`);
log(`Storage: ${config.storageMB} MB`);
console.log('');

connect();

// Print status every 60 seconds
setInterval(printStatus, 60000);

// Initial status after 5 seconds
setTimeout(printStatus, 5000);
NODEEOF

# Make executable
chmod +x "$INSTALL_DIR/relay-node.js"

# Install dependencies
echo -e "${BLUE}[INFO]${NC} Installing dependencies..."
cd "$INSTALL_DIR"

# Create minimal package.json
cat > "$INSTALL_DIR/package.json" << EOF
{
    "name": "mumblechat-relay",
    "version": "2.0.0",
    "description": "MumbleChat Desktop Relay Node",
    "main": "relay-node.js",
    "bin": {
        "mumblechat-relay": "./relay-node.js"
    },
    "dependencies": {
        "ws": "^8.14.0"
    }
}
EOF

npm install --silent

# Create launcher script
LAUNCHER="$INSTALL_DIR/start.sh"
cat > "$LAUNCHER" << EOF
#!/bin/bash
cd "$INSTALL_DIR"
node relay-node.js
EOF
chmod +x "$LAUNCHER"

# Create symlink for global access
if [[ "$OS" == "Darwin" ]]; then
    # macOS - add to path via .zshrc or .bashrc
    SHELL_RC="$HOME/.zshrc"
    if [ ! -f "$SHELL_RC" ]; then
        SHELL_RC="$HOME/.bashrc"
    fi
    
    if ! grep -q "mumblechat-relay" "$SHELL_RC" 2>/dev/null; then
        echo "" >> "$SHELL_RC"
        echo "# MumbleChat Relay Node" >> "$SHELL_RC"
        echo "alias mumblechat-relay='node $INSTALL_DIR/relay-node.js'" >> "$SHELL_RC"
    fi
    
    echo -e "${GREEN}[OK]${NC} Added 'mumblechat-relay' command to shell"
else
    # Linux - create symlink
    sudo ln -sf "$INSTALL_DIR/relay-node.js" /usr/local/bin/mumblechat-relay 2>/dev/null || true
fi

echo ""
echo -e "${GREEN}╔══════════════════════════════════════════════════════════════╗${NC}"
echo -e "${GREEN}║              ✅ Installation Complete!                       ║${NC}"
echo -e "${GREEN}╚══════════════════════════════════════════════════════════════╝${NC}"
echo ""
echo -e "${BLUE}Installation directory:${NC} $INSTALL_DIR"
echo ""
echo -e "${YELLOW}To configure your wallet (required for rewards):${NC}"
echo "  Edit: $CONFIG_FILE"
echo "  Set your wallet address in the 'wallet' field"
echo ""
echo -e "${YELLOW}To start the relay node:${NC}"
echo "  $LAUNCHER"
echo ""
echo -e "${YELLOW}Or open a new terminal and run:${NC}"
echo "  mumblechat-relay"
echo ""

# Ask if user wants to start now
read -p "Start relay node now? (y/n) " -n 1 -r
echo ""
if [[ $REPLY =~ ^[Yy]$ ]]; then
    echo ""
    echo -e "${BLUE}Starting MumbleChat Relay Node...${NC}"
    echo ""
    node "$INSTALL_DIR/relay-node.js"
fi
