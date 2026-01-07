#!/usr/bin/env node
/**
 * MumbleChat - Register Test Nodes
 * 
 * This script:
 * 1. Creates 2 new wallets for test nodes
 * 2. Sends MCT tokens from deployer to node wallets
 * 3. Registers both nodes on the RelayManager contract
 * 4. Verifies registration
 */

const { ethers } = require('ethers');
const fs = require('fs');

// Configuration
const RPC_URL = 'https://blockchain.ramestta.com';
const CHAIN_ID = 1370;

// Contract addresses
const MCT_TOKEN = '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE';
const RELAY_MANAGER = '0xF78F840eF0e321512b09e98C76eA0229Affc4b73';

// Deployer wallet (has MCT tokens)
const DEPLOYER_KEY = 'deec7d287996f966385cb5977200083464c4282410a82d7ae57f880e860665e0';

// ABIs
const ERC20_ABI = [
    'function transfer(address to, uint256 amount) external returns (bool)',
    'function approve(address spender, uint256 amount) external returns (bool)',
    'function balanceOf(address account) external view returns (uint256)',
    'function allowance(address owner, address spender) external view returns (uint256)'
];

const RELAY_MANAGER_ABI = [
    'function registerNodeWithId(bytes32 nodeId, bytes32 machineIdHash, uint256 storageMB, string endpoint) external returns (bool)',
    'function updateEndpoint(bytes32 nodeId, string newEndpoint) external',
    'function isNodeRegistered(bytes32 nodeId) external view returns (bool)',
    'function nodes(bytes32 nodeId) external view returns (address owner, bytes32 machineIdHash, uint256 storageMB, uint8 tier, string endpoint, bool isActive, uint256 registeredAt, uint256 lastActivityAt, uint256 pendingRewards)',
    'function getActiveEndpoints() external view returns (bytes32[] memory nodeIds, string[] memory endpoints, address[] memory wallets, uint8[] memory tiers)',
    'function walletNodeIds(address wallet, uint256 index) external view returns (bytes32)'
];

// Get machine ID
function getMachineIdHash() {
    const os = require('os');
    const crypto = require('crypto');
    
    const machineId = process.env.MACHINE_ID || 
        fs.readFileSync('/etc/machine-id', 'utf8').trim() || 
        os.hostname();
    
    return ethers.keccak256(ethers.toUtf8Bytes(machineId));
}

// Generate node ID
function generateNodeId(wallet, index) {
    return ethers.keccak256(
        ethers.solidityPacked(
            ['address', 'uint256', 'uint256'],
            [wallet, Date.now(), index]
        )
    );
}

