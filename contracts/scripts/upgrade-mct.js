const { ethers, upgrades } = require("hardhat");
require("dotenv").config();

async function main() {
    console.log("═══════════════════════════════════════════════════════════");
    console.log("   MumbleChat Protocol - Upgrade MCT Token");
    console.log("═══════════════════════════════════════════════════════════\n");

    const [deployer] = await ethers.getSigners();
    console.log("👤 Upgrading with account:", deployer.address);

    // Read existing proxy address from deployments.json
    const fs = require("fs");
    const deployments = JSON.parse(fs.readFileSync("./deployments.json", "utf8"));
    
    const proxyAddress = deployments.contracts.MCTToken.proxy;
    console.log("📦 Current Proxy:", proxyAddress);

    // Deploy new implementation
    console.log("\n1️⃣  Deploying new MCTToken implementation...");
    
    const MCTTokenV2 = await ethers.getContractFactory("MCTToken");
    const upgraded = await upgrades.upgradeProxy(proxyAddress, MCTTokenV2, {
        kind: "uups",
    });
    
    await upgraded.waitForDeployment();
    
    const newImplementation = await upgrades.erc1967.getImplementationAddress(proxyAddress);
    console.log("   ✅ New Implementation:", newImplementation);

    // Update deployments.json
    deployments.contracts.MCTToken.implementation = newImplementation;
    deployments.contracts.MCTToken.lastUpgraded = new Date().toISOString();
    fs.writeFileSync("./deployments.json", JSON.stringify(deployments, null, 2));

    console.log("\n═══════════════════════════════════════════════════════════");
    console.log("   🎉 MCT Token Upgraded Successfully!");
    console.log("═══════════════════════════════════════════════════════════\n");
}

main().catch((error) => {
    console.error("❌ Upgrade error:", error);
    process.exit(1);
});
