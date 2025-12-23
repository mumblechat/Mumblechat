/**
 * RamaPay Chrome Extension - Core Wallet Module
 * Handles HD wallet creation, key management, and encryption
 */

import { ethers } from 'ethers';
import QRCode from 'qrcode';

// Export QR code generator
export async function generateQRCode(text, options = {}) {
  const defaultOptions = {
    width: 200,
    margin: 2,
    color: {
      dark: '#000000',
      light: '#ffffff'
    },
    errorCorrectionLevel: 'M'
  };
  
  try {
    console.log('wallet.js generateQRCode called with:', text);
    const merged = { ...defaultOptions, ...options };
    const dataUrl = await QRCode.toDataURL(text, merged);
    console.log('wallet.js QR generated, length:', dataUrl?.length);
    return dataUrl;
  } catch (error) {
    console.error('wallet.js QR generation error:', error);
    throw error; // Re-throw to let caller handle
  }
}

// ============================================
// NETWORK CONFIGURATIONS
// All available networks from RamaPay Android app
// Ramestta is the default and primary network
// ============================================

// Network Icons - CDN URLs for major blockchain networks
export const NETWORK_ICONS = {
  // Ramestta - use local icon
  ramestta: 'icons/rama.png',
  // Ethereum ecosystem
  ethereum: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png',
  etc: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/classic/info/logo.png',
  // Layer 2s
  polygon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/polygon/info/logo.png',
  arbitrum: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/arbitrum/info/logo.png',
  optimism: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/optimism/info/logo.png',
  base: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/base/info/logo.png',
  linea: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/linea/info/logo.png',
  mantle: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/mantle/info/logo.png',
  // Other chains
  binance: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png',
  avalanche: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/avalanchec/info/logo.png',
  fantom: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/fantom/info/logo.png',
  gnosis: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/xdai/info/logo.png',
  cronos: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/cronos/info/logo.png',
  klaytn: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/klaytn/info/logo.png',
  aurora: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/aurora/info/logo.png',
  iotex: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/iotex/info/logo.png',
  rootstock: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/rootstock/info/logo.png',
  okx: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/okc/info/logo.png',
  palm: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/palm/info/logo.png',
  milkomeda: 'https://raw.githubusercontent.com/milkomeda-com/assets/main/milkomeda-logo.png',
  mint: 'https://mint.club/assets/logo/mint-logo.png'
};

