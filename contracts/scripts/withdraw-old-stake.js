/**
 * Withdraw staked MCT from old Registry contract (V3.2)
 * Before migrating to V4 RelayManager
 */

const hre = require("hardhat");
const { ethers } = require("hardhat");

// Old Registry V3.2 ABI - just the functions we need
const OLD_REGISTRY_ABI = [
  "function relayNodes(address) view returns (bool isActive, uint256 tier, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, uint256 dailyUptimeSeconds, uint256 storageMB, bool isOnline, uint256 registeredAt)",
  "function deactivateRelay() external",
  "function claimRewards() external returns (uint256)",
  "function isRelayRegistered(address) view returns (bool)"
];

const MCT_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function transfer(address to, uint256 amount) external returns (bool)"
];

async function main() {
  console.log("\n🔄 Withdrawing from Old Registry Contract\n");
  console.log("=".repeat(60));

  const [deployer] = await ethers.getSigners();
  const walletAddress = "0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7";
  
  console.log(`\n📍 Deployer: ${deployer.address}`);
  console.log(`📍 Target Wallet: ${walletAddress}`);

  // Contract addresses
  const OLD_REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
  const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";

  // Connect to contracts
  const oldRegistry = new ethers.Contract(OLD_REGISTRY, OLD_REGISTRY_ABI, deployer);
  const mctToken = new ethers.Contract(MCT_TOKEN, MCT_ABI, deployer);

  console.log(`\n📜 Old Registry: ${OLD_REGISTRY}`);
  console.log(`🪙  MCT Token: ${MCT_TOKEN}`);

  // Check relay node info directly
  console.log("\n🔍 Checking old registration...");
  
  let nodeInfo;
  try {
    nodeInfo = await oldRegistry.relayNodes(deployer.address);
    console.log("✅ Found relay node data");
  } catch (error) {
    console.log("❌ Could not read relay nodes (function may not exist in V4)");
    nodeInfo = null;
  }
  
  if (!nodeInfo || nodeInfo.stakedAmount === 0n) {
    console.log("❌ Not registered in old contract");
    
    // Check MCT balance
    const balance = await mctToken.balanceOf(deployer.address);
    const balanceFormatted = ethers.formatEther(balance);
    console.log(`\n💰 Current MCT Balance: ${balanceFormatted} MCT`);
    
    if (parseFloat(balanceFormatted) >= 100) {
      console.log("✅ Sufficient MCT for node registration (100+ MCT)");
      
      // Transfer to target wallet if needed
      if (deployer.address.toLowerCase() !== walletAddress.toLowerCase()) {
        console.log(`\n📤 Transferring MCT to ${walletAddress}...`);
        const transferTx = await mctToken.transfer(walletAddress, balance);
        await transferTx.wait();
        console.log(`✅ Transferred ${balanceFormatted} MCT`);
        console.log(`   TxHash: ${transferTx.hash}`);
      }
    } else {
      console.log("⚠️  Insufficient MCT. You need at least 100 MCT to register Bronze tier node");
    }
    
    return;
  }

  console.log("✅ Found registration in old contract");

  // Get relay node info (already fetched above)
  console.log("\n📊 Old Node Info:");
  console.log(`   Staked: ${ethers.formatEther(nodeInfo.stakedAmount)} MCT`);
  console.log(`   Tier: ${nodeInfo.tier}`);
  console.log(`   Messages: ${nodeInfo.messagesRelayed}`);
  console.log(`   Rewards: ${ethers.formatEther(nodeInfo.rewardsEarned)} MCT`);
  console.log(`   Active: ${nodeInfo.isActive}`);

  // Claim rewards first if any
  if (nodeInfo.rewardsEarned > 0n) {
    console.log("\n💰 Claiming pending rewards...");
    try {
      const claimTx = await oldRegistry.claimRewards();
      await claimTx.wait();
      console.log(`✅ Claimed ${ethers.formatEther(nodeInfo.rewardsEarned)} MCT`);
      console.log(`   TxHash: ${claimTx.hash}`);
    } catch (error) {
      console.log(`⚠️  Claim failed: ${error.message}`);
    }
  }

  // Deactivate to withdraw stake
  if (nodeInfo.stakedAmount > 0n) {
    console.log("\n🔓 Deactivating relay to withdraw stake...");
    try {
      const deactivateTx = await oldRegistry.deactivateRelay();
      await deactivateTx.wait();
      console.log(`✅ Deactivated and withdrew ${ethers.formatEther(nodeInfo.stakedAmount)} MCT`);
      console.log(`   TxHash: ${deactivateTx.hash}`);
    } catch (error) {
      console.log(`⚠️  Deactivation failed: ${error.message}`);
    }
  }

  // Check final balance
  const finalBalance = await mctToken.balanceOf(deployer.address);
  const finalBalanceFormatted = ethers.formatEther(finalBalance);
  console.log(`\n💰 Final MCT Balance: ${finalBalanceFormatted} MCT`);

  // Transfer to target wallet if different
  if (deployer.address.toLowerCase() !== walletAddress.toLowerCase() && finalBalance > 0n) {
    console.log(`\n📤 Transferring MCT to ${walletAddress}...`);
    const transferTx = await mctToken.transfer(walletAddress, finalBalance);
    await transferTx.wait();
    console.log(`✅ Transferred ${finalBalanceFormatted} MCT`);
    console.log(`   TxHash: ${transferTx.hash}`);
  }

  console.log("\n" + "=".repeat(60));
  console.log("✅ Withdrawal Complete!");
  console.log("\nNext Steps:");
  console.log("1. Start relay node with wallet: " + walletAddress);
  console.log("2. Node will auto-register with V4 RelayManager");
  console.log("3. Earn rewards from new tier-based system");
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
