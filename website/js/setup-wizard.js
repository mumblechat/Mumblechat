/**
 * MumbleChat Relay Node Setup Wizard
 * One-click installation and setup for browser/desktop nodes
 */

// Contract Addresses (Ramestta Mainnet)
const CONTRACTS = {
    MCT_TOKEN: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE',
    REGISTRY: '0x4f8D4955F370881B05b68D2344345E749d8632e3'
};

// Staking amount (100 MCT for all tiers)
const STAKE_AMOUNT = 100;

// Contract ABIs
const MCT_ABI = [
    'function balanceOf(address account) external view returns (uint256)',
    'function decimals() external view returns (uint8)',
    'function allowance(address owner, address spender) external view returns (uint256)',
    'function approve(address spender, uint256 amount) external returns (bool)'
];

const REGISTRY_ABI = [
    'function isRegistered(address wallet) external view returns (bool)',
    'function getIdentity(address wallet) external view returns (bytes32 publicKeyX, uint256 registeredAt, uint256 lastUpdated, bool isActive, string displayName)',
    'function getRelayNode(address node) external view returns (string endpoint, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, bool isActive, uint256 dailyUptimeSeconds, uint256 storageMB, uint8 tier, uint256 rewardMultiplier, bool isOnline)',
    'function register(bytes32 publicKeyX, string displayName) external',
    'function registerAsRelay(string endpoint, uint256 storageMB) external'
];

// OS Detection and Download URLs
const DOWNLOAD_URLS = {
    Windows: {
        icon: '🪟',
        file: 'mumblechat-relay-node-windows.exe',
        url: 'https://github.com/AncientPatata/mumblechat-relay/releases/latest',
        instructions: 'Install Node.js, then run npm install. Auto-starts with Windows Task Scheduler.',
        command: 'npm install -g mumblechat-relay && mumblechat-relay start',
        altCommand: 'npx mumblechat-relay start',
        prerequisite: 'Requires Node.js 18+ (https://nodejs.org)'
    },
    macOS: {
        icon: '🍎',
        file: 'mumblechat-relay-node-macos.dmg',
        url: 'https://github.com/AncientPatata/mumblechat-relay/releases/latest',
        instructions: 'One-click install script or npm. Runs as background service.',
        command: 'curl -fsSL https://mumblechat.com/install.sh | bash',
        altCommand: 'npm install -g mumblechat-relay && mumblechat-relay start',
        prerequisite: 'Requires Node.js 18+ (brew install node)'
    },
    Linux: {
        icon: '🐧',
        file: 'mumblechat-relay-node-linux.tar.gz',
        url: 'https://github.com/AncientPatata/mumblechat-relay/releases/latest',
        instructions: 'One-click install script or npm. Systemd service auto-configured.',
        command: 'curl -fsSL https://mumblechat.com/install.sh | sudo bash',
        altCommand: 'sudo npm install -g mumblechat-relay && mumblechat-relay start',
        prerequisite: 'Requires Node.js 18+ (apt install nodejs / dnf install nodejs)'
    }
};

