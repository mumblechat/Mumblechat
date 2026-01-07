const { ethers, run } = require("hardhat");
const fs = require("fs");
const path = require("path");

async function main() {
    const address = "0xE12fc7F1085b0ee61Ad0F85BE8a8D5cf69896858";
    
    console.log("Attempting to verify Registry at:", address);
    
    // Get deployed bytecode
    const provider = ethers.provider;
    const deployedBytecode = await provider.getCode(address);
    console.log("Deployed bytecode length:", deployedBytecode.length);
    
    // Get local artifact bytecode
    const artifact = JSON.parse(fs.readFileSync("./artifacts/src/MumbleChatRegistry.sol/MumbleChatRegistry.json"));
    console.log("Local artifact bytecode length:", artifact.deployedBytecode.length);
    
    // Compare
    if (deployedBytecode === artifact.deployedBytecode) {
        console.log("✅ Bytecodes match!");
    } else {
        console.log("❌ Bytecodes don't match");
        console.log("Deployed starts with:", deployedBytecode.substring(0, 100));
        console.log("Local starts with:", artifact.deployedBytecode.substring(0, 100));
    }
    
    // Try verification anyway
    try {
        await run("verify:verify", {
            address: address,
            constructorArguments: [],
            contract: "src/MumbleChatRegistry.sol:MumbleChatRegistry"
        });
        console.log("✅ Verified!");
    } catch (e) {
        console.log("Verification error:", e.message);
    }
}

main().catch(console.error);
