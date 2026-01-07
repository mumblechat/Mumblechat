#!/usr/bin/env node
/**
 * MumbleChat Multi-Node CLI
 * 
 * Command-line tool to manage multiple relay nodes on one machine.
 * 
 * Usage:
 *   node cli.js info          - Show machine and node info
 *   node cli.js add           - Add a new node interactively
 *   node cli.js register      - Register node on blockchain
 *   node cli.js list          - List all configured nodes
 *   node cli.js start <id>    - Start a specific node
 *   node cli.js stop <id>     - Stop a specific node
 *   node cli.js start-all     - Start all nodes
 *   node cli.js stop-all      - Stop all nodes
 */

import { ethers } from 'ethers';
import crypto from 'crypto';
import fs from 'fs';
import path from 'path';
import readline from 'readline';

// Tier definitions matching the contract
const TIERS = {
    BRONZE: {
        minStake: ethers.parseEther('100'),
        minStorage: 1024,        // 1GB
        maxStorage: 4096,        // 4GB
        rewardMultiplier: 100,   // 1x
        name: 'BRONZE'
    },
    SILVER: {
        minStake: ethers.parseEther('500'),
        minStorage: 4096,        // 4GB
        maxStorage: 10240,       // 10GB
        rewardMultiplier: 150,   // 1.5x
        name: 'SILVER'
    },
    GOLD: {
        minStake: ethers.parseEther('1000'),
        minStorage: 10240,       // 10GB
        maxStorage: 51200,       // 50GB
        rewardMultiplier: 200,   // 2x
        name: 'GOLD'
    },
    PLATINUM: {
        minStake: ethers.parseEther('5000'),
        minStorage: 51200,       // 50GB
        maxStorage: 102400,      // 100GB
        rewardMultiplier: 300,   // 3x
        name: 'PLATINUM'
    }
};

// Contract addresses
const CONTRACTS = {
    MCT_TOKEN: '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE',
    RELAY_MANAGER: '0xF78F840eF0e321512b09e98C76eA0229Affc4b73', // Proxy address
    CHAIN_ID: 1370,
    RPC_URL: 'https://blockchain.ramestta.com'
};

// ABI fragments we need
const RELAY_MANAGER_ABI = [
    'function registerNodeWithId(bytes32 nodeId, bytes32 machineIdHash, uint256 storageMB, string endpoint) external returns (bool)',
    'function updateEndpoint(bytes32 nodeId, string newEndpoint) external',
    'function isNodeRegistered(bytes32 nodeId) external view returns (bool)',
    'function walletNodeIds(address wallet, uint256 index) external view returns (bytes32)',
    'function nodes(bytes32 nodeId) external view returns (address owner, bytes32 machineIdHash, uint256 storageMB, uint8 tier, string endpoint, bool isActive, uint256 registeredAt, uint256 lastActivityAt, uint256 pendingRewards)',
    'function getActiveEndpoints() external view returns (bytes32[] memory nodeIds, string[] memory endpoints, address[] memory wallets, uint8[] memory tiers)',
    'event NodeRegistered(bytes32 indexed nodeId, address indexed owner, uint256 storageMB, uint8 tier)'
];

const ERC20_ABI = [
    'function approve(address spender, uint256 amount) external returns (bool)',
    'function allowance(address owner, address spender) external view returns (uint256)',
    'function balanceOf(address account) external view returns (uint256)'
];

// Utility to get machine ID
function getMachineId(): string {
    const os = require('os');
    const cpus = os.cpus();
    const networkInterfaces = os.networkInterfaces();
    
    let uniqueData = os.hostname();
    
    if (cpus.length > 0) {
        uniqueData += cpus[0].model + cpus.length;
    }
    
    for (const [name, interfaces] of Object.entries(networkInterfaces)) {
        if (interfaces) {
            for (const iface of interfaces) {
                if (!iface.internal && iface.mac !== '00:00:00:00:00:00') {
                    uniqueData += iface.mac;
                    break;
                }
            }
        }
    }
    
    return crypto.createHash('sha256').update(uniqueData).digest('hex');
}