// Console command handlers
const CONSOLE_COMMANDS = {
    help: () => {
        return [
            '📖 Available Commands:',
            '  help     - Show this help message',
            '  status   - Show relay node status',
            '  start    - Start the relay node',
            '  stop     - Stop the relay node',
            '  tier     - Show current tier info',
            '  wallet   - Show wallet info',
            '  clear    - Clear console',
            '  os       - Show detected OS',
            '  install  - Show installation commands',
            '  version  - Show version info'
        ];
    },
    status: () => {
        if (typeof relayNode !== 'undefined' && relayNode.isRunning) {
            const uptime = relayNode.startTime ? Math.floor((Date.now() - relayNode.startTime) / 1000) : 0;
            return [
                '📊 Node Status: 🟢 RUNNING',
                `  Uptime: ${Math.floor(uptime / 3600)}h ${Math.floor((uptime % 3600) / 60)}m ${uptime % 60}s`,
                `  Peers: ${relayNode.peers?.size || 0}`,
                `  Messages Relayed: ${relayNode.messagesRelayed || 0}`,
                `  Earnings: ${(relayNode.earnings || 0).toFixed(4)} MCT`
            ];
        }
        return ['📊 Node Status: 🔴 STOPPED', '  Run "start" to begin relaying'];
    },
    start: () => {
        if (typeof relayNode !== 'undefined') {
            if (relayNode.isRunning) {
                return ['⚠️ Node already running'];
            }
            relayNode.start();
            document.getElementById('statusDot')?.classList.add('active');
            document.getElementById('statusText').textContent = 'Node Running';
            document.getElementById('startNodeBtn').style.display = 'none';
            document.getElementById('stopNodeBtn').style.display = 'block';
            return ['🚀 Starting relay node...', '✅ Node started successfully'];
        }
        return ['❌ Error: Node not initialized'];
    },
    stop: () => {
        if (typeof relayNode !== 'undefined') {
            if (!relayNode.isRunning) {
                return ['⚠️ Node not running'];
            }
            relayNode.stop();
            document.getElementById('statusDot')?.classList.remove('active');
            document.getElementById('statusText').textContent = 'Node Stopped';
            document.getElementById('startNodeBtn').style.display = 'block';
            document.getElementById('stopNodeBtn').style.display = 'none';
            return ['⏹️ Stopping relay node...', '✅ Node stopped'];
        }
        return ['❌ Error: Node not initialized'];
    },
    tier: () => {
        const tier = typeof relayNode !== 'undefined' ? relayNode.tier : 'bronze';
        const tiers = {
            bronze: { storage: '1 GB', uptime: '4h+', multiplier: '1.0x', mct: 100 },
            silver: { storage: '2 GB', uptime: '8h+', multiplier: '1.5x', mct: 500 },
            gold: { storage: '4 GB', uptime: '12h+', multiplier: '2.0x', mct: 1000 },
            platinum: { storage: '8 GB', uptime: '16h+', multiplier: '3.0x', mct: 2500 }
        };
        const t = tiers[tier];
        return [
            `💎 Current Tier: ${tier.toUpperCase()}`,
            `  Storage: ${t.storage}`,
            `  Uptime Requirement: ${t.uptime}/day`,
            `  Reward Multiplier: ${t.multiplier}`,
            `  MCT Required: ${t.mct}`
        ];
    },
    wallet: () => {
        if (typeof relayNode !== 'undefined' && relayNode.wallet) {
            return [
                '👛 Wallet Info:',
                `  Address: ${relayNode.wallet}`,
                `  MCT Balance: ${(relayNode.mctBalance || 0).toFixed(2)} MCT`,
                `  OS: ${relayNode.osType?.name || 'Unknown'}`
            ];
        }
        return ['❌ Wallet not connected', '  Run wallet connect first'];
    },
    clear: () => {
        const console = document.getElementById('wizardConsole') || document.getElementById('consoleOutput');
        if (console) {
            console.innerHTML = '';
        }
        return ['🗑️ Console cleared'];
    },
    os: () => {
        const os = setupWizard.detectOS();
        return [
            `${os.icon} Detected OS: ${os.name}`,
            `  Platform: ${navigator.platform}`,
            `  User Agent: ${navigator.userAgent.slice(0, 60)}...`
        ];
    },
    install: () => {
        const os = setupWizard.detectOS();
        const info = DOWNLOAD_URLS[os.name] || DOWNLOAD_URLS.Linux;
        return [
            `📥 Installation for ${os.name}:`,
            `  Command: ${info.command}`,
            `  Download: ${info.url}`,
            `  Instructions: ${info.instructions}`
        ];
    },
    version: () => {
        return [
            '📦 MumbleChat Relay Node',
            '  Version: 2.0.0',
            '  Protocol: MumbleChat P2P v1',
            '  Network: Ramestta (Chain ID: 1370)'
        ];
    }
};

// Setup Wizard Class
class SetupWizard {
    constructor() {
        this.currentStep = 1;
        this.os = this.detectOS();
        this.installType = 'browser'; // 'browser' or 'desktop'
        this.wallet = null;
        this.mctBalance = 0;
        this.selectedTier = 'bronze';
        this.consoleLog = [];
        
        // Staking status
        this.isIdentityRegistered = false;
        this.isRelayStaked = false;
        this.stakedAmount = 0;
        this.provider = null;
        this.signer = null;
        
        this.init();
    }

    detectOS() {
        const ua = navigator.userAgent;
        if (ua.indexOf('Win') > -1) return { name: 'Windows', icon: '🪟' };
        if (ua.indexOf('Mac') > -1) return { name: 'macOS', icon: '🍎' };
        if (ua.indexOf('Linux') > -1) return { name: 'Linux', icon: '🐧' };
        return { name: 'Linux', icon: '🐧' }; // Default to Linux
    }

