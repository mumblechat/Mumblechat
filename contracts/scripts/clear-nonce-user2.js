const { ethers } = require("hardhat");

async function main() {
    const PRIVATE_KEY = "0x0d78b32c65bb22bf4f40d76eff61dab200d0a443ff1922f371a0bbb8a8570c03";
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
    console.log(`\n⚠️ Found ${stuckCount} stuck transaction(s)`);
    console.log("Sending replacement transactions with higher gas...\n");
    
    // Get current gas price and TRIPLE it for replacement
    const feeData = await provider.getFeeData();
    const gasPrice = feeData.gasPrice * 3n; // Triple the gas price
    
    console.log("Using gas price:", ethers.formatUnits(gasPrice, "gwei"), "gwei");
    
    // Send replacement transactions for each stuck nonce
    for (let nonce = confirmedNonce; nonce < pendingNonce; nonce++) {
        console.log(`\nClearing nonce ${nonce}...`);
        
        try {
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
        } catch (e) {
            if (e.message.includes("nonce too low") || e.message.includes("already known")) {
                console.log(`  ✅ Already confirmed or replaced`);
            } else {
                console.log(`  ❌ Error: ${e.message}`);
            }
        }
    }
    
    // Verify final state
    const finalNonce = await provider.getTransactionCount(wallet.address, "latest");
    console.log("\n🎉 Done! Final confirmed nonce:", finalNonce);
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
