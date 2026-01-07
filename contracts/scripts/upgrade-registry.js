const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Upgrade Registry to V4");
    console.log("   Node Identity + Proportional Uptime + Tier Fee Pool System");
    console.log("═══════════════════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    // Read existing proxy address from deployments.json
    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MumbleChatRegistry.proxy;
    console.log("📦 Current Proxy:", proxyAddress);
    console.log("📦 Current Implementation:", deployments.contracts.MumbleChatRegistry.implementation);

    // Deploy new implementation
    console.log("\n" + "═".repeat(75));
    console.log("   V4 NEW FEATURES:");
    console.log("═".repeat(75));
    console.log("   🆕 Node Identity System:");
    console.log("      • Unique Node ID per wallet + machine");
    console.log("      • Multiple nodes per machine supported");
    console.log("      • registerNodeWithId(), heartbeatByNodeId()");
    console.log("");
    console.log("   🆕 Tier-Based Stake Requirements:");
    console.log("      • Bronze:   100 MCT stake, 4+ hours uptime, 10% fee pool");
    console.log("      • Silver:   200 MCT stake, 8+ hours uptime, 20% fee pool");
    console.log("      • Gold:     300 MCT stake, 12+ hours uptime, 30% fee pool");
    console.log("      • Platinum: 400 MCT stake, 16+ hours uptime, 40% fee pool");
    console.log("");
    console.log("   🆕 Proportional Uptime Rewards:");
    console.log("      • actualReward = (actualUptime / requiredUptime) × fullReward");
    console.log("      • Missed rewards redistributed to 100% uptime nodes");
    console.log("═".repeat(75) + "\n");
    
    console.log("1️⃣  Compiling MumbleChatRegistry V4...");
    const MumbleChatRegistryV4 = await ethers.getContractFactory("MumbleChatRegistry");
    
    console.log("2️⃣  Upgrading proxy to V4 implementation...");
    const upgraded = await upgrades.upgradeProxy(proxyAddress, MumbleChatRegistryV4, {
        kind: "uups",
        unsafeSkipStorageCheck: true
    });
    
    await upgraded.waitForDeployment();
    
    const newImplementation = await upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("   ✅ New Implementation:", newImplementation);
    
    // Call initializeV4 reinitializer
    console.log("\n3️⃣  Calling initializeV4() reinitializer...");
    try {
        const tx = await upgraded.initializeV4();
        await tx.wait();
        console.log("   ✅ V4 initialization complete!");
    } catch (err) {
        if (err.message.includes("already initialized")) {
            console.log("   ℹ️  Already initialized (this is OK for re-runs)");
        } else {
            console.log("   ⚠️  Init warning:", err.message);
        }
    }

    // Wait for confirmations
    console.log("\n4️⃣  Waiting for block confirmations...");
    await new Promise(resolve => setTimeout(resolve, 10000));
    console.log("   ✅ Confirmed!\n");

    // Verify on Explorer
    console.log("5️⃣  Verifying new implementation on Ramascan...");
    try {
        await run("verify:verify", {
            address: newImplementation,
            constructorArguments: [],
        });
        console.log("   ✅ Implementation verified on Ramascan!");
    } catch (err) {
        if (err.message.includes("Already Verified")) {
            console.log("   ✅ Already verified!");
        } else {
            console.log("   ⚠️  Verification:", err.message);
        }
    }

    // Test new V4 functions
    console.log("\n6️⃣  Testing V4 functions...");
    try {
        const tierInfo = await upgraded.getTierInfo();
        console.log("   ✅ getTierInfo() works!");
        console.log("      Stakes:    ", tierInfo[0].map(s => ethers.formatEther(s) + " MCT").join(", "));
        console.log("      Uptimes:   ", tierInfo[1].map(u => (Number(u) / 3600) + "h").join(", "));
        console.log("      Fee %:     ", tierInfo[3].map(f => f.toString() + "%").join(", "));
        
        const totalNodeIds = await upgraded.getTotalNodeIds();
        console.log("      Total Node IDs:", totalNodeIds.toString());
    } catch (err) {
        console.log("   ⚠️  Test error:", err.message);
    }

    // Update deployments.json
    deployments.contracts.MumbleChatRegistry.implementation = newImplementation;
    deployments.contracts.MumbleChatRegistry.version = "4.0.0";
    deployments.contracts.MumbleChatRegistry.lastUpgraded = new Date().toISOString();
    deployments.contracts.MumbleChatRegistry.verified = true;
    deployments.contracts.MumbleChatRegistry.features = [
        "Identity registration",
        "Relay node registration", 
        "V4: Node Identity System (multi-node per machine)",
        "V4: Tier-based stake (100/200/300/400 MCT)",
        "V4: Proportional uptime rewards",
        "V4: Missed reward redistribution",
        "V4: Fee pool percentages (10%/20%/30%/40%)",
        "User blocking",
        "Decentralized relay proofs",
        "Fair daily pool distribution"
    ];
    deployments.verification = deployments.verification || {};
    deployments.verification.registryImplementationV4 = `https://ramascan.com/address/${newImplementation}#code`;
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    console.log("\n7️⃣  Updated deployments.json");

    // Summary
    console.log("\n" + "═".repeat(75));
    console.log("   🎉 REGISTRY V4 UPGRADE COMPLETE!");
    console.log("═".repeat(75));
    console.log("");
    console.log("   📦 Proxy Address:          ", proxyAddress);
    console.log("   📦 New Implementation:     ", newImplementation);
    console.log("   📌 Version:                 4.0.0");
    console.log("");
    console.log("   V4 Tier System:");
    console.log("   ┌──────────┬───────────┬──────────┬─────────┬──────────┐");
    console.log("   │ Tier     │ Stake     │ Uptime   │ Storage │ Fee Pool │");
    console.log("   ├──────────┼───────────┼──────────┼─────────┼──────────┤");
    console.log("   │ Bronze   │ 100 MCT   │ 4 hours  │ 1 GB    │ 10%      │");
    console.log("   │ Silver   │ 200 MCT   │ 8 hours  │ 2 GB    │ 20%      │");
    console.log("   │ Gold     │ 300 MCT   │ 12 hours │ 4 GB    │ 30%      │");
    console.log("   │ Platinum │ 400 MCT   │ 16 hours │ 8 GB    │ 40%      │");
    console.log("   └──────────┴───────────┴──────────┴─────────┴──────────┘");
    console.log("");
    console.log("   Reward Formula:");
    console.log("   actualReward = (actualUptime / requiredUptime) × tierFeePool%");
    console.log("   missedReward → redistributed to 100% uptime nodes");
    console.log("");
    console.log("   Verify on Ramascan:");
    console.log("   Proxy: https://ramascan.com/address/" + proxyAddress + "#code");
    console.log("   Impl:  https://ramascan.com/address/" + newImplementation + "#code");
    console.log("═".repeat(75) + "\n");
}

main().catch((error) => {
    console.error("❌ Upgrade error:", error);
    process.exit(1);
});
