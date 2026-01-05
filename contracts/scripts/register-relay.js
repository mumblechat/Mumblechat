const { ethers } = require("hardhat");

// User's private key - need to get this from the user or use a test account
// For testing, we'll use deployer to approve on behalf

async function main() {
    const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
    const USER_ADDRESS = "0xAC59CEA3E124CE70A7d88b8Ba4f3e3325Acb9DC7";
    
    // User's private key from seed phrase - first account
    // This is the user's wallet key to sign the transaction
    const USER_PRIVATE_KEY = process.env.USER_KEY;
    
    console.log("=== Registering as Relay Node ===\n");
    
    // Get deployer
    const [deployer] = await ethers.getSigners();
    console.log("Deployer address:", deployer.address);
    
    // Create user signer if we have their key
    let userSigner;
    if (USER_PRIVATE_KEY) {
        userSigner = new ethers.Wallet(USER_PRIVATE_KEY, deployer.provider);
        console.log("User signer address:", userSigner.address);
    }
    
    // Get contracts
    const Registry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = Registry.attach(REGISTRY_PROXY);
    
    const MCT = await ethers.getContractFactory("MCTToken");
    const mct = MCT.attach(MCT_TOKEN);
    
    // Check user's identity
    console.log("\n--- Checking User State ---");
    const identity = await registry.identities(USER_ADDRESS);
    console.log("Identity registered:", identity.isActive);
    
    if (!identity.isActive) {
        console.log("❌ User must register identity first!");
        return;
    }
    
    // Check if already a relay
    const relayNode = await registry.relayNodes(USER_ADDRESS);
    console.log("Already a relay:", relayNode.isActive);
    
    if (relayNode.isActive) {
        console.log("✅ User is already a relay node!");
        console.log("Endpoint:", relayNode.endpoint);
        console.log("Staked:", ethers.formatEther(relayNode.stakedAmount), "MCT");
        return;
    }
    
    // Check MCT balance
    const balance = await mct.balanceOf(USER_ADDRESS);
    console.log("MCT Balance:", ethers.formatEther(balance), "MCT");
    
    // Check minRelayStake
    const minStake = await registry.minRelayStake();
    console.log("Min Stake Required:", ethers.formatEther(minStake), "MCT");
    
    if (balance < minStake) {
        console.log("❌ Insufficient MCT balance!");
        console.log("Need:", ethers.formatEther(minStake), "MCT");
        console.log("Have:", ethers.formatEther(balance), "MCT");
        
        // Transfer MCT from deployer
        console.log("\nTransferring MCT from deployer...");
        const needed = minStake - balance;
        const tx = await mct.transfer(USER_ADDRESS, needed);
        await tx.wait();
        console.log("✅ Transferred", ethers.formatEther(needed), "MCT");
    }
    
    // Check allowance
    const allowance = await mct.allowance(USER_ADDRESS, REGISTRY_PROXY);
    console.log("Current Allowance:", ethers.formatEther(allowance), "MCT");
    
    if (allowance < minStake) {
        console.log("\n⚠️ Allowance is insufficient!");
        console.log("User needs to approve from the app or we need user's private key");
        console.log("Required:", ethers.formatEther(minStake), "MCT");
        console.log("Current:", ethers.formatEther(allowance), "MCT");
    }
    
    // Check blacklist
    const isBlacklisted = await registry.isBlacklisted(USER_ADDRESS);
    console.log("Is Blacklisted:", isBlacklisted);
    
    // Check registry contract on MCT
    const registryOnMct = await mct.registryContract();
    console.log("\n--- MCT Token Config ---");
    console.log("Registry Contract on MCT:", registryOnMct);
    console.log("Expected Registry:", REGISTRY_PROXY);
    console.log("Match:", registryOnMct.toLowerCase() === REGISTRY_PROXY.toLowerCase());
    
    console.log("\n--- Summary ---");
    console.log("Identity: ✅ Registered");
    console.log("Relay: ❌ Not registered yet");
    console.log("MCT Balance:", ethers.formatEther(balance), "MCT");
    console.log("Allowance:", ethers.formatEther(allowance), "MCT");
    console.log("Min Stake:", ethers.formatEther(minStake), "MCT");
    
    if (allowance >= minStake) {
        console.log("\n✅ All preconditions met! User can register from app.");
        console.log("If still failing, there might be an issue with the transaction itself.");
    } else {
        console.log("\n⚠️ User needs to approve MCT spending first from the app.");
    }
    
    // Try to simulate the registerAsRelay call using staticCall
    console.log("\n--- Simulating registerAsRelay call ---");
    
    // Get ABI for the call
    const endpoint = "relay.mumblechat.io";
    const storageMB = 50;
    
    // Encode the function call
    const calldata = registry.interface.encodeFunctionData("registerAsRelay(string,uint256)", [endpoint, storageMB]);
    console.log("Calldata:", calldata);
    
    // Try to estimate gas as the user
    console.log("\nTrying eth_call as user...");
    try {
        const result = await deployer.provider.call({
            from: USER_ADDRESS,
            to: REGISTRY_PROXY,
            data: calldata
        });
        console.log("Call succeeded! Result:", result);
    } catch (error) {
        console.log("❌ Call failed!");
        console.log("Error:", error.message);
        if (error.data) {
            console.log("Error data:", error.data);
        }
    }
    
    // Try estimate gas
    console.log("\nTrying estimateGas as user...");
    try {
        const gasEstimate = await deployer.provider.estimateGas({
            from: USER_ADDRESS,
            to: REGISTRY_PROXY,
            data: calldata
        });
        console.log("Gas estimate:", gasEstimate.toString());
        
        // If we have user's key, actually send the transaction
        if (userSigner) {
            console.log("\n--- Sending registerAsRelay transaction ---");
            const tx = await userSigner.sendTransaction({
                to: REGISTRY_PROXY,
                data: calldata,
                gasLimit: 500000n
            });
            console.log("TX Hash:", tx.hash);
            console.log("Waiting for confirmation...");
            const receipt = await tx.wait();
            console.log("✅ Transaction confirmed!");
            console.log("Block:", receipt.blockNumber);
            console.log("Gas Used:", receipt.gasUsed.toString());
            console.log("Status:", receipt.status);
            
            // Verify registration
            const relayNode = await registry.relayNodes(USER_ADDRESS);
            console.log("\nRelay Node Status:", relayNode.isActive ? "✅ Active" : "❌ Inactive");
            if (relayNode.isActive) {
                console.log("Endpoint:", relayNode.endpoint);
                console.log("Staked:", ethers.formatEther(relayNode.stakedAmount), "MCT");
            }
        }
    } catch (error) {
        console.log("❌ Gas estimation failed!");
        console.log("Error:", error.message);
        
        // Try to decode revert reason
        if (error.data) {
            try {
                const decodedError = registry.interface.parseError(error.data);
                console.log("Decoded error:", decodedError);
            } catch (e) {
                // Try standard revert string
                if (error.data.startsWith("0x08c379a0")) {
                    const reason = ethers.AbiCoder.defaultAbiCoder().decode(
                        ["string"],
                        "0x" + error.data.slice(10)
                    );
                    console.log("Revert reason:", reason[0]);
                }
            }
        }
    }
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
