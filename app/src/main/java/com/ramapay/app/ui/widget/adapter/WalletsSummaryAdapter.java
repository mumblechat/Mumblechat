package com.ramapay.app.ui.widget.adapter;

import static com.ramapay.app.entity.tokenscript.TokenscriptFunction.ZERO_ADDRESS;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.ramapay.app.R;
import com.ramapay.app.entity.Wallet;
import com.ramapay.app.entity.WalletType;
import com.ramapay.app.entity.tokens.Token;
import com.ramapay.app.interact.GenericWalletInteract;
import com.ramapay.app.repository.WalletItem;
import com.ramapay.app.ui.widget.entity.WalletClickCallback;
import com.ramapay.app.ui.widget.holder.BinderViewHolder;
import com.ramapay.app.ui.widget.holder.TextHolder;
import com.ramapay.app.ui.widget.holder.WalletHolder;
import com.ramapay.app.ui.widget.holder.WalletSummaryHeaderHolder;
import com.ramapay.app.ui.widget.holder.WalletSummaryHolder;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import io.realm.Realm;

public class WalletsSummaryAdapter extends RecyclerView.Adapter<BinderViewHolder> implements WalletClickCallback, Runnable
{
    private final OnSetWalletDefaultListener onSetWalletDefaultListener;
    private final ArrayList<Wallet> wallets;
    private final Map<String, Pair<Double, Double>> valueMap = new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Wallet defaultWallet = null;
    private final Wallet summaryWallet = new Wallet(ZERO_ADDRESS);
    private final Context context;
    private final Realm realm;
    private final GenericWalletInteract walletInteract;

    public WalletsSummaryAdapter(Context ctx,
                                 OnSetWalletDefaultListener onSetWalletDefaultListener, GenericWalletInteract genericWalletInteract)
    {
        this.onSetWalletDefaultListener = onSetWalletDefaultListener;
        this.wallets = new ArrayList<>();
        this.context = ctx;
        this.realm = genericWalletInteract.getWalletRealm();
        this.walletInteract = genericWalletInteract;
    }

    @NotNull
    @Override
    public BinderViewHolder<?> onCreateViewHolder(@NotNull ViewGroup parent, int viewType)
    {
        BinderViewHolder<?> binderViewHolder = null;
        switch (viewType)
        {
            case WalletHolder.VIEW_TYPE:
                binderViewHolder = new WalletSummaryHolder(R.layout.item_wallet_summary_manage, parent, this, realm);
                break;
            case TextHolder.VIEW_TYPE:
                binderViewHolder = new TextHolder(R.layout.item_standard_header, parent);
                break;
            case WalletSummaryHeaderHolder.VIEW_TYPE:
                binderViewHolder = new WalletSummaryHeaderHolder(R.layout.item_wallet_summary_large_title, parent);
                break;
            default:
                break;
        }
        return binderViewHolder;
    }

    @Override
    public void onBindViewHolder(@NotNull BinderViewHolder holder, int position)
    {
        Bundle bundle;
        Wallet wallet;
        switch (getItemViewType(position))
        {
            case WalletHolder.VIEW_TYPE:
                wallet = wallets.get(position);
                bundle = new Bundle();
                bundle.putBoolean(
                        WalletHolder.IS_DEFAULT_ADDITION,
                        defaultWallet != null && defaultWallet.sameAddress(wallet.address));
                bundle.putBoolean(WalletHolder.IS_LAST_ITEM, getItemCount() == 1);
                bundle.putBoolean(WalletHolder.IS_SYNCED, wallet.isSynced);

                if (valueMap.containsKey(wallet.address.toLowerCase()))
                {
                    Pair<Double, Double> valuePair = valueMap.get(wallet.address.toLowerCase());
                    bundle.putDouble(WalletHolder.FIAT_VALUE, valuePair.first);
                    bundle.putDouble(WalletHolder.FIAT_CHANGE, valuePair.second);
                }

                holder.bind(wallet, bundle);
                break;
            case TextHolder.VIEW_TYPE:
                wallet = wallets.get(position);
                holder.bind(wallet.address);
                break;
            case WalletSummaryHeaderHolder.VIEW_TYPE:
                wallet = summaryWallet;
                bundle = new Bundle();
                Pair<Double, Double> totalPair = getSummaryBalance();
                bundle.putDouble(WalletHolder.FIAT_VALUE, totalPair.first);
                bundle.putDouble(WalletHolder.FIAT_CHANGE, totalPair.second);
                holder.bind(wallet, bundle);
                break;

            default:
                break;
        }
    }