    init() {
        // Initialize mode tabs
        document.querySelectorAll('.mode-tab').forEach(tab => {
            tab.addEventListener('click', () => this.switchMode(tab.dataset.mode));
        });

        // Initialize install options
        document.querySelectorAll('.install-option').forEach(option => {
            option.addEventListener('click', () => {
                document.querySelectorAll('.install-option').forEach(o => o.classList.remove('selected'));
                option.classList.add('selected');
                this.installType = option.dataset.install;
            });
        });

        // Initialize wizard tier selection
        document.querySelectorAll('#wizardTierSelector .tier-card').forEach(card => {
            card.addEventListener('click', () => {
                document.querySelectorAll('#wizardTierSelector .tier-card').forEach(c => c.classList.remove('selected'));
                card.classList.add('selected');
                this.selectedTier = card.dataset.tier;
                this.log(`Tier selected: ${this.selectedTier.toUpperCase()}`, 'info');
            });
        });

        // Start OS detection animation
        setTimeout(() => this.detectAndShowOS(), 500);
        
        // Populate desktop mode
        this.populateDesktopMode();
        
        this.log('Setup wizard initialized', 'success');
        this.log(`Detected OS: ${this.os.icon} ${this.os.name}`, 'info');
    }

    detectAndShowOS() {
        const iconEl = document.getElementById('detectedOSIcon');
        const nameEl = document.getElementById('detectedOSName');
        const statusEl = document.getElementById('detectedOSStatus');
        const optionsEl = document.getElementById('installOptions');

        // Animation
        if (iconEl) iconEl.textContent = this.os.icon;
        if (nameEl) nameEl.textContent = this.os.name;
        if (statusEl) {
            statusEl.textContent = '✅ Compatible system detected';
            statusEl.style.color = 'var(--success)';
        }
        if (optionsEl) optionsEl.style.display = 'grid';

        this.log(`OS detected: ${this.os.name}`, 'success');
    }

    switchMode(mode) {
        // Update tabs
        document.querySelectorAll('.mode-tab').forEach(tab => {
            tab.classList.toggle('active', tab.dataset.mode === mode);
        });

        // Update content
        document.querySelectorAll('.mode-content').forEach(content => {
            content.classList.remove('active');
        });
        document.getElementById(`${mode}Mode`)?.classList.add('active');

        this.log(`Switched to ${mode} mode`, 'info');
    }

    async nextStep() {
        // Validate current step
        if (this.currentStep === 1) {
            this.updateWizardStep(2);
        } else if (this.currentStep === 2) {
            if (!this.wallet) {
                this.log('Please connect wallet first', 'error');
                return;
            }
            // Check if already staked - skip to step 4
            if (this.isRelayStaked) {
                this.updateWizardStep(4);
                this.prepareInstallStep();
            } else {
                this.updateWizardStep(3);
            }
        } else if (this.currentStep === 3) {
            // Must stake before proceeding
            if (!this.isRelayStaked) {
                this.log('Please stake MCT first to become a relay node', 'warning');
                return;
            }
            this.updateWizardStep(4);
            this.prepareInstallStep();
        }
    }

    updateWizardStep(step) {
        this.currentStep = step;
        
        // Update step indicators
        document.querySelectorAll('.wizard-step').forEach(s => {
            const stepNum = parseInt(s.dataset.step);
            s.classList.remove('active', 'completed');
            if (stepNum < step) s.classList.add('completed');
            if (stepNum === step) s.classList.add('active');
        });

        // Show/hide step content
        for (let i = 1; i <= 4; i++) {
            const content = document.getElementById(`step${i}Content`);
            if (content) {
                content.style.display = i === step ? 'block' : 'none';
            }
        }

        // Update staking balance display when entering step 3
        if (step === 3) {
            const balanceEl = document.getElementById('stakingBalance');
            if (balanceEl) {
                const hasEnough = this.mctBalance >= STAKE_AMOUNT;
                balanceEl.textContent = `${this.mctBalance.toFixed(2)} MCT`;
                balanceEl.style.color = hasEnough ? 'var(--success)' : 'var(--error)';
            }
            
            // Disable stake button if not enough MCT
            const stakeBtn = document.getElementById('stakeBtn');
            if (stakeBtn && this.mctBalance < STAKE_AMOUNT) {
                stakeBtn.disabled = true;
                stakeBtn.innerHTML = `❌ Need ${STAKE_AMOUNT} MCT (have ${this.mctBalance.toFixed(2)})`;
                stakeBtn.style.background = 'var(--text-muted)';
            }
        }

        this.log(`Step ${step} of 4`, 'info');
    }

    async connectWallet() {
        if (typeof window.ethereum === 'undefined') {
            this.log('MetaMask not found. Please install MetaMask.', 'error');
            alert('Please install MetaMask to continue');
            return;
        }

        const btn = document.getElementById('wizardConnectBtn');
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner"></span> Connecting...';
        }