// Get free disk space
function getDiskSpace(): { totalMB: number; freeMB: number } {
    const os = require('os');
    const { execSync } = require('child_process');
    
    try {
        // Linux/Mac
        const output = execSync('df -k / | tail -1').toString();
        const parts = output.split(/\s+/);
        const totalKB = parseInt(parts[1]);
        const freeKB = parseInt(parts[3]);
        
        return {
            totalMB: Math.floor(totalKB / 1024),
            freeMB: Math.floor(freeKB / 1024)
        };
    } catch {
        return { totalMB: 100000, freeMB: 50000 }; // Default
    }
}

// Generate node ID from wallet
function generateNodeId(wallet: string): string {
    return ethers.keccak256(
        ethers.solidityPacked(['address', 'uint256'], [wallet, Date.now()])
    );
}

// Interactive readline
function question(prompt: string): Promise<string> {
    const rl = readline.createInterface({
        input: process.stdin,
        output: process.stdout
    });
    
    return new Promise((resolve) => {
        rl.question(prompt, (answer) => {
            rl.close();
            resolve(answer.trim());
        });
    });
}

// Load local node config
interface LocalConfig {
    nodes: Array<{
        nodeId: string;
        walletAddress: string;
        privateKey: string;  // Should be encrypted in production
        tier: string;
        storageMB: number;
        registered: boolean;
        endpoint?: string;
    }>;
    machineId: string;
}

function loadConfig(): LocalConfig {
    const configPath = './multi-node-config.json';
    
    if (fs.existsSync(configPath)) {
        return JSON.parse(fs.readFileSync(configPath, 'utf8'));
    }
    
    return {
        nodes: [],
        machineId: getMachineId()
    };
}

function saveConfig(config: LocalConfig): void {
    fs.writeFileSync('./multi-node-config.json', JSON.stringify(config, null, 2));
}

// Commands
async function showInfo(): Promise<void> {
    const config = loadConfig();
    const disk = getDiskSpace();
    const machineIdHash = ethers.keccak256(ethers.toUtf8Bytes(config.machineId));
    
    // Calculate allocated storage
    const allocatedMB = config.nodes.reduce((sum, n) => sum + n.storageMB, 0);
    const availableMB = disk.freeMB - allocatedMB - 1024; // Keep 1GB free
    
    console.log('\n╔══════════════════════════════════════════════════════════════════╗');
    console.log('║         MUMBLECHAT MULTI-NODE MANAGER - MACHINE INFO            ║');
    console.log('╠══════════════════════════════════════════════════════════════════╣');
    console.log(`║  Machine ID Hash: ${machineIdHash.slice(0, 42)}  ║`);
    console.log('╠══════════════════════════════════════════════════════════════════╣');
    console.log('║  DISK STORAGE                                                    ║');
    console.log(`║    Total:     ${(disk.totalMB / 1024).toFixed(1).padEnd(10)} GB                                  ║`);
    console.log(`║    Free:      ${(disk.freeMB / 1024).toFixed(1).padEnd(10)} GB                                  ║`);
    console.log(`║    Allocated: ${(allocatedMB / 1024).toFixed(1).padEnd(10)} GB (${config.nodes.length} nodes)                        ║`);
    console.log(`║    Available: ${(availableMB / 1024).toFixed(1).padEnd(10)} GB (for new nodes)                  ║`);
    console.log('╠══════════════════════════════════════════════════════════════════╣');
    console.log('║  TIER REQUIREMENTS                                               ║');
    console.log('║    BRONZE:   100 MCT stake,   1-4 GB storage  (1.0x rewards)    ║');
    console.log('║    SILVER:   500 MCT stake,   4-10 GB storage (1.5x rewards)    ║');
    console.log('║    GOLD:    1000 MCT stake,  10-50 GB storage (2.0x rewards)    ║');
    console.log('║    PLATINUM: 5000 MCT stake, 50-100 GB storage (3.0x rewards)   ║');
    console.log('╠══════════════════════════════════════════════════════════════════╣');
    
    // Recommend tier based on available space
    let recommendedTier = 'BRONZE';
    let recommendedStorage = 1024;
    
    if (availableMB >= 51200) {
        recommendedTier = 'PLATINUM';
        recommendedStorage = 51200;
    } else if (availableMB >= 10240) {
        recommendedTier = 'GOLD';
        recommendedStorage = 10240;
    } else if (availableMB >= 4096) {
        recommendedTier = 'SILVER';
        recommendedStorage = 4096;
    }
    
    console.log(`║  RECOMMENDED: ${recommendedTier.padEnd(8)} tier with ${(recommendedStorage / 1024).toFixed(0)} GB storage           ║`);
    console.log('╚══════════════════════════════════════════════════════════════════╝\n');
}

