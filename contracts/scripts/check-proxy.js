const { ethers } = require("hardhat");

async function main() {
    const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    const REGISTRY_IMPL = "0xC69C387a67324A08d1410aEB770dB0AC18c9ad15";
    
    console.log("Checking MumbleChatRegistry proxy status...\n");
    
    // Get provider
    const provider = ethers.provider;
    
    // Check proxy has code
    const proxyCode = await provider.getCode(REGISTRY_PROXY);
    console.log("Proxy has code:", proxyCode.length > 2 ? "YES" : "NO");
    console.log("Proxy code length:", proxyCode.length);
    
    // Check impl has code  
    const implCode = await provider.getCode(REGISTRY_IMPL);
    console.log("Implementation has code:", implCode.length > 2 ? "YES" : "NO");
    console.log("Implementation code length:", implCode.length);
    
    // Get the contract via proxy
    const Registry = await ethers.getContractFactory("MumbleChatRegistry");
    const registry = Registry.attach(REGISTRY_PROXY);
    
    // Try reading some state
    console.log("\n--- Reading contract state ---");
    
    try {
        const totalUsers = await registry.totalUsers();
        console.log("totalUsers:", totalUsers.toString());
    } catch (e) {
        console.log("totalUsers error:", e.message);
    }
    
    try {
        const minRelayStake = await registry.minRelayStake();
        console.log("minRelayStake:", ethers.formatEther(minRelayStake), "MCT");
    } catch (e) {
        console.log("minRelayStake error:", e.message);
    }
    
    try {
        const mctToken = await registry.mctToken();
        console.log("mctToken:", mctToken);
    } catch (e) {
        console.log("mctToken error:", e.message);
    }
    
    try {
        const owner = await registry.owner();
        console.log("owner:", owner);
    } catch (e) {
        console.log("owner error:", e.message);
    }
    
    // Check EIP-1967 implementation slot
    console.log("\n--- Checking proxy implementation slot ---");
    const IMPLEMENTATION_SLOT = "0x360894a13ba1a3210667c828492db98dca3e2076cc3735a920a3ca505d382bbc";
    const implSlot = await provider.getStorage(REGISTRY_PROXY, IMPLEMENTATION_SLOT);
    console.log("Implementation slot raw:", implSlot);
    
    // Parse as address
    const implAddress = "0x" + implSlot.slice(-40);
    console.log("Implementation address from slot:", implAddress);
    console.log("Expected implementation:", REGISTRY_IMPL);
    console.log("Match:", implAddress.toLowerCase() === REGISTRY_IMPL.toLowerCase() ? "YES" : "NO");
    
    // Estimate gas for a register call
    console.log("\n--- Estimating gas for register ---");
    const testKey = ethers.zeroPadBytes("0x1234567890abcdef1234567890abcdef", 32);
    try {
        const gasEstimate = await registry.register.estimateGas(testKey, "TestUser");
        console.log("Gas estimate:", gasEstimate.toString());
    } catch (e) {
        console.log("Gas estimate error:", e.message);
        if (e.data) {
            console.log("Error data:", e.data);
        }
    }
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