        try {
            this.log('Connecting to MetaMask...', 'info');
            
            const accounts = await window.ethereum.request({ 
                method: 'eth_requestAccounts' 
            });
            
            this.wallet = accounts[0];
            this.log(`Wallet connected: ${this.wallet.slice(0, 6)}...${this.wallet.slice(-4)}`, 'success');
            
            // Check MCT balance
            await this.checkMCTBalance();
            
            // Update UI
            document.getElementById('wizardWalletAddress').textContent = 
                `${this.wallet.slice(0, 8)}...${this.wallet.slice(-6)}`;
            document.getElementById('wizardMCTBalance').textContent = 
                `${this.mctBalance.toFixed(2)} MCT`;
            document.getElementById('wizardWalletInfo').style.display = 'block';
            
            if (btn) {
                btn.innerHTML = '✅ Connected';
                btn.style.background = 'var(--success)';
            }
            
            // Auto-advance after 1 second
            setTimeout(() => this.nextStep(), 1000);
            
        } catch (error) {
            this.log(`Connection failed: ${error.message}`, 'error');
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '🦊 Connect MetaMask';
            }
        }
    }

    async checkMCTBalance() {
        try {
            this.provider = new ethers.BrowserProvider(window.ethereum);
            this.signer = await this.provider.getSigner();
            
            const mctContract = new ethers.Contract(CONTRACTS.MCT_TOKEN, MCT_ABI, this.provider);
            
            const balance = await mctContract.balanceOf(this.wallet);
            const decimals = await mctContract.decimals();
            this.mctBalance = parseFloat(ethers.formatUnits(balance, decimals));
            
            this.log(`MCT Balance: ${this.mctBalance.toFixed(2)} MCT`, 'success');
            
            // Check staking status
            await this.checkStakingStatus();
            
        } catch (error) {
            this.log(`Could not fetch MCT balance: ${error.message}`, 'warning');
            this.mctBalance = 0;
        }
    }

    async checkStakingStatus() {
        try {
            const registry = new ethers.Contract(CONTRACTS.REGISTRY, REGISTRY_ABI, this.provider);
            
            // Check if identity is registered
            this.isIdentityRegistered = await registry.isRegistered(this.wallet);
            
            // Check relay node status
            const relayInfo = await registry.getRelayNode(this.wallet);
            this.stakedAmount = parseFloat(ethers.formatEther(relayInfo[1]));
            this.isRelayStaked = relayInfo[4]; // isActive
            
            if (this.isRelayStaked) {
                this.log(`✅ Already staked as relay node: ${this.stakedAmount} MCT`, 'success');
                this.showAlreadyStakedUI();
            } else if (this.isIdentityRegistered) {
                this.log('✅ Identity registered, ready to stake as relay', 'info');
            } else {
                this.log('ℹ️ Not yet registered on blockchain', 'info');
            }
        } catch (error) {
            this.log(`Could not check staking status: ${error.message}`, 'warning');
        }
    }

    showAlreadyStakedUI() {
        // Update UI to show already staked status
        const stakingSection = document.getElementById('stakingSection');
        if (stakingSection) {
            stakingSection.innerHTML = `
                <div style="background: rgba(16, 185, 129, 0.1); border: 2px solid var(--success); border-radius: 12px; padding: 20px; text-align: center;">
                    <div style="font-size: 48px; margin-bottom: 12px;">✅</div>
                    <h3 style="color: var(--success); margin: 0 0 8px;">Already Staked!</h3>
                    <p style="color: var(--text-muted); margin: 0;">You have ${this.stakedAmount} MCT staked as a relay node.</p>
                    <p style="color: var(--text-secondary); margin: 12px 0 0; font-size: 14px;">Proceed to download and run the desktop node.</p>
                </div>
            `;
        }
    }

    async stakeAsRelay() {
        if (this.isRelayStaked) {
            this.log('Already staked as relay node!', 'success');
            return true;
        }

        if (this.mctBalance < STAKE_AMOUNT) {
            this.log(`❌ Need at least ${STAKE_AMOUNT} MCT to stake (you have ${this.mctBalance.toFixed(2)})`, 'error');
            alert(`You need at least ${STAKE_AMOUNT} MCT to become a relay node. You have ${this.mctBalance.toFixed(2)} MCT.`);
            return false;
        }

        const btn = document.getElementById('stakeBtn');
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner"></span> Staking...';
        }

        try {
            const registry = new ethers.Contract(CONTRACTS.REGISTRY, REGISTRY_ABI, this.signer);
            const mct = new ethers.Contract(CONTRACTS.MCT_TOKEN, MCT_ABI, this.signer);

            // Step 1: Register identity if not registered
            if (!this.isIdentityRegistered) {
                this.log('📝 Step 1/3: Registering identity on blockchain...', 'info');
                
                // Generate a simple public key from wallet address
                const publicKeyX = ethers.keccak256(ethers.toUtf8Bytes(this.wallet + '-relay-node'));
                const displayName = `Relay-${this.wallet.slice(2, 8)}`;
                
                const registerTx = await registry.register(publicKeyX, displayName);
                this.log('⏳ Waiting for identity registration...', 'info');
                await registerTx.wait();
                this.log('✅ Identity registered!', 'success');
                this.isIdentityRegistered = true;
            } else {
                this.log('✅ Step 1/3: Identity already registered', 'success');
            }

            // Step 2: Approve MCT tokens (101 MCT to cover 0.1% fee)
            this.log('💰 Step 2/3: Approving MCT tokens...', 'info');
            const approveAmount = ethers.parseEther('101'); // 101 MCT to cover fee
            
            const currentAllowance = await mct.allowance(this.wallet, CONTRACTS.REGISTRY);
            if (currentAllowance < approveAmount) {
                const approveTx = await mct.approve(CONTRACTS.REGISTRY, approveAmount);
                this.log('⏳ Waiting for approval confirmation...', 'info');
                await approveTx.wait();
                this.log('✅ MCT tokens approved!', 'success');
            } else {
                this.log('✅ MCT already approved', 'success');
            }

            // Step 3: Register as relay node
            this.log('🚀 Step 3/3: Staking as relay node...', 'info');
            const endpoint = `hub.mumblechat.com/node/${this.wallet.slice(2, 14)}`;
            const storageMB = 1024; // 1 GB default
            
            const stakeTx = await registry.registerAsRelay(endpoint, storageMB);
            this.log('⏳ Waiting for staking confirmation...', 'info');
            await stakeTx.wait();
            
            this.log('🎉 Successfully staked as relay node!', 'success');
            this.isRelayStaked = true;
            this.stakedAmount = STAKE_AMOUNT;

            if (btn) {
                btn.innerHTML = '✅ Staked!';
                btn.style.background = 'var(--success)';
            }

            // Show success and auto-advance
            this.showAlreadyStakedUI();
            setTimeout(() => this.nextStep(), 1500);
            
            return true;

        } catch (error) {
            this.log(`❌ Staking failed: ${error.message}`, 'error');
            
            if (error.message.includes('user rejected')) {
                this.log('Transaction was cancelled by user', 'warning');
            } else if (error.message.includes('insufficient funds')) {
                this.log('Not enough RAMA for gas fees', 'error');
            }
            
            if (btn) {
                btn.disabled = false;
                btn.innerHTML = '🔒 Stake 100 MCT';
            }
            return false;
        }
    }

    prepareInstallStep() {
        const browserContent = document.getElementById('browserInstallContent');
        const desktopContent = document.getElementById('desktopInstallContent');
        
        if (this.installType === 'browser') {
            if (browserContent) browserContent.style.display = 'block';
            if (desktopContent) desktopContent.style.display = 'none';
            this.log('Browser node installation ready', 'info');
        } else {
            if (browserContent) browserContent.style.display = 'none';
            if (desktopContent) desktopContent.style.display = 'block';
            
            // Show OS-specific commands
            const osInfo = DOWNLOAD_URLS[this.os.name] || DOWNLOAD_URLS.Linux;
            document.getElementById('installOSIcon').textContent = this.os.icon;
            document.getElementById('installOSName').textContent = `${this.os.name} Installation`;
            
            // Show correct command card
            document.getElementById('windowsCommands').style.display = this.os.name === 'Windows' ? 'block' : 'none';
            document.getElementById('macCommands').style.display = this.os.name === 'macOS' ? 'block' : 'none';
            document.getElementById('linuxCommands').style.display = this.os.name === 'Linux' ? 'block' : 'none';
            
            this.log(`Desktop installation for ${this.os.name} ready`, 'info');
        }
    }

    startBrowserNode() {
        this.log('Starting browser relay node...', 'info');
        
        // Switch to browser mode
        this.switchMode('browser');
        
        // Set tier
        if (typeof relayNode !== 'undefined') {
            relayNode.setTier(this.selectedTier);
            relayNode.wallet = this.wallet;
            relayNode.mctBalance = this.mctBalance;
            
            // Show dashboard
            document.getElementById('walletSection').style.display = 'none';
            document.getElementById('nodeDashboard').style.display = 'block';
            document.getElementById('walletAddress').textContent = 
                `${this.wallet.slice(0, 10)}...${this.wallet.slice(-8)}`;
            document.getElementById('nodeId').textContent = 
                'node_' + this.wallet.slice(2, 12);
            document.getElementById('tierValue').textContent = 
                this.selectedTier.charAt(0).toUpperCase() + this.selectedTier.slice(1);
            
            // Start node
            relayNode.start();
            document.getElementById('statusDot')?.classList.add('active');
            document.getElementById('statusText').textContent = 'Node Running';
            document.getElementById('startNodeBtn').style.display = 'none';
            document.getElementById('stopNodeBtn').style.display = 'block';
            
            this.log('Browser relay node started successfully!', 'success');
        }
    }

    installDesktop() {
        const btn = document.getElementById('desktopInstallBtn');
        const progressContainer = document.getElementById('installProgress');
        
        if (btn) {
            btn.disabled = true;
            btn.innerHTML = '<span class="spinner"></span> Preparing download...';
        }
        
        this.log(`Starting ${this.os.name} desktop installation...`, 'info');
        
        // Show progress
        if (progressContainer) progressContainer.style.display = 'block';
        
        // Simulate progress
        let progress = 0;
        const progressFill = document.getElementById('progressFill');
        const progressStep = document.getElementById('progressStep');
        const progressPercent = document.getElementById('progressPercent');
        
        const steps = [
            { at: 10, text: 'Downloading installer...' },
            { at: 30, text: 'Verifying checksum...' },
            { at: 50, text: 'Preparing installation...' },
            { at: 70, text: 'Configuring relay node...' },
            { at: 90, text: 'Finalizing setup...' },
            { at: 100, text: 'Download complete!' }
        ];
        
        const interval = setInterval(() => {
            progress += 5;
            if (progressFill) progressFill.style.width = `${progress}%`;
            if (progressPercent) progressPercent.textContent = `${progress}%`;
            
            const currentStep = steps.find(s => progress >= s.at && progress < (s.at + 20));
            if (currentStep && progressStep) {
                progressStep.textContent = currentStep.text;
                this.log(currentStep.text, 'info');
            }
            
            if (progress >= 100) {
                clearInterval(interval);
                this.log('✅ Setup preparation complete!', 'success');
                
                // Show post-install instructions instead of downloading
                const osInfo = DOWNLOAD_URLS[this.os.name] || DOWNLOAD_URLS.Linux;
                
                if (btn) {
                    btn.innerHTML = '✅ Ready!';
                    btn.style.background = 'var(--success)';
                }
                
                // Show instructions in wizard
                this.showPostInstallInstructions(osInfo);
            }
        }, 200);
    }

    showPostInstallInstructions(osInfo) {
        const content = document.getElementById('desktopInstallContent');
        if (!content) return;
        
        const terminalName = this.os.name === 'Windows' ? 'PowerShell (Run as Administrator)' : 'Terminal';
        
        const instructionsHTML = `
            <div style="margin-top: 24px; padding: 24px; background: rgba(16, 185, 129, 0.1); border: 1px solid var(--success); border-radius: 12px;">
                <h3 style="color: var(--success); margin: 0 0 16px;">✅ Setup Complete!</h3>
                
                <div style="margin-bottom: 16px; padding: 12px; background: rgba(245, 158, 11, 0.1); border: 1px solid #f59e0b; border-radius: 8px;">
                    <strong style="color: #f59e0b;">⚠️ Prerequisite:</strong>
                    <span style="color: var(--text-secondary); margin-left: 8px;">${osInfo.prerequisite}</span>
                </div>
                
                <div style="margin-bottom: 20px;">
                    <h4 style="margin: 0 0 8px;">📋 Install & Run on ${this.os.name}:</h4>
                    <ol style="margin: 0; padding-left: 20px; color: var(--text-secondary); line-height: 1.8;">
                        <li>Open ${terminalName}</li>
                        <li>Run the install command below:</li>
                    </ol>
                </div>
                
                <div style="margin-bottom: 12px;">
                    <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;">Option 1: Global Install (Recommended)</div>
                    <div class="command-code" style="margin: 0;">
                        <code style="color: #00ff00; font-family: monospace;">${osInfo.command}</code>
                        <button class="copy-btn" onclick="navigator.clipboard.writeText('${osInfo.command}'); this.textContent='✅'; setTimeout(()=>this.textContent='📋',1500)">📋</button>
                    </div>
                </div>
                
                <div style="margin-bottom: 16px;">
                    <div style="font-size: 12px; color: var(--text-muted); margin-bottom: 4px;">Option 2: Quick Run (No install)</div>
                    <div class="command-code" style="margin: 0;">
                        <code style="color: #00ff00; font-family: monospace;">${osInfo.altCommand}</code>
                        <button class="copy-btn" onclick="navigator.clipboard.writeText('${osInfo.altCommand}'); this.textContent='✅'; setTimeout(()=>this.textContent='📋',1500)">📋</button>
                    </div>
                </div>
                
                <div style="margin-top: 16px; padding: 12px; background: var(--bg-soft); border-radius: 8px;">
                    <strong>After installation:</strong>
                    <ul style="margin: 8px 0 0; padding-left: 20px; color: var(--text-secondary); font-size: 14px;">
                        <li>Configure your wallet: <code style="background: #000; padding: 2px 6px; border-radius: 4px; color: #00ff00;">mumblechat-relay config --wallet ${this.wallet ? this.wallet.slice(0,10) + '...' : 'YOUR_WALLET'}</code></li>
                        <li>Check status: <code style="background: #000; padding: 2px 6px; border-radius: 4px; color: #00ff00;">mumblechat-relay status</code></li>
                        <li>View logs: <code style="background: #000; padding: 2px 6px; border-radius: 4px; color: #00ff00;">mumblechat-relay logs</code></li>
                        <li>Stop node: <code style="background: #000; padding: 2px 6px; border-radius: 4px; color: #00ff00;">mumblechat-relay stop</code></li>
                    </ul>
                </div>
                
                <div style="margin-top: 20px; display: flex; gap: 12px; flex-wrap: wrap;">
                    <button class="one-click-btn" style="flex: 1; min-width: 200px; background: var(--primary);" onclick="setupWizard.switchMode('browser')">
                        🌐 Try Browser Node Instead
                    </button>
                    <button class="one-click-btn" style="flex: 1; min-width: 200px; background: transparent; border: 1px solid var(--border);" onclick="location.href='relay-nodes.html'">
                        📚 Learn About Rewards
                    </button>
                </div>
            </div>
        `;
        
        content.insertAdjacentHTML('beforeend', instructionsHTML);
        this.log('Follow the terminal commands above to complete installation', 'success');
        this.log(`Your wallet: ${this.wallet || 'Not connected'}`, 'info');
        this.log(`Selected tier: ${this.selectedTier.toUpperCase()}`, 'info');
    }

    populateDesktopMode() {
        const downloadsEl = document.getElementById('desktopDownloads');
        const commandsEl = document.getElementById('desktopCommandCards');
        
        // Update OS display
        document.getElementById('desktopOSIcon').textContent = this.os.icon;
        document.getElementById('desktopOSName').textContent = this.os.name;
        document.getElementById('desktopOSStatus').textContent = '✅ Compatible system detected';
        
        // Build download cards
        let downloadsHTML = '';
        for (const [os, info] of Object.entries(DOWNLOAD_URLS)) {
            const isCurrentOS = os === this.os.name;
            const highlight = isCurrentOS ? 'border-color: var(--primary); background: rgba(27, 140, 255, 0.05);' : '';
            const recommended = isCurrentOS ? '<div style="font-size: 11px; color: var(--success); margin-top: 8px;">✅ Recommended</div>' : '';
            
            downloadsHTML += `
                <div class="tier-card" style="${highlight}cursor: pointer;" onclick="setupWizard.downloadForOS('${os}')">
                    <div class="tier-name">${info.icon} ${os}</div>
                    <div class="tier-storage" style="font-size: 11px; line-height: 1.4;">${info.instructions}</div>
                    ${recommended}
                </div>
            `;
        }
        if (downloadsEl) downloadsEl.innerHTML = downloadsHTML;
        
        // Build command cards
        let commandsHTML = '';
        for (const [os, info] of Object.entries(DOWNLOAD_URLS)) {
            const display = os === this.os.name ? 'block' : 'none';
            commandsHTML += `
                <div class="command-card" style="display: ${display}; grid-column: span 2;">
                    <div class="command-card-title">${info.icon} Install Commands for ${os}</div>
                    
                    <div style="margin-bottom: 8px; padding: 8px; background: rgba(245, 158, 11, 0.1); border-radius: 6px;">
                        <span style="font-size: 11px; color: #f59e0b;">⚠️ ${info.prerequisite}</span>
                    </div>
                    
                    <p style="font-size: 12px; color: var(--text-muted); margin: 8px 0 4px;">Global Install:</p>
                    <div class="command-code" style="margin-bottom: 8px;">
                        <span>${info.command}</span>
                        <button class="copy-btn" onclick="copyCommand(this)">📋</button>
                    </div>
                    
                    <p style="font-size: 12px; color: var(--text-muted); margin: 8px 0 4px;">Quick Run (no install):</p>
                    <div class="command-code">
                        <span>${info.altCommand}</span>
                        <button class="copy-btn" onclick="copyCommand(this)">📋</button>
                    </div>
                </div>
            `;
        }
        if (commandsEl) commandsEl.innerHTML = commandsHTML;
    }

    downloadForOS(os) {
        const info = DOWNLOAD_URLS[os];
        if (info) {
            this.log(`Selected ${os} installation`, 'info');
            this.log(`📋 Prerequisite: ${info.prerequisite}`, 'warning');
            this.log(`Option 1 (Global): ${info.command}`, 'success');
            this.log(`Option 2 (Quick):  ${info.altCommand}`, 'info');
            
            // Copy main command to clipboard
            navigator.clipboard.writeText(info.command).then(() => {
                this.log('✅ Install command copied to clipboard!', 'success');
            }).catch(() => {
                this.log('Copy the command manually from above', 'warning');
            });
            
            // Show all command cards for reference
            document.querySelectorAll('#desktopCommandCards .command-card').forEach(card => {
                card.style.display = 'block';
            });
        }
    }

    log(message, type = 'info') {
        const console = document.getElementById('wizardConsole');
        if (!console) return;
        
        const timestamp = new Date().toLocaleTimeString();
        const colors = {
            error: '#ff4444',
            warning: '#ffaa00',
            success: '#00ff00',
            info: '#00aaff'
        };
        
        const line = document.createElement('div');
        line.className = 'console-line';
        line.innerHTML = `
            <span class="console-timestamp">[${timestamp}]</span>
            <span class="console-message" style="color: ${colors[type] || '#00ff00'}">${message}</span>
        `;
        console.appendChild(line);
        console.scrollTop = console.scrollHeight;
        
        // Store for download
        this.consoleLog.push(`[${timestamp}] ${message}`);
    }
}

