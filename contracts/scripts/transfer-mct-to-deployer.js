/**
 * Transfer MCT from Relay Wallet to Deployer using relay wallet's private key
 */

const hre = require("hardhat");
const { ethers } = require("hardhat");

const MCT_ABI = [
  "function balanceOf(address) view returns (uint256)",
  "function transfer(address to, uint256 amount) external returns (bool)"
];

async function main() {
  console.log("\n📤 Transferring MCT to Deployer\n");
  console.log("=".repeat(70));

  // Addresses
  const DEPLOYER = "0xDF5522431567362bDBF901DB3bCCa7B26524AF99";
  const RELAY_WALLET = "0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7";
  const RELAY_PK = "deec7d287996f966385cb5977200083864c4282410a82d7ae57f880e860665e0";
  const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";

  // Connect with relay wallet
  const provider = new ethers.JsonRpcProvider("https://blockchain.ramestta.com");
  const relayWallet = new ethers.Wallet(RELAY_PK, provider);
  const mct = new ethers.Contract(MCT_TOKEN, MCT_ABI, relayWallet);

  console.log(`\nFrom: ${RELAY_WALLET}`);
  console.log(`To:   ${DEPLOYER}\n`);

  // Check balance
  const balance = await mct.balanceOf(RELAY_WALLET);
  console.log(`💰 Relay Wallet Balance: ${ethers.formatEther(balance)} MCT`);

  if (balance === 0n) {
    console.log("\n⚠️  No MCT to transfer!\n");
    return;
  }

  // Transfer all MCT to deployer
  console.log(`\n📤 Transferring ${ethers.formatEther(balance)} MCT to deployer...`);
  
  const tx = await mct.transfer(DEPLOYER, balance);
  console.log(`⏳ Transaction submitted: ${tx.hash}`);
  
  const receipt = await tx.wait();
  console.log(`✅ Transfer complete! Block: ${receipt.blockNumber}`);

  // Verify
  const newRelayBalance = await mct.balanceOf(RELAY_WALLET);
  const deployerBalance = await mct.balanceOf(DEPLOYER);

  console.log("\n📊 Final Balances:");
  console.log(`   Relay Wallet: ${ethers.formatEther(newRelayBalance)} MCT`);
  console.log(`   Deployer:     ${ethers.formatEther(deployerBalance)} MCT`);
  
  console.log("\n" + "=".repeat(70));
  console.log("\n✅ All MCT consolidated to deployer!\n");
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
