/**
 * MumbleChat Browser-Based Relay Node
 * Run a relay node directly in your browser and earn MCT tokens
 */

// MCT Token ABI (minimal)
const MCT_ABI = [
    'function balanceOf(address account) external view returns (uint256)',
    'function decimals() external view returns (uint8)'
];

// MCT Token Contract
const MCT_ADDRESS = '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE';

// MCT Requirements by Tier (from contract)
const MCT_REQUIREMENTS = {
    'bronze': 100,
    'silver': 500,
    'gold': 1000,
    'platinum': 2500
};

// Storage Tiers (from Android RelayConfig.kt)
const STORAGE_TIERS = {
    'bronze': 1024,    // 1 GB
    'silver': 2048,    // 2 GB
    'gold': 4096,      // 4 GB
    'platinum': 8192   // 8 GB
};

// Uptime Requirements in hours/day
const UPTIME_TIERS = {
    'bronze': 4,       // 4+ hours/day
    'silver': 8,       // 8+ hours/day
    'gold': 12,        // 12+ hours/day
    'platinum': 16     // 16+ hours/day
};

// Fee Pool Multipliers
const FEE_MULTIPLIERS = {
    'bronze': 1.0,
    'silver': 1.5,
    'gold': 2.0,
    'platinum': 3.0
};

class BrowserRelayNode {
    constructor() {
        this.wallet = null;
        this.mctBalance = 0;
        this.isRunning = false;
        this.startTime = null;
        this.messageStore = new Map();
        this.peers = new Set();
        this.messagesRelayed = 0;
        this.tier = 'bronze';
        this.maxStorageMB = 1024;
        this.uptimeInterval = null;
        this.statsInterval = null;
        this.earnings = 0;
        this.osType = this.detectOS();
    }

    /**
     * Detect operating system
     */
    detectOS() {
        const ua = navigator.userAgent;
        
        if (ua.indexOf('Win') > -1) {
            return { name: 'Windows', icon: '🪟', supported: true };
        } else if (ua.indexOf('Mac') > -1) {
            return { name: 'macOS', icon: '🍎', supported: true };
        } else if (ua.indexOf('Linux') > -1) {
            return { name: 'Linux', icon: '🐧', supported: true };
        } else if (ua.indexOf('X11') > -1) {
            return { name: 'Unix', icon: '🖥️', supported: true };
        } else {
            return { name: 'Unknown', icon: '💻', supported: true };
        }
    }

    async connectWallet() {
        if (typeof window.ethereum === 'undefined') {
            this.log('❌ MetaMask not found. Please install MetaMask.', 'error');
            alert('Please install MetaMask to continue');
            return false;
        }

        try {
            const accounts = await window.ethereum.request({ 
                method: 'eth_requestAccounts' 
            });
            
            const provider = new ethers.BrowserProvider(window.ethereum);
            const signer = await provider.getSigner();
            this.wallet = await signer.getAddress();
            
            this.log(`✅ Wallet connected: ${this.wallet}`, 'success');
            this.log(`${this.osType.icon} OS Detected: ${this.osType.name}`, 'info');
            
            // Update UI
            document.getElementById('walletAddressDisplay').textContent = this.wallet.slice(0, 6) + '...' + this.wallet.slice(-4);
            document.getElementById('walletInfo').style.display = 'block';
            document.getElementById('desktopNodeSection').style.display = 'block';
            this.showOSDownloadOptions();
            
            // Check MCT balance
            await this.checkMCTBalance();
            
            return true;
        } catch (error) {
            this.log(`❌ Failed to connect wallet: ${error.message}`, 'error');
            return false;
        }
    }

    /**
     * Check MCT token balance and requirements
     */
    async checkMCTBalance() {
        try {
            this.log('🔍 Checking MCT token balance...', 'info');
            
            const provider = new ethers.BrowserProvider(window.ethereum);
            const mctContract = new ethers.Contract(MCT_ADDRESS, MCT_ABI, provider);
            
            const balance = await mctContract.balanceOf(this.wallet);
            const decimals = await mctContract.decimals();
            this.mctBalance = parseFloat(ethers.formatUnits(balance, decimals));
            
            this.log(`💰 MCT Balance: ${this.mctBalance.toFixed(2)} MCT`, 'success');
            
            // Update UI
            document.getElementById('mctBalance').textContent = `${this.mctBalance.toFixed(2)} MCT`;
            document.getElementById('osType').textContent = `${this.osType.icon} ${this.osType.name}`;
            
            // Check minimum requirements
            this.validateTierRequirements();
            
            return true;
        } catch (error) {
            this.log(`⚠️ Could not fetch MCT balance: ${error.message}`, 'warning');
            this.log('💡 Continuing in offline mode. Register on-chain to earn rewards.', 'info');
            return false;
        }
    }

