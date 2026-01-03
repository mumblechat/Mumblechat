const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Contract Deployment");
    console.log("   Network: Ramestta (Chain ID: 1370)");
    console.log("═══════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Deployer:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    // ════════════════════════════════════════════════════════════════
    // Step 1: Deploy MCT Token (UUPS Proxy)
    // ════════════════════════════════════════════════════════════════
    console.log("1️⃣  Deploying MCT Token (UUPS Proxy)...");
    
    const MCTToken = await ethers.getContractFactory("MCTToken");
    const mctProxy = await upgrades.deployProxy(MCTToken, [deployer.address], {
        initializer: "initialize",
        kind: "uups",
    });
    await mctProxy.waitForDeployment();
    
    const mctProxyAddress = mctProxy.target;
    const mctImplementation = await upgrades.erc1967.getImplementationAddress(mctProxyAddress);
    
    console.log("   ✅ MCT Proxy:", mctProxyAddress);
    console.log("   📦 MCT Implementation:", mctImplementation);
    
    // Check initial supply
    const totalSupply = await mctProxy.totalSupply();
    console.log("   💎 Initial Supply:", ethers.formatEther(totalSupply), "MCT");
    
    // Show relay reward calculation
    const relayReward = await mctProxy.calculateRelayReward();
    console.log("   🎁 Relay Reward (0.01%):", ethers.formatEther(relayReward), "MCT\n");

    // ════════════════════════════════════════════════════════════════
    // Step 2: Deploy MumbleChat Registry (UUPS Proxy)
    // ════════════════════════════════════════════════════════════════
    console.log("2️⃣  Deploying MumbleChat Registry (UUPS Proxy)...");
    
    const MumbleChatRegistry = await ethers.getContractFactory("MumbleChatRegistry");
    const registryProxy = await upgrades.deployProxy(MumbleChatRegistry, [mctProxyAddress], {
        initializer: "initialize",
        kind: "uups",
    });
    await registryProxy.waitForDeployment();
    
    const registryProxyAddress = registryProxy.target;
    const registryImplementation = await upgrades.erc1967.getImplementationAddress(registryProxyAddress);
    
    console.log("   ✅ Registry Proxy:", registryProxyAddress);
    console.log("   📦 Registry Implementation:", registryImplementation);
    console.log("   🔗 MCT Token linked:", mctProxyAddress, "\n");

    // ════════════════════════════════════════════════════════════════
    // Step 3: Wait for confirmations
    // ════════════════════════════════════════════════════════════════
    console.log("3️⃣  Waiting for 5 block confirmations...");
    await mctProxy.deploymentTransaction().wait(5);
    await registryProxy.deploymentTransaction().wait(5);
    console.log("   ✅ Confirmed!\n");

    // ════════════════════════════════════════════════════════════════
    // Step 4: Verify on Explorer
    // ════════════════════════════════════════════════════════════════
    console.log("4️⃣  Verifying contracts on Ramascan...");
    
    try {
        await run("verify:verify", {
            address: mctImplementation,
            constructorArguments: [],
        });
        console.log("   ✅ MCT Implementation verified!");
    } catch (err) {
        console.warn("   ⚠️ MCT verification:", err.message);
    }
    
    try {
        await run("verify:verify", {
            address: registryImplementation,
            constructorArguments: [],
        });
        console.log("   ✅ Registry Implementation verified!");
    } catch (err) {
        console.warn("   ⚠️ Registry verification:", err.message);
    }

    // ════════════════════════════════════════════════════════════════
    // Step 5: Save deployment info
    // ════════════════════════════════════════════════════════════════
    const fs = require("fs");
    
    const deploymentInfo = {
        network: "ramestta",
        chainId: 1370,
        deployedAt: new Date().toISOString(),
        deployer: deployer.address,
        contracts: {
            MCTToken: {
                proxy: mctProxyAddress,
                implementation: mctImplementation,
                symbol: "MCT",
                initialSupply: "1000",
                relayRewardBps: "1" // 0.01%
            },
            MumbleChatRegistry: {
                proxy: registryProxyAddress,
                implementation: registryImplementation
            }
        }
    };
    
    fs.writeFileSync("./deployments.json", JSON.stringify(deploymentInfo, null, 2));
    console.log("   💾 Saved to: deployments.json\n");

    // ════════════════════════════════════════════════════════════════
    // Step 6: Update Android config
    // ════════════════════════════════════════════════════════════════
    console.log("5️⃣  Updating Android config...");
    
    const androidConfig = `package com.ramapay.app.chat

/**
 * MumbleChat Contract Addresses
 * AUTO-GENERATED - DO NOT EDIT
 * Deployed: ${new Date().toISOString()}
 */
object MumbleChatContracts {
    const val CHAIN_ID = 1370L
    const val RPC_URL = "https://blockchain.ramestta.com"
    
    // MCT Token (UUPS Proxy)
    const val MCT_TOKEN_PROXY = "${mctProxyAddress}"
    const val MCT_TOKEN_IMPL = "${mctImplementation}"
    
    // MumbleChat Registry (UUPS Proxy)
    const val REGISTRY_PROXY = "${registryProxyAddress}"
    const val REGISTRY_IMPL = "${registryImplementation}"
}
`;
    
    fs.writeFileSync("../app/src/main/java/com/ramapay/app/chat/MumbleChatContracts.kt", androidConfig);
    console.log("   📱 Android config updated!\n");

    // ════════════════════════════════════════════════════════════════
    // Summary
    // ════════════════════════════════════════════════════════════════
    console.log("═══════════════════════════════════════════════════════════");
    console.log("   🎉 DEPLOYMENT COMPLETE!");
    console.log("═══════════════════════════════════════════════════════════");
    console.log("");
    console.log("   MCT Token (Proxy):        ", mctProxyAddress);
    console.log("   MCT Token (Impl):         ", mctImplementation);
    console.log("   Registry (Proxy):         ", registryProxyAddress);
    console.log("   Registry (Impl):          ", registryImplementation);
    console.log("");
    console.log("   Initial MCT Supply:        1,000 MCT");
    console.log("   Relay Reward:              0.01% of supply per message");
    console.log("   Min Relay Stake:           100 MCT");
    console.log("");
    console.log("   Next steps:");
    console.log("   1. Test mint/burn functionality");
    console.log("   2. Configure relay nodes");
    console.log("   3. Build and test Android app");
    console.log("═══════════════════════════════════════════════════════════\n");
}

main().catch((error) => {
    console.error("❌ Deployment error:", error);
    process.exit(1);
});
