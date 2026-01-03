const { ethers } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("Calling initializeV3_1 reinitializer...\n");

    const [deployer] = await ethers.getSigners();
    console.log("Account:", deployer.address);

    // Read proxy address from deployments.json
    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MumbleChatRegistry.proxy;
    console.log("Proxy:", proxyAddress);

    const registry = await ethers.getContractAt("MumbleChatRegistry", proxyAddress);
    
    // Check current values
    console.log("\nBefore reinitializer:");
    console.log("  Version:", await registry.version());
    
    try {
        const relayReward = await registry.relayRewardPerMessage();
        console.log("  relayRewardPerMessage:", ethers.formatEther(relayReward), "MCT");
    } catch (e) {
        console.log("  relayRewardPerMessage: 0 (not initialized)");
    }
    
    try {
        const dailyPool = await registry.dailyPoolAmount();
        console.log("  dailyPoolAmount:", ethers.formatEther(dailyPool), "MCT");
    } catch (e) {
        console.log("  dailyPoolAmount: 0 (not initialized)");
    }
    
    // Try to call reinitializer
    console.log("\nCalling initializeV3_1...");
    try {
        const tx = await registry.initializeV3_1();
        console.log("  TX Hash:", tx.hash);
        await tx.wait();
        console.log("  ✅ Reinitializer called successfully!");
    } catch (e) {
        console.log("  ⚠️  Already initialized or error:", e.message);
    }
    
    // Check new values
    console.log("\nAfter reinitializer:");
    console.log("  Version:", await registry.version());
    console.log("  relayRewardPerMessage:", ethers.formatEther(await registry.relayRewardPerMessage()), "MCT");
    console.log("  dailyPoolAmount:", ethers.formatEther(await registry.dailyPoolAmount()), "MCT");
    
    // Update deployments.json
    deployments.contracts.MumbleChatRegistry.version = "3.1.0";
    deployments.contracts.MumbleChatRegistry.lastUpgraded = new Date().toISOString();
    deployments.contracts.MumbleChatRegistry.features = [
        "Identity registration",
        "Relay node registration",
        "Tier-based rewards",
        "User blocking",
        "Decentralized relay proofs",
        "Batch proof submission",
        "Fair daily pool distribution"
    ];
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    
    console.log("\n✅ Done!");
}

main().catch((error) => {
    console.error("Error:", error);
    process.exit(1);
});
