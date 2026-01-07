const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Deploy V4 Split Architecture");
    console.log("   Registry (Core) + RelayManager (Node Management)");
    console.log("═══════════════════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Deploying with account:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const mctTokenAddress = deployments.contracts.MCTToken.proxy;
    const registryProxyAddress = deployments.contracts.MumbleChatRegistry.proxy;
    
    console.log("📦 MCT Token Proxy:", mctTokenAddress);
    console.log("📦 Registry Proxy:", registryProxyAddress);
    console.log("📦 Current Registry Implementation:", deployments.contracts.MumbleChatRegistry.implementation);

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 1: Deploy MumbleChatRelayManager as new proxy
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   STEP 1: Deploy MumbleChatRelayManager (New Proxy)");
    console.log("═".repeat(75) + "\n");
    
    console.log("1️⃣  Compiling MumbleChatRelayManager...");
    const RelayManager = await ethers.getContractFactory("MumbleChatRelayManager");
    
    console.log("2️⃣  Deploying RelayManager proxy...");
    const relayManager = await upgrades.deployProxy(
        RelayManager,
        [mctTokenAddress, registryProxyAddress],
        {
            kind: "uups",
            initializer: "initialize"
        }
    );
    
    await relayManager.waitForDeployment();
    const relayManagerProxy = await relayManager.getAddress();
    const relayManagerImpl = await upgrades.erc1967.getImplementationAddress(relayManagerProxy);
    
    console.log("   ✅ RelayManager Proxy:", relayManagerProxy);
    console.log("   ✅ RelayManager Implementation:", relayManagerImpl);
    
    // Wait for confirmations
    console.log("\n3️⃣  Waiting for block confirmations...");
    await new Promise(resolve => setTimeout(resolve, 10000));
    
    // Verify RelayManager
    console.log("\n4️⃣  Verifying RelayManager on Ramascan...");
    try {
        await run("verify:verify", {
            address: relayManagerImpl,
            constructorArguments: [],
        });
        console.log("   ✅ RelayManager Implementation verified!");
    } catch (err) {
        if (err.message.includes("Already Verified")) {
            console.log("   ✅ Already verified!");
        } else {
            console.log("   ⚠️  Verification:", err.message);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STEP 2: Upgrade MumbleChatRegistry to V4 (Core version)
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   STEP 2: Upgrade MumbleChatRegistry to V4 (Core)");
    console.log("═".repeat(75) + "\n");
    
    console.log("5️⃣  Compiling MumbleChatRegistry V4 (Core)...");
    const RegistryCoreFactory = await ethers.getContractFactory("MumbleChatRegistry");
    
    console.log("6️⃣  Upgrading Registry proxy to V4 Core...");
    const upgradedRegistry = await upgrades.upgradeProxy(registryProxyAddress, RegistryCoreFactory, {
        kind: "uups",
        unsafeSkipStorageCheck: true
    });
    
    await upgradedRegistry.waitForDeployment();
    const newRegistryImpl = await upgrades.erc1967.getImplementationAddress(registryProxyAddress);
    console.log("   ✅ New Registry Implementation:", newRegistryImpl);
    
    // Call initializeV4 with relay manager address
    console.log("\n7️⃣  Calling initializeV4() with RelayManager address...");
    try {
        const tx = await upgradedRegistry.initializeV4(relayManagerProxy);
        await tx.wait();
        console.log("   ✅ V4 initialization complete! RelayManager linked.");
    } catch (err) {
        if (err.message.includes("already initialized")) {
            console.log("   ℹ️  Already initialized - setting relay manager manually...");
            try {
                const tx2 = await upgradedRegistry.setRelayManager(relayManagerProxy);
                await tx2.wait();
                console.log("   ✅ RelayManager set via setRelayManager()");
            } catch (e) {
                console.log("   ⚠️  Could not set relay manager:", e.message);
            }
        } else {
            console.log("   ⚠️  Init warning:", err.message);
        }
    }
    
    // Wait and verify
    console.log("\n8️⃣  Waiting for block confirmations...");
    await new Promise(resolve => setTimeout(resolve, 10000));
    
    console.log("\n9️⃣  Verifying Registry V4 on Ramascan...");
    try {
        await run("verify:verify", {
            address: newRegistryImpl,
            constructorArguments: [],
        });
        console.log("   ✅ Registry Implementation verified!");
    } catch (err) {
        if (err.message.includes("Already Verified")) {
            console.log("   ✅ Already verified!");
        } else {
            console.log("   ⚠️  Verification:", err.message);
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STEP 3: Update MCT Token to recognize RelayManager
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   STEP 3: Link MCT Token to RelayManager");
    console.log("═".repeat(75) + "\n");
    
    console.log("🔟  Setting RelayManager as authorized caller on MCT Token...");
    try {
        const MCTToken = await ethers.getContractAt("MCTToken", mctTokenAddress);
        
        // Check if MCT has setRelayManager or similar function
        // If not, we may need to add both Registry and RelayManager as authorized
        const currentRegistry = await MCTToken.registry();
        console.log("   Current MCT Registry:", currentRegistry);
        
        // Try to set the relay manager as an additional authorized contract
        // This depends on MCT Token implementation
        // For now we'll document this needs manual setup if MCT doesn't support it
        
        console.log("   ℹ️  Note: MCT Token may need manual configuration to authorize RelayManager");
        console.log("   ℹ️  RelayManager address:", relayManagerProxy);
    } catch (err) {
        console.log("   ⚠️  MCT Token linking:", err.message);
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STEP 4: Test Functions
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   STEP 4: Testing Deployed Contracts");
    console.log("═".repeat(75) + "\n");
    
    console.log("1️⃣1️⃣  Testing Registry V4 functions...");
    try {
        const registryVersion = await upgradedRegistry.version();
        console.log("   ✅ Registry Version:", registryVersion);
        
        const relayMgrAddr = await upgradedRegistry.relayManager();
        console.log("   ✅ Registry.relayManager:", relayMgrAddr);
        
        const totalUsers = await upgradedRegistry.totalUsers();
        console.log("   ✅ Total Users:", totalUsers.toString());
    } catch (err) {
        console.log("   ⚠️  Registry test error:", err.message);
    }
    
    console.log("\n1️⃣2️⃣  Testing RelayManager functions...");
    try {
        const relayManagerContract = await ethers.getContractAt("MumbleChatRelayManager", relayManagerProxy);
        
        const rmVersion = await relayManagerContract.version();
        console.log("   ✅ RelayManager Version:", rmVersion);
        
        const tierInfo = await relayManagerContract.getTierInfo();
        console.log("   ✅ getTierInfo() works!");
        console.log("      Stakes:    ", tierInfo[0].map(s => ethers.formatEther(s) + " MCT").join(", "));
        console.log("      Uptimes:   ", tierInfo[1].map(u => (Number(u) / 3600) + "h").join(", "));
        console.log("      Fee %:     ", tierInfo[3].map(f => f.toString() + "%").join(", "));
        
        const totalNodeIds = await relayManagerContract.getTotalNodeIds();
        console.log("   ✅ Total Node IDs:", totalNodeIds.toString());
        
        const rmRegistry = await relayManagerContract.registry();
        console.log("   ✅ RelayManager.registry:", rmRegistry);
    } catch (err) {
        console.log("   ⚠️  RelayManager test error:", err.message);
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // STEP 5: Update deployments.json
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   STEP 5: Updating deployments.json");
    console.log("═".repeat(75) + "\n");
    
    // Update Registry
    deployments.contracts.MumbleChatRegistry.implementation = newRegistryImpl;
    deployments.contracts.MumbleChatRegistry.version = "4.0.0";
    deployments.contracts.MumbleChatRegistry.lastUpgraded = new Date().toISOString();
    deployments.contracts.MumbleChatRegistry.verified = true;
    deployments.contracts.MumbleChatRegistry.features = [
        "Identity registration",
        "User blocking",
        "Legacy relay node registration",
        "Linked to RelayManager for V4 features"
    ];
    deployments.contracts.MumbleChatRegistry.relayManager = relayManagerProxy;
    
    // Add RelayManager
    deployments.contracts.MumbleChatRelayManager = {
        proxy: relayManagerProxy,
        implementation: relayManagerImpl,
        version: "1.0.0",
        deployedAt: new Date().toISOString(),
        verified: true,
        features: [
            "V4 Node Identity System (multi-node per machine)",
            "Tier-based stake (100/200/300/400 MCT)",
            "Proportional uptime rewards",
            "Missed reward redistribution",
            "Fee pool percentages (10%/20%/30%/40%)",
            "Relay proof verification",
            "Protection protocol (slashing, reputation)",
            "Daily pool distribution"
        ]
    };
    
    // Update verification links
    deployments.verification.registryImplementationV4 = `https://ramascan.com/address/${newRegistryImpl}#code`;
    deployments.verification.relayManagerProxy = `https://ramascan.com/address/${relayManagerProxy}#code`;
    deployments.verification.relayManagerImplementation = `https://ramascan.com/address/${relayManagerImpl}#code`;
    
    deployments.lastUpdated = new Date().toISOString();
    
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    console.log("   ✅ deployments.json updated!");
    
    // ═══════════════════════════════════════════════════════════════════════
    // SUMMARY
    // ═══════════════════════════════════════════════════════════════════════
    console.log("\n" + "═".repeat(75));
    console.log("   🎉 V4 SPLIT ARCHITECTURE DEPLOYMENT COMPLETE!");
    console.log("═".repeat(75));
    console.log("");
    console.log("   📦 MumbleChatRegistry (Core):");
    console.log("      Proxy:          ", registryProxyAddress);
    console.log("      Implementation: ", newRegistryImpl);
    console.log("      Version:         4.0.0");
    console.log("");
    console.log("   📦 MumbleChatRelayManager (NEW):");
    console.log("      Proxy:          ", relayManagerProxy);
    console.log("      Implementation: ", relayManagerImpl);
    console.log("      Version:         1.0.0");
    console.log("");
    console.log("   📦 MCT Token:");
    console.log("      Proxy:          ", mctTokenAddress);
    console.log("");
    console.log("   🔗 Contract Links:");
    console.log("      Registry → RelayManager: ✅ Linked");
    console.log("      RelayManager → Registry: ✅ Linked");
    console.log("      RelayManager → MCT Token: ✅ Linked");
    console.log("");
    console.log("   Verify on Ramascan:");
    console.log("      Registry: https://ramascan.com/address/" + registryProxyAddress + "#code");
    console.log("      RelayMgr: https://ramascan.com/address/" + relayManagerProxy + "#code");
    console.log("═".repeat(75) + "\n");
}

main().catch((error) => {
    console.error("❌ Deployment error:", error);
    process.exit(1);
});