async function listNodes(): Promise<void> {
    const config = loadConfig();
    
    if (config.nodes.length === 0) {
        console.log('\n  No nodes configured. Use "add" command to add a node.\n');
        return;
    }
    
    console.log('\n╔══════════════════════════════════════════════════════════════════════════════╗');
    console.log('║                         CONFIGURED RELAY NODES                               ║');
    console.log('╠═══════════════════════════════════════════════════════════════════════════════╣');
    console.log('║  #  │ NODE ID    │ WALLET        │ TIER     │ STORAGE │ REGISTERED │ ENDPOINT ║');
    console.log('╠═══════════════════════════════════════════════════════════════════════════════╣');
    
    let i = 1;
    for (const node of config.nodes) {
        const nodeIdShort = node.nodeId.slice(0, 10);
        const walletShort = node.walletAddress.slice(0, 12) + '...';
        const storage = `${(node.storageMB / 1024).toFixed(1)} GB`;
        const registered = node.registered ? '✅' : '❌';
        const endpoint = node.endpoint ? node.endpoint.slice(0, 20) : 'Not set';
        
        console.log(`║  ${i.toString().padEnd(2)} │ ${nodeIdShort} │ ${walletShort.padEnd(13)} │ ${node.tier.padEnd(8)} │ ${storage.padEnd(7)} │ ${registered}         │ ${endpoint.padEnd(8)} ║`);
        i++;
    }
    
    console.log('╚══════════════════════════════════════════════════════════════════════════════╝\n');
}