// All available networks (full list from Android app)
export const ALL_NETWORKS = {
  // ============================================
  // RAMESTTA NETWORKS (Primary - Default Enabled)
  // ============================================
  ramestta_mainnet: {
    chainId: 1370,
    chainIdHex: '0x55a',
    name: 'Ramestta Mainnet',
    symbol: 'RAMA',
    decimals: 18,
    rpcUrl: 'https://blockchain.ramestta.com',
    rpcUrls: [
      'https://blockchain.ramestta.com',
      'https://blockchain2.ramestta.com',
      'https://blockchain.rfrm.io'
    ],
    explorerUrl: 'https://ramascan.com',
    isTestnet: false,
    isDefault: true,
    category: 'ramestta',
    icon: 'icons/rama.png'
  },
  ramestta_testnet: {
    chainId: 1369,
    chainIdHex: '0x559',
    name: 'Ramestta Testnet',
    symbol: 'RAMA',
    decimals: 18,
    rpcUrl: 'https://testnet.ramestta.com',
    rpcUrls: [
      'https://testnet.ramestta.com',
      'https://testnet.rfrm.io'
    ],
    explorerUrl: 'https://testnet.ramascan.com',
    isTestnet: true,
    isDefault: true,
    category: 'ramestta',
    icon: 'icons/rama.png'
  },

  // ============================================
  // ETHEREUM NETWORKS
  // ============================================
  ethereum_mainnet: {
    chainId: 1,
    chainIdHex: '0x1',
    name: 'Ethereum Mainnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://eth.llamarpc.com',
    rpcUrls: [
      'https://eth.llamarpc.com',
      'https://ethereum.publicnode.com',
      'https://rpc.payload.de',
      'https://eth-mainnet.public.blastapi.io'
    ],
    explorerUrl: 'https://etherscan.io',
    isTestnet: false,
    category: 'ethereum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png'
  },
  ethereum_classic: {
    chainId: 61,
    chainIdHex: '0x3d',
    name: 'Ethereum Classic',
    symbol: 'ETC',
    decimals: 18,
    rpcUrl: 'https://www.ethercluster.com/etc',
    rpcUrls: ['https://www.ethercluster.com/etc', 'https://etc.rivet.link'],
    explorerUrl: 'https://blockscout.com/etc/mainnet',
    isTestnet: false,
    category: 'ethereum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/classic/info/logo.png'
  },
  sepolia_testnet: {
    chainId: 11155111,
    chainIdHex: '0xaa36a7',
    name: 'Sepolia Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://rpc.sepolia.org',
    rpcUrls: ['https://rpc.sepolia.org', 'https://sepolia.drpc.org'],
    explorerUrl: 'https://sepolia.etherscan.io',
    isTestnet: true,
    category: 'ethereum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png'
  },
  holesky_testnet: {
    chainId: 17000,
    chainIdHex: '0x4268',
    name: 'Holesky Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://rpc.holesky.ethpandaops.io',
    rpcUrls: ['https://rpc.holesky.ethpandaops.io', 'https://holesky.drpc.org'],
    explorerUrl: 'https://holesky.etherscan.io',
    isTestnet: true,
    category: 'ethereum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/ethereum/info/logo.png'
  },

  // ============================================
  // POLYGON NETWORKS
  // ============================================
  polygon_mainnet: {
    chainId: 137,
    chainIdHex: '0x89',
    name: 'Polygon Mainnet',
    symbol: 'POL',
    decimals: 18,
    rpcUrl: 'https://polygon-rpc.com',
    rpcUrls: [
      'https://polygon-rpc.com',
      'https://polygon.llamarpc.com',
      'https://polygon.lava.build'
    ],
    explorerUrl: 'https://polygonscan.com',
    isTestnet: false,
    category: 'polygon',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/polygon/info/logo.png'
  },
  polygon_amoy: {
    chainId: 80002,
    chainIdHex: '0x13882',
    name: 'Polygon Amoy Testnet',
    symbol: 'POL',
    decimals: 18,
    rpcUrl: 'https://rpc-amoy.polygon.technology',
    rpcUrls: ['https://rpc-amoy.polygon.technology', 'https://polygon-amoy.drpc.org'],
    explorerUrl: 'https://amoy.polygonscan.com',
    isTestnet: true,
    category: 'polygon',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/polygon/info/logo.png'
  },

  // ============================================
  // BINANCE SMART CHAIN
  // ============================================
  binance_mainnet: {
    chainId: 56,
    chainIdHex: '0x38',
    name: 'BNB Smart Chain',
    symbol: 'BNB',
    decimals: 18,
    rpcUrl: 'https://bsc-dataseed.binance.org',
    rpcUrls: [
      'https://bsc-dataseed.binance.org',
      'https://binance.llamarpc.com',
      'https://bsc-rpc.publicnode.com'
    ],
    explorerUrl: 'https://bscscan.com',
    isTestnet: false,
    category: 'binance',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png'
  },
  binance_testnet: {
    chainId: 97,
    chainIdHex: '0x61',
    name: 'BNB Smart Chain Testnet',
    symbol: 'tBNB',
    decimals: 18,
    rpcUrl: 'https://data-seed-prebsc-1-s1.binance.org:8545',
    rpcUrls: ['https://data-seed-prebsc-1-s1.binance.org:8545', 'https://bsc-testnet.drpc.org'],
    explorerUrl: 'https://testnet.bscscan.com',
    isTestnet: true,
    category: 'binance',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/smartchain/info/logo.png'
  },

  // ============================================
  // AVALANCHE NETWORKS
  // ============================================
  avalanche_mainnet: {
    chainId: 43114,
    chainIdHex: '0xa86a',
    name: 'Avalanche C-Chain',
    symbol: 'AVAX',
    decimals: 18,
    rpcUrl: 'https://api.avax.network/ext/bc/C/rpc',
    rpcUrls: [
      'https://api.avax.network/ext/bc/C/rpc',
      'https://avax.meowrpc.com',
      'https://avalanche.drpc.org'
    ],
    explorerUrl: 'https://snowtrace.io',
    isTestnet: false,
    category: 'avalanche',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/avalanchec/info/logo.png'
  },
  avalanche_fuji: {
    chainId: 43113,
    chainIdHex: '0xa869',
    name: 'Avalanche Fuji Testnet',
    symbol: 'AVAX',
    decimals: 18,
    rpcUrl: 'https://api.avax-test.network/ext/bc/C/rpc',
    rpcUrls: ['https://api.avax-test.network/ext/bc/C/rpc', 'https://avalanche-fuji.drpc.org'],
    explorerUrl: 'https://testnet.snowtrace.io',
    isTestnet: true,
    category: 'avalanche',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/avalanchec/info/logo.png'
  },

  // ============================================
  // ARBITRUM NETWORKS
  // ============================================
  arbitrum_mainnet: {
    chainId: 42161,
    chainIdHex: '0xa4b1',
    name: 'Arbitrum One',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://arb1.arbitrum.io/rpc',
    rpcUrls: [
      'https://arb1.arbitrum.io/rpc',
      'https://arbitrum.meowrpc.com',
      'https://rpc.ankr.com/arbitrum'
    ],
    explorerUrl: 'https://arbiscan.io',
    isTestnet: false,
    category: 'arbitrum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/arbitrum/info/logo.png'
  },
  arbitrum_sepolia: {
    chainId: 421614,
    chainIdHex: '0x66eee',
    name: 'Arbitrum Sepolia Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://arbitrum-sepolia.drpc.org',
    rpcUrls: ['https://arbitrum-sepolia.drpc.org', 'https://sepolia-rollup.arbitrum.io/rpc'],
    explorerUrl: 'https://sepolia.arbiscan.io',
    isTestnet: true,
    category: 'arbitrum',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/arbitrum/info/logo.png'
  },

  // ============================================
  // OPTIMISM NETWORKS
  // ============================================
  optimism_mainnet: {
    chainId: 10,
    chainIdHex: '0xa',
    name: 'Optimism',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://mainnet.optimism.io',
    rpcUrls: ['https://mainnet.optimism.io', 'https://optimism.drpc.org'],
    explorerUrl: 'https://optimistic.etherscan.io',
    isTestnet: false,
    category: 'optimism',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/optimism/info/logo.png'
  },

  // ============================================
  // BASE NETWORKS
  // ============================================
  base_mainnet: {
    chainId: 8453,
    chainIdHex: '0x2105',
    name: 'Base',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://base-rpc.publicnode.com',
    rpcUrls: ['https://base-rpc.publicnode.com', 'https://base.drpc.org'],
    explorerUrl: 'https://basescan.org',
    isTestnet: false,
    category: 'base',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/base/info/logo.png'
  },
  base_sepolia: {
    chainId: 84532,
    chainIdHex: '0x14a34',
    name: 'Base Sepolia Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://sepolia.base.org',
    rpcUrls: ['https://sepolia.base.org', 'https://base-sepolia-rpc.publicnode.com'],
    explorerUrl: 'https://sepolia.basescan.org',
    isTestnet: true,
    category: 'base',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/base/info/logo.png'
  },

  // ============================================
  // FANTOM NETWORKS
  // ============================================
  fantom_mainnet: {
    chainId: 250,
    chainIdHex: '0xfa',
    name: 'Fantom Opera',
    symbol: 'FTM',
    decimals: 18,
    rpcUrl: 'https://rpcapi.fantom.network',
    rpcUrls: ['https://rpcapi.fantom.network', 'https://rpc.fantom.network'],
    explorerUrl: 'https://ftmscan.com',
    isTestnet: false,
    category: 'fantom',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/fantom/info/logo.png'
  },
  fantom_testnet: {
    chainId: 4002,
    chainIdHex: '0xfa2',
    name: 'Fantom Testnet',
    symbol: 'FTM',
    decimals: 18,
    rpcUrl: 'https://rpc.testnet.fantom.network',
    rpcUrls: ['https://rpc.testnet.fantom.network'],
    explorerUrl: 'https://testnet.ftmscan.com',
    isTestnet: true,
    category: 'fantom',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/fantom/info/logo.png'
  },

  // ============================================
  // GNOSIS CHAIN
  // ============================================
  gnosis_mainnet: {
    chainId: 100,
    chainIdHex: '0x64',
    name: 'Gnosis Chain',
    symbol: 'xDAI',
    decimals: 18,
    rpcUrl: 'https://rpc.gnosischain.com',
    rpcUrls: ['https://rpc.gnosischain.com', '0xrpc.io/gno'],
    explorerUrl: 'https://gnosisscan.io',
    isTestnet: false,
    category: 'gnosis',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/xdai/info/logo.png'
  },

  // ============================================
  // CRONOS NETWORKS
  // ============================================
  cronos_mainnet: {
    chainId: 25,
    chainIdHex: '0x19',
    name: 'Cronos Mainnet',
    symbol: 'CRO',
    decimals: 18,
    rpcUrl: 'https://evm.cronos.org',
    rpcUrls: ['https://evm.cronos.org', 'https://cronos.drpc.org'],
    explorerUrl: 'https://cronoscan.com',
    isTestnet: false,
    category: 'cronos',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/cronos/info/logo.png'
  },
  cronos_testnet: {
    chainId: 338,
    chainIdHex: '0x152',
    name: 'Cronos Testnet',
    symbol: 'tCRO',
    decimals: 18,
    rpcUrl: 'https://evm-t3.cronos.org',
    rpcUrls: ['https://evm-t3.cronos.org', 'https://cronos-testnet.drpc.org'],
    explorerUrl: 'https://testnet.cronoscan.com',
    isTestnet: true,
    category: 'cronos',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/cronos/info/logo.png'
  },

  // ============================================
  // LINEA NETWORKS
  // ============================================
  linea_mainnet: {
    chainId: 59144,
    chainIdHex: '0xe708',
    name: 'Linea Mainnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://rpc.linea.build',
    rpcUrls: ['https://rpc.linea.build', 'https://linea.drpc.org'],
    explorerUrl: 'https://lineascan.build',
    isTestnet: false,
    category: 'linea',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/linea/info/logo.png'
  },
  linea_testnet: {
    chainId: 59141,
    chainIdHex: '0xe705',
    name: 'Linea Sepolia Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://rpc.sepolia.linea.build',
    rpcUrls: ['https://rpc.sepolia.linea.build'],
    explorerUrl: 'https://sepolia.lineascan.build',
    isTestnet: true,
    category: 'linea',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/linea/info/logo.png'
  },

  // ============================================
  // MANTLE NETWORKS
  // ============================================
  mantle_mainnet: {
    chainId: 5000,
    chainIdHex: '0x1388',
    name: 'Mantle',
    symbol: 'MNT',
    decimals: 18,
    rpcUrl: 'https://rpc.mantle.xyz',
    rpcUrls: ['https://rpc.mantle.xyz', 'https://mantle-mainnet.public.blastapi.io'],
    explorerUrl: 'https://explorer.mantle.xyz',
    isTestnet: false,
    category: 'mantle',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/mantle/info/logo.png'
  },
  mantle_testnet: {
    chainId: 5003,
    chainIdHex: '0x138b',
    name: 'Mantle Sepolia Testnet',
    symbol: 'MNT',
    decimals: 18,
    rpcUrl: 'https://rpc.sepolia.mantle.xyz',
    rpcUrls: ['https://rpc.sepolia.mantle.xyz'],
    explorerUrl: 'https://sepolia.mantlescan.xyz',
    isTestnet: true,
    category: 'mantle',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/mantle/info/logo.png'
  },

  // ============================================
  // KLAYTN / KAIA NETWORKS
  // ============================================
  klaytn_mainnet: {
    chainId: 8217,
    chainIdHex: '0x2019',
    name: 'Kaia Mainnet',
    symbol: 'KAIA',
    decimals: 18,
    rpcUrl: 'https://klaytn.blockpi.network/v1/rpc/public',
    rpcUrls: ['https://klaytn.blockpi.network/v1/rpc/public'],
    explorerUrl: 'https://scope.klaytn.com',
    isTestnet: false,
    category: 'klaytn',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/klaytn/info/logo.png'
  },
  klaytn_baobab: {
    chainId: 1001,
    chainIdHex: '0x3e9',
    name: 'Kaia Kairos Testnet',
    symbol: 'KAIA',
    decimals: 18,
    rpcUrl: 'https://klaytn-baobab.blockpi.network/v1/rpc/public',
    rpcUrls: ['https://klaytn-baobab.blockpi.network/v1/rpc/public'],
    explorerUrl: 'https://baobab.scope.klaytn.com',
    isTestnet: true,
    category: 'klaytn',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/klaytn/info/logo.png'
  },

  // ============================================
  // AURORA NETWORKS
  // ============================================
  aurora_mainnet: {
    chainId: 1313161554,
    chainIdHex: '0x4e454152',
    name: 'Aurora Mainnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://mainnet.aurora.dev',
    rpcUrls: ['https://mainnet.aurora.dev', 'https://aurora.drpc.org'],
    explorerUrl: 'https://aurorascan.dev',
    isTestnet: false,
    category: 'aurora',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/aurora/info/logo.png'
  },
  aurora_testnet: {
    chainId: 1313161555,
    chainIdHex: '0x4e454153',
    name: 'Aurora Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://testnet.aurora.dev',
    rpcUrls: ['https://testnet.aurora.dev'],
    explorerUrl: 'https://testnet.aurorascan.dev',
    isTestnet: true,
    category: 'aurora',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/aurora/info/logo.png'
  },

  // ============================================
  // IOTEX NETWORKS
  // ============================================
  iotex_mainnet: {
    chainId: 4689,
    chainIdHex: '0x1251',
    name: 'IoTeX Mainnet',
    symbol: 'IOTX',
    decimals: 18,
    rpcUrl: 'https://babel-api.mainnet.iotex.io',
    rpcUrls: ['https://babel-api.mainnet.iotex.io'],
    explorerUrl: 'https://iotexscan.io',
    isTestnet: false,
    category: 'iotex',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/iotex/info/logo.png'
  },
  iotex_testnet: {
    chainId: 4690,
    chainIdHex: '0x1252',
    name: 'IoTeX Testnet',
    symbol: 'IOTX',
    decimals: 18,
    rpcUrl: 'https://babel-api.testnet.iotex.io',
    rpcUrls: ['https://babel-api.testnet.iotex.io'],
    explorerUrl: 'https://testnet.iotexscan.io',
    isTestnet: true,
    category: 'iotex',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/iotex/info/logo.png'
  },

  // ============================================
  // ROOTSTOCK (RSK) NETWORKS
  // ============================================
  rootstock_mainnet: {
    chainId: 30,
    chainIdHex: '0x1e',
    name: 'Rootstock Mainnet',
    symbol: 'RBTC',
    decimals: 18,
    rpcUrl: 'https://public-node.rsk.co',
    rpcUrls: ['https://public-node.rsk.co'],
    explorerUrl: 'https://explorer.rsk.co',
    isTestnet: false,
    category: 'rootstock',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/rootstock/info/logo.png'
  },
  rootstock_testnet: {
    chainId: 31,
    chainIdHex: '0x1f',
    name: 'Rootstock Testnet',
    symbol: 'tRBTC',
    decimals: 18,
    rpcUrl: 'https://public-node.testnet.rsk.co',
    rpcUrls: ['https://public-node.testnet.rsk.co'],
    explorerUrl: 'https://explorer.testnet.rsk.co',
    isTestnet: true,
    category: 'rootstock',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/rootstock/info/logo.png'
  },

  // ============================================
  // OKX CHAIN
  // ============================================
  okx_mainnet: {
    chainId: 66,
    chainIdHex: '0x42',
    name: 'OKXChain Mainnet',
    symbol: 'OKT',
    decimals: 18,
    rpcUrl: 'https://exchainrpc.okex.org',
    rpcUrls: ['https://exchainrpc.okex.org', 'https://oktc.drpc.org'],
    explorerUrl: 'https://www.oklink.com/oktc',
    isTestnet: false,
    category: 'okx',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/okc/info/logo.png'
  },

  // ============================================
  // PALM NETWORK
  // ============================================
  palm_mainnet: {
    chainId: 11297108109,
    chainIdHex: '0x2a15c308d',
    name: 'Palm Mainnet',
    symbol: 'PALM',
    decimals: 18,
    rpcUrl: 'https://palm-mainnet.public.blastapi.io',
    rpcUrls: ['https://palm-mainnet.public.blastapi.io'],
    explorerUrl: 'https://explorer.palm.io',
    isTestnet: false,
    category: 'palm',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/palm/info/logo.png'
  },
  palm_testnet: {
    chainId: 11297108099,
    chainIdHex: '0x2a15c3083',
    name: 'Palm Testnet',
    symbol: 'PALM',
    decimals: 18,
    rpcUrl: 'https://palm-testnet.public.blastapi.io',
    rpcUrls: ['https://palm-testnet.public.blastapi.io'],
    explorerUrl: 'https://explorer.palm-uat.xyz',
    isTestnet: true,
    category: 'palm',
    icon: 'https://raw.githubusercontent.com/trustwallet/assets/master/blockchains/palm/info/logo.png'
  },

  // ============================================
  // MILKOMEDA NETWORK
  // ============================================
  milkomeda_c1: {
    chainId: 2001,
    chainIdHex: '0x7d1',
    name: 'Milkomeda Cardano',
    symbol: 'milkADA',
    decimals: 18,
    rpcUrl: 'https://rpc-mainnet-cardano-evm.c1.milkomeda.com',
    rpcUrls: ['https://rpc-mainnet-cardano-evm.c1.milkomeda.com'],
    explorerUrl: 'https://explorer-mainnet-cardano-evm.c1.milkomeda.com',
    isTestnet: false,
    category: 'milkomeda',
    icon: 'https://raw.githubusercontent.com/milkomeda-com/assets/main/milkomeda-logo.png'
  },

  // ============================================
  // MINT NETWORK
  // ============================================
  mint_mainnet: {
    chainId: 185,
    chainIdHex: '0xb9',
    name: 'Mint Mainnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://global.rpc.mintchain.io',
    rpcUrls: ['https://global.rpc.mintchain.io', 'https://asia.rpc.mintchain.io'],
    explorerUrl: 'https://explorer.mintchain.io',
    isTestnet: false,
    category: 'mint',
    icon: 'https://mintchain.io/favicon.png'
  },
  mint_sepolia: {
    chainId: 1687,
    chainIdHex: '0x697',
    name: 'Mint Sepolia Testnet',
    symbol: 'ETH',
    decimals: 18,
    rpcUrl: 'https://sepolia-testnet-rpc.mintchain.io',
    rpcUrls: ['https://sepolia-testnet-rpc.mintchain.io'],
    explorerUrl: 'https://sepolia-testnet-explorer.mintchain.io',
    isTestnet: true,
    category: 'mint',
    icon: 'https://mintchain.io/favicon.png'
  }
};

