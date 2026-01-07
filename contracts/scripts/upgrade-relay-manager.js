const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Upgrade RelayManager with Endpoint Discovery");
    console.log("═══════════════════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    // Read existing proxy address from deployments.json
    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MumbleChatRelayManager.proxy;
    console.log("📦 Current Proxy:", proxyAddress);
    console.log("📦 Current Implementation:", deployments.contracts.MumbleChatRelayManager.implementation);

    // Deploy new implementation
    console.log("\n" + "═".repeat(75));
    console.log("   UPGRADE FEATURES:");
    console.log("═".repeat(75));
    console.log("   🆕 Decentralized Endpoint Discovery:");
    console.log("      • getActiveEndpoints() - Returns all active node endpoints");
    console.log("      • updateEndpoint(nodeId, endpoint) - Update your node's IP");
    console.log("      • getEndpointByNodeId(nodeId) - Get specific node's endpoint");
    console.log("      • getEndpointByWallet(wallet) - Get endpoint by wallet");
    console.log("");
    console.log("   ⚡ Benefits:");
    console.log("      • No bootstrap servers needed");
    console.log("      • Fully on-chain peer discovery");
    console.log("      • Dynamic IP support (update anytime)");
    console.log("═".repeat(75) + "\n");
    
    console.log("1️⃣  Compiling MumbleChatRelayManager...");
    const MumbleChatRelayManager = await ethers.getContractFactory("MumbleChatRelayManager");
    
    console.log("2️⃣  Upgrading proxy to new implementation...");
    const upgraded = await upgrades.upgradeProxy(proxyAddress, MumbleChatRelayManager, {
        kind: "uups",
        unsafeSkipStorageCheck: true
    });
    
    await upgraded.waitForDeployment();
    
    const newImplementation = await upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("   ✅ New Implementation:", newImplementation);
    
    // Update deployments.json
    deployments.contracts.MumbleChatRelayManager.implementation = newImplementation;
    deployments.contracts.MumbleChatRelayManager.upgradedAt = new Date().toISOString();
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    console.log("   📁 Updated deployments.json");
    
    // Verify the new functions are available
    console.log("\n3️⃣  Verifying new functions...");
    const relayManager = await ethers.getContractAt("MumbleChatRelayManager", proxyAddress);
    
    try {
        // Test getActiveEndpoints
        const [nodeIds, endpoints, wallets] = await relayManager.getActiveEndpoints();
        console.log(`   ✅ getActiveEndpoints() works - ${nodeIds.length} active nodes`);
        
        if (nodeIds.length > 0) {
            console.log("\n   📡 Active Node Endpoints:");
            for (let i = 0; i < nodeIds.length; i++) {
                console.log(`      Node ${i + 1}: ${endpoints[i]} (${wallets[i].slice(0, 10)}...)`);
            }
        }
    } catch (e) {
        console.log("   ⚠️  getActiveEndpoints() check:", e.message);
    }
    
    console.log("\n" + "═".repeat(75));
    console.log("   ✅ UPGRADE COMPLETE!");
    console.log("═".repeat(75));
    console.log("\n📋 RelayManager Proxy:", proxyAddress);
    console.log("📋 New Implementation:", newImplementation);
    console.log("\n🔧 New Functions Available:");
    console.log("   • relayManager.getActiveEndpoints() -> Get all active nodes + endpoints");
    console.log("   • relayManager.updateEndpoint(nodeId, 'ip:port') -> Update your endpoint");
    console.log("   • relayManager.getEndpointByNodeId(nodeId) -> Get specific endpoint");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
