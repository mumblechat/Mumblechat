package com.ramapay.app.entity;

import android.os.Parcel;
import android.os.Parcelable;

import com.ramapay.app.BuildConfig;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.service.KeyService;
import com.ramapay.app.util.BalanceUtils;

import java.math.BigDecimal;
import java.util.Objects;

public class Wallet implements Parcelable
{
    public final String address;
    public String balance;
    public String ENSname;
    public String name;
    public WalletType type;
    public long lastBackupTime;
    public KeyService.AuthenticationLevel authLevel;
    public long walletCreationTime;
    public String balanceSymbol;
    public String ENSAvatar;
    public boolean isSynced;
    public Token[] tokens;
    public int hdKeyIndex = 0; // HD wallet account index
    public String parentAddress; // Parent HD wallet address for derived accounts

    public Wallet(String address)
    {
        this.address = address;
        this.balance = "-";
        this.ENSname = "";
        this.name = "";
        this.type = WalletType.NOT_DEFINED;
        this.lastBackupTime = 0;
        this.authLevel = KeyService.AuthenticationLevel.NOT_SET;
        this.walletCreationTime = 0;
        this.balanceSymbol = "";
        this.ENSAvatar = "";
    }

    private Wallet(Parcel in)
    {
        address = in.readString();
        balance = in.readString();
        ENSname = in.readString();
        name = in.readString();
        int t = in.readInt();
        type = WalletType.values()[t];
        lastBackupTime = in.readLong();
        t = in.readInt();
        authLevel = KeyService.AuthenticationLevel.values()[t];
        walletCreationTime = in.readLong();
        balanceSymbol = in.readString();
        ENSAvatar = in.readString();
        hdKeyIndex = in.readInt();
        parentAddress = in.readString();
    }

    public void setWalletType(WalletType wType)
    {
        type = wType;
    }

    public static final Creator<Wallet> CREATOR = new Creator<Wallet>()
    {
        @Override
        public Wallet createFromParcel(Parcel in)
        {
            return new Wallet(in);
        }

        @Override
        public Wallet[] newArray(int size)
        {
            return new Wallet[size];
        }
    };

    public boolean sameAddress(String address)
    {
        return this.address.equalsIgnoreCase(address);
    }

    @Override
    public int describeContents()
    {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i)
    {
        parcel.writeString(address);
        parcel.writeString(balance);
        parcel.writeString(ENSname);
        parcel.writeString(name);
        parcel.writeInt(type.ordinal());
        parcel.writeLong(lastBackupTime);
        parcel.writeInt(authLevel.ordinal());
        parcel.writeLong(walletCreationTime);
        parcel.writeString(balanceSymbol);
        parcel.writeString(ENSAvatar);
        parcel.writeInt(hdKeyIndex);
        parcel.writeString(parentAddress);
    }

    public boolean setWalletBalance(Token token)
    {
        balanceSymbol = token.tokenInfo != null ? token.tokenInfo.symbol : "ETH";
        String newBalance = token.getFixedFormattedBalance();
        if (newBalance.equals(balance))
        {
            return false;
        }
        else
        {
            balance = newBalance;
            return true;
        }
    }

    public void zeroWalletBalance(NetworkInfo networkInfo)
    {
        if (balance.equals("-"))
        {
            balanceSymbol = networkInfo.symbol;
            balance = BalanceUtils.getScaledValueFixed(BigDecimal.ZERO, 0, Token.TOKEN_BALANCE_PRECISION);
        }
    }

    public boolean canSign()
    {
        return BuildConfig.DEBUG || !watchOnly();
    }

    public boolean watchOnly()
    {
        return type == WalletType.WATCH;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Wallet wallet = (Wallet) o;
        return Objects.equals(address, wallet.address) && type == wallet.type;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(address, type);
    }

    /**
     * Check if this wallet is an HD wallet (created from seed phrase)
     */
    public boolean isHDWallet()
    {
        return type == WalletType.HDKEY;
    }

    /**
     * Check if this is the master HD wallet (index 0)
     */
    public boolean isMasterHDWallet()
    {
        return type == WalletType.HDKEY && hdKeyIndex == 0 && (parentAddress == null || parentAddress.isEmpty());
    }

    /**
     * Check if this is a derived HD account (index > 0)
     */
    public boolean isDerivedHDAccount()
    {
        return type == WalletType.HDKEY && (hdKeyIndex > 0 || (parentAddress != null && !parentAddress.isEmpty()));
    }

    /**
     * Get the HD account label (e.g., "Account 1", "Account 2")
     */
    public String getHDAccountLabel()
    {
        if (type == WalletType.HDKEY)
        {
            return "Account " + (hdKeyIndex + 1);
        }
        return null;
    }
}
