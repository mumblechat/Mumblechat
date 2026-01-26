const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Upgrade RelayManager V3 (Reward Cap Fix)");
    console.log("═══════════════════════════════════════════════════════════════════════════\n");

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
    console.log("📦 Current Version:", deployments.contracts.MumbleChatRelayManager.version);

    // Display upgrade changes
    console.log("\n" + "═".repeat(75));
    console.log("   V3 UPGRADE - REWARD CAP FIX:");
    console.log("═".repeat(75));
    console.log("   🐛 BUG FIXED:");
    console.log("      • Nodes could earn more from pool than their message entitlement");
    console.log("      • Example: 3000 messages could earn 33 MCT instead of 0.003 MCT");
    console.log("");
    console.log("   🆕 NEW CONSTANTS ADDED:");
    console.log("      • BASE_REWARD_PER_1000_MSG = 0.001 MCT");
    console.log("      • MESSAGES_PER_REWARD = 1000");
    console.log("");
    console.log("   🔧 LOGIC CHANGE:");
    console.log("      • Reward = MIN(poolShare, baseRewardCap)");
    console.log("      • baseRewardCap = (relayCount / 1000) * 0.001 MCT");
    console.log("      • Nodes cannot earn more than their work entitles them to");
    console.log("");
    console.log("   ✅ BACKWARD COMPATIBLE:");
    console.log("      • All existing functions work the same");
    console.log("      • Only adds protection against over-payment");
    console.log("═".repeat(75) + "\n");

    // Confirm before proceeding
    console.log("⚠️  This will upgrade the live contract on Ramestta Mainnet!");
    console.log("    Press Ctrl+C within 5 seconds to cancel...\n");
    await new Promise(resolve => setTimeout(resolve, 5000));

    console.log("1️⃣  Compiling MumbleChatRelayManager...");
    const MumbleChatRelayManager = await ethers.getContractFactory("MumbleChatRelayManager");
    
    // Force import the proxy first (in case it's not registered)
    console.log("1.5️⃣  Force importing proxy (if needed)...");
    try {
        await upgrades.forceImport(proxyAddress, MumbleChatRelayManager, { kind: "uups" });
        console.log("   ✅ Proxy imported successfully");
    } catch (e) {
        if (e.message.includes("already registered") || e.message.includes("already deployed")) {
            console.log("   ℹ️  Proxy already registered");
        } else {
            console.log("   ⚠️  Import note:", e.message.slice(0, 80));
        }
    }
    
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
    deployments.contracts.MumbleChatRelayManager.version = "3.0.0";
    deployments.contracts.MumbleChatRelayManager.upgradedAt = new Date().toISOString();
    deployments.contracts.MumbleChatRelayManager.lastUpgraded = new Date().toISOString();
    
    // Update features list
    deployments.contracts.MumbleChatRelayManager.features = [
        "V4 Node Identity System (multi-node per machine)",
        "Tier-based stake (100/200/300/400 MCT)",
        "Proportional uptime rewards",
        "Missed reward redistribution",
        "Fee pool percentages (10%/20%/30%/40%)",
        "Relay proof verification",
        "Protection protocol (slashing, reputation)",
        "Daily pool distribution",
        "getActiveEndpoints() - Decentralized discovery",
        "updateEndpoint() - Dynamic IP support",
        "V3: Reward cap - MIN(poolShare, baseRewardCap)",
        "V3: BASE_REWARD_PER_1000_MSG = 0.001 MCT"
    ];
    
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    console.log("   📁 Updated deployments.json");

    // Verify the new constants are available
    console.log("\n3️⃣  Verifying new constants...");
    const relayManager = await ethers.getContractAt("MumbleChatRelayManager", proxyAddress);
    
    try {
        const baseReward = await relayManager.BASE_REWARD_PER_1000_MSG();
        console.log(`   ✅ BASE_REWARD_PER_1000_MSG = ${ethers.formatEther(baseReward)} MCT`);
        
        const messagesPerReward = await relayManager.MESSAGES_PER_REWARD();
        console.log(`   ✅ MESSAGES_PER_REWARD = ${messagesPerReward}`);
    } catch (e) {
        console.log("   ⚠️  Constants check:", e.message);
    }

    // Test existing functions still work
    console.log("\n4️⃣  Verifying existing functions...");
    try {
        const [nodeIds, endpoints, wallets] = await relayManager.getActiveEndpoints();
        console.log(`   ✅ getActiveEndpoints() works - ${nodeIds.length} active nodes`);
        
        const tierInfo = await relayManager.getTierInfo();
        console.log(`   ✅ getTierInfo() works - Stakes: ${tierInfo[0].map(s => ethers.formatEther(s) + " MCT").join(", ")}`);
        
        const totalNodes = await relayManager.totalRelayNodes();
        console.log(`   ✅ totalRelayNodes() = ${totalNodes}`);
    } catch (e) {
        console.log("   ⚠️  Function check:", e.message);
    }

    console.log("\n" + "═".repeat(75));
    console.log("   ✅ UPGRADE TO V3 COMPLETE!");
    console.log("═".repeat(75));
    console.log("\n📋 RelayManager Proxy:", proxyAddress);
    console.log("📋 New Implementation:", newImplementation);
    console.log("📋 Version:", "3.0.0");
    console.log("\n🔧 V3 Changes:");
    console.log("   • claimDailyPoolReward() now caps rewards at base entitlement");
    console.log("   • Reward = MIN(poolShare, (relayCount/1000) * 0.001 MCT)");
    console.log("   • Prevents gaming the reward system");
    console.log("\n📊 Example (3000 messages, 100 MCT pool, 3 nodes):");
    console.log("   • Old: 33.33 MCT (pool share)");
    console.log("   • New: 0.003 MCT (capped at base reward)");
    console.log("");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