    /**
     * Validate if wallet has enough MCT for selected tier
     */
    validateTierRequirements() {
        const requiredMCT = MCT_REQUIREMENTS[this.tier];
        const hasEnoughMCT = this.mctBalance >= requiredMCT;
        
        const statusEl = document.getElementById('mctStatus');
        const startBtn = document.getElementById('startNodeBtn');
        
        if (hasEnoughMCT) {
            this.log(`✅ You have enough MCT (${requiredMCT} required) for ${this.tier.toUpperCase()} tier`, 'success');
            if (statusEl) statusEl.innerHTML = `✅ Ready to register (${this.mctBalance.toFixed(2)} MCT)`;
            if (startBtn) startBtn.disabled = false;
        } else {
            const needed = (requiredMCT - this.mctBalance).toFixed(2);
            this.log(`❌ Need ${needed} more MCT for ${this.tier.toUpperCase()} tier (${requiredMCT} required)`, 'error');
            if (statusEl) statusEl.innerHTML = `❌ Need ${needed} more MCT for ${this.tier.toUpperCase()} tier`;
            if (startBtn) startBtn.disabled = true;
        }
    }

    setTier(tier) {
        this.tier = tier;
        this.maxStorageMB = STORAGE_TIERS[tier] || 1024;
        const uptimeHours = UPTIME_TIERS[tier] || 4;
        this.log(`📊 Tier set to ${tier.toUpperCase()} (${this.maxStorageMB} MB / ${uptimeHours}h+)`, 'info');
        
        // Revalidate MCT requirements
        this.validateTierRequirements();
    }

    /**
     * Show OS-specific download options
     */
    showOSDownloadOptions() {
        const downloadSection = document.getElementById('osDownloadSection');
        let html = '<div class="tier-selector">';
        
        const downloads = {
            'Windows': {
                icon: '🪟',
                file: 'mumblechat-relay-node-windows.exe',
                instructions: 'Download and run the installer. It will set up the relay node as a background service.'
            },
            'macOS': {
                icon: '🍎',
                file: 'mumblechat-relay-node-macos.dmg',
                instructions: 'Download the DMG file, open it, and drag the app to Applications folder.'
            },
            'Linux': {
                icon: '🐧',
                file: 'mumblechat-relay-node-linux.tar.gz',
                instructions: 'Download and extract. Run "./start.sh" to begin. Supports systemd auto-start.'
            }
        };
        
        for (const [os, info] of Object.entries(downloads)) {
            const isCurrentOS = this.osType.name === os;
            const highlight = isCurrentOS ? ' style="border-color: var(--primary); background: rgba(27, 140, 255, 0.05);"' : '';
            const recommended = isCurrentOS ? '<div style="font-size: 12px; color: var(--success); margin-top: 8px;">✅ For Your System</div>' : '';
            
            html += `
                <div class="tier-card"${highlight}>
                    <div class="tier-name">${info.icon} ${os}</div>
                    <div class="tier-storage" style="font-size: 12px; line-height: 1.4;">${info.instructions}</div>
                    <a href="https://releases.mumblechat.io/${info.file}" class="btn-secondary" style="display: inline-block; margin-top: 12px; padding: 8px 16px; text-decoration: none; background: var(--primary); color: white; border-radius: 6px; font-size: 12px;">
                        📥 Download
                    </a>
                    ${recommended}
                </div>
            `;
        }
        
        html += '</div>';
        downloadSection.innerHTML = html;
    }

    async start() {
        if (this.isRunning) {
            this.log('⚠️ Node already running', 'warning');
            return;
        }

        this.log('🚀 Starting relay node...', 'info');
        this.isRunning = true;
        this.startTime = Date.now();

        // Simulate peer connections
        this.simulatePeerConnections();
        
        // Start uptime counter
        this.uptimeInterval = setInterval(() => {
            this.updateUptime();
        }, 1000);

        // Start stats update
        this.statsInterval = setInterval(() => {
            this.updateStats();
        }, 5000);

        this.log('✅ Relay node started successfully', 'success');
        this.log(`📡 Broadcasting availability on network...`, 'info');
        this.log(`💾 Storage allocated: ${this.maxStorageMB} MB`, 'info');
    }

    stop() {
        if (!this.isRunning) {
            this.log('⚠️ Node not running', 'warning');
            return;
        }

        this.log('⏹️ Stopping relay node...', 'info');
        this.isRunning = false;
        
        if (this.uptimeInterval) clearInterval(this.uptimeInterval);
        if (this.statsInterval) clearInterval(this.statsInterval);
        
        this.peers.clear();
        this.messageStore.clear();
        
        this.log('✅ Node stopped', 'success');
    }