    @Override
    public int getItemCount()
    {
        return wallets.size();
    }

    @Override
    public int getItemViewType(int position)
    {
        switch (wallets.get(position).type)
        {
            default:
            case WATCH:
            case KEYSTORE:
            case KEYSTORE_LEGACY:
            case HDKEY:
                return WalletHolder.VIEW_TYPE;
            case TEXT_MARKER:
                return TextHolder.VIEW_TYPE;
            case LARGE_TITLE:
                return WalletSummaryHeaderHolder.VIEW_TYPE;
        }
    }

    public void setDefaultWallet(Wallet wallet)
    {
        this.defaultWallet = wallet;
        notifyDataSetChanged();
    }

    private int getWalletIndex(String wallet)
    {
        for (int i = 0; i < wallets.size(); i++)
        {
            if (wallets.get(i).address.equalsIgnoreCase(wallet))
            {
                return i;
            }
        }

        return -1;
    }

    public void setUnsyncedWalletValue(String wallet, Pair<Double, Double> value)
    {
        int index = getWalletIndex(wallet);
        if (index >= 0)
        {
            if (value != null)
            {
                valueMap.put(wallet.toLowerCase(), value);
            }
            else
            {
                wallets.get(index).isSynced = false;
            }
            notifyItemChanged(index);
            updateWalletSummary();
        }
    }

    private Pair<Double, Double> getSummaryBalance()
    {
        double totalValue = 0.0;
        double totalOldValue = 0.0;
        for (Pair<Double, Double> value : valueMap.values())
        {
            totalValue += value.first;
            totalOldValue += value.second;
        }

        return new Pair<>(totalValue, totalOldValue);
    }

    private void updateWalletSummary()
    {
        handler.postDelayed(this, 1000); //updates can be bunched together
    }

    @Override
    public void run()
    {
        notifyItemChanged(1);
    }

    public void completeWalletSync(String walletAddress, Pair<Double, Double> value)
    {
        int index = getWalletIndex(walletAddress);
        if (index >= 0)
        {
            wallets.get(index).isSynced = true;
            updateWalletState(walletAddress, value);
        }
    }

    public void updateWalletState(String walletAddress, Pair<Double, Double> value)
    {
        int index = getWalletIndex(walletAddress);
        if (index >= 0)
        {
            valueMap.put(walletAddress.toLowerCase(), value);
            notifyItemChanged(index);
            updateWalletSummary();
        }
    }