// Default enabled networks (only Ramestta by default)
export const DEFAULT_ENABLED_NETWORKS = ['ramestta_mainnet', 'ramestta_testnet'];

// Current active networks (starts with defaults, user can add more)
export let NETWORKS = {};

// Initialize NETWORKS with defaults
Object.keys(ALL_NETWORKS).forEach(key => {
  if (DEFAULT_ENABLED_NETWORKS.includes(key)) {
    NETWORKS[key] = ALL_NETWORKS[key];
  }
});

/**
 * Enable a network from the pre-built list
 * @param {string} networkKey - Key of the network to enable
 */
export function enableNetwork(networkKey) {
  if (ALL_NETWORKS[networkKey]) {
    NETWORKS[networkKey] = ALL_NETWORKS[networkKey];
    return true;
  }
  return false;
}

/**
 * Disable a network (remove from active list)
 * @param {string} networkKey - Key of the network to disable
 */
export function disableNetwork(networkKey) {
  // Don't allow disabling Ramestta mainnet
  if (networkKey === 'ramestta_mainnet') {
    return false;
  }
  if (NETWORKS[networkKey] && !NETWORKS[networkKey].isCustom) {
    delete NETWORKS[networkKey];
    return true;
  }
  return false;
}

/**
 * Get all available networks (for settings/pre-built list)
 */
export function getAllAvailableNetworks() {
  return ALL_NETWORKS;
}

/**
 * Get enabled network keys
 */
export function getEnabledNetworkKeys() {
  return Object.keys(NETWORKS).filter(key => !key.startsWith('custom_'));
}

/**
 * Set enabled networks from array of keys
 */
export function setEnabledNetworks(networkKeys) {
  // Always include Ramestta mainnet
  if (!networkKeys.includes('ramestta_mainnet')) {
    networkKeys.unshift('ramestta_mainnet');
  }
  
  // Clear current built-in networks (keep custom)
  const customNetworks = {};
  Object.keys(NETWORKS).forEach(key => {
    if (key.startsWith('custom_')) {
      customNetworks[key] = NETWORKS[key];
    }
  });
  
  // Rebuild NETWORKS
  NETWORKS = { ...customNetworks };
  networkKeys.forEach(key => {
    if (ALL_NETWORKS[key]) {
      NETWORKS[key] = ALL_NETWORKS[key];
    }
  });
}

// BIP44 derivation path for Ethereum-compatible chains
const DERIVATION_PATH = "m/44'/60'/0'/0";

