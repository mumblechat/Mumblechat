#!/bin/bash
#
# MumbleChat Relay Node - macOS Installation Script
#
# This script installs the MumbleChat Desktop Relay Node on macOS.
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

echo -e "${CYAN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║         MumbleChat Desktop Relay Node - macOS Installer        ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"

# Check for Homebrew
if ! command -v brew &> /dev/null; then
    echo -e "${YELLOW}Installing Homebrew...${NC}"
    /bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
fi

# Install Node.js if not present
if ! command -v node &> /dev/null; then
    echo -e "${YELLOW}Installing Node.js via Homebrew...${NC}"
    brew install node@20
fi

NODE_VERSION=$(node -v | cut -d'v' -f2 | cut -d'.' -f1)
echo -e "${GREEN}Node.js version: $(node -v)${NC}"

if [ "$NODE_VERSION" -lt 18 ]; then
    echo -e "${RED}Node.js 18+ required. Please upgrade.${NC}"
    exit 1
fi

# Create directories
echo -e "${YELLOW}Creating directories...${NC}"
mkdir -p /usr/local/var/mumblechat/data
mkdir -p /usr/local/var/log
mkdir -p /usr/local/etc/mumblechat

# Get script directory
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

# Install dependencies and build
echo -e "${YELLOW}Building relay node...${NC}"
npm install
npm run build

# Install globally
echo -e "${YELLOW}Installing globally...${NC}"
npm link

# Copy default config
if [ ! -f /usr/local/etc/mumblechat/config.json ]; then
    cp config.example.json /usr/local/etc/mumblechat/config.json
    
    # Update paths in config
    sed -i '' 's|./data/messages.db|/usr/local/var/mumblechat/data/messages.db|g' /usr/local/etc/mumblechat/config.json
    sed -i '' 's|./logs/relay.log|/usr/local/var/log/mumblechat-relay.log|g' /usr/local/etc/mumblechat/config.json
fi

# Copy launchd plist
echo -e "${YELLOW}Installing launchd service...${NC}"
cp scripts/com.mumblechat.relay.plist ~/Library/LaunchAgents/

echo -e "${GREEN}"
echo "╔════════════════════════════════════════════════════════════════╗"
echo "║                    Installation Complete!                       ║"
echo "╠════════════════════════════════════════════════════════════════╣"
echo "║                                                                 ║"
echo "║  Next steps:                                                    ║"
echo "║                                                                 ║"
echo "║  1. Run the setup wizard:                                       ║"
echo "║     mumblechat-relay setup                                      ║"
echo "║                                                                 ║"
echo "║  2. Register on blockchain:                                     ║"
echo "║     mumblechat-relay register                                   ║"
echo "║                                                                 ║"
echo "║  3. Start the service:                                          ║"
echo "║     launchctl load ~/Library/LaunchAgents/com.mumblechat.relay.plist"
echo "║                                                                 ║"
echo "║  Or run manually:                                               ║"
echo "║     mumblechat-relay start                                      ║"
echo "║                                                                 ║"
echo "║  View logs:                                                     ║"
echo "║     tail -f /usr/local/var/log/mumblechat-relay.log            ║"
echo "║                                                                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"