async function main() {
    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║       MUMBLECHAT - REGISTER TEST NODES                        ║');
    console.log('╚══════════════════════════════════════════════════════════════╝\n');

    // Connect to network
    const provider = new ethers.JsonRpcProvider(RPC_URL);
    const deployer = new ethers.Wallet(DEPLOYER_KEY, provider);
    
    console.log(`Deployer: ${deployer.address}`);
    
    // Check deployer balance
    const mct = new ethers.Contract(MCT_TOKEN, ERC20_ABI, deployer);
    const deployerBalance = await mct.balanceOf(deployer.address);
    console.log(`Deployer MCT Balance: ${ethers.formatEther(deployerBalance)} MCT\n`);
    
    if (deployerBalance < ethers.parseEther('700')) {
        console.error('❌ Insufficient MCT balance. Need at least 700 MCT');
        return;
    }

    // Create 2 test wallets
    console.log('Creating test wallets...\n');
    
    const wallet1 = ethers.Wallet.createRandom().connect(provider);
    const wallet2 = ethers.Wallet.createRandom().connect(provider);
    
    console.log('Node 1 Wallet:');
    console.log(`  Address: ${wallet1.address}`);
    console.log(`  Private Key: ${wallet1.privateKey}`);
    console.log('');
    
    console.log('Node 2 Wallet:');
    console.log(`  Address: ${wallet2.address}`);
    console.log(`  Private Key: ${wallet2.privateKey}`);
    console.log('');
    
    // Generate node IDs
    const nodeId1 = generateNodeId(wallet1.address, 1);
    const nodeId2 = generateNodeId(wallet2.address, 2);
    const machineIdHash = getMachineIdHash();
    
    console.log('Generated Node IDs:');
    console.log(`  Node 1: ${nodeId1}`);
    console.log(`  Node 2: ${nodeId2}`);
    console.log(`  Machine Hash: ${machineIdHash}\n`);
    
    // Send RAMA for gas to both wallets
    console.log('Sending RAMA for gas...');
    const gasAmount = ethers.parseEther('1'); // 1 RAMA each
    
    const tx1 = await deployer.sendTransaction({
        to: wallet1.address,
        value: gasAmount
    });
    await tx1.wait();
    console.log(`  ✅ Sent 1 RAMA to ${wallet1.address}`);
    
    const tx2 = await deployer.sendTransaction({
        to: wallet2.address,
        value: gasAmount
    });
    await tx2.wait();
    console.log(`  ✅ Sent 1 RAMA to ${wallet2.address}\n`);
    
    // Send MCT tokens
    console.log('Sending MCT tokens...');
    const bronzeStake = ethers.parseEther('110'); // 100 + buffer
    const silverStake = ethers.parseEther('550'); // 500 + buffer
    
    const mctTx1 = await mct.transfer(wallet1.address, bronzeStake);
    await mctTx1.wait();
    console.log(`  ✅ Sent 110 MCT to Node 1 (BRONZE stake)`);
    
    const mctTx2 = await mct.transfer(wallet2.address, silverStake);
    await mctTx2.wait();
    console.log(`  ✅ Sent 550 MCT to Node 2 (SILVER stake)\n`);
    
    // Register Node 1 (BRONZE - 1024 MB)
    console.log('Registering Node 1 (BRONZE)...');
    const mct1 = new ethers.Contract(MCT_TOKEN, ERC20_ABI, wallet1);
    const relayManager1 = new ethers.Contract(RELAY_MANAGER, RELAY_MANAGER_ABI, wallet1);
    
    // Approve
    const approveTx1 = await mct1.approve(RELAY_MANAGER, ethers.parseEther('100'));
    await approveTx1.wait();
    console.log('  ✅ Approved MCT spend');
    
    // Register
    const endpoint1 = 'hub.mumblechat.com'; // Will get tunnel from hub
    try {
        const regTx1 = await relayManager1.registerNodeWithId(
            nodeId1,
            machineIdHash,
            1024, // 1 GB
            endpoint1
        );
        const receipt1 = await regTx1.wait();
        console.log(`  ✅ Registered in block ${receipt1.blockNumber}`);
    } catch (error) {
        console.error(`  ❌ Registration failed: ${error.message}`);
    }
    
    // Register Node 2 (SILVER - 4096 MB)
    console.log('\nRegistering Node 2 (SILVER)...');
    const mct2 = new ethers.Contract(MCT_TOKEN, ERC20_ABI, wallet2);
    const relayManager2 = new ethers.Contract(RELAY_MANAGER, RELAY_MANAGER_ABI, wallet2);
    
    // Approve
    const approveTx2 = await mct2.approve(RELAY_MANAGER, ethers.parseEther('500'));
    await approveTx2.wait();
    console.log('  ✅ Approved MCT spend');
    
    // Register
    const endpoint2 = 'hub.mumblechat.com';
    try {
        const regTx2 = await relayManager2.registerNodeWithId(
            nodeId2,
            machineIdHash,
            4096, // 4 GB
            endpoint2
        );
        const receipt2 = await regTx2.wait();
        console.log(`  ✅ Registered in block ${receipt2.blockNumber}`);
    } catch (error) {
        console.error(`  ❌ Registration failed: ${error.message}`);
    }
    
    // Verify registrations
    console.log('\nVerifying registrations...');
    const relayManager = new ethers.Contract(RELAY_MANAGER, RELAY_MANAGER_ABI, provider);
    
    const node1Registered = await relayManager.isNodeRegistered(nodeId1);
    const node2Registered = await relayManager.isNodeRegistered(nodeId2);
    
    console.log(`  Node 1 registered: ${node1Registered ? '✅' : '❌'}`);
    console.log(`  Node 2 registered: ${node2Registered ? '✅' : '❌'}`);
    
    // Get active endpoints
    console.log('\nFetching active endpoints...');
    try {
        const [nodeIds, endpoints, wallets, tiers] = await relayManager.getActiveEndpoints();
        console.log(`  Found ${nodeIds.length} active nodes`);
        
        for (let i = 0; i < nodeIds.length; i++) {
            const tierNames = ['BRONZE', 'SILVER', 'GOLD', 'PLATINUM'];
            console.log(`    - ${nodeIds[i].slice(0, 10)}... | ${tierNames[tiers[i]]} | ${endpoints[i]}`);
        }
    } catch (error) {
        console.log(`  Could not fetch endpoints: ${error.message}`);
    }
    
    // Save configuration
    const config = {
        createdAt: new Date().toISOString(),
        machineIdHash,
        nodes: [
            {
                nodeId: nodeId1,
                walletAddress: wallet1.address,
                privateKey: wallet1.privateKey,
                tier: 'BRONZE',
                storageMB: 1024,
                registered: node1Registered,
                endpoint: endpoint1
            },
            {
                nodeId: nodeId2,
                walletAddress: wallet2.address,
                privateKey: wallet2.privateKey,
                tier: 'SILVER',
                storageMB: 4096,
                registered: node2Registered,
                endpoint: endpoint2
            }
        ]
    };
    
    fs.writeFileSync('./test-nodes-config.json', JSON.stringify(config, null, 2));
    console.log('\n✅ Configuration saved to: test-nodes-config.json');
    
    console.log('\n╔══════════════════════════════════════════════════════════════╗');
    console.log('║                      SUMMARY                                  ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  Node 1 (BRONZE):                                             ║`);
    console.log(`║    ID:     ${nodeId1.slice(0, 42)}      ║`);
    console.log(`║    Wallet: ${wallet1.address}         ║`);
    console.log(`║    Storage: 1 GB                                              ║`);
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log(`║  Node 2 (SILVER):                                             ║`);
    console.log(`║    ID:     ${nodeId2.slice(0, 42)}      ║`);
    console.log(`║    Wallet: ${wallet2.address}         ║`);
    console.log(`║    Storage: 4 GB                                              ║`);
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log('║  Both nodes use same Machine ID Hash                          ║');
    console.log('║  Endpoint: hub.mumblechat.com (managed mode)                  ║');
    console.log('╚══════════════════════════════════════════════════════════════╝');
    console.log('\nNext: Start nodes to connect to hub and begin serving users.\n');
}

main().catch(console.error);
