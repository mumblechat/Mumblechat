const { ethers } = require("hardhat");

async function main() {
    const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    
    console.log("Testing MumbleChatRegistry registration...\n");
    
    // Get signer
    const [signer] = await ethers.getSigners();
    console.log("Signer address:", signer.address);
    
    // Get the contract
    const Registry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = Registry.attach(REGISTRY_PROXY);
    
    // Check if signer is already registered
    console.log(`\nChecking if ${signer.address} is already registered...`);
    const existingIdentity = await registry.identities(signer.address);
    
    if (existingIdentity.isActive) {
        console.log("✅ Already registered!");
        console.log("Identity:", {
            publicKeyX: existingIdentity.publicKeyX,
            registeredAt: existingIdentity.registeredAt.toString(),
            displayName: existingIdentity.displayName
        });
        return;
    }
    
    console.log("❌ Not registered yet. Attempting registration...\n");
    
    // Create a test public key (32 bytes)
    const testPublicKey = ethers.randomBytes(32);
    const publicKeyHex = ethers.hexlify(testPublicKey);
    console.log("Public key:", publicKeyHex);
    
    // Encode the function call manually
    const calldata = registry.interface.encodeFunctionData("register", [
        publicKeyHex,
        "TestRegistration"
    ]);
    console.log("Calldata:", calldata);
    
    // Register
    console.log("\nSending register transaction...");
    
    try {
        const tx = await signer.sendTransaction({
            to: REGISTRY_PROXY,
            data: calldata,
            gasLimit: 250000
        });
        
        console.log("Transaction hash:", tx.hash);
        console.log("Waiting for confirmation...");
        
        const receipt = await tx.wait();
        console.log("Transaction confirmed in block:", receipt.blockNumber);
        console.log("Gas used:", receipt.gasUsed.toString());
        
        // Verify registration
        const newIdentity = await registry.identities(signer.address);
        console.log("\n✅ Registration successful!");
        console.log("Identity:", {
            publicKeyX: newIdentity.publicKeyX,
            registeredAt: newIdentity.registeredAt.toString(),
            isActive: newIdentity.isActive,
            displayName: newIdentity.displayName
        });
        
        // Get total users
        const totalUsers = await registry.totalUsers();
        console.log(`\nTotal registered users: ${totalUsers.toString()}`);
        
    } catch (error) {
        console.error("\n❌ Registration failed!");
        console.error("Error:", error.message);
        if (error.data) {
            console.error("Error data:", error.data);
        }
    }
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
