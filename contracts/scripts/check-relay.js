const { ethers } = require("hardhat");
async function main() {
    const registry = await ethers.getContractAt("MumbleChatRegistry", "0x4f8D4955F370881B05b68D2344345E749d8632e3");
    
    // Check S20 FE as relay
    const s20 = "0xac59cea3e124ce70a7d88b8ba4f3e3325acb9dc7";
    const relayNode = await registry.relayNodes(s20);
    console.log("=== S20 FE Relay Status ===");
    console.log("Endpoint:", relayNode.endpoint);
    console.log("Is Active:", relayNode.isActive);
    console.log("Staked:", ethers.formatEther(relayNode.stakedAmount), "MCT");
    console.log("Messages Relayed:", relayNode.messagesRelayed.toString());
    
    // Get all active relay nodes
    const activeRelays = await registry.getActiveRelayNodes();
    console.log("\n=== Active Relay Nodes ===");
    console.log("Count:", activeRelays.length);
    for (let i = 0; i < activeRelays.length; i++) {
        const addr = activeRelays[i];
        const node = await registry.relayNodes(addr);
        console.log(`Relay ${i+1}: ${addr}`);
        console.log(`  Endpoint: ${node.endpoint}`);
    }
}
main().catch(console.error);