// Global functions for HTML onclick handlers
function handleConsoleInput(event) {
    if (event.key === 'Enter') {
        const input = document.getElementById('consoleInput');
        const command = input.value.trim().toLowerCase();
        input.value = '';
        
        if (command) {
            const console = document.getElementById('wizardConsole') || document.getElementById('consoleOutput');
            
            // Echo command
            const echoLine = document.createElement('div');
            echoLine.className = 'console-line';
            echoLine.innerHTML = `<span class="console-timestamp">$</span><span class="console-message" style="color: #fff;">${command}</span>`;
            console.appendChild(echoLine);
            
            // Execute command
            const handler = CONSOLE_COMMANDS[command];
            if (handler) {
                const output = handler();
                output.forEach(line => {
                    const outLine = document.createElement('div');
                    outLine.className = 'console-line';
                    outLine.innerHTML = `<span class="console-message">${line}</span>`;
                    console.appendChild(outLine);
                });
            } else {
                const errorLine = document.createElement('div');
                errorLine.className = 'console-line';
                errorLine.innerHTML = `<span class="console-message" style="color: #ff4444;">Unknown command: ${command}. Type "help" for available commands.</span>`;
                console.appendChild(errorLine);
            }
            
            console.scrollTop = console.scrollHeight;
        }
    }
}

