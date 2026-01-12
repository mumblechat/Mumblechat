const { ethers } = require('ethers');

const RPC_URL = 'https://blockchain.ramestta.com';
const PRIVATE_KEY = 'deec7d287996f966385cb5977200083864c4282410a82d7ae57f880e860665e0';
const MCT_TOKEN = '0xEfD7B65676FCD4b6d242CbC067C2470df19df1dE';
const TO_ADDRESS = '0x33116e4e889338f1C907a50812beA4F49c6b6B32';
const AMOUNT = '101'; // 101 MCT

const ERC20_ABI = [
    'function transfer(address to, uint256 amount) returns (bool)',
    'function balanceOf(address) view returns (uint256)',
    'function decimals() view returns (uint8)'
];

async function main() {
    const provider = new ethers.JsonRpcProvider(RPC_URL);
    const wallet = new ethers.Wallet(PRIVATE_KEY, provider);
    
    console.log('Deployer Address:', wallet.address);
    
    const mct = new ethers.Contract(MCT_TOKEN, ERC20_ABI, wallet);
    
    const decimals = await mct.decimals();
    const balance = await mct.balanceOf(wallet.address);
    console.log('MCT Balance:', ethers.formatUnits(balance, decimals), 'MCT');
    
    const amount = ethers.parseUnits(AMOUNT, decimals);
    console.log('Sending', AMOUNT, 'MCT to', TO_ADDRESS);
    
    const tx = await mct.transfer(TO_ADDRESS, amount);
    console.log('Transaction hash:', tx.hash);
    
    const receipt = await tx.wait();
    console.log('✅ Transaction confirmed! Block:', receipt.blockNumber);
    
    const newBalance = await mct.balanceOf(TO_ADDRESS);
    console.log('Recipient new balance:', ethers.formatUnits(newBalance, decimals), 'MCT');
}

main().catch(console.error);
