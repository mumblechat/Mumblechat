package com.ramapay.app.ui.widget.adapter;

import com.ramapay.app.entity.lifi.Chain;
import com.ramapay.ethereum.EthereumNetworkBase;

import java.util.ArrayList;
import java.util.List;

public class ChainFilter
{
    private final List<Chain> chains;

    public ChainFilter(List<Chain> chains)
    {
        this.chains = chains;
    }

    public List<Chain> getSupportedChains()
    {
        List<Chain> filteredChains = new ArrayList<>();
        for (Chain c : chains)
        {
            if (EthereumNetworkBase.getNetworkByChain(c.id) != null)
            {
                filteredChains.add(c);
            }
        }
        return filteredChains;
    }
}
