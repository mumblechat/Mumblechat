const { ethers } = require("hardhat");

async function main() {
    const [signer] = await ethers.getSigners();
    console.log("Sending from:", signer.address);
    
    const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
    const RECIPIENT = "0xa14cD3394462744b87EBcC800Be499F0C8826949";
    const AMOUNT = ethers.parseEther("1");
    
    // Get MCT contract
    const mct = await ethers.getContractAt("MCTToken", MCT_TOKEN);
    
    // Check balance first
    const balance = await mct.balanceOf(signer.address);
    console.log("Current balance:", ethers.formatEther(balance), "MCT");
    
    // Send tokens
    console.log(`\nSending 1 MCT to ${RECIPIENT}...`);
    const tx = await mct.transfer(RECIPIENT, AMOUNT);
    console.log("TX Hash:", tx.hash);
    
    await tx.wait();
    console.log("✅ Sent 1 MCT successfully!");
    
    // Check new balances
    const newBalance = await mct.balanceOf(signer.address);
    const recipientBalance = await mct.balanceOf(RECIPIENT);
    console.log("\nNew balances:");
    console.log("  Sender:", ethers.formatEther(newBalance), "MCT");
    console.log("  Recipient:", ethers.formatEther(recipientBalance), "MCT");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