    public void setWallets(Wallet[] wallets)
    {
        this.wallets.clear();
        boolean hasLegacyWallet = false;
        boolean hasWatchWallet = false;
        boolean hasHardwareWallet = false;
        if (wallets != null)
        {
            Wallet summaryItem = new Wallet(context.getString(R.string.summary));
            summaryItem.type = WalletType.TEXT_MARKER;
            this.wallets.add(summaryItem);

            Wallet largeTitle = new Wallet(context.getString(R.string.summary));
            largeTitle.type = WalletType.LARGE_TITLE;
            this.wallets.add(largeTitle); //index 1

            // Group HD wallets by master wallet
            // First, find all master wallets and create a map of parent -> derived accounts
            java.util.List<Wallet> masterWallets = new ArrayList<>();
            java.util.Map<String, java.util.List<Wallet>> derivedWalletsMap = new java.util.HashMap<>();
            
            for (Wallet w : wallets)
            {
                if (w.type == WalletType.HDKEY)
                {
                    if (w.isMasterHDWallet())
                    {
                        masterWallets.add(w);
                        // Initialize empty list for derived accounts
                        derivedWalletsMap.put(w.address.toLowerCase(), new ArrayList<>());
                    }
                }
            }
            
            // Now assign derived wallets to their parent master wallets
            for (Wallet w : wallets)
            {
                if (w.type == WalletType.HDKEY && w.isDerivedHDAccount())
                {
                    String parentAddr = w.parentAddress != null ? w.parentAddress.toLowerCase() : "";
                    if (derivedWalletsMap.containsKey(parentAddr))
                    {
                        derivedWalletsMap.get(parentAddr).add(w);
                    }
                    else
                    {
                        // If parent not found, try to find master wallet by checking who has no parent
                        // and add to that master's list (fallback for older data)
                        if (!masterWallets.isEmpty())
                        {
                            String firstMasterAddr = masterWallets.get(0).address.toLowerCase();
                            if (derivedWalletsMap.containsKey(firstMasterAddr))
                            {
                                derivedWalletsMap.get(firstMasterAddr).add(w);
                            }
                        }
                    }
                }
            }
            
            // Add each master wallet with its section header and derived accounts
            int masterIndex = 1;
            for (Wallet masterWallet : masterWallets)
            {
                // Add section header for this master wallet group
                String sectionTitle = masterWallets.size() > 1 
                    ? context.getString(R.string.your_wallets) + " " + masterIndex
                    : context.getString(R.string.your_wallets);
                Wallet sectionHeader = new Wallet(sectionTitle);
                sectionHeader.type = WalletType.TEXT_MARKER;
                this.wallets.add(sectionHeader);
                
                // Add master wallet
                masterWallet.isSynced = false;
                this.wallets.add(masterWallet);
                
                // Add derived wallets for this master
                java.util.List<Wallet> derivedList = derivedWalletsMap.get(masterWallet.address.toLowerCase());
                if (derivedList != null)
                {
                    // Sort by hdKeyIndex
                    derivedList.sort((a, b) -> Integer.compare(a.hdKeyIndex, b.hdKeyIndex));
                    for (Wallet derived : derivedList)
                    {
                        derived.isSynced = false;
                        this.wallets.add(derived);
                    }
                }
                
                masterIndex++;
            }
            
            // Check for other wallet types
            for (Wallet w : wallets)
            {
                switch (w.type)
                {
                    case KEYSTORE_LEGACY:
                    case KEYSTORE:
                        hasLegacyWallet = true;
                        w.isSynced = false;
                        break;
                    case WATCH:
                        hasWatchWallet = true;
                        w.isSynced = true;
                        break;
                    case HARDWARE:
                        hasHardwareWallet = true;
                        w.isSynced = false;
                        break;
                    default:
                        break;
                }
            }

            if (hasHardwareWallet)
            {
                Wallet hardwareText = new Wallet(context.getString(R.string.hardware_wallets));
                hardwareText.type = WalletType.TEXT_MARKER;
                this.wallets.add(hardwareText);

                for (Wallet w : wallets)
                {
                    if (w.type == WalletType.HARDWARE)
                    {
                        this.wallets.add(w);
                    }
                }
            }

            if (hasLegacyWallet)
            {
                Wallet legacyText = new Wallet(context.getString(R.string.legacy_wallets));
                legacyText.type = WalletType.TEXT_MARKER;
                this.wallets.add(legacyText);

                for (Wallet w : wallets)
                {
                    if (w.type == WalletType.KEYSTORE || w.type == WalletType.KEYSTORE_LEGACY)
                    {
                        this.wallets.add(w);
                    }
                }
            }

            if (hasWatchWallet)
            {
                Wallet watchText = new Wallet(context.getString(R.string.watch_wallet));
                watchText.type = WalletType.TEXT_MARKER;
                this.wallets.add(watchText);

                for (Wallet w : wallets)
                {
                    if (w.type == WalletType.WATCH)
                    {
                        this.wallets.add(w);
                    }
                }
            }
        }
        notifyDataSetChanged();
    }

    @Override
    public void onWalletClicked(Wallet wallet)
    {
        onSetWalletDefaultListener.onSetDefault(wallet);
    }

    @Override
    public void ensAvatar(Wallet wallet)
    {
        //update the ENS avatar in the database
        walletInteract.updateWalletItem(wallet, WalletItem.ENS_AVATAR, () -> {});
    }

    public void onDestroy()
    {
        realm.close();
    }

    public int getDefaultWalletIndex()
    {
        if (defaultWallet != null)
        {
            return getWalletIndex(defaultWallet.address);
        }
        return -1;
    }

    public interface OnSetWalletDefaultListener
    {
        void onSetDefault(Wallet wallet);
    }

    public void setTokens(Map<String, Token[]> walletTokens)
    {
        if (walletTokens == null) return;

        for (Token[] token : walletTokens.values())
        {
            Token[] t = walletTokens.get(token[0].getAddress());
            String walletAddress = token[0].getAddress();
            int walletIndex = getWalletIndex(walletAddress);
            if (walletIndex != -1)
            {
                this.wallets.get(walletIndex).tokens = t;
                notifyItemChanged(walletIndex);
            }
        }
    }
}