async function addNode(): Promise<void> {
    const config = loadConfig();
    const disk = getDiskSpace();
    
    // Calculate available
    const allocatedMB = config.nodes.reduce((sum, n) => sum + n.storageMB, 0);
    const availableMB = disk.freeMB - allocatedMB - 1024;
    
    console.log('\n═══════════════════════════════════════════════════════════════');
    console.log('                    ADD NEW RELAY NODE');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`Available storage: ${(availableMB / 1024).toFixed(1)} GB\n`);
    
    // Get private key
    let privateKey = await question('Enter wallet private key (or press Enter to generate new): ');
    let wallet: ethers.Wallet;
    
    if (!privateKey) {
        wallet = ethers.Wallet.createRandom();
        privateKey = wallet.privateKey;
        console.log(`\n🆕 Generated new wallet:`);
        console.log(`   Address: ${wallet.address}`);
        console.log(`   Private Key: ${privateKey}`);
        console.log(`\n⚠️  SAVE THIS PRIVATE KEY! You will need MCT tokens in this wallet.\n`);
    } else {
        try {
            wallet = new ethers.Wallet(privateKey);
            console.log(`\n✅ Wallet loaded: ${wallet.address}\n`);
        } catch (error) {
            console.error('❌ Invalid private key');
            return;
        }
    }
    
    // Check if wallet already has a node
    const existingNode = config.nodes.find(n => n.walletAddress.toLowerCase() === wallet.address.toLowerCase());
    if (existingNode) {
        console.error(`❌ This wallet already has a node configured: ${existingNode.nodeId.slice(0, 10)}...`);
        return;
    }
    
    // Select tier
    console.log('Select tier:');
    console.log('  1. BRONZE   (100 MCT,  1-4 GB)');
    console.log('  2. SILVER   (500 MCT,  4-10 GB)');
    console.log('  3. GOLD     (1000 MCT, 10-50 GB)');
    console.log('  4. PLATINUM (5000 MCT, 50-100 GB)');
    
    const tierChoice = await question('Tier (1-4): ');
    const tiers = ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM'];
    const tier = tiers[parseInt(tierChoice) - 1] || 'BRONZE';
    const tierInfo = TIERS[tier as keyof typeof TIERS];
    
    // Get storage amount
    console.log(`\n${tier} tier storage range: ${tierInfo.minStorage / 1024} - ${tierInfo.maxStorage / 1024} GB`);
    const storageInput = await question(`Storage to allocate (MB, default ${tierInfo.minStorage}): `);
    let storageMB = parseInt(storageInput) || tierInfo.minStorage;
    
    // Validate
    if (storageMB < tierInfo.minStorage) storageMB = tierInfo.minStorage;
    if (storageMB > tierInfo.maxStorage) storageMB = tierInfo.maxStorage;
    if (storageMB > availableMB) {
        console.error(`❌ Not enough space. Available: ${availableMB}MB, Requested: ${storageMB}MB`);
        return;
    }
    
    // Generate node ID
    const nodeId = generateNodeId(wallet.address);
    
    // Select mode
    console.log('\nNode operation mode:');
    console.log('  1. MANAGED - Use hub.mumblechat.com (recommended for most users)');
    console.log('  2. SELF_HOSTED - Provide your own public endpoint');
    
    const modeChoice = await question('Mode (1-2, default 1): ');
    const isManaged = modeChoice !== '2';
    
    let endpoint = '';
    if (isManaged) {
        endpoint = 'hub.mumblechat.com';
    } else {
        endpoint = await question('Enter your public endpoint (e.g., relay.yourdomain.com:19370): ');
        if (!endpoint) {
            console.error('❌ Self-hosted mode requires an endpoint');
            return;
        }
    }
    
    // Add to config
    config.nodes.push({
        nodeId,
        walletAddress: wallet.address,
        privateKey,  // In production, encrypt this
        tier,
        storageMB,
        registered: false,
        endpoint: isManaged ? `https://${endpoint}/node/${nodeId.slice(0, 10)}` : endpoint
    });
    
    saveConfig(config);
    
    console.log('\n✅ Node configured successfully!');
    console.log('═══════════════════════════════════════════════════════════════');
    console.log(`   Node ID:    ${nodeId}`);
    console.log(`   Wallet:     ${wallet.address}`);
    console.log(`   Tier:       ${tier}`);
    console.log(`   Storage:    ${storageMB}MB (${(storageMB / 1024).toFixed(2)} GB)`);
    console.log(`   Mode:       ${isManaged ? 'MANAGED (via hub)' : 'SELF_HOSTED'}`);
    console.log(`   Endpoint:   ${isManaged ? 'Will be assigned by hub' : endpoint}`);
    console.log('═══════════════════════════════════════════════════════════════');
    console.log('\n📝 Next steps:');
    console.log(`   1. Send ${ethers.formatEther(tierInfo.minStake)} MCT to ${wallet.address}`);
    console.log('   2. Run "node cli.js register" to register on blockchain');
    console.log('   3. Run "node cli.js start <node-id>" to start the node');
    console.log('');
}

