const { ethers } = require("hardhat");

async function main() {
    const PRIVATE_KEY = "0x41d22a9df02be3c5d9875ee3f6f82d0ec9d804609d2d501629489051070dfee1";
    const provider = ethers.provider;
    
    // Create wallet from private key
    const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
    console.log("Clearing nonces for:", wallet.address);
    
    // Get current nonce status
    const confirmedNonce = await provider.getTransactionCount(wallet.address, "latest");
    const pendingNonce = await provider.getTransactionCount(wallet.address, "pending");
    
    console.log("Confirmed nonce:", confirmedNonce);
    console.log("Pending nonce:", pendingNonce);
    
    if (pendingNonce <= confirmedNonce) {
        console.log("\n✅ No stuck transactions to clear!");
        return;
    }
    
    const stuckCount = pendingNonce - confirmedNonce;
    console.log(`\n⚠️ Found ${stuckCount} stuck transactions`);
    console.log("Sending replacement transactions...\n");
    
    // Get current gas price and increase it
    const feeData = await provider.getFeeData();
    const gasPrice = feeData.gasPrice * 2n; // Double the gas price
    
    console.log("Using gas price:", ethers.formatUnits(gasPrice, "gwei"), "gwei");
    
    // Send replacement transactions for each stuck nonce
    for (let nonce = confirmedNonce; nonce < pendingNonce; nonce++) {
        console.log(`\nClearing nonce ${nonce}...`);
        
        const tx = await wallet.sendTransaction({
            to: wallet.address, // Send to self
            value: 0,
            nonce: nonce,
            gasLimit: 21000,
            gasPrice: gasPrice
        });
        
        console.log(`  TX Hash: ${tx.hash}`);
        
        const receipt = await tx.wait();
        console.log(`  ✅ Confirmed in block ${receipt.blockNumber}`);
    }
    
    // Verify final state
    const finalNonce = await provider.getTransactionCount(wallet.address, "latest");
    console.log("\n🎉 All nonces cleared!");
    console.log("Final confirmed nonce:", finalNonce);
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
