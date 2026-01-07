const { ethers, upgrades } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════");
    console.log("   Force Upgrade RelayManager with Endpoint Discovery");
    console.log("═══════════════════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MumbleChatRelayManager.proxy;
    console.log("📦 Proxy:", proxyAddress);

    console.log("\n1️⃣  Force importing existing proxy...");
    const MumbleChatRelayManager = await ethers.getContractFactory("MumbleChatRelayManager");
    
    // Force import the existing deployment
    await upgrades.forceImport(proxyAddress, MumbleChatRelayManager, {
        kind: "uups"
    });
    console.log("   ✅ Proxy imported successfully");
    
    console.log("\n2️⃣  Upgrading to new implementation...");
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
        const [nodeIds, endpoints, wallets] = await relayManager.getActiveEndpoints();
        console.log(`   ✅ getActiveEndpoints() works - ${nodeIds.length} active nodes`);
    } catch (e) {
        console.log("   ⚠️  getActiveEndpoints() check:", e.message);
    }
    
    console.log("\n" + "═".repeat(75));
    console.log("   ✅ UPGRADE COMPLETE!");
    console.log("═".repeat(75));
    console.log("\n📋 RelayManager Proxy:", proxyAddress);
    console.log("📋 New Implementation:", newImplementation);
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
