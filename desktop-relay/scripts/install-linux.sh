#!/bin/bash
#
# MumbleChat Relay Node - Linux Installation Script
#
# This script installs the MumbleChat Desktop Relay Node on Linux systems.
# Supports: Ubuntu, Debian, CentOS, RHEL, Fedora, Arch Linux
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

# Install Node.js if not present
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
            echo -e "${RED}Unsupported OS for automatic Node.js installation${NC}"
            echo "Please install Node.js 18+ manually"
            exit 1
            ;;
    esac
}

# Check Node.js version
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

if ! check_nodejs; then
    install_nodejs
fi

# Create user for the relay
echo -e "${YELLOW}Creating mumblechat user...${NC}"
if ! id "mumblechat" &>/dev/null; then
    useradd -r -s /bin/false mumblechat
fi

# Create directories
echo -e "${YELLOW}Creating directories...${NC}"
mkdir -p /opt/mumblechat-relay
mkdir -p /etc/mumblechat
mkdir -p /var/lib/mumblechat/data
mkdir -p /var/log/mumblechat

# Copy files
echo -e "${YELLOW}Installing relay node...${NC}"
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cd "$SCRIPT_DIR/.."

# Install dependencies and build
npm install
npm run build

# Copy binary
cp -r dist/* /opt/mumblechat-relay/
cp package.json /opt/mumblechat-relay/
cd /opt/mumblechat-relay && npm install --production

# Create symlink for CLI
ln -sf /opt/mumblechat-relay/cli.js /usr/local/bin/mumblechat-relay
chmod +x /usr/local/bin/mumblechat-relay

# Copy default config
if [ ! -f /etc/mumblechat/config.json ]; then
    cp "$SCRIPT_DIR/../config.example.json" /etc/mumblechat/config.json
    
    # Update paths in config
    sed -i 's|./data/messages.db|/var/lib/mumblechat/data/messages.db|g' /etc/mumblechat/config.json
    sed -i 's|./logs/relay.log|/var/log/mumblechat/relay.log|g' /etc/mumblechat/config.json
fi

# Create environment file
if [ ! -f /etc/mumblechat/relay.env ]; then
    cat > /etc/mumblechat/relay.env << EOF
# MumbleChat Relay Node Environment
# Add your private key here (NEVER commit this file)
# RELAY_PRIVATE_KEY=0x...
EOF
fi

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
echo "║                                                                 ║"
echo "║  Next steps:                                                    ║"
echo "║                                                                 ║"
echo "║  1. Add your private key to /etc/mumblechat/relay.env         ║"
echo "║     RELAY_PRIVATE_KEY=0x...                                     ║"
echo "║                                                                 ║"
echo "║  2. Edit configuration:                                         ║"
echo "║     nano /etc/mumblechat/config.json                           ║"
echo "║                                                                 ║"
echo "║  3. Run the setup wizard:                                       ║"
echo "║     sudo -u mumblechat mumblechat-relay setup                  ║"
echo "║                                                                 ║"
echo "║  4. Register on blockchain:                                     ║"
echo "║     sudo -u mumblechat mumblechat-relay register               ║"
echo "║                                                                 ║"
echo "║  5. Start the service:                                          ║"
echo "║     sudo systemctl start mumblechat-relay                      ║"
echo "║                                                                 ║"
echo "║  View logs:                                                     ║"
echo "║     journalctl -u mumblechat-relay -f                          ║"
echo "║                                                                 ║"
echo "╚════════════════════════════════════════════════════════════════╝"
echo -e "${NC}"