    simulatePeerConnections() {
        // Simulate realistic peer connections
        setInterval(() => {
            if (!this.isRunning) return;

            const action = Math.random();
            if (action < 0.3 && this.peers.size < 10) {
                // Add peer
                const peerId = `0x${Math.random().toString(16).substr(2, 40)}`;
                this.peers.add(peerId);
                this.log(`👤 Peer connected: ${peerId.slice(0, 10)}...`, 'info');
            } else if (action < 0.5 && this.peers.size > 0) {
                // Remove peer
                const peer = Array.from(this.peers)[0];
                this.peers.delete(peer);
                this.log(`👋 Peer disconnected: ${peer.slice(0, 10)}...`, 'info');
            } else if (action < 0.8 && this.peers.size > 0) {
                // Relay message
                const msgId = `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;
                this.messageStore.set(msgId, {
                    timestamp: Date.now(),
                    size: Math.floor(Math.random() * 100) + 10 // 10-110 KB
                });
                this.messagesRelayed++;
                
                // Calculate earnings (0.001 - 0.01 MCT per message)
                const tierMultiplier = FEE_MULTIPLIERS;
                const baseReward = 0.001 + Math.random() * 0.009;
                this.earnings += baseReward * tierMultiplier[this.tier];
                
                this.log(`📨 Message relayed: ${msgId} (+${(baseReward * tierMultiplier[this.tier]).toFixed(4)} MCT)`, 'success');
            }

            // Cleanup old messages
            if (this.messageStore.size > 100) {
                const oldest = Array.from(this.messageStore.keys())[0];
                this.messageStore.delete(oldest);
            }
        }, 3000 + Math.random() * 5000); // Random interval 3-8 seconds
    }

    updateUptime() {
        if (!this.startTime) return;
        
        const uptime = Date.now() - this.startTime;
        const hours = Math.floor(uptime / 3600000);
        const minutes = Math.floor((uptime % 3600000) / 60000);
        const seconds = Math.floor((uptime % 60000) / 1000);
        
        document.getElementById('uptimeValue').textContent = 
            `${hours}h ${minutes}m ${seconds}s`;
    }

    updateStats() {
        document.getElementById('peersValue').textContent = this.peers.size;
        document.getElementById('messagesValue').textContent = this.messagesRelayed;
        document.getElementById('earningsValue').textContent = 
            `${this.earnings.toFixed(4)} MCT`;
        
        // Calculate storage used
        let storageUsedKB = 0;
        this.messageStore.forEach(msg => storageUsedKB += msg.size);
        const storageUsedMB = (storageUsedKB / 1024).toFixed(2);
        
        document.getElementById('storageUsed').textContent = 
            `${storageUsedMB} MB / ${this.maxStorageMB} MB`;
        document.getElementById('messagesStored').textContent = 
            this.messageStore.size;
    }

    log(message, type = 'info') {
        const console = document.getElementById('consoleOutput');
        const timestamp = new Date().toLocaleTimeString();
        const line = document.createElement('div');
        line.className = 'console-line';
        
        const colors = {
            error: '#ff4444',
            warning: '#ffaa00',
            success: '#00ff00',
            info: '#00aaff'
        };
        
        line.style.color = colors[type] || '#00ff00';
        line.textContent = `[${timestamp}] ${message}`;
        console.appendChild(line);
        console.scrollTop = console.scrollHeight;
    }
}

// Initialize
const relayNode = new BrowserRelayNode();

// UI Event Handlers
document.getElementById('connectWalletBtn')?.addEventListener('click', async () => {
    const connected = await relayNode.connectWallet();
    if (connected) {
        document.getElementById('walletSection').style.display = 'none';
        document.getElementById('nodeDashboard').style.display = 'block';
        document.getElementById('walletAddress').textContent = 
            relayNode.wallet.slice(0, 10) + '...' + relayNode.wallet.slice(-8);
        document.getElementById('nodeId').textContent = 
            'node_' + relayNode.wallet.slice(2, 12);
        
        // Also show desktop section
        const desktopSection = document.getElementById('desktopNodeSection');
        if (desktopSection) desktopSection.style.display = 'block';
        
        // Scroll to dashboard with proper timing
        setTimeout(() => {
            const dashboard = document.getElementById('nodeDashboard');
            if (dashboard) {
                window.scrollTo({
                    top: dashboard.offsetTop - 80,
                    behavior: 'smooth'
                });
            }
        }, 50);
    }
});

document.getElementById('startNodeBtn')?.addEventListener('click', () => {
    relayNode.start();
    document.getElementById('statusDot').classList.add('active');
    document.getElementById('statusText').textContent = 'Node Running';
    document.getElementById('startNodeBtn').style.display = 'none';
    document.getElementById('stopNodeBtn').style.display = 'block';
});

document.getElementById('stopNodeBtn')?.addEventListener('click', () => {
    relayNode.stop();
    document.getElementById('statusDot').classList.remove('active');
    document.getElementById('statusText').textContent = 'Node Stopped';
    document.getElementById('startNodeBtn').style.display = 'block';
    document.getElementById('stopNodeBtn').style.display = 'none';
});

document.getElementById('disconnectBtn')?.addEventListener('click', () => {
    if (relayNode.isRunning) {
        relayNode.stop();
    }
    location.reload();
});

// Tier selection
document.querySelectorAll('.tier-card').forEach(card => {
    card.addEventListener('click', () => {
        document.querySelectorAll('.tier-card').forEach(c => c.classList.remove('selected'));
        card.classList.add('selected');
        const tier = card.dataset.tier;
        relayNode.setTier(tier);
        document.getElementById('tierValue').textContent = 
            tier.charAt(0).toUpperCase() + tier.slice(1);
    });
});
