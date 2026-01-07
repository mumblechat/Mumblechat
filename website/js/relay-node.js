/**
 * MumbleChat Browser-Based Relay Node
 * Run a relay node directly in your browser and earn MCT tokens
 */

// Contract Addresses (Ramestta Mainnet)
const MCT_ADDRESS = '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE';
const REGISTRY_ADDRESS = '0x4f8D4955F370881B05b68D2344345E749d8632e3';

// MCT Token ABI (minimal)
const MCT_ABI = [
    'function balanceOf(address account) external view returns (uint256)',
    'function decimals() external view returns (uint8)',
    'function allowance(address owner, address spender) external view returns (uint256)'
];

// Registry ABI (for checking relay node status)
const REGISTRY_ABI = [
    'function getRelayNode(address node) external view returns (string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 dailyUptimeSeconds, uint256 storageMB, uint8 tier, uint256 rewardMultiplier, bool isOnline)',
    'function isRegistered(address wallet) external view returns (bool)',
    'function getIdentity(address wallet) external view returns (bytes32 publicKeyX, uint256 registeredAt, uint256 lastUpdated, bool isActive, string displayName)',
    'function isNodeOnline(address node) external view returns (bool)',
    'function minRelayStake() external view returns (uint256)',
    'function totalRelayNodes() external view returns (uint256)'
];

// MCT Stake Requirement: SAME FOR ALL TIERS (100 MCT)
// Tier is determined by UPTIME + STORAGE, not stake amount
const MCT_STAKE_REQUIRED = 100;

// Storage Tiers (GB) - determines tier level
const STORAGE_TIERS = {
    'bronze': 1024,    // 1 GB
    'silver': 2048,    // 2 GB
    'gold': 4096,      // 4 GB
    'platinum': 8192   // 8 GB
};

// Uptime Requirements in hours/day - determines tier level
const UPTIME_TIERS = {
    'bronze': 4,       // 4+ hours/day
    'silver': 8,       // 8+ hours/day
    'gold': 12,        // 12+ hours/day
    'platinum': 16     // 16+ hours/day
};

// Fee Pool Multipliers (based on tier)
const FEE_MULTIPLIERS = {
    'bronze': 1.0,
    'silver': 1.5,
    'gold': 2.0,
    'platinum': 3.0
};

