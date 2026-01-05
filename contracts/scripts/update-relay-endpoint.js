const { ethers } = require("hardhat");

/**
 * Update relay endpoint by deactivating and re-registering with new IP
 * This script gets the real public IP and updates the relay's endpoint
 */
async function main() {
    // S20 FE Relay Node
    const RELAY_PRIVATE_KEY = "0x41d22a9df02be3c5d9875ee3f6f82d0ec9d804609d2d501629489051070dfee1";
    const REGISTRY_ADDRESS = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
    
    const provider = ethers.provider;
    const relayWallet = new ethers.Wallet(RELAY_PRIVATE_KEY, provider);
    
    console.log("╔════════════════════════════════════════════════════════════╗");
    console.log("║         UPDATE RELAY NODE ENDPOINT                         ║");
    console.log("╚════════════════════════════════════════════════════════════╝");
    console.log("\nRelay Address:", relayWallet.address);
    
    // Get contracts
    const registry = await ethers.getContractAt("MumbleChatRegistry", REGISTRY_ADDRESS, relayWallet);
    const mct = await ethers.getContractAt("MCTToken", MCT_TOKEN, relayWallet);
    
    // Check current status
    const currentInfo = await registry.relayNodes(relayWallet.address);
    console.log("\n📊 Current Status:");
    console.log("   Endpoint:", currentInfo.endpoint);
    console.log("   Stake:", ethers.formatEther(currentInfo.stakedAmount), "MCT");
    console.log("   Active:", currentInfo.isActive);
    console.log("   Tier:", currentInfo.tier);
    console.log("   Storage MB:", currentInfo.storageMB.toString());
    
    if (!currentInfo.isActive) {
        console.log("\n❌ Relay is not active. Nothing to update.");
        return;
    }
    
    // Get public IP via STUN-like HTTP service
    console.log("\n🌐 Discovering public IP...");
    let publicIP;
    try {
        const response = await fetch("https://api.ipify.org?format=json");
        const data = await response.json();
        publicIP = data.ip;
    } catch (e) {
        // Fallback
        const response = await fetch("https://ifconfig.me/ip");
        publicIP = (await response.text()).trim();
    }
    
    const newEndpoint = `${publicIP}:19372`;
    console.log("   Public IP:", publicIP);
    console.log("   New Endpoint:", newEndpoint);
    
    // Step 1: Deactivate relay
    console.log("\n🔄 Step 1: Deactivating relay...");
    const deactivateTx = await registry.deactivateRelay();
    console.log("   TX:", deactivateTx.hash);
    await deactivateTx.wait();
    console.log("   ✅ Deactivated!");
    
    // Check MCT balance was returned
    const mctBalance = await mct.balanceOf(relayWallet.address);
    console.log("   MCT Balance after unstake:", ethers.formatEther(mctBalance), "MCT");
    
    // Step 2: Approve MCT for new stake
    console.log("\n🔄 Step 2: Approving MCT for stake...");
    const stakeAmount = ethers.parseEther("100"); // 100 MCT
    const approveTx = await mct.approve(REGISTRY_ADDRESS, stakeAmount);
    console.log("   TX:", approveTx.hash);
    await approveTx.wait();
    console.log("   ✅ Approved!");
    
    // Step 3: Re-register with new endpoint
    console.log("\n🔄 Step 3: Re-registering with new endpoint...");
    const storageMB = currentInfo.storageMB; // Keep same storage
    const registerTx = await registry.registerAsRelay(newEndpoint, storageMB);
    console.log("   TX:", registerTx.hash);
    await registerTx.wait();
    console.log("   ✅ Registered!");
    
    // Verify new status
    const newInfo = await registry.relayNodes(relayWallet.address);
    console.log("\n╔════════════════════════════════════════════════════════════╗");
    console.log("║         UPDATE COMPLETE! ✅                                 ║");
    console.log("╚════════════════════════════════════════════════════════════╝");
    console.log("\n📊 New Status:");
    console.log("   Endpoint:", newInfo.endpoint);
    console.log("   Stake:", ethers.formatEther(newInfo.stakedAmount), "MCT");
    console.log("   Active:", newInfo.isActive);
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
