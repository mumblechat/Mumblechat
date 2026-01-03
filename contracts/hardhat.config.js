require("@nomicfoundation/hardhat-toolbox");
require("@openzeppelin/hardhat-upgrades");
require("dotenv").config();

/** @type import('hardhat/config').HardhatUserConfig */
module.exports = {
  defaultNetwork: "ramestta",

  solidity: {
    version: "0.8.22",
    settings: {
      optimizer: {
        enabled: true,
        runs: 200
      },
      viaIR: true
    }
  },
  networks: {
    ramestta: {
      url: process.env.RAMESTTA_RPC_URL || "https://blockchain.ramestta.com",
      chainId: 1370,
      accounts: process.env.PRIVATE_KEY ? [process.env.PRIVATE_KEY] : [],
      gasPrice: 20000000000, // 20 gwei
    },
    localhost: {
      url: "http://127.0.0.1:8545",
      chainId: 31337,
    },
    hardhat: {
      chainId: 31337,
    }
  },
  etherscan: {
    apiKey: {
      ramestta: process.env.RAMASCAN_API_KEY || "abc"
    },
    customChains: [
      {
        network: "ramestta",
        chainId: 1370,
        urls: {
          apiURL: "https://latest-backendapi.ramascan.com/api/v1",
          browserURL: "https://ramascan.com"
        }
      }
    ]
  },
  sourcify: {
    enabled: false
  },
  paths: {
    sources: "./src",
    tests: "./test",
    cache: "./cache",
    artifacts: "./artifacts"
  }
};
