/**
 * Check MCT Token Distribution and Consolidate to Deployer
 */

const hre = require("hardhat");
const { ethers } = require("hardhat");

const MCT_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function transfer(address to, uint256 amount) external returns (bool)",
  "function totalSupply() view returns (uint256)",
  "function feePool() view returns (uint256)",
  "function owner() view returns (address)"
];

const REGISTRY_ABI = [
  "function relayNodes(address) view returns (bool isActive, uint256 tier, uint256 stakedAmount, uint256 messagesRelayed, uint256 rewardsEarned, uint256 dailyUptimeSeconds, uint256 storageMB, bool isOnline, uint256 registeredAt)",
  "function deactivateRelay() external",
  "function claimRewards() external returns (uint256)"
];

const RELAY_MANAGER_ABI = [
  "function getWalletNodeIds(address) view returns (bytes32[])",
  "function getNodeByNodeId(bytes32) view returns (address, bytes32, uint256, uint256, uint256, uint256, bool, uint256, uint256, uint256, bool, uint256)",
  "function withdrawStake(bytes32 nodeId) external"
];

async function main() {
  console.log("\n💰 MCT Token Distribution Check\n");
  console.log("=".repeat(70));

  const [deployer] = await ethers.getSigners();
  const DEPLOYER = "0xDF5522431567362bDBF901DB3bCCa7B26524AF99";
  const RELAY_WALLET = "0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7";
  
  console.log(`\n🔑 Deployer: ${DEPLOYER}`);
  console.log(`🔑 Relay Wallet: ${RELAY_WALLET}`);

  // Contract addresses
  const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
  const REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
  const RELAY_MANAGER = "0xF78F840eF0e321512b09e98C76eA0229Affc4b73";

  const mct = new ethers.Contract(MCT_TOKEN, MCT_ABI, deployer);
  const registry = new ethers.Contract(REGISTRY, REGISTRY_ABI, deployer);
  const relayManager = new ethers.Contract(RELAY_MANAGER, RELAY_MANAGER_ABI, deployer);

  console.log("\n📊 Current MCT Distribution:\n");

  // 1. Check deployer balance
  const deployerBalance = await mct.balanceOf(DEPLOYER);
  console.log(`1️⃣  Deployer (${DEPLOYER})`);
  console.log(`    Balance: ${ethers.formatEther(deployerBalance)} MCT\n`);

  // 2. Check relay wallet balance
  const relayBalance = await mct.balanceOf(RELAY_WALLET);
  console.log(`2️⃣  Relay Wallet (${RELAY_WALLET})`);
  console.log(`    Balance: ${ethers.formatEther(relayBalance)} MCT\n`);

  // 3. Check MCT token contract (fee pool)
  const feePool = await mct.feePool();
  console.log(`3️⃣  MCT Token Fee Pool`);
  console.log(`    Amount: ${ethers.formatEther(feePool)} MCT\n`);

  // 4. Check Registry contract balance
  const registryBalance = await mct.balanceOf(REGISTRY);
  console.log(`4️⃣  Registry Contract (${REGISTRY})`);
  console.log(`    Balance: ${ethers.formatEther(registryBalance)} MCT\n`);

  // 5. Check RelayManager contract balance
  const relayManagerBalance = await mct.balanceOf(RELAY_MANAGER);
  console.log(`5️⃣  RelayManager Contract (${RELAY_MANAGER})`);
  console.log(`    Balance: ${ethers.formatEther(relayManagerBalance)} MCT\n`);

  // 6. Check total supply
  const totalSupply = await mct.totalSupply();
  console.log(`📈 Total Supply: ${ethers.formatEther(totalSupply)} MCT`);

  // Calculate totals
  const totalAccounted = deployerBalance + relayBalance + feePool + registryBalance + relayManagerBalance;
  console.log(`📊 Total Accounted: ${ethers.formatEther(totalAccounted)} MCT\n`);

  console.log("=".repeat(70));
  console.log("\n🔄 Starting Consolidation Process...\n");

  let totalConsolidated = 0n;

  // Step 1: Transfer from relay wallet to deployer
  if (relayBalance > 0n) {
    console.log(`📤 Step 1: Transferring from Relay Wallet...`);
    try {
      const tx = await mct.connect(deployer).transfer(DEPLOYER, relayBalance);
      await tx.wait();
      console.log(`   ✅ Transferred ${ethers.formatEther(relayBalance)} MCT`);
      console.log(`   TxHash: ${tx.hash}\n`);
      totalConsolidated += relayBalance;
    } catch (error) {
      console.log(`   ⚠️  Already in deployer or transfer failed: ${error.message}\n`);
    }
  } else {
    console.log(`📤 Step 1: Relay wallet is empty, skipping...\n`);
  }

  // Step 2: Check and claim from old Registry (if any staked/rewards)
  console.log(`📤 Step 2: Checking Old Registry for staked MCT...`);
  try {
    const nodeInfo = await registry.relayNodes(DEPLOYER);
    
    if (nodeInfo.rewardsEarned > 0n) {
      console.log(`   💰 Found ${ethers.formatEther(nodeInfo.rewardsEarned)} MCT rewards`);
      const claimTx = await registry.claimRewards();
      await claimTx.wait();
      console.log(`   ✅ Claimed rewards`);
      console.log(`   TxHash: ${claimTx.hash}`);
      totalConsolidated += nodeInfo.rewardsEarned;
    }
    
    if (nodeInfo.stakedAmount > 0n && nodeInfo.isActive) {
      console.log(`   🔓 Found ${ethers.formatEther(nodeInfo.stakedAmount)} MCT staked`);
      const deactivateTx = await registry.deactivateRelay();
      await deactivateTx.wait();
      console.log(`   ✅ Deactivated and withdrew stake`);
      console.log(`   TxHash: ${deactivateTx.hash}`);
      totalConsolidated += nodeInfo.stakedAmount;
    } else {
      console.log(`   ℹ️  No staked MCT found in old Registry`);
    }
  } catch (error) {
    console.log(`   ℹ️  No relay node in old Registry or already withdrawn\n`);
  }

  // Step 3: Check RelayManager for any staked nodes
  console.log(`\n📤 Step 3: Checking RelayManager for staked nodes...`);
  try {
    const nodeIds = await relayManager.getWalletNodeIds(DEPLOYER);
    
    if (nodeIds.length > 0) {
      console.log(`   Found ${nodeIds.length} node(s) registered`);
      
      for (const nodeId of nodeIds) {
        const nodeInfo = await relayManager.getNodeByNodeId(nodeId);
        const stakedAmount = nodeInfo[3]; // stakedAmount is 4th field
        
        if (stakedAmount > 0n) {
          console.log(`   🔓 Withdrawing ${ethers.formatEther(stakedAmount)} MCT from node ${nodeId.slice(0, 10)}...`);
          try {
            const withdrawTx = await relayManager.withdrawStake(nodeId);
            await withdrawTx.wait();
            console.log(`   ✅ Withdrawn`);
            console.log(`   TxHash: ${withdrawTx.hash}`);
            totalConsolidated += stakedAmount;
          } catch (error) {
            console.log(`   ⚠️  Withdraw failed: ${error.message}`);
          }
        }
      }
    } else {
      console.log(`   ℹ️  No nodes found in RelayManager\n`);
    }
  } catch (error) {
    console.log(`   ℹ️  No nodes in RelayManager or query failed\n`);
  }

  // Final balance check
  console.log("\n" + "=".repeat(70));
  console.log("\n✅ Consolidation Complete!\n");
  
  const finalBalance = await mct.balanceOf(DEPLOYER);
  console.log(`💰 Final Deployer Balance: ${ethers.formatEther(finalBalance)} MCT`);
  
  if (totalConsolidated > 0n) {
    console.log(`📊 Total Consolidated: ${ethers.formatEther(totalConsolidated)} MCT`);
  }
  
  console.log("\n" + "=".repeat(70));
  console.log("\n📋 Summary:");
  console.log(`   • Deployer has: ${ethers.formatEther(finalBalance)} MCT`);
  console.log(`   • Ready to stake and run relay node`);
  console.log(`   • Minimum required: 100 MCT (Bronze tier)`);
  
  if (finalBalance >= ethers.parseEther("100")) {
    console.log(`\n✅ Sufficient MCT available!\n`);
  } else {
    console.log(`\n⚠️  WARNING: Not enough MCT for relay node!\n`);
  }
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
