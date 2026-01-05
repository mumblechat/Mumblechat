const { ethers } = require("hardhat");

async function main() {
    const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
    const USER_PK = "0xf95a9b764258bb647e2d2fc2dd313fe19fe3a0c8ce27115cce2abc4a5d8683ac";
    
    console.log("=== Direct Relay Node Registration ===\n");
    
    // Create wallet from private key
    const provider = new ethers.JsonRpcProvider("https://blockchain.ramestta.com");
    const userWallet = new ethers.Wallet(USER_PK, provider);
    
    console.log("User address:", userWallet.address);
    
    // Get contracts
    const Registry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = Registry.attach(REGISTRY_PROXY).connect(userWallet);
    
    const MCT = await ethers.getContractFactory("MCTToken");
    const mct = MCT.attach(MCT_TOKEN).connect(userWallet);
    
    // Check current state
    console.log("\n--- Pre-checks ---");
    
    const identity = await registry.identities(userWallet.address);
    console.log("Identity registered:", identity.isActive);
    
    const relayNode = await registry.relayNodes(userWallet.address);
    if (relayNode.isActive) {
        console.log("✅ Already a relay node!");
        console.log("Endpoint:", relayNode.endpoint);
        return;
    }
    
    const balance = await mct.balanceOf(userWallet.address);
    console.log("MCT Balance:", ethers.formatEther(balance));
    
    const minStake = await registry.minRelayStake();
    console.log("Min Stake:", ethers.formatEther(minStake));
    
    const allowance = await mct.allowance(userWallet.address, REGISTRY_PROXY);
    console.log("Allowance:", ethers.formatEther(allowance));
    
    // Check if we need to approve
    if (allowance < minStake) {
        console.log("\n--- Approving MCT ---");
        const approveTx = await mct.approve(REGISTRY_PROXY, minStake);
        console.log("Approve tx:", approveTx.hash);
        await approveTx.wait();
        console.log("✅ Approved!");
    }
    
    // Register as relay
    console.log("\n--- Registering as Relay Node ---");
    const endpoint = `/dns4/relay.mumblechat.io/tcp/4001/p2p/${userWallet.address.toLowerCase()}`;
    const storageMB = 128;
    
    console.log("Endpoint:", endpoint);
    console.log("Storage:", storageMB, "MB");
    
    // Get gas price
    const feeData = await provider.getFeeData();
    const gasPrice = feeData.gasPrice * 120n / 100n; // +20%
    console.log("Gas price:", ethers.formatUnits(gasPrice, "gwei"), "gwei");
    
    // Send transaction
    const tx = await registry["registerAsRelay(string,uint256)"](endpoint, storageMB, {
        gasLimit: 500000,
        gasPrice: gasPrice
    });
    
    console.log("Transaction hash:", tx.hash);
    console.log("Waiting for confirmation...");
    
    const receipt = await tx.wait();
    console.log("✅ Confirmed in block:", receipt.blockNumber);
    console.log("Gas used:", receipt.gasUsed.toString());
    
    // Verify
    const newRelayNode = await registry.relayNodes(userWallet.address);
    console.log("\n--- Verification ---");
    console.log("Is Active:", newRelayNode.isActive);
    console.log("Endpoint:", newRelayNode.endpoint);
    console.log("Staked:", ethers.formatEther(newRelayNode.stakedAmount), "MCT");
    
    console.log("\n🎉 Successfully registered as relay node!");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error("Error:", error.message);
        if (error.data) {
            console.error("Error data:", error.data);
        }
        process.exit(1);
    });