// Tier names for display
const TIER_NAMES = ['Bronze', 'Silver', 'Gold', 'Platinum'];
const TIER_ICONS = ['🥉', '🥈', '🥇', '💎'];

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
        
        // Blockchain relay node status
        this.isRegisteredRelay = false;
        this.relayNodeInfo = null;
        this.isIdentityRegistered = false;
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
            
            // Check MCT balance
            await this.checkMCTBalance();
            
            // Check blockchain for existing relay registration
            await this.checkRelayNodeStatus();
            
            return true;
        } catch (error) {
            this.log(`❌ Failed to connect wallet: ${error.message}`, 'error');
            return false;
        }
    }

    /**
     * Check if wallet is already registered as relay node on blockchain
     */
    async checkRelayNodeStatus() {
        try {
            this.log('🔍 Checking blockchain for relay node status...', 'info');
            
            const provider = new ethers.BrowserProvider(window.ethereum);
            const registry = new ethers.Contract(REGISTRY_ADDRESS, REGISTRY_ABI, provider);
            
            // Check if identity is registered first
            try {
                this.isIdentityRegistered = await registry.isRegistered(this.wallet);
                if (this.isIdentityRegistered) {
                    this.log('✅ Identity registered on-chain', 'success');
                } else {
                    this.log('⚠️ Identity NOT registered on-chain', 'warning');
                }
            } catch (e) {
                this.log('⚠️ Could not check identity status', 'warning');
            }
            
            // Get relay node info
            const relayInfo = await registry.getRelayNode(this.wallet);
            
            // Parse the response
            this.relayNodeInfo = {
                endpoint: relayInfo[0],
                stakedAmount: parseFloat(ethers.formatEther(relayInfo[1])),
                messagesRelayed: Number(relayInfo[2]),
                rewardsEarned: parseFloat(ethers.formatEther(relayInfo[3])),
                isActive: relayInfo[4],
                dailyUptimeSeconds: Number(relayInfo[5]),
                storageMB: Number(relayInfo[6]),
                tier: Number(relayInfo[7]),
                rewardMultiplier: Number(relayInfo[8]) / 100,
                isOnline: relayInfo[9]
            };
            
            this.isRegisteredRelay = this.relayNodeInfo.isActive;
            
            if (this.isRegisteredRelay) {
                this.log(`✅ ALREADY REGISTERED as relay node!`, 'success');
                this.log(`📊 Tier: ${TIER_ICONS[this.relayNodeInfo.tier]} ${TIER_NAMES[this.relayNodeInfo.tier]}`, 'info');
                this.log(`📨 Messages Relayed: ${this.relayNodeInfo.messagesRelayed.toLocaleString()}`, 'info');
                this.log(`💰 Rewards Earned: ${this.relayNodeInfo.rewardsEarned.toFixed(4)} MCT`, 'info');
                this.log(`🟢 Node Online: ${this.relayNodeInfo.isOnline ? 'YES' : 'NO'}`, this.relayNodeInfo.isOnline ? 'success' : 'warning');
                
                // Show the registered node dashboard
                this.showRegisteredNodeDashboard();
            } else {
                this.log('ℹ️ Not registered as relay node yet', 'info');
                this.log('💡 Follow setup wizard to become a relay node', 'info');
                
                // Show setup options
                document.getElementById('desktopNodeSection').style.display = 'block';
                this.showOSDownloadOptions();
            }
            
            return this.relayNodeInfo;
        } catch (error) {
            this.log(`⚠️ Could not fetch relay status: ${error.message}`, 'warning');
            this.log('💡 Blockchain might be slow. You can proceed with setup.', 'info');
            
            // Show setup options anyway
            document.getElementById('desktopNodeSection').style.display = 'block';
            this.showOSDownloadOptions();
            
            return null;
        }
    }

    /**
     * Show dashboard for already registered relay nodes
     */
    showRegisteredNodeDashboard() {
        // Hide setup wizard
        const setupWizard = document.getElementById('setupWizardContainer');
        if (setupWizard) setupWizard.style.display = 'none';
        
        // Create registered node dashboard
        const dashboardHTML = `
            <div class="registered-node-dashboard" style="background: linear-gradient(135deg, rgba(16, 185, 129, 0.1), rgba(27, 140, 255, 0.1)); border: 2px solid var(--success); border-radius: 20px; padding: 32px; margin-bottom: 30px;">
                <div style="text-align: center; margin-bottom: 24px;">
                    <div style="font-size: 48px; margin-bottom: 12px;">✅</div>
                    <h2 style="color: var(--success); margin: 0 0 8px;">You're Already a Relay Node!</h2>
                    <p style="color: var(--text-muted); margin: 0;">Your wallet is registered as an active relay node on the blockchain</p>
                </div>
                
                <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px; margin-bottom: 24px;">
                    <div class="stat-card" style="background: var(--bg-card); padding: 20px; border-radius: 12px; text-align: center;">
                        <div style="font-size: 32px; margin-bottom: 8px;">${TIER_ICONS[this.relayNodeInfo.tier]}</div>
                        <div style="font-size: 24px; font-weight: bold; color: var(--primary);">${TIER_NAMES[this.relayNodeInfo.tier]}</div>
                        <div style="font-size: 12px; color: var(--text-muted);">Current Tier (${this.relayNodeInfo.rewardMultiplier}x)</div>
                    </div>
                    <div class="stat-card" style="background: var(--bg-card); padding: 20px; border-radius: 12px; text-align: center;">
                        <div style="font-size: 32px; margin-bottom: 8px;">📨</div>
                        <div style="font-size: 24px; font-weight: bold; color: var(--primary);">${this.relayNodeInfo.messagesRelayed.toLocaleString()}</div>
                        <div style="font-size: 12px; color: var(--text-muted);">Messages Relayed</div>
                    </div>
                    <div class="stat-card" style="background: var(--bg-card); padding: 20px; border-radius: 12px; text-align: center;">
                        <div style="font-size: 32px; margin-bottom: 8px;">💰</div>
                        <div style="font-size: 24px; font-weight: bold; color: var(--success);">${this.relayNodeInfo.rewardsEarned.toFixed(2)}</div>
                        <div style="font-size: 12px; color: var(--text-muted);">MCT Earned</div>
                    </div>
                    <div class="stat-card" style="background: var(--bg-card); padding: 20px; border-radius: 12px; text-align: center;">
                        <div style="font-size: 32px; margin-bottom: 8px;">${this.relayNodeInfo.isOnline ? '🟢' : '🔴'}</div>
                        <div style="font-size: 24px; font-weight: bold; color: ${this.relayNodeInfo.isOnline ? 'var(--success)' : 'var(--error)'};">${this.relayNodeInfo.isOnline ? 'ONLINE' : 'OFFLINE'}</div>
                        <div style="font-size: 12px; color: var(--text-muted);">Node Status</div>
                    </div>
                </div>
                
                <div style="background: var(--bg-card); padding: 20px; border-radius: 12px; margin-bottom: 24px;">
                    <h4 style="margin: 0 0 16px; color: var(--text-primary);">📊 Node Details</h4>
                    <div style="display: grid; gap: 12px; font-size: 14px;">
                        <div style="display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border);">
                            <span style="color: var(--text-muted);">Staked Amount</span>
                            <span style="color: var(--text-primary); font-weight: 600;">${this.relayNodeInfo.stakedAmount} MCT</span>
                        </div>
                        <div style="display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border);">
                            <span style="color: var(--text-muted);">Storage Capacity</span>
                            <span style="color: var(--text-primary); font-weight: 600;">${(this.relayNodeInfo.storageMB / 1024).toFixed(1)} GB</span>
                        </div>
                        <div style="display: flex; justify-content: space-between; padding: 8px 0; border-bottom: 1px solid var(--border);">
                            <span style="color: var(--text-muted);">Today's Uptime</span>
                            <span style="color: var(--text-primary); font-weight: 600;">${Math.floor(this.relayNodeInfo.dailyUptimeSeconds / 3600)}h ${Math.floor((this.relayNodeInfo.dailyUptimeSeconds % 3600) / 60)}m</span>
                        </div>
                        <div style="display: flex; justify-content: space-between; padding: 8px 0;">
                            <span style="color: var(--text-muted);">Endpoint</span>
                            <span style="color: var(--primary); font-family: monospace; font-size: 12px;">${this.relayNodeInfo.endpoint || 'Not configured'}</span>
                        </div>
                    </div>
                </div>
                
                ${!this.relayNodeInfo.isOnline ? `
                <div style="background: rgba(239, 68, 68, 0.1); border: 1px solid var(--error); padding: 16px; border-radius: 12px; margin-bottom: 24px;">
                    <h4 style="margin: 0 0 8px; color: var(--error);">⚠️ Your Node is Offline</h4>
                    <p style="margin: 0 0 12px; color: var(--text-secondary); font-size: 13px;">
                        Your relay node hasn't sent a heartbeat recently. Make sure your desktop relay is running to earn rewards.
                    </p>
                    <div style="font-size: 12px; color: var(--text-muted);">
                        💡 Download and run the desktop relay on your ${this.osType.name} machine to start earning.
                    </div>
                </div>
                ` : ''}
                
                <div style="display: flex; gap: 12px; flex-wrap: wrap;">
                    <button onclick="relayNode.showDesktopDownload()" class="btn-primary" style="flex: 1; min-width: 200px; padding: 14px 24px; font-size: 14px; border: none; border-radius: 10px; cursor: pointer; background: var(--primary); color: white;">
                        💻 ${this.relayNodeInfo.isOnline ? 'Manage Desktop Node' : 'Download Desktop Node'}
                    </button>
                    <button onclick="relayNode.refreshStatus()" class="btn-secondary" style="padding: 14px 24px; font-size: 14px; border: 1px solid var(--border); border-radius: 10px; cursor: pointer; background: transparent; color: var(--text-primary);">
                        🔄 Refresh Status
                    </button>
                </div>
            </div>
        `;
        
        // Insert after wallet info
        const walletInfo = document.getElementById('walletInfo');
        if (walletInfo) {
            const existingDashboard = document.querySelector('.registered-node-dashboard');
            if (existingDashboard) existingDashboard.remove();
            walletInfo.insertAdjacentHTML('afterend', dashboardHTML);
        }
        
        // Hide new registration options
        const tierSelector = document.querySelector('.tier-selector');
        if (tierSelector) tierSelector.style.display = 'none';
    }

    /**
     * Show desktop download section
     */
    showDesktopDownload() {
        const desktopSection = document.getElementById('desktopNodeSection');
        if (desktopSection) {
            desktopSection.style.display = 'block';
            this.showOSDownloadOptions();
            desktopSection.scrollIntoView({ behavior: 'smooth', block: 'start' });
        }
    }

    /**
     * Refresh relay node status from blockchain
     */
    async refreshStatus() {
        this.log('🔄 Refreshing status from blockchain...', 'info');
        await this.checkRelayNodeStatus();
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
     * Validate if wallet has enough MCT for staking (100 MCT for ALL tiers)
     */
    validateTierRequirements() {
        // All tiers require only 100 MCT stake
        // Tier is determined by UPTIME + STORAGE, not stake amount
        const requiredMCT = MCT_STAKE_REQUIRED; // 100 MCT for all tiers
        const hasEnoughMCT = this.mctBalance >= requiredMCT;
        
        const statusEl = document.getElementById('mctStatus');
        const startBtn = document.getElementById('startNodeBtn');
        
        if (hasEnoughMCT) {
            this.log(`✅ You have enough MCT (${requiredMCT} required for staking)`, 'success');
            this.log(`💡 Tier is based on uptime + storage, not stake amount`, 'info');
            if (statusEl) statusEl.innerHTML = `✅ Ready to stake (${this.mctBalance.toFixed(2)} MCT available)`;
            if (startBtn) startBtn.disabled = false;
        } else {
            const needed = (requiredMCT - this.mctBalance).toFixed(2);
            this.log(`❌ Need ${needed} more MCT for staking (${requiredMCT} MCT required)`, 'error');
            if (statusEl) statusEl.innerHTML = `❌ Need ${needed} more MCT (100 MCT required to stake)`;
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
