/**
 * Withdraw MCT from Registry using owner function
 */

const { ethers } = require("hardhat");

async function main() {
  console.log("\n💰 Withdrawing MCT from Registry\n");

  const [deployer] = await ethers.getSigners();
  
  const MCT = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
  const REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";

  const mct = new ethers.Contract(MCT, [
    "function balanceOf(address) view returns (uint256)"
  ], deployer);
  
  const registry = new ethers.Contract(REGISTRY, [
    "function withdrawExcessMCT(uint256 amount) external",
    "function owner() view returns (address)"
  ], deployer);

  // Check owner
  const owner = await registry.owner();
  console.log(`Registry Owner: ${owner}`);
  console.log(`Deployer: ${deployer.address}`);
  
  if (owner.toLowerCase() !== deployer.address.toLowerCase()) {
    console.log("❌ Not owner!");
    return;
  }

  // Check balance
  const registryBalance = await mct.balanceOf(REGISTRY);
  console.log(`\nRegistry MCT: ${ethers.formatEther(registryBalance)} MCT`);

  if (registryBalance === 0n) {
    console.log("No MCT to withdraw");
    return;
  }

  // Withdraw all
  console.log(`\n📤 Withdrawing ${ethers.formatEther(registryBalance)} MCT...`);
  const tx = await registry.withdrawExcessMCT(registryBalance);
  console.log(`⏳ TX: ${tx.hash}`);
  await tx.wait();
  console.log(`✅ Withdrawn!`);

  // Verify
  const finalRegistryBalance = await mct.balanceOf(REGISTRY);
  const finalDeployerBalance = await mct.balanceOf(deployer.address);
  
  console.log(`\n📊 Final:`);
  console.log(`   Registry: ${ethers.formatEther(finalRegistryBalance)} MCT`);
  console.log(`   Deployer: ${ethers.formatEther(finalDeployerBalance)} MCT`);
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