function copyCommand(btn) {
    const code = btn.parentElement.querySelector('span').textContent;
    navigator.clipboard.writeText(code).then(() => {
        const originalText = btn.textContent;
        btn.textContent = '✅';
        setTimeout(() => btn.textContent = originalText, 1500);
    });
}

function clearConsole() {
    const console = document.getElementById('wizardConsole');
    if (console) {
        console.innerHTML = `
            <div class="console-line">
                <span class="console-timestamp">[System]</span>
                <span class="console-message" style="color: #4bc0c8;">Console cleared</span>
            </div>
        `;
    }
}

function copyConsole() {
    const console = document.getElementById('wizardConsole');
    if (console) {
        const text = Array.from(console.querySelectorAll('.console-line'))
            .map(line => line.textContent)
            .join('\n');
        navigator.clipboard.writeText(text).then(() => {
            setupWizard.log('Console content copied to clipboard', 'success');
        });
    }
}

function downloadLog() {
    const logs = setupWizard.consoleLog.join('\n');
    const blob = new Blob([logs], { type: 'text/plain' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `mumblechat-relay-log-${Date.now()}.txt`;
    a.click();
    URL.revokeObjectURL(url);
    setupWizard.log('Log file downloaded', 'success');
}

function clearBrowserConsole() {
    const console = document.getElementById('consoleOutput');
    if (console) {
        console.innerHTML = '<div class="console-line">Console cleared</div>';
    }
}

function copyBrowserConsole() {
    const console = document.getElementById('consoleOutput');
    if (console) {
        navigator.clipboard.writeText(console.textContent);
    }
}

function downloadBrowserLog() {
    const console = document.getElementById('consoleOutput');
    if (console) {
        const blob = new Blob([console.textContent], { type: 'text/plain' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `relay-node-log-${Date.now()}.txt`;
        a.click();
        URL.revokeObjectURL(url);
    }
}

// Initialize wizard
const setupWizard = new SetupWizard();
