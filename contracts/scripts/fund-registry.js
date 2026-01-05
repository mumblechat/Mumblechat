const { ethers } = require("hardhat");

async function main() {
    const MCT_TOKEN = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
    const REGISTRY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";
    
    const [deployer] = await ethers.getSigners();
    console.log("Deployer:", deployer.address);
    
    const MCT = await ethers.getContractFactory("MCTToken");
    const mct = MCT.attach(MCT_TOKEN);
    
    console.log("\n--- Current Balances ---");
    console.log("Registry:", ethers.formatEther(await mct.balanceOf(REGISTRY)), "MCT");
    console.log("Deployer:", ethers.formatEther(await mct.balanceOf(deployer.address)), "MCT");
    
    // Transfer 1 MCT to registry
    const amount = ethers.parseEther("1");
    console.log("\nTransferring 1 MCT to Registry...");
    
    const tx = await mct.transfer(REGISTRY, amount);
    console.log("TX:", tx.hash);
    await tx.wait();
    
    console.log("\n--- Updated Balances ---");
    console.log("Registry:", ethers.formatEther(await mct.balanceOf(REGISTRY)), "MCT");
    console.log("\n✅ Done! Registry now has enough MCT to return stakes.");
}

main()
    .then(() => process.exit(0))
    .catch((error) => {
        console.error(error);
        process.exit(1);
    });
