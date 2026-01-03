const { ethers } = require("hardhat");

async function main() {
    const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    const TEST_ADDRESS = "0xb5d1327337F3901F03483F98c90d10d5E2aCB1d2";
    
    console.log("Testing MumbleChatRegistry contract...\n");
    
    // Get the contract
    const Registry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = Registry.attach(REGISTRY_PROXY);
    
    // Check if test address is registered
    console.log(`Checking if ${TEST_ADDRESS} is registered...`);
    
    try {
        const identity = await registry.identities(TEST_ADDRESS);
        console.log("Identity data:", {
            publicKeyX: identity.publicKeyX,
            registeredAt: identity.registeredAt.toString(),
            isActive: identity.isActive,
            displayName: identity.displayName
        });
        
        if (identity.isActive) {
            console.log("\n✅ Address IS registered!");
        } else {
            console.log("\n❌ Address is NOT registered.");
        }
    } catch (error) {
        console.error("Error reading identity:", error.message);
    }
    
    // Try to check isRegistered function
    try {
        const isRegistered = await registry.isRegistered(TEST_ADDRESS);
        console.log(`\nisRegistered(${TEST_ADDRESS}): ${isRegistered}`);
    } catch (error) {
        console.error("Error checking isRegistered:", error.message);
    }
    
    // Get total users
    try {
        const totalUsers = await registry.totalUsers();
        console.log(`\nTotal registered users: ${totalUsers.toString()}`);
    } catch (error) {
        console.error("Error getting totalUsers:", error.message);
    }
    
    // Get full identity
    try {
        const identity = await registry.getIdentity(TEST_ADDRESS);
        console.log("\nFull identity from getIdentity():", {
            publicKeyX: identity.publicKeyX,
            registeredAt: identity.registeredAt.toString(),
            lastUpdated: identity.lastUpdated.toString(),
            isActive: identity.isActive,
            displayName: identity.displayName
        });
    } catch (error) {
        console.error("Error getting full identity:", error.message);
    }
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