/**
 * Wallet Manager Class
 * Handles all wallet operations including creation, import, and transactions
 */
export class WalletManager {
  constructor() {
    this.currentWallet = null;
    this.currentNetwork = NETWORKS.ramestta_mainnet;
    this.provider = null;
    this.accounts = [];
  }

  /**
   * Initialize provider for the current network
   */
  async initProvider() {
    const rpcUrl = this.currentNetwork.rpcUrl;
    console.log('Initializing provider for', this.currentNetwork.name, 'with RPC:', rpcUrl);
    
    this.provider = new ethers.JsonRpcProvider(rpcUrl, {
      chainId: this.currentNetwork.chainId,
      name: this.currentNetwork.name
    });
    
    return this.provider;
  }

  /**
   * Create a new HD wallet with mnemonic
   * @returns {Object} Wallet data including mnemonic
   */
  async createNewWallet() {
    const mnemonic = ethers.Mnemonic.entropyToPhrase(ethers.randomBytes(16));
    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic, '', DERIVATION_PATH);
    const wallet = hdNode.deriveChild(0);

    return {
      mnemonic: mnemonic,
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.publicKey,
      derivationPath: `${DERIVATION_PATH}/0`
    };
  }

  /**
   * Import wallet from mnemonic phrase
   * @param {string} mnemonic - 12 or 24 word seed phrase
   * @param {number} accountIndex - Account index to derive
   * @returns {Object} Wallet data
   */
  async importFromMnemonic(mnemonic, accountIndex = 0) {
    if (!ethers.Mnemonic.isValidMnemonic(mnemonic)) {
      throw new Error('Invalid mnemonic phrase');
    }

    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic.trim(), '', DERIVATION_PATH);
    const wallet = hdNode.deriveChild(accountIndex);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.publicKey,
      derivationPath: `${DERIVATION_PATH}/${accountIndex}`,
      accountIndex: accountIndex
    };
  }

  /**
   * Import wallet from private key
   * @param {string} privateKey - Private key hex string
   * @returns {Object} Wallet data
   */
  async importFromPrivateKey(privateKey) {
    let key = privateKey.trim();
    if (!key.startsWith('0x')) {
      key = '0x' + key;
    }

    const wallet = new ethers.Wallet(key);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.signingKey.publicKey,
      type: 'privateKey'
    };
  }

  /**
   * Import wallet from keystore JSON
   * @param {string} keystore - Keystore JSON string
   * @param {string} password - Keystore password
   * @returns {Object} Wallet data
   */
  async importFromKeystore(keystore, password) {
    const wallet = await ethers.Wallet.fromEncryptedJson(keystore, password);

    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.signingKey.publicKey,
      type: 'keystore'
    };
  }

  /**
   * Derive multiple accounts from mnemonic
   * @param {string} mnemonic - Seed phrase
   * @param {number} count - Number of accounts to derive
   * @returns {Array} Array of wallet data
   */
  async deriveAccounts(mnemonic, count = 1) {
    const accounts = [];
    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic.trim(), '', DERIVATION_PATH);

    for (let i = 0; i < count; i++) {
      const wallet = hdNode.deriveChild(i);
      accounts.push({
        address: wallet.address,
        privateKey: wallet.privateKey,
        publicKey: wallet.publicKey,
        derivationPath: `${DERIVATION_PATH}/${i}`,
        accountIndex: i,
        name: `Account ${i + 1}`,
        type: 'derived'
      });
    }

    return accounts;
  }

  /**
   * Derive a single account at a specific index
   * @param {string} mnemonic - Seed phrase
   * @param {number} index - Account index to derive
   * @returns {Object} Wallet data for the derived account
   */
  async deriveAccount(mnemonic, index) {
    const hdNode = ethers.HDNodeWallet.fromPhrase(mnemonic.trim(), '', DERIVATION_PATH);
    const wallet = hdNode.deriveChild(index);
    
    return {
      address: wallet.address,
      privateKey: wallet.privateKey,
      publicKey: wallet.publicKey,
      derivationPath: `${DERIVATION_PATH}/${index}`,
      accountIndex: index,
      name: `Account ${index + 1}`,
      type: 'derived'
    };
  }

  /**
   * Get balance for an address
   * @param {string} address - Wallet address
   * @returns {Object} Balance info
   */
  async getBalance(address) {
    // Always reinitialize provider to ensure correct network
    await this.initProvider();

    console.log('getBalance using RPC:', this.currentNetwork.rpcUrl, 'for', this.currentNetwork.name);
    
    const balance = await this.provider.getBalance(address);
    const ether = ethers.formatEther(balance);
    
    console.log('Balance result:', ether, this.currentNetwork.symbol);
    
    return {
      wei: balance.toString(),
      ether: ether,
      symbol: this.currentNetwork.symbol
    };
  }

  /**
   * Get ERC20 token balance
   * @param {string} address - Wallet address
   * @param {string} tokenAddress - Token contract address
   * @returns {Object} Token balance info
   */
  async getTokenBalance(address, tokenAddress) {
    if (!this.provider) {
      await this.initProvider();
    }

    const erc20Abi = [
      'function balanceOf(address) view returns (uint256)',
      'function decimals() view returns (uint8)',
      'function symbol() view returns (string)',
      'function name() view returns (string)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, this.provider);
    const [balance, decimals, symbol, name] = await Promise.all([
      contract.balanceOf(address),
      contract.decimals(),
      contract.symbol(),
      contract.name()
    ]);

    return {
      balance: balance.toString(),
      formatted: ethers.formatUnits(balance, decimals),
      decimals: Number(decimals),
      symbol: symbol,
      name: name
    };
  }

  /**
   * Get ERC20 token info (without balance)
   * @param {string} tokenAddress - Token contract address
   * @returns {Object} Token info
   */
  async getTokenInfo(tokenAddress) {
    if (!this.provider) {
      await this.initProvider();
    }

    const erc20Abi = [
      'function decimals() view returns (uint8)',
      'function symbol() view returns (string)',
      'function name() view returns (string)',
      'function totalSupply() view returns (uint256)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, this.provider);
    const [decimals, symbol, name, totalSupply] = await Promise.all([
      contract.decimals(),
      contract.symbol(),
      contract.name(),
      contract.totalSupply().catch(() => BigInt(0))
    ]);

    return {
      address: tokenAddress,
      decimals: Number(decimals),
      symbol: symbol,
      name: name,
      totalSupply: totalSupply.toString()
    };
  }

  /**
   * Send native token transaction
   * @param {string} privateKey - Sender's private key
   * @param {string} to - Recipient address
   * @param {string} amount - Amount in ether
   * @returns {Object} Transaction receipt
   */
  async sendTransaction(privateKey, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    // Validate and sanitize amount
    const sanitizedAmount = parseFloat(amount).toString();
    if (isNaN(parseFloat(sanitizedAmount)) || parseFloat(sanitizedAmount) <= 0) {
      throw new Error('Invalid amount');
    }

    const wallet = new ethers.Wallet(privateKey, this.provider);
    
    const tx = await wallet.sendTransaction({
      to: to,
      value: ethers.parseEther(sanitizedAmount)
    });

    return await tx.wait();
  }

  /**
   * Send ERC20 token transaction
   * @param {string} privateKey - Sender's private key
   * @param {string} tokenAddress - Token contract address
   * @param {string} to - Recipient address
   * @param {string} amount - Amount to send
   * @returns {Object} Transaction receipt
   */
  async sendToken(privateKey, tokenAddress, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    const wallet = new ethers.Wallet(privateKey, this.provider);
    
    const erc20Abi = [
      'function transfer(address to, uint256 amount) returns (bool)',
      'function decimals() view returns (uint8)'
    ];

    const contract = new ethers.Contract(tokenAddress, erc20Abi, wallet);
    const decimals = await contract.decimals();
    const parsedAmount = ethers.parseUnits(amount, decimals);

    const tx = await contract.transfer(to, parsedAmount);
    return await tx.wait();
  }

  /**
   * Estimate gas for a transaction
   * @param {string} from - Sender address
   * @param {string} to - Recipient address
   * @param {string} amount - Amount in ether
   * @returns {Object} Gas estimate info
   */
  async estimateGas(from, to, amount) {
    if (!this.provider) {
      await this.initProvider();
    }

    const gasEstimate = await this.provider.estimateGas({
      from: from,
      to: to,
      value: ethers.parseEther(amount)
    });

    const feeData = await this.provider.getFeeData();

    return {
      gasLimit: gasEstimate.toString(),
      gasPrice: feeData.gasPrice?.toString() || '0',
      maxFeePerGas: feeData.maxFeePerGas?.toString() || '0',
      maxPriorityFeePerGas: feeData.maxPriorityFeePerGas?.toString() || '0'
    };
  }

  /**
   * Sign a message
   * @param {string} privateKey - Private key
   * @param {string} message - Message to sign
   * @returns {string} Signature
   */
  async signMessage(privateKey, message) {
    const wallet = new ethers.Wallet(privateKey);
    return await wallet.signMessage(message);
  }

  /**
   * Sign typed data (EIP-712)
   * @param {string} privateKey - Private key
   * @param {Object} domain - Domain separator
   * @param {Object} types - Type definitions
   * @param {Object} value - Data to sign
   * @returns {string} Signature
   */
  async signTypedData(privateKey, domain, types, value) {
    const wallet = new ethers.Wallet(privateKey);
    return await wallet.signTypedData(domain, types, value);
  }

  /**
   * Switch network
   * @param {string} networkKey - Network identifier
   */
  switchNetwork(networkKey) {
    if (NETWORKS[networkKey]) {
      this.currentNetwork = NETWORKS[networkKey];
      this.provider = null; // Reset provider to reinitialize with new network
    } else {
      throw new Error(`Unknown network: ${networkKey}`);
    }
  }

  /**
   * Add custom network
   * @param {Object} networkConfig - Network configuration
   */
  addCustomNetwork(networkConfig) {
    const key = `custom_${networkConfig.chainId}`;
    NETWORKS[key] = networkConfig;
    return key;
  }

  /**
   * Get comprehensive transaction history from explorer API
   * Fetches native transactions, ERC-20 token transfers, and NFT transfers
   * Supports Etherscan-compatible APIs (Ramascan, Etherscan, Polygonscan, etc.)
   * @param {string} address - Wallet address
   * @returns {Array} Combined and sorted transaction list
   */
  async getTransactionHistory(address) {
    console.log('getTransactionHistory called with address:', address);
    console.log('Current network:', this.currentNetwork?.name, 'chainId:', this.currentNetwork?.chainId);
    
    if (!address) {
      console.log('No address provided, returning empty');
      return [];
    }
    
    const explorerUrl = this.currentNetwork?.explorerUrl;
    if (!explorerUrl) {
      console.log('No explorerUrl found, returning empty');
      return [];
    }
    
    // Check if this network has a free transaction history API
    if (!this.hasTransactionHistoryApi()) {
      console.log('No free transaction history API for this network');
      // Return empty - the UI will show "No recent activity"
      // User can still view transactions on the explorer
      return [];
    }
    
    // Helper function to add timeout to fetch operations
    const withTimeout = (promise, timeoutMs = 10000, fallback = []) => {
      return Promise.race([
        promise,
        new Promise(resolve => setTimeout(() => {
          console.log('Fetch timed out after', timeoutMs, 'ms');
          resolve(fallback);
        }, timeoutMs))
      ]).catch(error => {
        console.warn('Fetch error:', error);
        return fallback;
      });
    };
    
    // Fetch all transaction types in parallel with timeout
    // Native transactions are most important, token/NFT are optional
    const [nativeTxs, tokenTxs, nftTxs] = await Promise.all([
      withTimeout(this.fetchNativeTransactions(address), 15000, []),
      withTimeout(this.fetchTokenTransfers(address), 10000, []),
      withTimeout(this.fetchNftTransfers(address), 10000, [])
    ]);
    
    // Combine all transactions
    const allTxs = [...nativeTxs, ...tokenTxs, ...nftTxs];
    
    // Sort by timestamp descending (most recent first)
    allTxs.sort((a, b) => (parseInt(b.timeStamp) || 0) - (parseInt(a.timeStamp) || 0));
    
    // Deduplicate by hash (keep the first occurrence which has more info for tokens)
    const seen = new Set();
    const uniqueTxs = allTxs.filter(tx => {
      // For token transfers, create a unique key with hash + token + from + to
      const key = tx.txType === 'native' ? tx.hash : `${tx.hash}_${tx.tokenSymbol || ''}_${tx.from}_${tx.to}`;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
    
    console.log(`Fetched ${nativeTxs.length} native, ${tokenTxs.length} token, ${nftTxs.length} NFT transactions`);
    return uniqueTxs;
  }

  /**
   * Get the API base URL for the current network
   * @returns {string} API base URL
   */
  getApiBaseUrl() {
    const explorerUrl = this.currentNetwork.explorerUrl;
    
    // Handle Ramascan - use the backend API
    if (explorerUrl.includes('ramascan.com')) {
      return 'https://latest-backendapi.ramascan.com/api/v1/';
    }
    // Etherscan-compatible APIs
    if (explorerUrl.includes('etherscan.io')) {
      return 'https://api.etherscan.io/api';
    }
    if (explorerUrl.includes('polygonscan.com')) {
      return 'https://api.polygonscan.com/api';
    }
    if (explorerUrl.includes('bscscan.com')) {
      return 'https://api.bscscan.com/api';
    }
    if (explorerUrl.includes('arbiscan.io')) {
      return 'https://api.arbiscan.io/api';
    }
    if (explorerUrl.includes('optimistic.etherscan.io')) {
      return 'https://api-optimistic.etherscan.io/api';
    }
    if (explorerUrl.includes('basescan.org')) {
      return 'https://api.basescan.org/api';
    }
    if (explorerUrl.includes('snowtrace.io') || explorerUrl.includes('snowscan.xyz')) {
      return 'https://api.snowscan.xyz/api';
    }
    if (explorerUrl.includes('ftmscan.com')) {
      return 'https://api.ftmscan.com/api';
    }
    
    // Default: try /api endpoint
    return `${explorerUrl.replace(/\/$/, '')}/api`;
  }

  /**
   * Get the Blockscout v2 API base URL for the current network
   * Blockscout v2 API provides better data quality and is available for many chains
   * @returns {string|null} Blockscout v2 API base URL or null if not available
   */
  getBlockscoutV2BaseUrl() {
    const explorerUrl = this.currentNetwork?.explorerUrl || '';
    const chainId = this.currentNetwork?.chainId;
    
    // Ramascan uses its own backend API
    if (explorerUrl.includes('ramascan.com')) {
      const isTestnet = explorerUrl.includes('testnet');
      return isTestnet 
        ? 'https://testnet-backendapi.ramascan.com'
        : 'https://latest-backendapi.ramascan.com';
    }
    
    // Blockscout v2 API mapping for major chains
    // These are verified working public Blockscout instances
    const blockscoutV2Urls = {
      // Ethereum
      1: 'https://eth.blockscout.com',           // Ethereum Mainnet ✓
      11155111: 'https://eth-sepolia.blockscout.com', // Sepolia Testnet ✓
      17000: 'https://eth-holesky.blockscout.com',    // Holesky Testnet ✓
      61: 'https://etc.blockscout.com',          // Ethereum Classic ✓
      
      // Polygon
      137: 'https://polygon.blockscout.com',     // Polygon Mainnet ✓
      80002: 'https://polygon-amoy.blockscout.com', // Polygon Amoy ✓
      
      // Arbitrum
      42161: 'https://arbitrum.blockscout.com',  // Arbitrum One ✓
      
      // Optimism
      10: 'https://optimism.blockscout.com',     // Optimism Mainnet ✓
      
      // Base
      8453: 'https://base.blockscout.com',       // Base Mainnet ✓
      84532: 'https://base-sepolia.blockscout.com',  // Base Sepolia ✓
      
      // zkSync Era
      324: 'https://zksync.blockscout.com',      // zkSync Era Mainnet ✓
      
      // Gnosis
      100: 'https://gnosis.blockscout.com',      // Gnosis Chain ✓
      
      // Scroll
      534352: 'https://scroll.blockscout.com',   // Scroll Mainnet ✓
      
      // Linea (has Blockscout)
      59144: 'https://linea.blockscout.com',     // Linea Mainnet
      
      // Mantle
      5000: 'https://explorer.mantle.xyz',       // Mantle uses Blockscout
      
      // Celo
      42220: 'https://celo.blockscout.com',      // Celo Mainnet
    };
    
    // Return the Blockscout v2 URL if available
    if (chainId && blockscoutV2Urls[chainId]) {
      return blockscoutV2Urls[chainId];
    }
    
    // Check for Blockscout URLs in explorer
    if (explorerUrl.includes('blockscout.com')) {
      // Already a blockscout URL, extract base
      const match = explorerUrl.match(/https?:\/\/[^\/]+/);
      return match ? match[0] : null;
    }
    
    return null;
  }

  /**
   * Get the Routescan API URL for chains not covered by Blockscout
   * Routescan provides free API access for many EVM chains
   * @returns {string|null} Routescan API URL or null if not available
   */
  getRoutescanApiUrl() {
    const chainId = this.currentNetwork?.chainId;
    
    // Routescan supported chains (that don't have Blockscout)
    // Note: BSC (56, 97) is NOT supported by Routescan - returns "Unknown chainId"
    // Note: These chains have limited/no free API access for transaction history
    const routescanChains = {
      // Avalanche - works with Routescan
      43114: { network: 'mainnet', chainId: 43114 },
      43113: { network: 'testnet', chainId: 43113 },
    };
    
    if (chainId && routescanChains[chainId]) {
      const config = routescanChains[chainId];
      return `https://api.routescan.io/v2/network/${config.network}/evm/${config.chainId}`;
    }
    
    return null;
  }

  /**
   * Check if transaction history is available for the current network
   * Some networks don't have free public APIs for transaction history
   * @returns {boolean} Whether transaction history is available
   */
  hasTransactionHistoryApi() {
    // Networks with working free APIs:
    // - Ramestta (Ramascan v2)
    // - Blockscout v2 networks (ETH, Polygon, Arbitrum, Optimism, Base, etc.)
    // - Routescan networks (Avalanche)
    
    // Networks WITHOUT free transaction history APIs:
    const noFreeApiChains = [
      56,   // BSC - requires paid Etherscan API
      97,   // BSC Testnet
      250,  // Fantom - deprecated v1 API
      4002, // Fantom Testnet
      25,   // Cronos - no free API
      338,  // Cronos Testnet
    ];
    
    const chainId = this.currentNetwork?.chainId;
    return !noFreeApiChains.includes(chainId);
  }

  /**
   * Check if the current network should use Blockscout v2 API
   * @returns {boolean} Whether to use Blockscout v2 API
   */
  usesBlockscoutV2() {
    // Use Blockscout v2 for all networks that have it available
    // This provides better reliability than deprecated Etherscan v1 APIs
    return this.getBlockscoutV2BaseUrl() !== null;
  }
  
  /**
   * Check if the current network should use Routescan API
   * @returns {boolean} Whether to use Routescan API
   */
  usesRoutescan() {
    return this.getRoutescanApiUrl() !== null;
  }

  /**
   * Fetch native RAMA/ETH transactions (txlist)
   * Uses v2 API for Ramascan (more reliable) with v1 fallback
   * @param {string} address - Wallet address
   * @returns {Array} Native transactions
   */
  async fetchNativeTransactions(address) {
    const explorerUrl = this.currentNetwork.explorerUrl || '';
    console.log('fetchNativeTransactions - explorerUrl:', explorerUrl, 'address:', address);
    
    // For Ramascan (mainnet or testnet), use v2 API first (more reliable, v1 often times out)
    const isRamascan = explorerUrl.includes('ramascan.com');
    
    if (isRamascan) {
      // Determine the correct API base URL
      const isTestnet = explorerUrl.includes('testnet');
      const apiBaseUrl = isTestnet 
        ? 'https://testnet-backendapi.ramascan.com' 
        : 'https://latest-backendapi.ramascan.com';
      
      try {
        const v2Url = `${apiBaseUrl}/api/v2/addresses/${address}/transactions`;
        console.log('Fetching native transactions from v2 API:', v2Url);
        
        const response = await fetch(v2Url, {
          headers: {
            'Accept': 'application/json',
            'User-Agent': 'RamaPay-Extension/1.0'
          }
        });
        
        console.log('V2 API response status:', response.status);
        
        if (response.ok) {
          const data = await response.json();
          console.log('V2 API data items count:', data.items?.length || 0);
          if (data.items && Array.isArray(data.items)) {
            const parsed = this.parseV2Transactions(data.items, address);
            console.log('Parsed V2 transactions:', parsed.length);
            return parsed;
          }
        } else {
          console.warn('V2 API returned non-ok status:', response.status);
        }
      } catch (error) {
        console.warn('V2 transactions fetch failed, trying v1:', error);
      }
      
      // Fallback to v1 API
      try {
        const v1Url = `${apiBaseUrl}/api/v1/?module=account&action=txlist&address=${address}&sort=desc&page=1&offset=50`;
        console.log('Trying v1 API:', v1Url);
        
        const response = await fetch(v1Url);
        if (response.ok) {
          const data = await response.json();
          if (data.status === '1' && Array.isArray(data.result)) {
            return this.parseV1Transactions(data.result);
          }
        }
      } catch (error) {
        console.warn('V1 transactions fetch also failed:', error);
      }
      
      return [];
    }
    
    // For other networks, check if Blockscout v2 is available first
    const blockscoutV2Url = this.getBlockscoutV2BaseUrl();
    if (blockscoutV2Url && this.usesBlockscoutV2()) {
      try {
        const v2Url = `${blockscoutV2Url}/api/v2/addresses/${address}/transactions`;
        console.log('Fetching from Blockscout v2 API:', v2Url);
        
        const response = await fetch(v2Url, {
          headers: { 'Accept': 'application/json' }
        });
        
        if (response.ok) {
          const data = await response.json();
          if (data.items && Array.isArray(data.items)) {
            return this.parseV2Transactions(data.items, address);
          }
        }
      } catch (error) {
        console.warn('Blockscout v2 fetch failed, trying alternatives:', error);
      }
    }
    
    // Try Routescan API for chains that support it (BSC, Avalanche, Fantom, etc.)
    const routescanUrl = this.getRoutescanApiUrl();
    if (routescanUrl) {
      try {
        const rsUrl = `${routescanUrl}/address/${address}/transactions`;
        console.log('Fetching from Routescan API:', rsUrl);
        
        const response = await fetch(rsUrl, {
          headers: { 'Accept': 'application/json' }
        });
        
        if (response.ok) {
          const data = await response.json();
          if (data.items && Array.isArray(data.items)) {
            return this.parseRoutescanTransactions(data.items, address);
          }
        }
      } catch (error) {
        console.warn('Routescan fetch failed:', error);
      }
    }
    
    // Use standard Etherscan-compatible API (v1) as last fallback
    const baseUrl = this.getApiBaseUrl();
    const apiUrl = `${baseUrl}?module=account&action=txlist&address=${address}&sort=desc&page=1&offset=50`;
    
    try {
      console.log('Fetching native transactions from:', apiUrl);
      const response = await fetch(apiUrl);
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const data = await response.json();
      
      // Accept data if result is an array (even with status '0' some APIs return data)
      if (Array.isArray(data.result) && data.result.length > 0) {
        console.log(`Fetched ${data.result.length} transactions (status: ${data.status})`);
        return this.parseV1Transactions(data.result);
      }
      
      // Log the message if no results
      if (data.message) {
        console.log('API response:', data.message);
      }
      
      return [];
    } catch (error) {
      console.warn('Native transactions fetch failed:', error);
      return [];
    }
  }

  /**
   * Parse Routescan API transactions
   * @param {Array} items - Raw Routescan API transaction items
   * @param {string} walletAddress - Current wallet address
   * @returns {Array} Parsed transactions
   */
  parseRoutescanTransactions(items, walletAddress) {
    return items.map(item => {
      // Parse timestamp (ISO 8601 format)
      let timeStamp;
      if (item.timestamp) {
        try {
          const date = new Date(item.timestamp);
          timeStamp = Math.floor(date.getTime() / 1000).toString();
        } catch (e) {
          timeStamp = Math.floor(Date.now() / 1000).toString();
        }
      } else {
        timeStamp = Math.floor(Date.now() / 1000).toString();
      }
      
      return {
        hash: item.id || item.hash || '',
        from: item.from || '',
        to: item.to || '',
        value: item.value || '0',
        timeStamp,
        blockNumber: String(item.blockNumber || ''),
        gasPrice: item.gasPrice || '0',
        gasUsed: String(item.gasUsed || '0'),
        isError: !item.status,
        txreceipt_status: item.status ? '1' : '0',
        input: '0x',
        methodId: '',
        functionName: '',
        txType: 'native',
        tokenSymbol: this.currentNetwork.symbol || 'ETH'
      };
    });
  }

  /**
   * Parse v1 API transactions (Etherscan-compatible format)
   * @param {Array} transactions - Raw v1 API transactions
   * @returns {Array} Parsed transactions
   */
  parseV1Transactions(transactions) {
    return transactions.map(tx => ({
      hash: tx.hash,
      from: tx.from,
      to: tx.to,
      value: tx.value,
      timeStamp: tx.timeStamp,
      blockNumber: tx.blockNumber,
      gasPrice: tx.gasPrice,
      gasUsed: tx.gasUsed,
      isError: tx.isError === '1',
      txreceipt_status: tx.txreceipt_status,
      input: tx.input,
      methodId: tx.methodId,
      functionName: tx.functionName,
      txType: 'native',
      tokenSymbol: this.currentNetwork.symbol || 'RAMA'
    }));
  }

  /**
   * Parse v2 API transactions (Blockscout format used by Ramascan)
   * @param {Array} items - Raw v2 API transaction items
   * @param {string} walletAddress - Current wallet address
   * @returns {Array} Parsed transactions
   */
  parseV2Transactions(items, walletAddress) {
    return items.map(item => {
      // Parse from/to addresses (v2 uses nested objects)
      const from = item.from?.hash || item.from || '';
      const to = item.to?.hash || item.to || '';
      
      // Parse timestamp (ISO 8601 format)
      let timeStamp;
      if (item.timestamp) {
        try {
          const cleanTimestamp = item.timestamp.split('.')[0] + 'Z';
          const date = new Date(cleanTimestamp);
          timeStamp = Math.floor(date.getTime() / 1000).toString();
        } catch (e) {
          timeStamp = Math.floor(Date.now() / 1000).toString();
        }
      } else {
        timeStamp = Math.floor(Date.now() / 1000).toString();
      }
      
      // Determine if this is a token transfer based on transaction_types
      const txTypes = item.transaction_types || [];
      const isTokenTransfer = txTypes.includes('token_transfer');
      
      // Parse method name from decoded_input if available
      let functionName = item.method || '';
      if (item.decoded_input?.method_call) {
        functionName = item.decoded_input.method_call.split('(')[0];
      }
      
      // Determine if transaction failed
      const isError = item.status !== 'ok' || item.result !== 'success';
      
      return {
        hash: item.hash || '',
        from,
        to,
        value: item.value || '0',
        timeStamp,
        blockNumber: String(item.block_number || item.block || ''),
        gasPrice: item.gas_price || '0',
        gasUsed: String(item.gas_used || '0'),
        isError,
        txreceipt_status: item.status === 'ok' ? '1' : '0',
        input: item.raw_input || '0x',
        methodId: item.raw_input?.slice(0, 10) || '',
        functionName,
        txType: 'native',
        tokenSymbol: this.currentNetwork.symbol || 'RAMA',
        // Extra v2 fields for enhanced display
        fee: item.fee?.value || '0',
        nonce: item.nonce,
        contractName: item.to?.name || null,
        isContractCall: item.to?.is_contract || false
      };
    });
  }

  /**
   * Fetch ERC-20/RAMA-20 token transfers (tokentx)
   * @param {string} address - Wallet address
   * @returns {Array} Token transfers
   */
  async fetchTokenTransfers(address) {
    const baseUrl = this.getApiBaseUrl();
    const explorerUrl = this.currentNetwork.explorerUrl || '';
    
    // Check if this network uses Blockscout v2 API
    const blockscoutV2Url = this.getBlockscoutV2BaseUrl();
    
    if (blockscoutV2Url) {
      try {
        // Try v2 API first (better data quality)
        const v2Url = `${blockscoutV2Url}/api/v2/addresses/${address}/token-transfers?type=ERC-20`;
        console.log('Fetching token transfers from v2 API:', v2Url);
        
        const response = await fetch(v2Url);
        if (response.ok) {
          const data = await response.json();
          console.log('Token transfers v2 API items:', data.items?.length || 0);
          if (data.items && Array.isArray(data.items)) {
            return this.parseV2TokenTransfers(data.items, address);
          }
        }
      } catch (error) {
        console.warn('V2 token transfers fetch failed, falling back:', error);
      }
    }
    
    // Try Routescan for token transfers
    const routescanUrl = this.getRoutescanApiUrl();
    if (routescanUrl) {
      try {
        const rsUrl = `${routescanUrl}/address/${address}/erc20-transfers`;
        console.log('Fetching token transfers from Routescan:', rsUrl);
        
        const response = await fetch(rsUrl, {
          headers: { 'Accept': 'application/json' }
        });
        
        if (response.ok) {
          const data = await response.json();
          if (data.items && Array.isArray(data.items)) {
            return this.parseRoutescanTokenTransfers(data.items, address);
          }
        }
      } catch (error) {
        console.warn('Routescan token transfers fetch failed:', error);
      }
    }
    
    // Fall back to v1 API (Etherscan-compatible)
    const apiUrl = `${baseUrl}?module=account&action=tokentx&address=${address}&sort=desc&page=1&offset=50`;
    
    try {
      console.log('Fetching token transfers from:', apiUrl);
      const response = await fetch(apiUrl);
      
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      
      const data = await response.json();
      
      // Accept data if result is an array (even with status '0' some APIs return data)
      if (Array.isArray(data.result) && data.result.length > 0) {
        console.log(`Fetched ${data.result.length} token transfers (status: ${data.status})`);
        return data.result.map(tx => ({
          hash: tx.hash,
          from: tx.from,
          to: tx.to,
          value: tx.value,
          timeStamp: tx.timeStamp,
          blockNumber: tx.blockNumber,
          contractAddress: tx.contractAddress,
          tokenName: tx.tokenName,
          tokenSymbol: tx.tokenSymbol,
          tokenDecimal: tx.tokenDecimal,
          gasPrice: tx.gasPrice,
          gasUsed: tx.gasUsed,
          txType: 'erc20'
        }));
      }
      
      return [];
    } catch (error) {
      console.warn('Token transfers fetch failed:', error);
      return [];
    }
  }

  /**
   * Parse V2 API token transfer format
   * @param {Array} items - Token transfer items from v2 API
   * @param {string} address - Wallet address
   * @returns {Array} Parsed token transfers
   */
  parseV2TokenTransfers(items, address) {
    return items.filter(item => {
      // Filter out burn events
      if (item.type === 'token_burning') return false;
      const toAddress = item.to?.hash || item.to;
      if (toAddress && toAddress.toLowerCase() === '0x0000000000000000000000000000000000000000') return false;
      return true;
    }).map(item => {
      // Parse from/to addresses (v2 uses nested objects)
      const from = item.from?.hash || item.from || '';
      const to = item.to?.hash || item.to || '';
      
      // Parse token info
      const token = item.token || {};
      const contractAddress = token.address || '';
      const tokenName = token.name || 'Unknown Token';
      const tokenSymbol = token.symbol || 'TOKEN';
      const tokenDecimal = token.decimals || '18';
      
      // Parse value
      const total = item.total || {};
      const value = total.value || item.value || '0';
      
      // Parse timestamp
      let timeStamp;
      if (item.timestamp) {
        try {
          const date = new Date(item.timestamp.split('.')[0] + 'Z');
          timeStamp = Math.floor(date.getTime() / 1000).toString();
        } catch (e) {
          timeStamp = Math.floor(Date.now() / 1000).toString();
        }
      } else {
        timeStamp = Math.floor(Date.now() / 1000).toString();
      }
      
      return {
        hash: item.transaction_hash || item.tx_hash || '',
        from,
        to,
        value,
        timeStamp,
        blockNumber: String(item.block_number || item.block || ''),
        contractAddress,
        tokenName,
        tokenSymbol,
        tokenDecimal,
        txType: 'erc20'
      };
    });
  }

  /**
   * Parse Routescan API token transfers
   * @param {Array} items - Raw Routescan token transfer items
   * @param {string} walletAddress - Current wallet address
   * @returns {Array} Parsed token transfers
   */
  parseRoutescanTokenTransfers(items, walletAddress) {
    return items.filter(item => {
      // Filter out burn events
      const toAddress = item.to || '';
      if (toAddress.toLowerCase() === '0x0000000000000000000000000000000000000000') return false;
      return true;
    }).map(item => {
      // Parse timestamp
      let timeStamp;
      if (item.timestamp) {
        try {
          const date = new Date(item.timestamp);
          timeStamp = Math.floor(date.getTime() / 1000).toString();
        } catch (e) {
          timeStamp = Math.floor(Date.now() / 1000).toString();
        }
      } else {
        timeStamp = Math.floor(Date.now() / 1000).toString();
      }
      
      return {
        hash: item.id || item.transactionHash || '',
        from: item.from || '',
        to: item.to || '',
        value: item.value || '0',
        timeStamp,
        blockNumber: String(item.blockNumber || ''),
        contractAddress: item.tokenAddress || '',
        tokenName: item.tokenName || 'Unknown Token',
        tokenSymbol: item.tokenSymbol || 'TOKEN',
        tokenDecimal: item.tokenDecimals || '18',
        txType: 'erc20'
      };
    });
  }

  /**
   * Fetch NFT transfers (tokennfttx for ERC-721, token1155tx for ERC-1155)
   * @param {string} address - Wallet address
   * @returns {Array} NFT transfers
   */
  async fetchNftTransfers(address) {
    const baseUrl = this.getApiBaseUrl();
    const explorerUrl = this.currentNetwork.explorerUrl || '';
    
    // Check if this network uses Blockscout v2 API
    const blockscoutV2Url = this.getBlockscoutV2BaseUrl();
    
    if (blockscoutV2Url) {
      try {
        // Try v2 API for NFT transfers (ERC-721 and ERC-1155)
        const v2Url = `${blockscoutV2Url}/api/v2/addresses/${address}/token-transfers?type=ERC-721,ERC-1155`;
        console.log('Fetching NFT transfers from v2 API:', v2Url);
        
        const response = await fetch(v2Url, {
          headers: {
            'Accept': 'application/json',
            'User-Agent': 'RamaPay-Extension/1.0'
          }
        });
        
        if (response.ok) {
          const data = await response.json();
          console.log('NFT transfers v2 API items:', data.items?.length || 0);
          if (data.items && Array.isArray(data.items) && data.items.length > 0) {
            return this.parseV2NftTransfers(data.items, address);
          }
        }
      } catch (error) {
        console.warn('V2 NFT transfers fetch failed, trying v1:', error);
      }
    }
      
    // Use v1 API for NFT transfers (Etherscan-compatible)
    try {
      const nftUrl = `${baseUrl}?module=account&action=tokennfttx&address=${address}&sort=desc&page=1&offset=50`;
      console.log('Fetching NFT transfers from v1 API:', nftUrl);
      
      const response = await fetch(nftUrl);
      if (response.ok) {
        const data = await response.json();
        // Accept data if result is an array (even with status '0')
        if (Array.isArray(data.result) && data.result.length > 0) {
          console.log(`Fetched ${data.result.length} NFT transfers (status: ${data.status})`);
          return data.result.map(tx => ({
            hash: tx.hash,
            from: tx.from,
            to: tx.to,
            tokenID: tx.tokenID,
            timeStamp: tx.timeStamp,
            blockNumber: tx.blockNumber,
            contractAddress: tx.contractAddress,
            tokenName: tx.tokenName,
            tokenSymbol: tx.tokenSymbol,
            txType: 'nft'
          }));
        }
      }
    } catch (error) {
      console.warn('NFT transfers fetch failed:', error);
    }
    
    return [];
  }

  /**
   * Parse V2 API NFT transfer format
   * @param {Array} items - NFT transfer items from v2 API
   * @param {string} address - Wallet address
   * @returns {Array} Parsed NFT transfers
   */
  parseV2NftTransfers(items, address) {
    return items.filter(item => {
      // Filter out burn events
      if (item.type === 'token_burning') return false;
      const toAddress = item.to?.hash || item.to;
      if (toAddress && toAddress.toLowerCase() === '0x0000000000000000000000000000000000000000') return false;
      return true;
    }).map(item => {
      // Parse from/to addresses (v2 uses nested objects)
      const from = item.from?.hash || item.from || '';
      const to = item.to?.hash || item.to || '';
      
      // Parse token info
      const token = item.token || {};
      const contractAddress = token.address || '';
      const tokenName = token.name || 'Unknown NFT';
      const tokenSymbol = token.symbol || 'NFT';
      
      // Parse token ID
      const total = item.total || {};
      const tokenID = total.token_id || item.token_id || '';
      
      // Parse timestamp
      let timeStamp;
      if (item.timestamp) {
        try {
          const cleanTimestamp = item.timestamp.split('.')[0] + 'Z';
          const date = new Date(cleanTimestamp);
          timeStamp = Math.floor(date.getTime() / 1000).toString();
        } catch (e) {
          timeStamp = Math.floor(Date.now() / 1000).toString();
        }
      } else {
        timeStamp = Math.floor(Date.now() / 1000).toString();
      }
      
      return {
        hash: item.transaction_hash || item.tx_hash || '',
        from,
        to,
        tokenID,
        timeStamp,
        blockNumber: String(item.block_number || item.block || ''),
        contractAddress,
        tokenName,
        tokenSymbol,
        txType: 'nft'
      };
    });
  }
}

/**
 * Storage Manager - Handles encrypted storage operations
 */
export class StorageManager {
  constructor() {
    this.storageKey = 'ramapay_wallet_data';
  }

  /**
   * Encrypt data with password
   * @param {Object} data - Data to encrypt
   * @param {string} password - Encryption password
   * @returns {string} Encrypted data
   */
  async encrypt(data, password) {
    const encoder = new TextEncoder();
    const dataStr = JSON.stringify(data);
    
    // Derive key from password
    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      encoder.encode(password),
      'PBKDF2',
      false,
      ['deriveBits', 'deriveKey']
    );

    const salt = crypto.getRandomValues(new Uint8Array(16));
    const iv = crypto.getRandomValues(new Uint8Array(12));

    const key = await crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256'
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt']
    );

    const encrypted = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: iv },
      key,
      encoder.encode(dataStr)
    );

    // Combine salt + iv + encrypted data
    const combined = new Uint8Array(salt.length + iv.length + encrypted.byteLength);
    combined.set(salt, 0);
    combined.set(iv, salt.length);
    combined.set(new Uint8Array(encrypted), salt.length + iv.length);

    return btoa(String.fromCharCode(...combined));
  }

  /**
   * Decrypt data with password
   * @param {string} encryptedData - Encrypted data
   * @param {string} password - Decryption password
   * @returns {Object} Decrypted data
   */
  async decrypt(encryptedData, password) {
    const encoder = new TextEncoder();
    const decoder = new TextDecoder();
    
    const combined = Uint8Array.from(atob(encryptedData), c => c.charCodeAt(0));
    
    const salt = combined.slice(0, 16);
    const iv = combined.slice(16, 28);
    const encrypted = combined.slice(28);

    const keyMaterial = await crypto.subtle.importKey(
      'raw',
      encoder.encode(password),
      'PBKDF2',
      false,
      ['deriveBits', 'deriveKey']
    );

    const key = await crypto.subtle.deriveKey(
      {
        name: 'PBKDF2',
        salt: salt,
        iterations: 100000,
        hash: 'SHA-256'
      },
      keyMaterial,
      { name: 'AES-GCM', length: 256 },
      false,
      ['decrypt']
    );

    const decrypted = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: iv },
      key,
      encrypted
    );

    return JSON.parse(decoder.decode(decrypted));
  }

  /**
   * Save encrypted wallet data to Chrome storage
   * @param {Object} walletData - Wallet data to save
   * @param {string} password - Encryption password
   */
  async saveWallet(walletData, password) {
    const encrypted = await this.encrypt(walletData, password);
    return new Promise((resolve, reject) => {
      chrome.storage.local.set({ [this.storageKey]: encrypted }, () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Load and decrypt wallet data from Chrome storage
   * @param {string} password - Decryption password
   * @returns {Object} Wallet data
   */
  async loadWallet(password) {
    return new Promise((resolve, reject) => {
      chrome.storage.local.get([this.storageKey], async (result) => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
          return;
        }

        const encrypted = result[this.storageKey];
        if (!encrypted) {
          resolve(null);
          return;
        }

        try {
          const decrypted = await this.decrypt(encrypted, password);
          resolve(decrypted);
        } catch (error) {
          reject(new Error('Invalid password'));
        }
      });
    });
  }

  /**
   * Check if wallet exists
   * @returns {boolean}
   */
  async hasWallet() {
    return new Promise((resolve) => {
      chrome.storage.local.get([this.storageKey], (result) => {
        resolve(!!result[this.storageKey]);
      });
    });
  }

  /**
   * Clear all wallet data
   */
  async clearWallet() {
    return new Promise((resolve, reject) => {
      chrome.storage.local.remove([this.storageKey], () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Save user preferences (non-sensitive)
   * @param {Object} preferences - User preferences
   */
  async savePreferences(preferences) {
    return new Promise((resolve, reject) => {
      chrome.storage.local.set({ ramapay_preferences: preferences }, () => {
        if (chrome.runtime.lastError) {
          reject(chrome.runtime.lastError);
        } else {
          resolve();
        }
      });
    });
  }

  /**
   * Load user preferences
   * @returns {Object} User preferences
   */
  async loadPreferences() {
    return new Promise((resolve) => {
      chrome.storage.local.get(['ramapay_preferences'], (result) => {
        resolve(result.ramapay_preferences || {
          network: 'ramestta_mainnet',
          currency: 'USD',
          theme: 'dark'
        });
      });
    });
  }
}

// Export singleton instances
export const walletManager = new WalletManager();
export const storageManager = new StorageManager();
