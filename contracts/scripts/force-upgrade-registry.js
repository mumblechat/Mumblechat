const { ethers, upgrades, run } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Force Upgrade Registry to Correct Version");
    console.log("═══════════════════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);
    
    const balance = await ethers.provider.getBalance(deployer.address);
    console.log("💰 Balance:", ethers.formatEther(balance), "RAMA\n");

    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const registryProxyAddress = deployments.contracts.MumbleChatRegistry.proxy;
    const relayManagerProxy = deployments.contracts.MumbleChatRelayManager.proxy;
    
    console.log("📦 Registry Proxy:", registryProxyAddress);
    console.log("📦 RelayManager Proxy:", relayManagerProxy);
    
    // Check current bytecode size
    const currentCode = await ethers.provider.getCode(await upgrades.erc1967.getImplementationAddress(registryProxyAddress));
    console.log("📦 Current Implementation bytecode:", currentCode.length / 2, "bytes");
    
    // Get new contract factory
    console.log("\n1️⃣  Getting MumbleChatRegistry factory...");
    const RegistryFactory = await ethers.getContractFactory("MumbleChatRegistry");
    
    // Deploy new implementation directly (not through upgrade)
    console.log("\n2️⃣  Deploying new implementation directly...");
    const newImpl = await RegistryFactory.deploy();
    await newImpl.waitForDeployment();
    const newImplAddress = await newImpl.getAddress();
    console.log("   ✅ New Implementation deployed:", newImplAddress);
    
    // Get proxy contract
    const registry = await ethers.getContractAt("MumbleChatRegistry", registryProxyAddress);
    
    // Upgrade the proxy to point to new implementation
    console.log("\n3️⃣  Upgrading proxy to new implementation...");
    try {
        const tx = await registry.upgradeToAndCall(newImplAddress, "0x");
        await tx.wait();
        console.log("   ✅ Proxy upgraded!");
    } catch (err) {
        console.log("   ⚠️  Upgrade error:", err.message);
        console.log("   Trying alternative method...");
        
        // Try using upgrades plugin with force
        const upgraded = await upgrades.upgradeProxy(registryProxyAddress, RegistryFactory, {
            kind: "uups",
            unsafeSkipStorageCheck: true,
            unsafeAllowRenames: true
        });
        await upgraded.waitForDeployment();
        console.log("   ✅ Proxy upgraded via plugin!");
    }
    
    // Get actual implementation address
    const finalImpl = await upgrades.erc1967.getImplementationAddress(registryProxyAddress);
    console.log("   📦 Final Implementation:", finalImpl);
    
    // Verify new bytecode size
    const newCode = await ethers.provider.getCode(finalImpl);
    console.log("   📦 New bytecode size:", newCode.length / 2, "bytes");
    
    // Set relay manager if needed
    console.log("\n4️⃣  Setting RelayManager address...");
    try {
        const registryContract = await ethers.getContractAt("MumbleChatRegistry", registryProxyAddress);
        const currentRM = await registryContract.relayManager();
        console.log("   Current RelayManager:", currentRM);
        
        if (currentRM === "0x0000000000000000000000000000000000000000") {
            const tx = await registryContract.setRelayManager(relayManagerProxy);
            await tx.wait();
            console.log("   ✅ RelayManager set!");
        } else {
            console.log("   ✅ RelayManager already set");
        }
    } catch (err) {
        console.log("   ⚠️  Error:", err.message);
    }
    
    // Wait for confirmations
    console.log("\n5️⃣  Waiting for block confirmations...");
    await new Promise(resolve => setTimeout(resolve, 10000));
    
    // Verify
    console.log("\n6️⃣  Verifying new implementation...");
    try {
        await run("verify:verify", {
            address: finalImpl,
            constructorArguments: [],
        });
        console.log("   ✅ Verified!");
    } catch (err) {
        if (err.message.includes("Already Verified")) {
            console.log("   ✅ Already verified!");
        } else {
            console.log("   ⚠️  Verification:", err.message);
        }
    }
    
    // Test
    console.log("\n7️⃣  Testing...");
    try {
        const registryContract = await ethers.getContractAt("MumbleChatRegistry", registryProxyAddress);
        const version = await registryContract.version();
        console.log("   ✅ Version:", version);
        
        const rm = await registryContract.relayManager();
        console.log("   ✅ RelayManager:", rm);
        
        const users = await registryContract.totalUsers();
        console.log("   ✅ Total Users:", users.toString());
    } catch (err) {
        console.log("   ⚠️  Test error:", err.message);
    }
    
    // Update deployments
    deployments.contracts.MumbleChatRegistry.implementation = finalImpl;
    deployments.contracts.MumbleChatRegistry.lastUpgraded = new Date().toISOString();
    deployments.verification.registryImplementationV4 = `https://ramascan.com/address/${finalImpl}#code`;
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));
    
    console.log("\n═══════════════════════════════════════════════════════════════════════");
    console.log("   ✅ DONE!");
    console.log("   Implementation:", finalImpl);
    console.log("═══════════════════════════════════════════════════════════════════════\n");
}

main().catch((error) => {
    console.error("❌ Error:", error);
    process.exit(1);
});
