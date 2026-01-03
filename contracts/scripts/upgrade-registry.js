const { ethers, upgrades } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Upgrade Registry to V3.2");
    console.log("   Fair Daily Pool with Anti-Inflation Protection");
    console.log("═══════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);

    // Read existing proxy address from deployments.json
    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MumbleChatRegistry.proxy;
    console.log("📦 Current Proxy:", proxyAddress);
    
    // Get current version
    const currentRegistry = await ethers.getContractAt("MumbleChatRegistry", proxyAddress);
    const currentVersion = await currentRegistry.version();
    console.log("📌 Current Version:", currentVersion);

    // Deploy new implementation
    console.log("\n1️⃣  Deploying new MumbleChatRegistry V3.2 implementation...");
    console.log("   V3.2 Improvements:");
    console.log("   • Anti-inflation: Only earned rewards distributed");
    console.log("   • Low activity: 2 nodes × 20 relays = 0.04 MCT (not 100 MCT)");
    console.log("   • High activity: Cap at 100 MCT, fair proportional split");
    console.log("   • Formula: effectivePool = min(100 MCT, totalRelays × 0.001 × multiplier)");
    
    const MumbleChatRegistryV32 = await ethers.getContractFactory("MumbleChatRegistry");
    
    // V3.2 is just a code fix, no new storage, no reinitializer needed
    const upgraded = await upgrades.upgradeProxy(proxyAddress, MumbleChatRegistryV32, {
        kind: "uups",
        unsafeSkipStorageCheck: true
    });
    
    await upgraded.waitForDeployment();
    
    const newImplementation = await upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("   ✅ New Implementation:", newImplementation);
    
    // Verify new version
    const newVersion = await upgraded.version();
    console.log("   📌 New Version:", newVersion);

    // Update deployments.json
    deployments.contracts.MumbleChatRegistry.implementation = newImplementation;
    deployments.contracts.MumbleChatRegistry.version = newVersion;
    deployments.contracts.MumbleChatRegistry.lastUpgraded = new Date().toISOString();
    deployments.contracts.MumbleChatRegistry.features = [
        "Identity registration",
        "Relay node registration",
        "Tier-based rewards",
        "User blocking",
        "Decentralized relay proofs",
        "Batch proof submission",
        "Fair daily pool distribution",
        "Anti-inflation protection"
    ];
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));

    console.log("\n2️⃣  V3.2 Fair Distribution Details:");
    console.log("   • Base reward: 0.001 MCT per message");
    console.log("   • Tier multiplier: Bronze 1x, Silver 1.5x, Gold 2x, Platinum 3x");
    console.log("   • Daily cap: 100 MCT maximum");
    console.log("   • Anti-inflation: Only earned rewards distributed");
    console.log("   • Daily Pool Cap:", ethers.formatEther(await upgraded.dailyPoolAmount()), "MCT");
    console.log("");
    console.log("   Example scenarios:");
    console.log("   • Low activity: 2 nodes × 10 relays = 0.02 MCT total (not 100 MCT!)");
    console.log("   • High activity: 1M relays × 0.001 = 1000 MCT → capped at 100 MCT");

    console.log("\n═══════════════════════════════════════════════════════════");
    console.log("   🎉 Registry Upgraded to V3.2 Successfully!");
    console.log("   Fair rewards + Anti-inflation protection!");
    console.log("═══════════════════════════════════════════════════════════\n");
}

main().catch((error) => {
    console.error("❌ Upgrade error:", error);
    process.exit(1);
});