async function registerNode(nodeIdArg?: string): Promise<void> {
    const config = loadConfig();
    
    // Find unregistered nodes
    const unregistered = config.nodes.filter(n => !n.registered);
    
    if (unregistered.length === 0) {
        console.log('\n  All nodes are already registered.\n');
        return;
    }
    
    let node;
    if (nodeIdArg) {
        node = config.nodes.find(n => n.nodeId.startsWith(nodeIdArg));
    } else if (unregistered.length === 1) {
        node = unregistered[0];
    } else {
        console.log('\nUnregistered nodes:');
        unregistered.forEach((n, i) => {
            console.log(`  ${i + 1}. ${n.nodeId.slice(0, 10)} - ${n.walletAddress} (${n.tier})`);
        });
        const choice = await question('Select node (number): ');
        node = unregistered[parseInt(choice) - 1];
    }
    
    if (!node) {
        console.error('❌ Node not found');
        return;
    }
    
    console.log(`\nRegistering node ${node.nodeId.slice(0, 10)}...`);
    
    // Connect to blockchain
    const provider = new ethers.JsonRpcProvider(CONTRACTS.RPC_URL);
    const wallet = new ethers.Wallet(node.privateKey, provider);
    
    // Check MCT balance
    const mctContract = new ethers.Contract(CONTRACTS.MCT_TOKEN, ERC20_ABI, wallet);
    const balance = await mctContract.balanceOf(wallet.address);
    const tierInfo = TIERS[node.tier as keyof typeof TIERS];
    
    console.log(`   MCT Balance: ${ethers.formatEther(balance)} MCT`);
    console.log(`   Required:    ${ethers.formatEther(tierInfo.minStake)} MCT`);
    
    if (balance < tierInfo.minStake) {
        console.error(`\n❌ Insufficient MCT balance. Need ${ethers.formatEther(tierInfo.minStake)} MCT`);
        return;
    }
    
    // Check allowance
    const relayManager = new ethers.Contract(CONTRACTS.RELAY_MANAGER, RELAY_MANAGER_ABI, wallet);
    const allowance = await mctContract.allowance(wallet.address, CONTRACTS.RELAY_MANAGER);
    
    if (allowance < tierInfo.minStake) {
        console.log('   Approving MCT spend...');
        const approveTx = await mctContract.approve(CONTRACTS.RELAY_MANAGER, tierInfo.minStake);
        await approveTx.wait();
        console.log('   ✅ Approved');
    }
    
    // Register node
    const machineIdHash = ethers.keccak256(ethers.toUtf8Bytes(config.machineId));
    
    console.log('   Registering on blockchain...');
    
    try {
        const tx = await relayManager.registerNodeWithId(
            node.nodeId,
            machineIdHash,
            node.storageMB,
            node.endpoint || ''
        );
        
        console.log(`   Transaction: ${tx.hash}`);
        const receipt = await tx.wait();
        console.log(`   ✅ Registered in block ${receipt.blockNumber}`);
        
        // Update config
        node.registered = true;
        saveConfig(config);
        
        console.log('\n✅ Node registered successfully!');
        console.log('   Run "node cli.js start" to start the node');
        
    } catch (error: any) {
        console.error(`\n❌ Registration failed: ${error.message}`);
        if (error.message.includes('already registered')) {
            node.registered = true;
            saveConfig(config);
            console.log('   (Node was already registered, updating local config)');
        }
    }
}

// Main
async function main(): Promise<void> {
    const args = process.argv.slice(2);
    const command = args[0];
    
    switch (command) {
        case 'info':
            await showInfo();
            break;
        case 'list':
            await listNodes();
            break;
        case 'add':
            await addNode();
            break;
        case 'register':
            await registerNode(args[1]);
            break;
        case 'start':
            console.log('\n  Start command not yet implemented. Use the desktop app.\n');
            break;
        case 'stop':
            console.log('\n  Stop command not yet implemented. Use the desktop app.\n');
            break;
        default:
            console.log(`
MumbleChat Multi-Node CLI
═══════════════════════════════════════════════════════════

Usage:
  node cli.js <command> [options]

Commands:
  info              Show machine info and storage status
  list              List all configured nodes
  add               Add a new node (interactive)
  register [id]     Register node on blockchain
  start <id>        Start a specific node
  stop <id>         Stop a specific node

Examples:
  node cli.js info
  node cli.js add
  node cli.js register 0x123abc
  node cli.js list
`);
    }
}

main().catch(console.error);
