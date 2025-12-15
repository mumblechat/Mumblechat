package com.ramapay.app.repository.entity;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * Point of Sale Invoice entity for storing merchant payment requests
 * Stores invoice data locally for payment tracking and receipt generation
 */
public class RealmPosInvoice extends RealmObject
{
    @PrimaryKey
    private String invoiceId;           // Unique invoice ID: address_timestamp_nonce
    
    private String invoiceHash;         // Short hash for TX data field matching
    private String merchantAddress;     // Merchant's wallet address
    private String merchantName;        // Optional merchant/store name
    
    // Amount details
    private String cryptoAmount;        // Amount in crypto (wei/smallest unit as string)
    private int tokenDecimals;          // Token decimals for display
    private String tokenAddress;        // Token contract address (empty for native)
    private String tokenSymbol;         // Token symbol (RAMA, USDT, etc.)
    private long chainId;               // Network chain ID
    
    // Fiat details
    private String fiatAmount;          // Fiat amount as string
    private String fiatCurrency;        // Fiat currency code (INR, USD, etc.)
    private double exchangeRate;        // Exchange rate at time of invoice
    
    // Status
    private String status;              // PENDING, PAID, EXPIRED, CANCELLED
    private long createdAt;             // Creation timestamp
    private long expiresAt;             // Expiration timestamp (0 = no expiry)
    private long paidAt;                // Payment received timestamp
    
    // Transaction details (filled when paid)
    private String txHash;              // Blockchain transaction hash
    private String payerAddress;        // Customer's wallet address
    
    // Optional metadata
    private String note;                // Optional invoice note/description
    private String itemsJson;           // Optional: JSON array of items
    private String category;            // Payment category (rent, grocery, etc.)
    private String businessLogoUrl;     // Business logo URL for display

    // Getters and Setters
    public String getInvoiceId() { return invoiceId; }
    public void setInvoiceId(String invoiceId) { this.invoiceId = invoiceId; }

    public String getInvoiceHash() { return invoiceHash; }
    public void setInvoiceHash(String invoiceHash) { this.invoiceHash = invoiceHash; }

    public String getMerchantAddress() { return merchantAddress; }
    public void setMerchantAddress(String merchantAddress) { this.merchantAddress = merchantAddress; }

    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }

    public String getCryptoAmount() { return cryptoAmount; }
    public void setCryptoAmount(String cryptoAmount) { this.cryptoAmount = cryptoAmount; }

    public int getTokenDecimals() { return tokenDecimals; }
    public void setTokenDecimals(int tokenDecimals) { this.tokenDecimals = tokenDecimals; }

    public String getTokenAddress() { return tokenAddress; }
    public void setTokenAddress(String tokenAddress) { this.tokenAddress = tokenAddress; }

    public String getTokenSymbol() { return tokenSymbol; }
    public void setTokenSymbol(String tokenSymbol) { this.tokenSymbol = tokenSymbol; }

    public long getChainId() { return chainId; }
    public void setChainId(long chainId) { this.chainId = chainId; }

    public String getFiatAmount() { return fiatAmount; }
    public void setFiatAmount(String fiatAmount) { this.fiatAmount = fiatAmount; }

    public String getFiatCurrency() { return fiatCurrency; }
    public void setFiatCurrency(String fiatCurrency) { this.fiatCurrency = fiatCurrency; }

    public double getExchangeRate() { return exchangeRate; }
    public void setExchangeRate(double exchangeRate) { this.exchangeRate = exchangeRate; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public long getPaidAt() { return paidAt; }
    public void setPaidAt(long paidAt) { this.paidAt = paidAt; }

    public String getTxHash() { return txHash; }
    public void setTxHash(String txHash) { this.txHash = txHash; }

    public String getPayerAddress() { return payerAddress; }
    public void setPayerAddress(String payerAddress) { this.payerAddress = payerAddress; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getItemsJson() { return itemsJson; }
    public void setItemsJson(String itemsJson) { this.itemsJson = itemsJson; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getBusinessLogoUrl() { return businessLogoUrl; }
    public void setBusinessLogoUrl(String businessLogoUrl) { this.businessLogoUrl = businessLogoUrl; }

    // Status constants
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PAID = "PAID";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CANCELLED = "CANCELLED";
}
