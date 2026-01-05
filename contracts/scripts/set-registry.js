const hre = require("hardhat");

async function main() {
  const MCT_TOKEN_PROXY = "0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE";
  const REGISTRY_PROXY = "0x4f8D4955F370881B05b68D2344345E749d8632e3";

  console.log("Setting registry contract on MCT Token...");
  console.log("MCT Token:", MCT_TOKEN_PROXY);
  console.log("Registry:", REGISTRY_PROXY);

  // Get the MCT Token contract
  const MCTToken = await hre.ethers.getContractAt("MCTToken", MCT_TOKEN_PROXY);

  // Check current registry
  try {
    const currentRegistry = await MCTToken.registryContract();
    console.log("Current registry:", currentRegistry);
  } catch (e) {
    console.log("Could not read current registry:", e.message);
  }

  // Set the registry contract
  console.log("\nSetting registry contract...");
  const tx = await MCTToken.setRegistryContract(REGISTRY_PROXY);
  console.log("Transaction hash:", tx.hash);
  
  await tx.wait();
  console.log("Transaction confirmed!");

  // Verify
  const newRegistry = await MCTToken.registryContract();
  console.log("New registry:", newRegistry);
  console.log("\nDone! Registry is now set correctly.");
}

main()
  .then(() => process.exit(0))
  .catch((error) => {
    console.error(error);
    process.exit(1);
  });
