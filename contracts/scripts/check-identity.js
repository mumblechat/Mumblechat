const { ethers } = require("hardhat");
async function main() {
    const registry = await ethers.getContractAt("MumbleChatRegistry", "0x4f8D4955F370881B05b68D2344345E749d8632e3");
    
    // Surface Duo
    const duo = "0x3bb36dba6ca302362056505c95d1aa4865b29b47";
    const duoIdentity = await registry.identities(duo);
    console.log("=== Surface Duo (0x3BB36dba...) ===");
    console.log("Registered:", duoIdentity.isActive);
    console.log("pubKeyX:", duoIdentity.publicKeyX);
    
    // S20 FE
    const s20 = "0xac59cea3e124ce70a7d88b8ba4f3e3325acb9dc7";
    const s20Identity = await registry.identities(s20);
    console.log("\n=== S20 FE (0xAC59CEA3...) ===");
    console.log("Registered:", s20Identity.isActive);
    console.log("pubKeyX:", s20Identity.publicKeyX);
}
main().catch(console.error);
