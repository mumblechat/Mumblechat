/**
 * Find and withdraw MCT stuck in Registry contract
 */

const { ethers } = require("hardhat");

const MCT_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function transfer(address,uint256) external returns (bool)"
];

const REGISTRY_ABI = [
  "function relayNodes(address) view returns (bool isActive, uint256 tier, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, uint256 dailyUptimeSeconds, uint256 storageMB, bool isOnline, uint256 registeredAt)",
  "function deactivateRelay() external",
  "function claimRewards() external returns (uint256)",
  "function owner() view returns (address)",
  "function withdrawERC20(address token, uint256 amount) external"
];

async function main() {
  console.log("\n🔍 Finding MCT in Registry Contract\n");
  console.log("=".repeat(60));

  const [deployer] = await ethers.getSigners();
  
  const MCT = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
  const REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
  const DEPLOYER = "0xDF5522431567362bDBF901DB3bCCa7B26524AF99";
  const RELAY_WALLET = "0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7";

  const mct = new ethers.Contract(MCT, MCT_ABI, deployer);
  const registry = new ethers.Contract(REGISTRY, REGISTRY_ABI, deployer);

  // Check registry balance
  const registryBalance = await mct.balanceOf(REGISTRY);
  console.log(`\n📊 Registry MCT Balance: ${ethers.formatEther(registryBalance)} MCT`);

  // Check who owns registry
  try {
    const owner = await registry.owner();
    console.log(`👤 Registry Owner: ${owner}`);
  } catch (e) {
    console.log(`👤 Registry Owner: Could not determine`);
  }

  // Check deployer's relay node status
  console.log(`\n🔍 Checking Deployer's node (${DEPLOYER})...`);
  try {
    const deployerNode = await registry.relayNodes(DEPLOYER);
    console.log(`   Active: ${deployerNode.isActive}`);
    console.log(`   Staked: ${ethers.formatEther(deployerNode.stakedAmount)} MCT`);
    console.log(`   Rewards: ${ethers.formatEther(deployerNode.rewardsEarned)} MCT`);
    
    if (deployerNode.stakedAmount > 0n) {
      console.log(`\n💰 Found stake! Attempting to withdraw...`);
      
      if (deployerNode.rewardsEarned > 0n) {
        console.log(`   Claiming rewards...`);
        const claimTx = await registry.claimRewards();
        await claimTx.wait();
        console.log(`   ✅ Claimed rewards`);
      }
      
      if (deployerNode.isActive) {
        console.log(`   Deactivating relay...`);
        const deactivateTx = await registry.deactivateRelay();
        await deactivateTx.wait();
        console.log(`   ✅ Deactivated and withdrew stake`);
      }
    }
  } catch (e) {
    console.log(`   Error: ${e.message}`);
  }

  // Check relay wallet's node status
  console.log(`\n🔍 Checking Relay Wallet's node (${RELAY_WALLET})...`);
  try {
    const relayNode = await registry.relayNodes(RELAY_WALLET);
    console.log(`   Active: ${relayNode.isActive}`);
    console.log(`   Staked: ${ethers.formatEther(relayNode.stakedAmount)} MCT`);
    console.log(`   Rewards: ${ethers.formatEther(relayNode.rewardsEarned)} MCT`);
    
    if (relayNode.stakedAmount > 0n && relayNode.isActive) {
      console.log(`\n💰 Found stake in relay wallet! Need relay wallet's key to withdraw.`);
    }
  } catch (e) {
    console.log(`   Error: ${e.message}`);
  }

  // Try admin withdraw if deployer is owner
  console.log(`\n🔐 Attempting admin withdrawal...`);
  try {
    const withdrawTx = await registry.withdrawERC20(MCT, registryBalance);
    await withdrawTx.wait();
    console.log(`✅ Admin withdrew ${ethers.formatEther(registryBalance)} MCT`);
  } catch (e) {
    console.log(`❌ Admin withdraw failed: ${e.message.slice(0, 100)}`);
  }

  // Final check
  const finalRegistryBalance = await mct.balanceOf(REGISTRY);
  const finalDeployerBalance = await mct.balanceOf(DEPLOYER);
  
  console.log("\n" + "=".repeat(60));
  console.log("\n📊 Final Status:");
  console.log(`   Registry: ${ethers.formatEther(finalRegistryBalance)} MCT`);
  console.log(`   Deployer: ${ethers.formatEther(finalDeployerBalance)} MCT`);
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
