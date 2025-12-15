package com.ramapay.app.repository.entity;

import io.realm.RealmObject;
import io.realm.annotations.PrimaryKey;

/**
 * Business Profile for PoS merchants
 * Stores merchant's business information for payment receipts
 */
public class RealmBusinessProfile extends RealmObject
{
    @PrimaryKey
    private String walletAddress;       // Merchant's wallet address (primary key)
    
    private String businessName;        // Business/Shop name
    private String businessType;        // Type of business (Retail, Restaurant, etc.)
    private String ownerName;           // Owner's name
    private String phoneNumber;         // Contact number
    private String email;               // Business email
    private String address;             // Physical address
    private String city;                // City
    private String state;               // State/Province
    private String country;             // Country
    private String pincode;             // Postal/ZIP code
    private String gstNumber;           // GST/Tax number (optional)
    private String logoUrl;             // Business logo URL/Base64
    private String defaultCurrency;     // Default fiat currency for PoS
    private long createdAt;             // Profile creation time
    private long updatedAt;             // Last update time

    // Getters and Setters
    public String getWalletAddress() { return walletAddress; }
    public void setWalletAddress(String walletAddress) { this.walletAddress = walletAddress; }

    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }

    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }

    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public String getPincode() { return pincode; }
    public void setPincode(String pincode) { this.pincode = pincode; }

    public String getGstNumber() { return gstNumber; }
    public void setGstNumber(String gstNumber) { this.gstNumber = gstNumber; }

    public String getLogoUrl() { return logoUrl; }
    public void setLogoUrl(String logoUrl) { this.logoUrl = logoUrl; }

    public String getDefaultCurrency() { return defaultCurrency; }
    public void setDefaultCurrency(String defaultCurrency) { this.defaultCurrency = defaultCurrency; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    // Business type constants
    public static final String TYPE_RETAIL = "Retail Store";
    public static final String TYPE_RESTAURANT = "Restaurant/Cafe";
    public static final String TYPE_GROCERY = "Grocery Store";
    public static final String TYPE_PHARMACY = "Pharmacy";
    public static final String TYPE_SERVICE = "Service Provider";
    public static final String TYPE_PROFESSIONAL = "Professional Services";
    public static final String TYPE_EDUCATION = "Education";
    public static final String TYPE_HEALTHCARE = "Healthcare";
    public static final String TYPE_OTHER = "Other";
}
